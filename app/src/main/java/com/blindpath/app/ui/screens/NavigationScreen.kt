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
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
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
import com.blindpath.module_navigation.data.search.SearchResultItem
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.module_navigation.domain.model.RouteStep
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import androidx.hilt.navigation.compose.hiltViewModel
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import com.blindpath.module_obstacle.domain.ObstacleRepository
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

    // 障碍物检测入口（Hilt EntryPoint）
    val obstacleRepo = remember {
        val ctx = context.applicationContext
        EntryPointAccessors.fromApplication(ctx, NavigationObstacleEntryPoint::class.java)
            .obstacleRepository()
    }
    val obstacleState by obstacleRepo.obstacleState.collectAsStateWithLifecycle(
        initialValue = com.blindpath.module_obstacle.domain.model.ObstacleState()
    )

    // 从 ViewModel 收集状态
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val destinationText by viewModel.destinationText.collectAsStateWithLifecycle()
    val isPlanning by viewModel.isPlanning.collectAsStateWithLifecycle()
    val announcement by viewModel.announcement.collectAsStateWithLifecycle()
    val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
    val isSearching by viewModel.isSearching.collectAsStateWithLifecycle()
    val searchError by viewModel.searchError.collectAsStateWithLifecycle()

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
                            // ★ P0 修复：在 factory 中立即调用 onCreate，初始化 OpenGL 渲染上下文
                            onCreate(null)
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
                    update = { mapView ->
                        // ★ 重组时同步生命周期
                        mapView.onResume()
                    }
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
                    // 目的地搜索
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
                                onValueChange = { 
                                    viewModel.updateDestination(it)
                                    // 输入超过2字自动联想
                                    if (it.length >= 2) {
                                        viewModel.searchInputTips(it)
                                    } else {
                                        viewModel.clearSearchResults()
                                    }
                                },
                                label = { Text("目的地") },
                                placeholder = { Text("输入至少2个字自动搜索...") },
                                modifier = Modifier.fillMaxWidth().semantics {
                                    contentDescription = "目的地搜索输入框，输入地址或地名进行搜索"
                                },
                                singleLine = true,
                                supportingText = {
                                    if (isSearching) {
                                        Text("正在搜索...")
                                    } else if (searchError != null) {
                                        Text(searchError ?: "", color = MaterialTheme.colorScheme.error)
                                    } else {
                                        Text("输入地址、地标或建筑名称，自动联想匹配")
                                    }
                                }
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            
                            // 搜索结果列表（无障碍适配）
                            if (searchResults.isNotEmpty()) {
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Column {
                                        searchResults.forEach { item ->
                                            OutlinedButton(
                                                onClick = { viewModel.selectSearchResult(item) },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .semantics {
                                                        contentDescription = item.toAccessibilityText()
                                                    }
                                            ) {
                                                Column(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalAlignment = Alignment.Start
                                                ) {
                                                    Text(
                                                        item.name,
                                                        fontWeight = FontWeight.Bold,
                                                        style = MaterialTheme.typography.bodyLarge
                                                    )
                                                    Text(
                                                        item.address,
                                                        style = MaterialTheme.typography.bodySmall,
                                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                                    )
                                                    if (item.distance > 0) {
                                                        val distText = if (item.distance >= 1000) {
                                                            "%.1f公里".format(item.distance / 1000.0)
                                                        } else {
                                                            "%d米".format(item.distance)
                                                        }
                                                        Text(
                                                            "距离约$distText",
                                                            style = MaterialTheme.typography.labelSmall,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                            }
                            
                            Button(
                                onClick = { viewModel.searchAddress(destinationText) },
                                modifier = Modifier.fillMaxWidth().height(52.dp),
                                enabled = destinationText.isNotBlank() && !isSearching
                            ) {
                                Icon(Icons.Default.LocationOn, contentDescription = null)
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("搜索", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                            }
                            Spacer(modifier = Modifier.height(8.dp))
                            Text("常用目的地：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                listOf("天安门广场", "北京西站", "北京南站", "首都机场T3").forEach { p ->
                                    AssistChip(
                                        onClick = { 
                                            viewModel.updateDestination(p)
                                            viewModel.searchAddress(p)
                                        }, 
                                        label = { Text(p, fontSize = 12.sp) }
                                    )
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
                                Icon(Icons.Default.Menu, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(40.dp))
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
                    // 障碍物检测提示
                    val nearObstacles = obstacleState.detectedObstacles.filter { it.distance < 5.0f }
                    if (nearObstacles.isNotEmpty()) {
                        val dangerObstacles = nearObstacles.filter { it.distance < 1.0f }
                        val bgColor = if (dangerObstacles.isNotEmpty()) ComposeColor(0xFFFFEBEE)
                            else ComposeColor(0xFFFFF3E0)
                        val textColor = if (dangerObstacles.isNotEmpty()) ComposeColor(0xFFD32F2F)
                            else ComposeColor(0xFFF57C00)
                        Card(modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = bgColor)) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.Warning, contentDescription = null, tint = textColor)
                                    Spacer(Modifier.width(8.dp))
                                    Text("环境感知提醒", fontWeight = FontWeight.Bold, color = textColor)
                                }
                                Spacer(Modifier.height(4.dp))
                                nearObstacles.take(3).forEach { obs ->
                                    Text(
                                        "${obs.type.chineseName} ${obs.direction.getChineseName()}${obs.distance.toInt()}米",
                                        color = textColor,
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
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

    // ★ P0 修复：MapView 生命周期管理
    // onCreate 已在 AndroidView.factory 中调用，这里只管理 onResume/onPause/onDestroy
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onResume(owner: LifecycleOwner) { mapViewRef?.onResume() }
            override fun onPause(owner: LifecycleOwner) { mapViewRef?.onPause() }
            override fun onDestroy(owner: LifecycleOwner) { mapViewRef?.onDestroy() }
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

/**
 * Hilt EntryPoint for ObstacleRepository in NavigationScreen
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface NavigationObstacleEntryPoint {
    fun obstacleRepository(): ObstacleRepository
}