package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.maps.*
import com.amap.api.maps.model.*
import com.amap.api.services.core.LatLonPoint
import com.amap.api.services.geocoder.GeocodeQuery
import com.amap.api.services.geocoder.GeocodeResult
import com.amap.api.services.geocoder.GeocodeSearch
import com.amap.api.services.geocoder.RegeocodeResult
import com.amap.api.services.route.*
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import timber.log.Timber
import kotlin.coroutines.resume

/**
 * 智能导航界面 - 实景应用级（含高德地图）
 * 1. 高德MapView显示真实地图
 * 2. 高德定位SDK获取当前位置
 * 3. 高德地理编码：目的地文本 -> 坐标
 * 4. 高德路线规划 + 地图上绘制路线
 * 5. VoiceRepository TTS 语音导航播报
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    val appContext = context.applicationContext
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(appContext, NavigationEntryPoint::class.java).voiceRepository()
    }

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }
    var destination by remember { mutableStateOf("") }
    var isNavigating by remember { mutableStateOf(false) }
    var currentStep by remember { mutableStateOf(0) }
    var routeSteps by remember { mutableStateOf<List<NavigationStep>>(emptyList()) }
    var announcement by remember { mutableStateOf("请输入目的地开始导航") }
    var isPlanning by remember { mutableStateOf(false) }
    var totalDistance by remember { mutableStateOf("") }
    var totalDuration by remember { mutableStateOf("") }
    var currentLocation by remember { mutableStateOf<LatLonPoint?>(null) }
    var routePolylines by remember { mutableStateOf<List<List<LatLonPoint>>>(emptyList()) } // 每步的坐标点
    var isOffRoute by remember { mutableStateOf(false) } // 是否偏航
    var locationClient by remember { mutableStateOf<AMapLocationClient?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions -> hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true }

    // 地图控制器引用
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var aMapRef by remember { mutableStateOf<AMap?>(null) }
    var routeOverlayRef by remember { mutableStateOf<com.amap.api.maps.model.Polyline?>(null) }

    // 获取当前位置
    suspend fun getCurrentPosition(): LatLonPoint? = withTimeoutOrNull(15000L) {
        suspendCancellableCoroutine { cont ->
            try {
                val client = AMapLocationClient(context)
                val option = AMapLocationClientOption().apply {
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    isOnceLocation = true
                    isNeedAddress = false
                    httpTimeOut = 12000
                }
                client.setLocationOption(option)
                client.setLocationListener(object : AMapLocationListener {
                    override fun onLocationChanged(loc: AMapLocation?) {
                        client.stopLocation()
                        client.onDestroy()
                        if (loc != null && loc.errorCode == 0) {
                            val point = LatLonPoint(loc.latitude, loc.longitude)
                            currentLocation = point
                            cont.resume(point) {}
                        } else {
                            val errorCode = loc?.errorCode ?: -1
                            val errorMsg = when (errorCode) {
                                4 -> "网络连接失败"
                                5 -> "GPS未开启"
                                6 -> "定位权限被拒绝"
                                12 -> "缺少定位权限"
                                13 -> "定位服务未开启"
                                else -> "定位失败($errorCode)"
                            }
                            Timber.e("Navigation location failed: $errorMsg")
                            cont.resume(null) {}
                        }
                    }
                })
                client.startLocation()
                cont.invokeOnCancellation {
                    client.stopLocation()
                    client.onDestroy()
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get current position")
                cont.resume(null) {}
            }
        }
    }

    // 地理编码
    suspend fun geocodeDestination(text: String): LatLonPoint? = withTimeoutOrNull(20000L) {
        suspendCancellableCoroutine { cont ->
            try {
                Timber.d("Geocoding destination: $text")
                val search = GeocodeSearch(context)
                search.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                    override fun onRegeocodeSearched(p0: RegeocodeResult?, p1: Int) {}
                    override fun onGeocodeSearched(result: GeocodeResult?, code: Int) {
                        if (code == 1000 && result != null && result.geocodeAddressList.isNotEmpty()) {
                            val address = result.geocodeAddressList[0]
                            val point = address.latLonPoint
                            Timber.d("Geocode success: (${point.latitude}, ${point.longitude})")
                            cont.resume(point) {}
                        } else {
                            Timber.e("Geocode failed: code=$code, result=$result, query=$text")
                            cont.resume(null) {}
                        }
                    }
                })
                
                // 尝试多种查询方式
                // 1. 先尝试不带城市参数
                val query1 = GeocodeQuery(text, "")
                search.getFromLocationNameAsyn(query1)
                
                cont.invokeOnCancellation {
                    Timber.d("Geocode cancelled: $text")
                }
            } catch (e: Exception) {
                Timber.e(e, "Geocode exception for: $text")
                cont.resume(null) {}
            }
        }
    }

    // 路线规划
    suspend fun planWalkRoute(origin: LatLonPoint, dest: LatLonPoint): List<NavigationStep> {
        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { cont ->
                try {
                    val routeSearch = RouteSearch(context)
                    val query = RouteSearch.WalkRouteQuery(RouteSearch.FromAndTo(origin, dest))
                    routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                        override fun onBusRouteSearched(p0: BusRouteResult?, p1: Int) {}
                        override fun onDriveRouteSearched(p0: DriveRouteResult?, p1: Int) {}
                        override fun onRideRouteSearched(p0: RideRouteResult?, p1: Int) {}
                        override fun onWalkRouteSearched(result: WalkRouteResult?, code: Int) {
                            if (code == 1000 && result != null && result.paths != null && result.paths.isNotEmpty()) {
                                val path = result.paths[0]
                                val steps = path.steps
                                if (steps != null && steps.isNotEmpty()) {
                                    val navSteps = steps.map { s ->
                                        NavigationStep(
                                            instruction = s.instruction ?: "继续前行",
                                            distance = "${s.distance.toInt()}米",
                                            duration = formatDuration(s.duration.toInt()),
                                            type = parseStepType(s.action),
                                            road = s.road ?: ""
                                        )
                                    }
                                    // 保存每步的 polyline 坐标点列表
                                    val polylines = steps.map { step ->
                                        step.polyline?.toList() ?: emptyList()
                                    }
                                    routePolylines = polylines
                                    totalDistance = "${path.distance.toInt()}米"
                                    totalDuration = formatDuration(path.duration.toInt())

                                    // 在地图上绘制路线
                                    aMapRef?.let { aMap ->
                                        // 清除旧路线
                                        routeOverlayRef?.remove()
                                        // 提取路径坐标点
                                        val latLngList = mutableListOf<LatLng>()
                                        for (step in steps) {
                                            step.polyline?.forEach { latLon ->
                                                latLngList.add(LatLng(latLon.latitude, latLon.longitude))
                                            }
                                        }
                                        if (latLngList.size >= 2) {
                                            val polyline = aMap.addPolyline(
                                                PolylineOptions().addAll(latLngList)
                                                    .width(12f)
                                                    .color(Color.parseColor("#4A90D9"))
                                            )
                                            routeOverlayRef = polyline
                                        }
                                        // 移动地图到路线区域
                                        if (latLngList.isNotEmpty()) {
                                            val bounds = LatLngBounds.Builder().include(latLngList.first()).include(latLngList.last()).build()
                                            aMap.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 80))
                                        }
                                    }

                                    cont.resume(navSteps) {}
                                } else cont.resume(emptyList()) {}
                            } else cont.resume(emptyList()) {}
                        }
                    })
                    routeSearch.calculateWalkRouteAsyn(query)
                    cont.invokeOnCancellation {}
                } catch (e: Exception) { cont.resume(emptyList()) {} }
            }
        } ?: emptyList()
    }

    // Haversine 公式计算两点距离（米）
    fun calculateDistance(p1: LatLonPoint, p2: LatLonPoint): Float {
        val R = 6371000.0
        val dLat = Math.toRadians(p2.latitude - p1.latitude)
        val dLon = Math.toRadians(p2.longitude - p1.longitude)
        val a = Math.sin(dLat / 2) * Math.sin(dLat / 2) +
                Math.cos(Math.toRadians(p1.latitude)) * Math.cos(Math.toRadians(p2.latitude)) *
                Math.sin(dLon / 2) * Math.sin(dLon / 2)
        val c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
        return (R * c).toFloat()
    }

    // 停止定位追踪
    fun stopLocationTracking() {
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null
    }

    fun nextStep() {
        if (currentStep < routeSteps.size - 1) {
            currentStep++
            val step = routeSteps[currentStep]
            val msg = "${step.instruction}。距离${step.distance}，${step.duration}后到达下一步。"
            announcement = msg
            scope.launch { voiceRepository.speakNavigation(msg) }
        } else {
            val msg = "已到达目的地${destination}附近，导航结束。"
            announcement = msg; isNavigating = false
            scope.launch { voiceRepository.speak(msg, queueMode = false) }
            stopLocationTracking()
        }
    }

    // 自动步进：当用户接近当前步骤终点时自动推进
    fun checkAutoAdvance(userPoint: LatLonPoint) {
        if (!isNavigating || currentStep >= routeSteps.size) return
        val stepPoints = routePolylines.getOrNull(currentStep) ?: return
        if (stepPoints.isEmpty()) return
        val endPoint = stepPoints.last()
        val distance = calculateDistance(userPoint, endPoint)
        if (distance < 20f) {
            nextStep()
        }
    }

    // 偏航检测：计算用户到路线最近点距离，超过50米判定偏航
    fun checkOffRoute(userPoint: LatLonPoint) {
        if (!isNavigating || routePolylines.isEmpty()) return
        var minDistance = Float.MAX_VALUE
        for (stepPoints in routePolylines) {
            for (point in stepPoints) {
                val d = calculateDistance(userPoint, point)
                if (d < minDistance) minDistance = d
            }
        }
        if (minDistance > 50f && !isOffRoute) {
            isOffRoute = true
            scope.launch {
                voiceRepository.speak("您已偏离路线，正在重新规划", queueMode = false)
            }
            announcement = "您已偏离路线，正在重新规划..."
            val origin = userPoint
            val destPoint = routePolylines.lastOrNull()?.lastOrNull() ?: return
            scope.launch {
                val steps = planWalkRoute(origin, destPoint)
                if (steps.isNotEmpty()) {
                    routeSteps = steps
                    currentStep = 0
                    isOffRoute = false
                    val msg = "路线已重新规划，全程${totalDistance}，共${routeSteps.size}个步骤。"
                    announcement = msg
                    voiceRepository.speak(msg, queueMode = false)
                }
            }
        } else if (minDistance <= 50f) {
            isOffRoute = false
        }
    }

    // 启动持续定位追踪
    fun startLocationTracking() {
        val client = AMapLocationClient(context)
        val option = AMapLocationClientOption().apply {
            locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
            isOnceLocation = false
            interval = 3000
            isNeedAddress = false
        }
        client.setLocationOption(option)
        client.setLocationListener(object : AMapLocationListener {
            override fun onLocationChanged(loc: AMapLocation?) {
                if (loc != null && loc.errorCode == 0) {
                    val point = LatLonPoint(loc.latitude, loc.longitude)
                    currentLocation = point
                    checkAutoAdvance(point)
                    checkOffRoute(point)
                }
            }
        })
        client.startLocation()
        locationClient = client
    }

    fun startNavigation() {
        if (!hasPermission) {
            permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
            return
        }
        if (destination.isBlank()) return
        
        // 验证目的地输入
        val trimmedDestination = destination.trim()
        if (trimmedDestination.length < 2) {
            announcement = "目的地名称太短，请输入更详细的地址"
            scope.launch {
                voiceRepository.speak("目的地名称太短，请输入更详细的地址", queueMode = false)
            }
            return
        }
        
        scope.launch {
            isPlanning = true
            announcement = "正在获取当前位置..."
            voiceRepository.speak("正在规划路线，请稍候", queueMode = false)

            val origin = getCurrentPosition()
            if (origin == null) {
                announcement = "无法获取当前位置。请检查：1. GPS是否开启 2. 位置权限是否授予 3. 是否在开阔区域"
                voiceRepository.speak("无法获取当前位置，请检查GPS和权限设置", queueMode = false)
                isPlanning = false
                return@launch
            }

            Timber.d("Current position: (${origin.latitude}, ${origin.longitude})")
            aMapRef?.addMarker(MarkerOptions().position(LatLng(origin.latitude, origin.longitude)).title("我的位置").icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_GREEN)))

            announcement = "正在解析目的地坐标..."
            Timber.d("Geocoding destination: $trimmedDestination")
            
            val destPoint = geocodeDestination(trimmedDestination)
            if (destPoint == null) {
                val errorMsg = buildString {
                    append("无法识别目的地：$trimmedDestination\n\n")
                    append("建议：\n")
                    append("1. 使用更详细的地址（如：北京市朝阳区三里屯）\n")
                    append("2. 添加城市名称（如：北京天安门）\n")
                    append("3. 使用知名地标名称（如：北京西站、首都机场）\n")
                    append("4. 检查网络连接是否正常")
                }
                announcement = errorMsg
                voiceRepository.speak("无法识别目的地，请尝试更详细的地址或添加城市名称", queueMode = false)
                isPlanning = false
                return@launch
            }

            Timber.d("Destination resolved: (${destPoint.latitude}, ${destPoint.longitude})")
            aMapRef?.addMarker(MarkerOptions().position(LatLng(destPoint.latitude, destPoint.longitude)).title(trimmedDestination).icon(BitmapDescriptorFactory.defaultMarker(BitmapDescriptorFactory.HUE_RED)))

            announcement = "正在规划步行路线..."
            val steps = planWalkRoute(origin, destPoint)
            if (steps.isEmpty()) {
                announcement = "路线规划失败。可能原因：\n1. 目的地太远\n2. 无法步行到达\n3. 网络问题\n\n请更换目的地重试"
                voiceRepository.speak("路线规划失败，请更换目的地或检查网络", queueMode = false)
                isPlanning = false
                return@launch
            }

            routeSteps = steps
            isNavigating = true
            currentStep = 0
            val msg = "路线规划成功！全程${totalDistance}，预计${totalDuration}到达${trimmedDestination}。共${routeSteps.size}个步骤。"
            announcement = msg
            voiceRepository.speak(msg, queueMode = false)
            isPlanning = false
            startLocationTracking()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text("智能导航") }, navigationIcon = {
                IconButton(onClick = {
                    isNavigating = false; routeOverlayRef?.remove(); routeOverlayRef = null
                    stopLocationTracking()
                    scope.launch { voiceRepository.speak("导航已退出", queueMode = false) }
                    onBackClick()
                }) { Icon(Icons.Default.ArrowBack, contentDescription = "返回") }
            })
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 高德地图视图
            Box(modifier = Modifier.fillMaxWidth().weight(0.6f)) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            mapViewRef = this
                            onCreate(null)
                            val aMap = this.map
                            aMapRef = aMap
                            aMap.uiSettings.apply {
                                isZoomControlsEnabled = true
                                isCompassEnabled = true
                                isMyLocationButtonEnabled = true
                            }
                            aMap.myLocationStyle = MyLocationStyle().apply {
                                myLocationType(MyLocationStyle.LOCATION_TYPE_LOCATION_ROTATE)
                                interval(3000)
                            }
                            aMap.isMyLocationEnabled = true
                            // 默认视角：中国
                            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.9042, 116.4074), 15f))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { mapView -> /* 生命周期由 LifecycleObserver 管理 */ }
                )

                // 规划中遮罩
                if (isPlanning) {
                    Box(modifier = Modifier.fillMaxSize().background(ComposeColor.Black.copy(alpha = 0.5f)), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = ComposeColor.White)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(announcement, color = ComposeColor.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // 导航信息面板
            Column(modifier = Modifier.fillMaxWidth().weight(0.4f).padding(12.dp)) {
                if (!isNavigating && !isPlanning) {
                    // 目的地输入
                    Card(modifier = Modifier.fillMaxWidth()) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("智能导航", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                "输入目的地后，系统将为您规划步行路线，并提供语音导航指引。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            OutlinedTextField(
                                value = destination,
                                onValueChange = { destination = it },
                                label = { Text("目的地") },
                                placeholder = { Text("例如：天安门广场、北京南站") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                supportingText = { Text("支持地址、地标、建筑名称") }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { startNavigation() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                enabled = destination.isNotBlank()
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("开始导航", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("常用目的地：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("天安门广场", "北京西站", "北京南站", "首都机场T3").forEach { p ->
                                    AssistChip(onClick = { destination = p }, label = { Text(p, fontSize = 12.sp) })
                                }
                            }
                        }
                    }
                    
                    // 使用说明
                    Spacer(modifier = Modifier.height(12.dp))
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("使用说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("• 需要开启GPS定位和位置权限", style = MaterialTheme.typography.bodySmall)
                            Text("• 支持步行导航，自动偏航重规划", style = MaterialTheme.typography.bodySmall)
                            Text("• 提供语音播报和振动提示", style = MaterialTheme.typography.bodySmall)
                            Text("• 建议在开阔区域使用以获得更好定位", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                } else if (isNavigating) {
                    // 当前导航指令
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("第 ${currentStep + 1}/${routeSteps.size} 步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(routeSteps.getOrNull(currentStep)?.instruction ?: "已到达", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                NavInfoItem("距离", routeSteps.getOrNull(currentStep)?.distance ?: "")
                                NavInfoItem("预计", routeSteps.getOrNull(currentStep)?.duration ?: "")
                                NavInfoItem("全程", totalDistance)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 语音播报
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text("语音播报", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(announcement, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 偏航提示
                    if (isOffRoute) {
                        Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = ComposeColor(0xFFFFEBEE))) {
                            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.LocationOn, contentDescription = null, tint = ComposeColor(0xFFD32F2F), modifier = Modifier.size(24.dp))
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("您已偏离路线，正在为您重新规划...", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = ComposeColor(0xFFD32F2F))
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Button(onClick = { nextStep() }, modifier = Modifier.weight(1f), enabled = currentStep < routeSteps.size) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null); Spacer(modifier = Modifier.width(4.dp)); Text("下一步")
                        }
                        OutlinedButton(onClick = {
                            isNavigating = false; routeOverlayRef?.remove(); routeOverlayRef = null
                            stopLocationTracking()
                            announcement = "导航已取消"; scope.launch { voiceRepository.speak("导航已取消", queueMode = false) }
                        }, modifier = Modifier.weight(1f)) { Text("结束导航") }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 路线步骤列表
                    Text("完整路线 (${routeSteps.size}步)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        routeSteps.forEachIndexed { index, step ->
                            RouteStepItem(step, index == currentStep, index < currentStep)
                            if (index < routeSteps.size - 1) Divider(modifier = Modifier.padding(start = 20.dp, end = 20.dp))
                        }
                    }
                }
            }
        }
    }

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { mapViewRef?.onResume() }
            override fun onPause(owner: LifecycleOwner) { mapViewRef?.onPause() }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            stopLocationTracking()
            mapViewRef?.onDestroy()
        }
    }
}

