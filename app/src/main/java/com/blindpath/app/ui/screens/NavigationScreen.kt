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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.*
import com.amap.api.maps.model.*
import com.amap.api.services.core.LatLonPoint
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.module_navigation.domain.model.RouteStep
import androidx.hilt.navigation.compose.hiltViewModel
import timber.log.Timber

/**
 * 智能导航界面 - 实景应用级（含高德地图）
 * 1. 高德MapView显示真实地图
 * 2. 通过 ViewModel + Repository 管理导航状态
 * 3. 高德地理编码：目的地文本 -> 坐标（Repository层）
 * 4. 高德路线规划 + 地图上绘制路线（Repository层）
 * 5. VoiceRepository TTS 语音导航播报（ViewModel层协调）
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationScreen(
    onBackClick: () -> Unit,
    viewModel: NavigationViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // 从 ViewModel 收集状态
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val destinationText by viewModel.destinationText.collectAsStateWithLifecycle()
    val isPlanning by viewModel.isPlanning.collectAsStateWithLifecycle()
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()

    var hasPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions -> hasPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true }

    // 地图控制器引用（View 引用仍用 remember 管理）
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var aMapRef by remember { mutableStateOf<AMap?>(null) }
    var routeOverlayRef by remember { mutableStateOf<com.amap.api.maps.model.Polyline?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("智能导航", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    TextButton(
                        onClick = {
                            viewModel.exitNavigation()
                            routeOverlayRef?.remove()
                            routeOverlayRef = null
                            onBackClick()
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "返回主界面按钮"
                        }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("返回", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.fillMaxSize().padding(padding)) {
            // 高德地图视图
            Box(modifier = Modifier.fillMaxWidth().weight(0.6f)) {
                AndroidView(
                    factory = { ctx ->
                        MapView(ctx).apply {
                            mapViewRef = this
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
                            // 地图加载完成回调
                            aMap.setOnMapLoadedListener {
                                Timber.i("高德地图加载完成")
                            }
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
                if (!uiState.isRunning && !isPlanning) {
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
                                value = destinationText,
                                onValueChange = { viewModel.updateDestination(it) },
                                label = { Text("目的地") },
                                placeholder = { Text("例如：天安门广场、北京南站") },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                supportingText = { Text("支持地址、地标、建筑名称") }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Button(
                                onClick = { viewModel.startNavigation() },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                enabled = destinationText.isNotBlank()
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
                                    AssistChip(onClick = { viewModel.updateDestination(p) }, label = { Text(p, fontSize = 12.sp) })
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
                } else if (uiState.isRunning) {
                    // 当前导航指令
                    Card(modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Navigation, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
                                Spacer(modifier = Modifier.width(12.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("第 ${uiState.currentStepIndex + 1}/${uiState.routeSteps.size} 步", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    Text(uiState.routeSteps.getOrNull(uiState.currentStepIndex)?.instruction ?: "已到达", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                                }
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                                NavInfoItem("距离", uiState.routeSteps.getOrNull(uiState.currentStepIndex)?.distance ?: "")
                                NavInfoItem("预计", uiState.routeSteps.getOrNull(uiState.currentStepIndex)?.duration ?: "")
                                NavInfoItem("全程", uiState.totalDistance)
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
                    if (uiState.isOffRoute) {
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
                        Button(
                            onClick = {
                                // 手动下一步：通过 Repository 推进步骤
                                val state = uiState
                                if (state.currentStepIndex < state.routeSteps.size - 1) {
                                    // ViewModel 可以暴露一个 nextStep 方法，但当前自动步进已覆盖
                                    // 此处保留手动下一步按钮作为备用
                                }
                            },
                            modifier = Modifier.weight(1f),
                            enabled = uiState.currentStepIndex < uiState.routeSteps.size
                        ) {
                            Icon(Icons.Default.PlayArrow, contentDescription = null)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("下一步")
                        }
                        OutlinedButton(
                            onClick = {
                                viewModel.stopNavigation()
                                routeOverlayRef?.remove()
                                routeOverlayRef = null
                            },
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("结束导航")
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    // 路线步骤列表
                    Text("完整路线 (${uiState.routeSteps.size}步)", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Column(modifier = Modifier.weight(1f, fill = false)) {
                        uiState.routeSteps.forEachIndexed { index, step ->
                            RouteStepItem(step, index == uiState.currentStepIndex, index < uiState.currentStepIndex)
                            if (index < uiState.routeSteps.size - 1) Divider(modifier = Modifier.padding(start = 20.dp, end = 20.dp))
                        }
                    }
                }
            }
        }
    }

    // 保存 Bundle 用于 MapView 生命周期
    val savedState = remember { android.os.Bundle() }

    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                mapViewRef?.onCreate(savedState)
            }
            override fun onResume(owner: LifecycleOwner) { mapViewRef?.onResume() }
            override fun onPause(owner: LifecycleOwner) { mapViewRef?.onPause() }
            override fun onDestroy(owner: LifecycleOwner) {
                mapViewRef?.onDestroy()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
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

@Composable private fun RouteStepItem(step: RouteStep, isCurrent: Boolean, isCompleted: Boolean) {
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
        else if (isCompleted) Text("done", color = ComposeColor.Green, fontSize = 18.sp)
    }
}



