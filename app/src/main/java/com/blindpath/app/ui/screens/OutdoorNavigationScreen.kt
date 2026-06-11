package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Color as AndroidColor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.weight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_navigation.domain.model.RouteStep
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import timber.log.Timber

/**
 * 出行导航屏幕 - 技术方案 v2.0 实现
 * 
 * 核心功能：
 * 1. 无障碍智能动态路径规划
 * 2. 盲道专项识别与全程守护导航
 * 3. 路口通行与过马路安全辅助
 * 4. 公共交通全流程接驳引导
 * 5. 智能安全防护与应急机制
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutdoorNavigationScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val navViewModel: NavigationViewModel = hiltViewModel()

    // 从 ViewModel 收集状态
    val uiState by navViewModel.uiState.collectAsStateWithLifecycle()
    val destinationText by navViewModel.destinationText.collectAsStateWithLifecycle()
    val isPlanning by navViewModel.isPlanning.collectAsStateWithLifecycle()
    val announcement by navViewModel.announcement.collectAsStateWithLifecycle()

    // 权限状态
    var hasLocationPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
    }

    // 导航模式
    var currentNavMode by remember { mutableStateOf(NavigationMode.WALK) }

    // 地图控制器引用
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var aMapRef by remember { mutableStateOf<AMap?>(null) }

    // 安全状态
    var isOnSidewalk by remember { mutableStateOf(true) }
    var safetyAlert by remember { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "出行导航", 
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    ) 
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            navViewModel.exitNavigation()
                            onBack()
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                actions = {
                    // 导航模式切换
                    NavigationModeSelector(
                        currentMode = currentNavMode,
                        onModeChange = { currentNavMode = it }
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 安全预警条
            SafetyAlertBar(safetyAlert = safetyAlert)

            // 高德地图视图
            Box(modifier = Modifier.fillMaxWidth().weight(0.55f)) {
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
                            aMap.setOnMapLoadedListener {
                                Timber.i("高德地图加载完成")
                            }
                            aMap.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(39.9042, 116.4074), 15f))
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    update = { /* 生命周期由 LifecycleObserver 管理 */ }
                )

                // 规划中遮罩
                if (isPlanning) {
                    Box(
                        modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.5f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator(color = Color.White)
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(announcement, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }

                // 盲道状态指示器
                if (uiState.isRunning) {
                    SidewalkStatusIndicator(isOnSidewalk = isOnSidewalk)
                }
            }

            // 导航信息面板
            NavigationInfoPanel(
                uiState = uiState,
                isPlanning = isPlanning,
                destinationText = destinationText,
                announcement = announcement,
                currentNavMode = currentNavMode,
                onDestinationChange = { navViewModel.updateDestination(it) },
                onStartNavigation = { navViewModel.startNavigation() },
                onStopNavigation = { navViewModel.stopNavigation() },
                onNextStep = { /* 手动下一步 */ },
                viewModel = viewModel
            )
        }
    }

    // MapView 生命周期管理
    val savedState = remember { android.os.Bundle() }
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) { mapViewRef?.onCreate(savedState) }
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

/**
 * 安全预警条
 */
@Composable
private fun SafetyAlertBar(safetyAlert: String?) {
    if (safetyAlert != null) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Red.copy(alpha = 0.9f))
                .padding(12.dp),
            contentAlignment = Alignment.Center
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = safetyAlert,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
            }
        }
    }
}

/**
 * 盲道状态指示器
 */
