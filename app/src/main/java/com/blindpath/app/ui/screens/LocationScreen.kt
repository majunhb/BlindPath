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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.amap.api.location.AMapLocation
import com.amap.api.location.AMapLocationClient
import com.amap.api.location.AMapLocationClientOption
import com.amap.api.location.AMapLocationListener
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * 实时定位界面 - 实景应用级
 * 1. 高德定位SDK获取真实GPS/北斗/网络定位
 * 2. 实时更新位置、道路、方位信息
 * 3. VoiceRepository TTS 语音播报定位结果
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 通过 Hilt EntryPoint 获取 VoiceRepository
    val appContext = context.applicationContext
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(
            appContext,
            LocationEntryPoint::class.java
        ).voiceRepository()
    }

    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(
                context, Manifest.permission.ACCESS_FINE_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
    }
    var isLocating by remember { mutableStateOf(false) }
    var locationInfo by remember { mutableStateOf<LocationDisplayInfo?>(null) }
    var lastAnnouncement by remember { mutableStateOf("请授权位置权限后开始定位") }
    var locationError by remember { mutableStateOf<String?>(null) }
    var locationClient by remember { mutableStateOf<AMapLocationClient?>(null) }

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
                || permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (hasLocationPermission) {
            lastAnnouncement = "位置权限已获取，点击开始定位"
        } else {
            lastAnnouncement = "位置权限被拒绝，无法进行定位"
        }
    }

    // 停止定位
    fun stopLocation() {
        locationClient?.stopLocation()
        locationClient?.onDestroy()
        locationClient = null
        isLocating = false
    }

    // 真实定位逻辑
    fun startRealLocation() {
        if (!hasLocationPermission) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
            )
            return
        }

        // 先停止之前的定位
        stopLocation()

        scope.launch {
            isLocating = true
            locationError = null
            lastAnnouncement = "正在通过GPS北斗网络获取位置信息..."

            // 语音播报
            voiceRepository.speak("正在定位，请稍候", queueMode = false)

            try {
                // 检查高德 SDK 是否初始化
                val client = try {
                    AMapLocationClient(context)
                } catch (e: Exception) {
                    Timber.e(e, "Failed to create AMapLocationClient")
                    locationError = "定位服务初始化失败，请检查应用权限"
                    lastAnnouncement = locationError ?: "定位服务初始化失败"
                    isLocating = false
                    voiceRepository.speak("定位服务初始化失败，请重启应用", queueMode = false)
                    return@launch
                }
                
                locationClient = client
                val locationOption = AMapLocationClientOption().apply {
                    // 高精度模式：GPS + 北斗 + 网络
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    // 持续定位
                    isOnceLocation = false
                    // 定位间隔3秒
                    interval = 3000
                    // 需要地址信息
                    isNeedAddress = true
                    // 设置超时时间
                    httpTimeOut = 20000
                }
                client.setLocationOption(locationOption)

                client.setLocationListener(object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation?) {
                        if (location != null && location.errorCode == 0) {
                            // 定位成功 - 提取真实数据
                            Timber.d("Location success: lat=${location.latitude}, lng=${location.longitude}")
                            Timber.d("Location details: province=${location.province}, city=${location.city}, district=${location.district}, road=${location.road}, street=${location.street}, address=${location.address}")
                            
                            // 构建道路名称（优先级：road > street > poiName > aoiName）
                            val roadName = location.road?.takeIf { it.isNotBlank() && it != "不知名街道" && it != "无名道路" }
                                ?: location.street?.takeIf { it.isNotBlank() && it != "不知名街道" && it != "无名道路" }
                                ?: location.poiName?.takeIf { it.isNotBlank() }
                                ?: location.aoiName?.takeIf { it.isNotBlank() }
                                ?: "未知道路"
                            
                            // 构建完整地址（优先使用 address 字段）
                            val addressText = location.address?.takeIf { it.isNotBlank() && it != "不知名街道" }
                                ?: buildString {
                                    // 按照行政区划级别构建地址
                                    location.province?.takeIf { it.isNotBlank() }?.let { append(it) }
                                    location.city?.takeIf { it.isNotBlank() }?.let { 
                                        if (it != province()) append(it) 
                                    }
                                    location.district?.takeIf { it.isNotBlank() }?.let { append(it) }
                                    location.road?.takeIf { it.isNotBlank() && it != "不知名街道" }?.let { append(it) }
                                    location.street?.takeIf { it.isNotBlank() && it != "不知名街道" }?.let { append(it) }
                                }.takeIf { it.isNotBlank() }
                                ?: "${location.province ?: ""}${location.city ?: ""}${location.district ?: ""}"
                            
                            // 构建地标信息
                            val landmarkText = location.poiName?.takeIf { it.isNotBlank() }
                                ?: location.aoiName?.takeIf { it.isNotBlank() }
                                ?: "暂无周边地标"
                            
                            val info = LocationDisplayInfo(
                                road = roadName,
                                direction = getDirectionText(location.bearing),
                                coordinates = "${"%.6f".format(location.latitude)}, ${"%.6f".format(location.longitude)}",
                                accuracy = "±${location.accuracy.toInt()}米",
                                landmarks = landmarkText,
                                city = location.city ?: "",
                                district = location.district ?: "",
                                address = addressText,
                                bearing = location.bearing,
                                speed = location.speed
                            )
                            locationInfo = info
                            isLocating = false

                            // 生成语音播报文本
                            val voiceText = buildString {
                                append("定位成功。您当前位于")
                                // 优先使用完整地址
                                if (addressText.isNotBlank() && addressText != "未知道路") {
                                    append(addressText)
                                } else {
                                    if (info.city.isNotEmpty()) append(info.city)
                                    if (info.district.isNotEmpty()) append(info.district)
                                    if (info.road != "未知道路") append(info.road)
                                }
                                append("，面向${info.direction}")
                                if (landmarkText != "暂无周边地标") {
                                    append("，附近有$landmarkText")
                                }
                                append("。定位精度${info.accuracy}")
                            }
                            lastAnnouncement = voiceText

                            // TTS语音播报
                            scope.launch {
                                voiceRepository.speak(voiceText, queueMode = false)
                            }
                            
                            Timber.d("Location display: road=$roadName, address=$addressText, landmark=$landmarkText")
                            
                            Timber.d("Location success: road=$roadName, address=$addressText")
                        } else {
                            // 定位失败，提供详细错误信息
                            val errorCode = location?.errorCode ?: -1
                            val errorInfo = location?.errorInfo ?: "未知错误"
                            val errorMsg = when (errorCode) {
                                4 -> "网络连接失败，请检查网络设置"
                                5 -> "GPS未开启，请在设置中开启GPS"
                                6 -> "定位权限被拒绝，请在设置中授权"
                                7 -> "定位失败，请到开阔区域重试"
                                12 -> "缺少定位权限，请在设置中授权"
                                13 -> "定位服务未开启，请在设置中开启定位"
                                else -> "定位失败($errorCode)：$errorInfo"
                            }
                            locationError = errorMsg
                            lastAnnouncement = errorMsg
                            isLocating = false

                            scope.launch {
                                voiceRepository.speak(errorMsg, queueMode = false)
                            }
                            
                            Timber.e("Location failed: code=$errorCode, info=$errorInfo")
                        }
                    }
                })

                client.startLocation()

                // 30秒超时
                delay(30000)
                if (locationInfo == null && locationError == null) {
                    lastAnnouncement = "定位超时，请检查GPS是否开启后重试"
                    isLocating = false
                    scope.launch {
                        voiceRepository.speak("定位超时，请检查GPS是否开启", queueMode = false)
                    }
                }
            } catch (e: Exception) {
                Timber.e(e, "Location exception")
                locationError = "定位异常：${e.message}"
                lastAnnouncement = locationError ?: "定位异常，请重试"
                isLocating = false
                scope.launch {
                    voiceRepository.speak("定位异常，请重试", queueMode = false)
                }
            }
        }
    }

    // 离开页面时停止定位
    DisposableEffect(Unit) {
        onDispose {
            locationClient?.stopLocation()
            locationClient?.onDestroy()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时定位") },
                navigationIcon = {
                    IconButton(onClick = {
                        stopLocation()
                        scope.launch { voiceRepository.speak("已退出定位", queueMode = false) }
                        onBackClick()
                    }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回")
                    }
                },
                actions = {
                    IconButton(
                        onClick = { startRealLocation() },
                        enabled = !isLocating
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = "刷新定位")
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
            if (locationInfo == null) {
                // 未定位状态
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Button(
                            onClick = { startRealLocation() },
                            enabled = !isLocating,
                            modifier = Modifier
                                .size(200.dp)
                                .semantics {
                                    contentDescription = "开始定位按钮"
                                },
                            shape = RoundedCornerShape(100.dp)
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    modifier = Modifier.size(48.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = if (isLocating) "定位中..." else "开始定位",
                                    fontSize = 20.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        if (isLocating) {
                            Spacer(modifier = Modifier.height(16.dp))
                            CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("正在通过GPS/北斗/网络获取位置...", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        if (locationError != null) {
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(locationError!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
            } else {
                // 定位成功 - 显示信息
                locationInfo?.let { info ->
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(20.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text("当前位置", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            LocationRow("所在道路", info.road)
                            LocationRow("行进方位", info.direction)
                            LocationRow("详细地址", info.address)
                            LocationRow("当前坐标", info.coordinates)
                            LocationRow("定位精度", info.accuracy)
                            LocationRow("周边地标", info.landmarks)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 语音播报
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text("语音播报", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(lastAnnouncement, style = MaterialTheme.typography.bodyLarge)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 定位状态
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = Color.Green.copy(alpha = 0.1f))
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .background(Color.Green, RoundedCornerShape(100.dp))
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Text("GPS+北斗+网络 三重定位正常", style = MaterialTheme.typography.bodyMedium)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // 重新定位按钮
                    Button(
                        onClick = { startRealLocation() },
                        modifier = Modifier.fillMaxWidth().height(48.dp),
                        enabled = !isLocating
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("重新定位")
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = "定位功能需要开启GPS并授权位置权限",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.CenterHorizontally)
            )
        }
    }
}

@Composable
private fun LocationRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            text = "$label：",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.width(100.dp)
        )
        Text(text = value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
    }
}

data class LocationDisplayInfo(
    val road: String,
    val direction: String,
    val coordinates: String,
    val accuracy: String,
    val landmarks: String,
    val city: String,
    val district: String,
    val address: String,
    val bearing: Float,
    val speed: Float
)

private fun getDirectionText(bearing: Float): String {
    val directions = arrayOf("正北", "东北", "正东", "东南", "正南", "西南", "正西", "西北")
    val index = ((bearing + 22.5f) % 360 / 45).toInt()
    return directions[index]
}

/**
 * Hilt EntryPoint - 用于在 Composable 中获取依赖
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface LocationEntryPoint {
    fun voiceRepository(): VoiceRepository
}
