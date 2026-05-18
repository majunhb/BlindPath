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
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 实时定位界面 - 高德地图定位版本
 * 使用高德定位SDK获取真实GPS/北斗/网络定位
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationScreen(
    onBackClick: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

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

        scope.launch {
            isLocating = true
            locationError = null
            lastAnnouncement = "正在获取位置信息，请稍候..."

            try {
                val locationClient = AMapLocationClient(context)
                val locationOption = AMapLocationClientOption().apply {
                    // 高精度模式：GPS + 北斗 + 网络
                    locationMode = AMapLocationClientOption.AMapLocationMode.Hight_Accuracy
                    // 单次定位
                    isOnceLocation = false
                    // 定位间隔2秒
                    interval = 2000
                    // 需要地址信息
                    isNeedAddress = true
                    // 需要逆地理编码
                    isNeedGeoCode = true
                }
                locationClient.setLocationOption(locationOption)

                locationClient.setLocationListener(object : AMapLocationListener {
                    override fun onLocationChanged(location: AMapLocation?) {
                        if (location != null && location.errorCode == 0) {
                            // 定位成功
                            locationInfo = LocationDisplayInfo(
                                road = location.road ?: "未知道路",
                                direction = getDirectionText(location.bearing),
                                coordinates = "${"%.6f".format(location.latitude)}°N, ${"%.6f".format(location.longitude)}°E",
                                accuracy = "±${location.accuracy.toInt()}米",
                                landmarks = location.poiName ?: location.aoiName ?: "暂无周边地标",
                                city = location.city ?: "",
                                district = location.district ?: "",
                                address = location.address ?: "",
                                bearing = location.bearing,
                                speed = location.speed
                            )
                            lastAnnouncement = "定位成功，您当前位于${locationInfo?.city}${locationInfo?.district}${locationInfo?.road}，面向${locationInfo?.direction}。${if (!locationInfo?.landmarks.isNullOrBlank()) "附近有${locationInfo?.landmarks}" else ""}"
                            isLocating = false
                        } else {
                            locationError = "定位失败：${location?.errorInfo ?: "未知错误"}"
                            lastAnnouncement = locationError ?: "定位失败，请重试"
                            isLocating = false
                        }
                    }
                })

                locationClient.startLocation()

                // 30秒超时
                delay(30000)
                if (locationInfo == null && locationError == null) {
                    lastAnnouncement = "定位超时，请检查GPS是否开启后重试"
                    isLocating = false
                }
                locationClient.stopLocation()
                locationClient.onDestroy()
            } catch (e: Exception) {
                locationError = "定位异常：${e.message}"
                lastAnnouncement = locationError ?: "定位异常，请重试"
                isLocating = false
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("实时定位") },
                navigationIcon = {
                    IconButton(onClick = onBackClick) {
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