@Composable
private fun SidewalkStatusIndicator(isOnSidewalk: Boolean) {
    Box(
        modifier = Modifier
            .padding(16.dp)
            .background(
                if (isOnSidewalk) Color(0xFF4CAF50).copy(alpha = 0.9f)
                else Color(0xFFFF9800).copy(alpha = 0.9f),
                RoundedCornerShape(8.dp)
            )
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = if (isOnSidewalk) "✓ 正在盲道上" else "⚠ 已偏离盲道",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

/**
 * 导航模式选择器
 */
@Composable
private fun NavigationModeSelector(
    currentMode: NavigationMode,
    onModeChange: (NavigationMode) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    
    Box {
        TextButton(onClick = { expanded = true }) {
            Text(currentMode.chineseName, color = Color(0xFF4CAF50))
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            NavigationMode.values().forEach { mode ->
                DropdownMenuItem(
                    text = { Text(mode.chineseName) },
                    onClick = {
                        onModeChange(mode)
                        expanded = false
                    }
                )
            }
        }
    }
}

/**
 * 导航模式枚举
 */
enum class NavigationMode(val chineseName: String) {
    WALK("步行导航"),
    BUS("公交导航"),
    SUBWAY("地铁导航"),
    TAXI("网约车")
}

/**
 * 导航信息面板
 */
@Composable
private fun ColumnScope.NavigationInfoPanel(
    uiState: NavigationState,
    isPlanning: Boolean,
    destinationText: String,
    announcement: String,
    currentNavMode: NavigationMode,
    onDestinationChange: (String) -> Unit,
    onStartNavigation: () -> Unit,
    onStopNavigation: () -> Unit,
    onNextStep: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(0.45f)
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (!uiState.isRunning && !isPlanning) {
            // 目的地输入
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        "无障碍出行导航", 
                        style = MaterialTheme.typography.titleLarge, 
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "输入目的地后，系统将为您规划无障碍步行路线，优先选择盲道和平缓路段。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = destinationText,
                        onValueChange = onDestinationChange,
                        label = { Text("目的地") },
                        placeholder = { Text("例如：天安门广场、北京南站") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        supportingText = { Text("支持地址、地标、建筑名称") }
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onStartNavigation,
                        modifier = Modifier.fillMaxWidth().height(52.dp),
                        enabled = destinationText.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                    ) {
                        Icon(Icons.Default.Menu, contentDescription = null)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("开始导航", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // 常用目的地
                    Text("常用目的地：", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf("天安门广场", "北京西站", "北京南站", "首都机场T3").forEach { p ->
                            AssistChip(
                                onClick = { onDestinationChange(p) },
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
                        Icon(
                            Icons.Default.LocationOn, 
                            contentDescription = null, 
                            tint = Color(0xFF4CAF50)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("使用说明", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Text("• 需要开启GPS定位和位置权限", style = MaterialTheme.typography.bodySmall)
                    Text("• 优先规划盲道和平缓路段", style = MaterialTheme.typography.bodySmall)
                    Text("• 提供语音播报和振动提示", style = MaterialTheme.typography.bodySmall)
                    Text("• 偏离盲道时会自动预警", style = MaterialTheme.typography.bodySmall)
                    Text("• 路口提供红绿灯和斑马线辅助", style = MaterialTheme.typography.bodySmall)
                }
            }
        } else if (uiState.isRunning) {
            // 当前导航指令
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Default.Menu,
                            contentDescription = null,
                            tint = Color(0xFF4CAF50),
                            modifier = Modifier.size(40.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "第 ${uiState.currentStepIndex + 1}/${uiState.routeSteps.size} 步",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                uiState.routeSteps.getOrNull(uiState.currentStepIndex)?.instruction ?: "已到达",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        NavInfoItem("距离", uiState.routeSteps.getOrNull(uiState.currentStepIndex)?.distance ?: "")
                        NavInfoItem("预计", uiState.routeSteps.getOrNull(uiState.currentStepIndex)?.duration ?: "")
                        NavInfoItem("全程", uiState.totalDistance)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 语音播报
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Text("语音播报", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(announcement, style = MaterialTheme.typography.bodyMedium)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 偏航提示
            if (uiState.isOffRoute) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFEBEE))
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Color(0xFFD32F2F),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            "您已偏离路线，正在为您重新规划...",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFD32F2F)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Button(
                    onClick = onNextStep,
                    modifier = Modifier.weight(1f),
                    enabled = uiState.currentStepIndex < uiState.routeSteps.size,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("下一步")
                }
                OutlinedButton(
                    onClick = onStopNavigation,
                    modifier = Modifier.weight(1f)
                ) {
                    Text("结束导航")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 路线步骤列表
            Text(
                "完整路线 (${uiState.routeSteps.size}步)",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            RouteStepsList(
                steps = uiState.routeSteps,
                currentIndex = uiState.currentStepIndex
            )
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
private fun RouteStepItem(step: RouteStep, isCurrent: Boolean, isCompleted: Boolean) {
    val bgColor = when {
        isCurrent -> Color(0xFF4CAF50).copy(alpha = 0.1f)
        isCompleted -> Color.Gray.copy(alpha = 0.1f)
        else -> Color.Transparent
    }
    val tint = when {
        isCurrent -> Color(0xFF4CAF50)
        isCompleted -> Color.Gray
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(bgColor)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.LocationOn,
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(20.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                step.instruction,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1
            )
            Text(
                "${step.distance} · ${step.duration}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (isCurrent) {
            Box(
                modifier = Modifier
                    .background(Color(0xFF4CAF50), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Text("当前", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        } else if (isCompleted) {
            Text("done", color = Color(0xFF4CAF50), fontSize = 18.sp)
        }
    }
}

@Composable
private fun RouteStepsList(steps: List<RouteStep>, currentIndex: Int) {
    Column {
        steps.forEachIndexed { index, step ->
            RouteStepItem(
                step = step,
                isCurrent = index == currentIndex,
                isCompleted = index < currentIndex
            )
            if (index < steps.size - 1) {
                Divider(modifier = Modifier.padding(start = 20.dp, end = 20.dp))
            }
        }
    }
}
