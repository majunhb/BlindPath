package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.amap.api.services.core.LatLonPoint
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
import kotlin.coroutines.resume

/**
 * 智能导航界面 - 实景应用级
 * 1. 高德地理编码：目的地文本 -> 经纬度坐标
 * 2. 高德定位SDK：获取当前位置坐标
 * 3. 高德路线规划：起点+终点 -> 真实步行路线
 * 4. VoiceRepository TTS 语音导航播报
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 通过 Hilt EntryPoint 获取 VoiceRepository
    val appContext = context.applicationContext
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            NavigationEntryPoint::class.java
        ).voiceRepository()
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

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    /**
     * 获取当前位置坐标（使用高德定位SDK，带超时）
     */
    suspend fun getCurrentPosition(): LatLonPoint? {
        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val client = AMapLocationClient(context)
                    val option = AMapLocationClientOption().apply {
                        locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                        isOnceLocation = true
                        isNeedAddress = false
                    }
                    client.setLocationOption(option)
                    client.setLocationListener(object : AMapLocationListener {
                        override fun onLocationChanged(location: AMapLocation?) {
                            client.stopLocation()
                            client.onDestroy()
                            if (location != null && location.errorCode == 0) {
                                continuation.resume(LatLonPoint(location.latitude, location.longitude)) {}
                            } else {
                                continuation.resume(null) {}
                            }
                        }
                    })
                    client.startLocation()
                    continuation.invokeOnCancellation {
                        client.stopLocation()
                        client.onDestroy()
                    }
                } catch (e: Exception) {
                    continuation.resume(null) {}
                }
            }
        }
    }

    /**
     * 地理编码：目的地名称 -> 坐标（带超时）
     */
    suspend fun geocodeDestination(destText: String): LatLonPoint? {
        return withTimeoutOrNull(8000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val geocodeSearch = GeocodeSearch(context)
                    geocodeSearch.setOnGeocodeSearchListener(object : GeocodeSearch.OnGeocodeSearchListener {
                        override fun onRegeocodeSearched(result: RegeocodeResult?, errorCode: Int) {}
                        override fun onGeocodeSearched(result: GeocodeResult?, errorCode: Int) {
                            if (errorCode == 1000 && result != null && result.geocodeAddressList != null && result.geocodeAddressList.isNotEmpty()) {
                                val point = result.geocodeAddressList[0].latLonPoint
                                continuation.resume(point) {}
                            } else {
                                continuation.resume(null) {}
                            }
                        }
                    })
                    val query = com.amap.api.services.geocoder.GeocodeQuery(destText, "")
                    geocodeSearch.getFromLocationNameAsyn(query)
                    continuation.invokeOnCancellation {}
                } catch (e: Exception) {
                    continuation.resume(null) {}
                }
            }
        }
    }

    /**
     * 步行路线规划（带超时）
     */
    suspend fun planWalkRoute(origin: LatLonPoint, dest: LatLonPoint): List<NavigationStep> {
        return withTimeoutOrNull(10000L) {
            suspendCancellableCoroutine { continuation ->
                try {
                    val routeSearch = RouteSearch(context)
                    val fromAndTo = RouteSearch.FromAndTo(origin, dest)
                    val query = RouteSearch.WalkRouteQuery(fromAndTo)

                    routeSearch.setRouteSearchListener(object : RouteSearch.OnRouteSearchListener {
                        override fun onBusRouteSearched(p0: BusRouteResult?, p1: Int) {}
                        override fun onDriveRouteSearched(p0: DriveRouteResult?, p1: Int) {}
                        override fun onRideRouteSearched(p0: RideRouteResult?, p1: Int) {}

                        override fun onWalkRouteSearched(result: WalkRouteResult?, errorCode: Int) {
                            if (errorCode == 1000 && result != null && result.paths != null && result.paths.isNotEmpty()) {
                                val path = result.paths[0]
                                val steps = path.steps
                                if (steps != null && steps.isNotEmpty()) {
                                    val navSteps = steps.map { step ->
                                        NavigationStep(
                                            instruction = step.instruction ?: "继续前行",
                                            distance = "${step.distance.toInt()}米",
                                            duration = formatDuration(step.duration.toInt()),
                                            type = parseStepType(step.action),
                                            road = step.road ?: ""
                                        )
                                    }
                                    totalDistance = "${path.distance.toInt()}米"
                                    totalDuration = formatDuration(path.duration.toInt())
                                    continuation.resume(navSteps) {}
                                } else {
                                    continuation.resume(emptyList()) {}
                                }
                            } else {
                                continuation.resume(emptyList()) {}
                            }
                        }
                    })

                    routeSearch.calculateWalkRouteAsyn(query)
                    continuation.invokeOnCancellation {}
                } catch (e: Exception) {
                    continuation.resume(emptyList()) {}
                }
            }
        } ?: emptyList()
    }

    fun startNavigation() {
        if (!hasPermission) {
            permissionLauncher.launch(
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
            )
            return
        }
        if (destination.isBlank()) return

        scope.launch {
            isPlanning = true
            announcement = "正在获取当前位置..."
            voiceRepository.speak("正在规划路线，请稍候", queueMode = false)

            // 1. 获取当前位置
            val origin = getCurrentPosition()
            if (origin == null) {
                announcement = "无法获取当前位置，请检查GPS权限"
                voiceRepository.speak("无法获取当前位置，请检查GPS权限", queueMode = false)
                isPlanning = false
                return@launch
            }

            announcement = "正在解析目的地坐标..."
            // 2. 地理编码目的地
            val destPoint = geocodeDestination(destination)
            if (destPoint == null) {
                announcement = "无法识别目的地：$destination，请尝试更详细的地址"
                voiceRepository.speak("无法识别目的地，请尝试更详细的地址", queueMode = false)
                isPlanning = false
                return@launch
            }

            announcement = "正在通过高德地图规划步行路线..."
            // 3. 路线规划
            val steps = planWalkRoute(origin, destPoint)
            if (steps.isEmpty()) {
                announcement = "路线规划失败，可能是距离过远或无法步行到达"
                voiceRepository.speak("路线规划失败，请更换目的地", queueMode = false)
                isPlanning = false
                return@launch
            }

            // 4. 导航开始
            routeSteps = steps
            isNavigating = true
            currentStep = 0
            val startMsg = "路线规划成功！全程${totalDistance}，预计${totalDuration}到达${destination}。共${routeSteps.size}个步骤。点击下一步开始导航。"
            announcement = startMsg
            voiceRepository.speak(startMsg, queueMode = false)
            isPlanning = false
        }
    }

    fun nextStep() {
        if (currentStep < routeSteps.size - 1) {
            currentStep++
            val step = routeSteps[currentStep]
            val msg = "${step.instruction}。距离${step.distance}，${step.duration}后到达下一步。"
            announcement = msg
            scope.launch {
                voiceRepository.speakNavigation(msg)
            }
        } else {
            val endMsg = "已到达目的地${destination}附近，导航结束。请注意安全。"
            announcement = endMsg
            isNavigating = false
            scope.launch {
                voiceRepository.speak(endMsg, queueMode = false)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能导航") },
                navigationIcon = {
                    IconButton(onClick = {
                        isNavigating = false
                        scope.launch { voiceRepository.speak("导航已退出", queueMode = false) }
                        onBackClick()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            if (!isNavigating && !isPlanning) {
                // 目的地输入
                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text("请输入目的地", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Spacer(modifier = Modifier.height(16.dp))
                        OutlinedTextField(
                            value = destination,
                            onValueChange = { destination = it },
                            label = { Text("目的地") },
                            placeholder = { Text("例如：天安门、地铁站、医院") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { startNavigation() },
                            modifier = Modifier.fillMaxWidth().height(56.dp),
                            enabled = destination.isNotBlank()
                        ) {
                            Icon(Icons.Default.Navigation, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text("开始导航", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("常用目的地：", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Spacer(modifier = Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            listOf("家", "公司", "地铁站", "医院").forEach { preset ->
                                AssistChip(onClick = { destination = preset }, label = { Text(preset) })
                            }
                        }
                    }
                }
            } else {
                // 导航中
                if (isPlanning) {
                    Box(modifier = Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(announcement, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                } else {
                    // 当前导航指令
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(48.dp))
                                Spacer(modifier = Modifier.width(16.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("第 ${currentStep + 1}/${routeSteps.size} 步", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(routeSteps.getOrNull(currentStep)?.instruction ?: "已到达", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                NavInfoItem("距离", routeSteps.getOrNull(currentStep)?.distance ?: "")
                                NavInfoItem("预计", routeSteps.getOrNull(currentStep)?.duration ?: "")
                                NavInfoItem("全程", totalDistance)
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // 语音播报
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("导航播报", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(announcement, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    // 控制按钮
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        Button(onClick = { nextStep() }, modifier = Modifier.weight(1f), enabled = currentStep < routeSteps.size) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下一步")
                        }
                        OutlinedButton(onClick = {
                            isNavigating = false
                            announcement = "导航已取消"
                            scope.launch { voiceRepository.speak("导航已取消", queueMode = false) }
                        }, modifier = Modifier.weight(1f)) {
                            Text("结束导航")
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                    Text("完整路线", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(8.dp))
                    Column {
                        routeSteps.forEachIndexed { index, step ->
                            RouteStepItem(step, index == currentStep, index < currentStep)
                            if (index < routeSteps.size - 1) Divider(modifier = Modifier.padding(start = 24.dp, end = 24.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun NavInfoItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun RouteStepItem(step: NavigationStep, isCurrent: Boolean, isCompleted: Boolean) {
    val bgColor = when { isCurrent -> MaterialTheme.colorScheme.primary.copy(alpha = 0.1f); isCompleted -> Color.Gray.copy(alpha = 0.1f); else -> Color.Transparent }
    val tint = when { isCurrent -> MaterialTheme.colorScheme.primary; isCompleted -> Color.Gray; else -> MaterialTheme.colorScheme.onSurfaceVariant }
    Row(modifier = Modifier.fillMaxWidth().background(bgColor).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Default.LocationOn, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(step.instruction, style = MaterialTheme.typography.bodyLarge, fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal)
            Text("${step.distance} · ${step.duration}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (isCurrent) Box(modifier = Modifier.background(MaterialTheme.colorScheme.primary, RoundedCornerShape(4.dp)).padding(horizontal = 8.dp, vertical = 4.dp)) { Text("当前", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold) }
        else if (isCompleted) Text("✓", color = Color.Green, fontSize = 20.sp)
    }
}

data class NavigationStep(val instruction: String, val distance: String, val duration: String, val type: String = "", val road: String = "")

private fun formatDuration(seconds: Int): String {
    val min = seconds / 60
    val sec = seconds % 60
    return if (min > 0) "${min}分${sec}秒" else "${sec}秒"
}

private fun parseStepType(action: String): String {
    return when {
        action.contains("左转") -> "左转"
        action.contains("右转") -> "右转"
        action.contains("直行") -> "直行"
        action.contains("到达") -> "到达"
        else -> "前行"
    }
}

/**
 * Hilt EntryPoint - 用于在 Composable 中获取依赖
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NavigationEntryPoint {
    fun voiceRepository(): VoiceRepository
}