@Composable private fun NavInfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun RouteStepItem(step: NavigationStep, isCurrent: Boolean, isCompleted: Boolean) {
    val bgColor = when { isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f); isCompleted -> ComposeColor.Gray.copy(alpha = 0.1f); else -> ComposeColor.Transparent }
    val tint = when { isCurrent -> MaterialTheme.colorScheme.primary; isCompleted -> ComposeColor.Gray; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Row(modifier = Modifier.fillMaxWidth().background(bgColor).padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(step.instruction, style = MaterialTheme.typography.bodyMedium, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal, maxLines = 1)
            Text("${step.distance} · ${step.duration}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isCurrent) Box(modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 6.dp, vertical = 2.dp)) { Text("当前", color = ComposeColor.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
        else if (isCompleted) Text("✓", color = ComposeColor.Green, fontSize = 18.sp)
    }
}

data class NavigationStep(val instruction: String, val distance: String, val duration: String, val type: String = "", val road: String = "")
private fun formatDuration(seconds: Int) = if (seconds >= 60) "${seconds / 60}分${seconds % 60}秒" else "${seconds}秒"
private fun parseStepType(action: String) = when { action.contains("左转") -> "左转"; action.contains("右转") -> "右转"; action.contains("直行") -> "直行"; action.contains("到达") -> "到达"; else -> "前行" }

@EntryPoint @InstallIn(SingletonComponent::class)
interface NavigationEntryPoint { fun voiceRepository(): VoiceRepository }
