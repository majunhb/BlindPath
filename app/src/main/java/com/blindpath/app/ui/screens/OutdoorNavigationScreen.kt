package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color as AndroidColor
import android.graphics.ImageFormat
import android.graphics.YuvImage
import android.media.Image
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.amap.api.maps.AMap
import com.amap.api.maps.CameraUpdateFactory
import com.amap.api.maps.MapView
import com.amap.api.maps.model.LatLng
import com.amap.api.maps.model.MyLocationStyle
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.base.navigation.BlindPathGuidanceEngine
import com.blindpath.base.perception.LowLightDetector
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_navigation.domain.model.RouteStep
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.data.detection.TactilePavingDetector
import com.blindpath.base.navigation.model.TactilePavingResult
import com.blindpath.module_obstacle.data.detection.TrafficLightClassifier
import com.blindpath.base.navigation.model.TrafficLightState as ModuleTrafficLightState
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.SceneRecognitionResult
import com.blindpath.module_obstacle.domain.model.SceneType
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors

// ============================================================================
// 数据模型 - 四层架构枚举与状态
// ============================================================================

/** 危险等级枚举 - 决策层 */
enum class DangerLevel(val label: String, val color: Color) {
    LOW("低风险", Color(0xFF2196F3)),       // 蓝色：远距离静态障碍物
    MEDIUM("中风险", Color(0xFFFFC107)),     // 黄色：缓慢移动障碍物
    HIGH("高风险", Color(0xFFFF9800)),       // 橙色：快速接近障碍物
    CRITICAL("紧急风险", Color(0xFFF44336))  // 红色：即将碰撞
}

/** 交通信号灯状态 - 感知层 */
enum class TrafficLightState(val label: String, val color: Color) {
    RED("红灯", Color(0xFFF44336)),
    GREEN("绿灯", Color(0xFF4CAF50)),
    YELLOW("黄灯", Color(0xFFFFC107)),
    UNKNOWN("未知", Color(0xFF9E9E9E))
}

/** 过街状态 - 决策层 */
enum class CrossingStatus(val label: String, val color: Color) {
    CAN_CROSS("允许通行", Color(0xFF4CAF50)),   // 斑马线+绿灯
    WAIT("等待避让", Color(0xFFF44336)),          // 斑马线+红灯
    DANGER("紧急避险", Color(0xFFFF9800)),        // 过街中+车辆逼近
    NONE("无过街需求", Color(0xFF9E9E9E))
}

/** 音频输出模式 - 引导层 */
enum class AudioOutputMode(val label: String) {
    BONE_CONDUCTION("骨传导"),
    SINGLE_EAR("单耳"),
    DUAL_EAR("双耳")
}

/** 震动模式 - 引导层 */
enum class VibrationPattern(val label: String, val description: String) {
    SHORT_100MS("短震", "100ms 轻触提示"),
    LONG_500MS("长震", "500ms 持续警告"),
    RAPID_3X("连续快震", "3x100ms 紧急警报")
}

/** 语音引导优先级 - 引导层 */
enum class VoiceGuidancePriority(val label: String, val color: Color) {
    ROUTINE("常规引导", Color(0xFF2196F3)),   // 每10-15秒
    EVENT("事件触发", Color(0xFFFF9800)),     // 即时
    URGENT("紧急打断", Color(0xFFF44336))      // 最高优先级
}

/** 导航模式枚举 - 保留原有 */
enum class NavigationMode(val chineseName: String) {
    WALK("步行导航"),
    BUS("公交导航"),
    SUBWAY("地铁导航"),
    TAXI("网约车")
}

/** 障碍物分类数据 - 感知层 */
data class ObstacleInfo(
    val type: ObstacleType,
    val distance: Float,      // 米
    val direction: String,    // 方位描述
    val speed: Float = 0f     // 移动速度 m/s
)

/** 障碍物类型 - 感知层 */
enum class ObstacleType(val label: String, val icon: String) {
    MOTOR_VEHICLE("机动车", "🚗"),
    NON_MOTOR_VEHICLE("非机动车", "🚲"),
    PEDESTRIAN("行人", "🚶"),
    STATIC("静态障碍", "🪨"),
    UNKNOWN("未知", "❓")
}

/** 路面高差类型 - 感知层 */
data class SurfaceChangeInfo(
    val type: SurfaceChangeType,
    val distance: Float,
    val heightDiff: Float     // 高差 cm
)

enum class SurfaceChangeType(val label: String, val icon: String) {
    STEP("台阶", "🪜"),
    RAMP("坡道", "↗"),
    CURB("路沿石", "▐")
}

/** 盲道监测状态 - 感知层 */
data class SidewalkStatus(
    val isOnSidewalk: Boolean,
    val textureDetected: Boolean = true,
    val confidence: Float = 1.0f,   // 0-1 检测置信度
    val distanceToNextBreak: Float = Float.MAX_VALUE  // 距下一段断点的距离
)

// ============================================================================
// 主屏幕 - 出行导航（四层架构重构版）
// ============================================================================

/**
 * 出行导航屏幕 - 四层架构重构版
 *
 * 架构分层：
 * 1. 感知层 (Perception)：交通信号、盲道监测、路面高差、障碍物检测
 * 2. 决策层 (Decision)：危险等级评估、过街决策、偏航检测
 * 3. 引导层 (Guidance)：语音引导、震动反馈、音频输出
 * 4. 交互层 (Interaction)：语音指令、实体按键
 *
 * 核心功能：
 * - 无障碍智能动态路径规划
 * - 盲道专项识别与全程守护导航
 * - 路口通行与过马路安全辅助
 * - 公共交通全流程接驳引导
 * - 智能安全防护与应急机制
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun OutdoorNavigationScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    sceneClassifier: SceneClassifier,
    onBack: () -> Unit,
    onHelpClick: () -> Unit = {},
    onSwitchToAr: () -> Unit = {},
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
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA)
                    == PackageManager.PERMISSION_GRANTED
        )
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        hasLocationPermission = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        hasCameraPermission = permissions[Manifest.permission.CAMERA] == true
    }

    // 自动请求权限
    LaunchedEffect(Unit) {
        val needed = mutableListOf<String>()
        if (!hasLocationPermission) needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
        if (!hasCameraPermission) needed.add(Manifest.permission.CAMERA)
        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        }
    }

    // 导航模式
    var currentNavMode by remember { mutableStateOf(NavigationMode.WALK) }

    // 地图控制器引用
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }
    var aMapRef by remember { mutableStateOf<AMap?>(null) }

    // ---- 感知层状态 ----
    var trafficLightState by remember { mutableStateOf(TrafficLightState.UNKNOWN) }
    var trafficCountdown by remember { mutableIntStateOf(0) }
    var sidewalkStatus by remember { mutableStateOf(SidewalkStatus(isOnSidewalk = true)) }
    var surfaceChangeAlerts by remember { mutableStateOf(listOf<SurfaceChangeInfo>()) }
    var detectedObstacles by remember { mutableStateOf(listOf<ObstacleInfo>()) }

    // ★ 从NavigationState同步障碍物感知数据（ObstacleRepository桥接）
    // 当NavigationService检测到障碍物时，数据会通过NavigationState.nearbyObstacles推送
    val navigationObstacles = uiState.nearbyObstacles
    val navigationAlertMsg = uiState.obstacleAlertMessage
    LaunchedEffect(navigationObstacles) {
        if (navigationObstacles.isNotEmpty()) {
            detectedObstacles = navigationObstacles.map { obs ->
                ObstacleInfo(
                    type = when {
                        obs.type.contains("行人") || obs.type.contains("人") -> ObstacleType.PEDESTRIAN
                        obs.type.contains("车") -> ObstacleType.MOTOR_VEHICLE
                        obs.type.contains("自行车") || obs.type.contains("电动车") -> ObstacleType.NON_MOTOR_VEHICLE
                        else -> ObstacleType.STATIC
                    },
                    distance = obs.distance,
                    direction = obs.direction,
                    speed = 0f  // 当前版本不提供速度数据
                )
            }
        }
    }

    // ---- 决策层状态 ----
    var dangerLevel by remember { mutableStateOf(DangerLevel.LOW) }
    var crossingStatus by remember { mutableStateOf(CrossingStatus.NONE) }
    // 偏航检测阈值：10米（方案要求从50米改为10米）
    val offRouteThreshold = 10f

    // ---- 引导层状态 ----
    var currentVoicePriority by remember { mutableStateOf(VoiceGuidancePriority.ROUTINE) }
    var lastVoiceMessage by remember { mutableStateOf("前方100米右转") }
    var currentVibrationPattern by remember { mutableStateOf(VibrationPattern.SHORT_100MS) }
    var audioOutputMode by remember { mutableStateOf(AudioOutputMode.BONE_CONDUCTION) }

    // ---- 交互层状态 ----
    var isListening by remember { mutableStateOf(false) }
    var lastVoiceCommand by remember { mutableStateOf<String?>(null) }

    // 安全状态（保留原有）
    var safetyAlert by remember { mutableStateOf<String?>(null) }

    // ====================================================================
    // ★ CameraX 实时环境感知（盲人的眼睛）
    // 后台 ImageAnalysis 帧 → SceneClassifier(斑马线/红绿灯/道牙/台阶/积水)
    //                       → ObstacleRepository(AI物体检测)
    // ====================================================================
    val frameChannel = remember { Channel<Bitmap>(Channel.CONFLATED) }
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var isDetectionActive by remember { mutableStateOf(false) }
    var cameraProviderRef by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    // ★ PRD V2.0 第三期：盲道/红绿灯专用检测器
    val tactilePavingDetector = remember { TactilePavingDetector() }
    val trafficLightClassifier = remember { TrafficLightClassifier() }

    // ★ PRD V2.0 第三期：弱光状态收集
    val lowLightState by navViewModel.lowLightState.collectAsStateWithLifecycle()

    // 加载模型并启动CameraX
    LaunchedEffect(Unit) {
        val modelResult = obstacleRepository.loadModel()
        if (modelResult !is com.blindpath.base.common.Result.Error) {
            isDetectionActive = true
            Timber.i("OutdoorNav: 障碍物检测模型加载成功")
        } else {
            Timber.w("OutdoorNav: 模型加载失败，使用CV视觉算法兜底")
        }
    }

    // 绑定CameraX生命周期
    DisposableEffect(lifecycleOwner) {
        val observer = object : DefaultLifecycleObserver {
            override fun onCreate(owner: LifecycleOwner) {
                val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                cameraProviderFuture.addListener({
                    val provider = cameraProviderFuture.get()
                    cameraProviderRef = provider
                    try {
                        val imageAnalysis = ImageAnalysis.Builder()
                            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                            .setTargetResolution(android.util.Size(480, 640))
                            .build()
                        imageAnalysis.setAnalyzer(cameraExecutor) { imageProxy ->
                            val bitmap = imageProxyToBitmap(imageProxy)
                            if (bitmap != null) {
                                frameChannel.trySend(bitmap)
                            }
                            imageProxy.close()
                        }
                        provider.bindToLifecycle(
                            owner,
                            CameraSelector.DEFAULT_BACK_CAMERA,
                            imageAnalysis
                        )
                        Timber.i("OutdoorNav: CameraX 已启动")
                    } catch (e: Exception) {
                        Timber.w(e, "OutdoorNav: CameraX 启动失败")
                    }
                }, ContextCompat.getMainExecutor(context))
            }
            override fun onDestroy(owner: LifecycleOwner) {
                cameraProviderRef?.unbindAll()
                cameraExecutor.shutdownNow()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // ★ 帧处理管道：实时场景识别 + 障碍物检测
    LaunchedEffect(isDetectionActive) {
        if (!isDetectionActive) return@LaunchedEffect
        var frameCount = 0
        var lastAnnounceTime = 0L
        while (!frameChannel.isClosedForReceive) {
            val result = frameChannel.receiveCatching()
            val bitmap = result.getOrNull() ?: continue
            frameCount++
            // 每5帧处理一次（平衡性能）
            if (frameCount % 5 != 0) {
                bitmap.recycle()
                continue
            }
            try {
                // ★ PRD V2.0 第三期：弱光检测（优先执行）
                navViewModel.processLowLightDetection(bitmap)

                // 1. 障碍物检测（AI）
                val bytes = ByteArray(bitmap.width * bitmap.height * 4)
                bitmap.copyPixelsToBuffer(ByteBuffer.wrap(bytes))
                val aiObstacles = try {
                    obstacleRepository.processFrame(bytes, bitmap.width, bitmap.height)
                } catch (e: Exception) { emptyList() }

                // ★ PRD V2.0 第三期：盲道检测（CV方案）
                val tactileResult: TactilePavingResult? = try {
                    tactilePavingDetector.detect(bitmap)
                } catch (e: Exception) { null }
                navViewModel.processBlindPathDetection(tactileResult)

                // 2. 场景识别（CV视觉算法）
                val sceneResult = sceneClassifier.recognizeScene(bitmap, aiObstacles)

                // ★ PRD V2.0 第三期：红绿灯分类
                val trafficLightObs = aiObstacles.firstOrNull {
                    it.type == com.blindpath.module_obstacle.domain.model.ObstacleType.TRAFFIC_LIGHT
                }
                if (trafficLightObs != null) {
                    val tlState = trafficLightClassifier.classify(
                        bitmap,
                        trafficLightObs.boundingBox.left,
                        trafficLightObs.boundingBox.top,
                        trafficLightObs.boundingBox.right,
                        trafficLightObs.boundingBox.bottom
                    )
                    navViewModel.processTrafficLightDetection(tlState)
                } else {
                    navViewModel.processTrafficLightDetection(ModuleTrafficLightState.UNKNOWN)
                }

                // 3. 更新UI状态
                if (sceneResult != null) {
                    // 更新交通信号灯状态
                    trafficLightState = when (sceneResult.sceneType) {
                        SceneType.TRAFFIC_SIGNAL_AREA -> TrafficLightState.UNKNOWN
                        SceneType.CROSSWALK -> TrafficLightState.UNKNOWN
                        else -> trafficLightState
                    }

                    // 更新盲道/人行道状态
                    sidewalkStatus = when (sceneResult.sceneType) {
                        SceneType.SIDEWALK -> SidewalkStatus(isOnSidewalk = true, textureDetected = true, confidence = 0.9f)
                        SceneType.ROAD -> SidewalkStatus(isOnSidewalk = false, textureDetected = false, confidence = 0.7f)
                        SceneType.CURB -> SidewalkStatus(isOnSidewalk = true, textureDetected = true, confidence = 0.8f)
                        else -> sidewalkStatus
                    }

                    // 更新路面高差告警
                    surfaceChangeAlerts = buildList {
                        when (sceneResult.sceneType) {
                            SceneType.CURB -> add(SurfaceChangeInfo(SurfaceChangeType.CURB, 1.5f, 10f))
                            SceneType.STAIR_ENTRANCE -> add(SurfaceChangeInfo(SurfaceChangeType.STEP, 2.0f, 15f))
                            SceneType.PUDDLE -> add(SurfaceChangeInfo(SurfaceChangeType.RAMP, 2.5f, 2f))
                            else -> {}
                        }
                    }

                    // 更新障碍物列表
                    if (aiObstacles.isNotEmpty()) {
                        detectedObstacles = aiObstacles.map { obs ->
                            ObstacleInfo(
                                type = mapObstacleType(obs.type.name),
                                distance = obs.distance,
                                direction = obs.direction.getChineseName(),
                                speed = 0f  // DetectedObstacle 无速度字段
                            )
                        }.sortedBy { it.distance }
                    }

                    // ★ 更新危险等级（PRD V2.0 对齐）
                    // - > 3m：不预警 (LOW)
                    // - 1.5m ≤ distance ≤ 3m：语音提示 (MEDIUM/HIGH)
                    // - < 1.5m：语音+震动，紧急 (CRITICAL)
                    val criticalObstacle = aiObstacles.any { it.distance < 1.5f }
                    val warningObstacle = aiObstacles.any { it.distance in 1.5f..3f }
                    dangerLevel = when {
                        criticalObstacle -> DangerLevel.CRITICAL
                        warningObstacle -> DangerLevel.HIGH
                        sceneResult.sceneType == SceneType.CROSSWALK -> DangerLevel.MEDIUM
                        sceneResult.sceneType == SceneType.INTERSECTION -> DangerLevel.HIGH
                        else -> DangerLevel.LOW
                    }

                    // 更新过街状态
                    crossingStatus = when (sceneResult.sceneType) {
                        SceneType.CROSSWALK -> CrossingStatus.WAIT
                        else -> CrossingStatus.NONE
                    }

                    // ★ 语音播报（PRD V2.0 对齐）
                    val now = System.currentTimeMillis()
                    val isImportant = sceneResult.sceneType in listOf(
                        SceneType.CROSSWALK, SceneType.INTERSECTION,
                        SceneType.TRAFFIC_SIGNAL_AREA, SceneType.CURB,
                        SceneType.STAIR_ENTRANCE, SceneType.PUDDLE
                    )
                    if (isImportant || now - lastAnnounceTime > 10000L) {
                        lastAnnounceTime = now
                        val announcement = sceneResult.sceneType.getEntryAnnouncement()
                        if (announcement.isNotEmpty()) {
                            viewModel.speak(announcement)
                        }
                    }
                    
                    // ★ PRD 障碍物预警规则（与 AR 模式保持一致）
                    val nearestCritical = aiObstacles.firstOrNull { it.distance < 1.5f }
                    val nearestWarning = aiObstacles.firstOrNull { it.distance in 1.5f..3f }
                    when {
                        // < 1.5m：语音 + 震动，"立即停止"
                        nearestCritical != null && now - lastAnnounceTime > 2000L -> {
                            lastAnnounceTime = now
                            viewModel.speak("立即停止！前方${nearestCritical.type.chineseName}距离${String.format("%.1f", nearestCritical.distance)}米")
                        }
                        // 1.5m-3m：语音提示"前方有障碍物"
                        nearestWarning != null && now - lastAnnounceTime > 5000L -> {
                            lastAnnounceTime = now
                            viewModel.speak("前方有障碍物，${nearestWarning.type.chineseName}在${nearestWarning.direction.getChineseName()}${nearestWarning.distance.toInt()}米")
                        }
                        // > 3m：不预警
                    }
                }

                bitmap.recycle()
            } catch (e: Exception) {
                Timber.w(e, "OutdoorNav: 帧处理失败")
                try { bitmap.recycle() } catch (_: Exception) {}
            }
        }
    }

    // ★ PRD V2.0 第三期：弱光自动亮度调节
    val outDoorActivity = context as? android.app.Activity
    LaunchedEffect(lowLightState.isLowLight) {
        outDoorActivity?.window?.let { window ->
            val layoutParams = window.attributes
            layoutParams.screenBrightness = if (lowLightState.isLowLight) {
                1.0f  // 最大亮度补光
            } else {
                -1.0f  // 恢复系统默认
            }
            window.attributes = layoutParams
        }
    }

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

                    // 帮助按钮
                    IconButton(onClick = onHelpClick) {
                        Icon(
                            Icons.Default.Info,
                            contentDescription = "使用帮助",
                            tint = Color(0xFF4CAF50)
                        )
                    }
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
            // 安全预警条（保留原有）
            SafetyAlertBar(safetyAlert = safetyAlert)

            // 高德地图视图（保留原有）
            Box(modifier = Modifier.fillMaxWidth().weight(0.45f)) {
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
                            aMap.setOnMapLoadedListener {
                                Timber.i("高德地图加载完成")
                            }
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

                // 盲道状态指示器（保留原有，导航中显示）
                if (uiState.isRunning) {
                    SidewalkStatusIndicator(isOnSidewalk = sidewalkStatus.isOnSidewalk)
                }

                // 感知层：交通信号状态浮层（导航中显示）
                if (uiState.isRunning) {
                    TrafficSignalIndicator(
                        state = trafficLightState,
                        countdown = trafficCountdown,
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                    )
                }

                // 感知层：路面高差预警浮层（导航中显示）
                if (uiState.isRunning && surfaceChangeAlerts.isNotEmpty()) {
                    SurfaceChangeAlert(
                        alerts = surfaceChangeAlerts,
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(8.dp)
                    )
                }

                // ★ AR实景导航按钮（高德地图风格 — 底部居中，带文字标签的圆角按钮）
                // 参考高德：导航界面左下角AR按钮，醒目大尺寸
                Button(
                    onClick = {
                        viewModel.speak("切换到AR实景导航模式")
                        onSwitchToAr()
                    },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .height(44.dp),
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color.White,
                        contentColor = Color(0xFF1B5E20)
                    ),
                    elevation = ButtonDefaults.buttonElevation(defaultElevation = 8.dp)
                ) {
                    Icon(
                        Icons.Default.CameraAlt,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = Color(0xFF4CAF50)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        "AR实景导航",
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                }
            }

            // ---- 导航信息面板（滚动区域）----
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
                viewModel = viewModel,
                // 感知层
                trafficLightState = trafficLightState,
                trafficCountdown = trafficCountdown,
                sidewalkStatus = sidewalkStatus,
                surfaceChangeAlerts = surfaceChangeAlerts,
                detectedObstacles = detectedObstacles,
                // 决策层
                dangerLevel = dangerLevel,
                crossingStatus = crossingStatus,
                offRouteThreshold = offRouteThreshold,
                // 引导层
                currentVoicePriority = currentVoicePriority,
                lastVoiceMessage = lastVoiceMessage,
                currentVibrationPattern = currentVibrationPattern,
                audioOutputMode = audioOutputMode,
                onAudioOutputModeChange = { audioOutputMode = it },
                // 交互层
                isListening = isListening,
                lastVoiceCommand = lastVoiceCommand,
                onVoiceCommand = { cmd ->
                    lastVoiceCommand = cmd
                    // TODO: 处理语音指令
                }
            )
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

// ============================================================================
// ★ CameraX 辅助函数
// ============================================================================

/**
 * 将 CameraX ImageProxy 转换为 Bitmap
 */
private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
    return try {
        // CameraX 1.3.0+ 内置方法，正确处理 YUV_420_888 → ARGB Bitmap
        imageProxy.toBitmap()
    } catch (e: Exception) {
        try {
            // 降级方案：手动 YUV → NV21 → JPEG → Bitmap
            val planes = imageProxy.planes
            val yBuffer = planes[0].buffer
            val uBuffer = planes[1].buffer
            val vBuffer = planes[2].buffer
            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()
            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)
            val yuvImage = YuvImage(nv21, ImageFormat.NV21, imageProxy.width, imageProxy.height, null)
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height), 80, out)
            val jpegBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e2: Exception) {
            null
        }
    }
}

/**
 * 将AI检测的障碍物类型映射到导航UI的障碍物类型
 */
private fun mapObstacleType(aiType: String): ObstacleType {
    return when {
        aiType.contains("car", true) || aiType.contains("truck", true) || aiType.contains("bus", true) ||
        aiType.contains("vehicle", true) || aiType.contains("motorcycle", true) -> ObstacleType.MOTOR_VEHICLE
        aiType.contains("bicycle", true) || aiType.contains("bike", true) -> ObstacleType.NON_MOTOR_VEHICLE
        aiType.contains("person", true) -> ObstacleType.PEDESTRIAN
        aiType.contains("bench", true) || aiType.contains("chair", true) || aiType.contains("table", true) ||
        aiType.contains("pole", true) || aiType.contains("sign", true) || aiType.contains("barrier", true) ||
        aiType.contains("cone", true) || aiType.contains("wall", true) -> ObstacleType.STATIC
        else -> ObstacleType.UNKNOWN
    }
}

// ============================================================================
// 感知层 UI 组件
// ============================================================================

/**
 * 交通信号状态指示器 - 感知层
 * 显示当前红绿灯状态及倒计时
 */
@Composable
fun TrafficSignalIndicator(
    state: TrafficLightState,
    countdown: Int,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFF212121).copy(alpha = 0.85f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 信号灯图标
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(state.color),
                contentAlignment = Alignment.Center
            ) {
                // 闪烁动画（红灯/黄灯时闪烁）
                if (state == TrafficLightState.RED || state == TrafficLightState.YELLOW) {
                    val infiniteTransition = rememberInfiniteTransition(label = "traffic_blink")
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(500, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "traffic_alpha"
                    )
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(state.color.copy(alpha = alpha))
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            // 状态文字
            Column {
                Text(
                    text = state.label,
                    color = state.color,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
                if (countdown > 0) {
                    Text(
                        text = "${countdown}s",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 盲道连续性实时监测组件 - 感知层
 * 显示盲道纹理检测状态、置信度及距下一段断点距离
 */
@Composable
fun SidewalkMonitor(
    status: SidewalkStatus,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (status.isOnSidewalk) {
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            } else {
                Color(0xFFFF9800).copy(alpha = 0.15f)
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 状态指示灯
            Box(
                modifier = Modifier
                    .size(12.dp)
                    .clip(CircleShape)
                    .background(
                        if (status.isOnSidewalk) Color(0xFF4CAF50)
                        else Color(0xFFFF9800)
                    )
            )
            Spacer(modifier = Modifier.width(8.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = if (status.isOnSidewalk) "盲道连续" else "盲道中断",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (status.isOnSidewalk) Color(0xFF4CAF50)
                    else Color(0xFFFF9800)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "纹理检测: ${if (status.textureDetected) "正常" else "异常"}",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "置信度: ${(status.confidence * 100).toInt()}%",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    if (status.distanceToNextBreak < Float.MAX_VALUE) {
                        Text(
                            text = "断点: ${status.distanceToNextBreak.toInt()}m",
                            fontSize = 11.sp,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 路面高差预警组件 - 感知层
 * 显示前方台阶/坡道/路沿石预警
 */
@Composable
fun SurfaceChangeAlert(
    alerts: List<SurfaceChangeInfo>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color(0xFFFF9800).copy(alpha = 0.9f)
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
            alerts.take(3).forEach { alert ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${alert.type.icon} ${alert.type.label}",
                        color = Color.White,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "前方${alert.distance.toInt()}m · 高差${alert.heightDiff.toInt()}cm",
                        color = Color.White.copy(alpha = 0.9f),
                        fontSize = 12.sp
                    )
                }
            }
        }
    }
}

/**
 * 动态障碍物检测面板 - 感知层
 * 分类显示机动车/非机动车/行人等障碍物
 */
@Composable
fun ObstacleDetectionPanel(
    obstacles: List<ObstacleInfo>,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Build,
                    contentDescription = null,
                    tint = Color(0xFFFF9800),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "障碍物检测",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    "${obstacles.size}个目标",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (obstacles.isEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "前方暂无障碍物",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF4CAF50)
                )
            } else {
                Spacer(modifier = Modifier.height(8.dp))
                // 按距离排序显示
                obstacles.sortedBy { it.distance }.take(5).forEach { obstacle ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = obstacle.type.icon,
                            fontSize = 18.sp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = obstacle.type.label,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.width(60.dp)
                        )
                        Text(
                            text = "${obstacle.direction} ${obstacle.distance.toInt()}m",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (obstacle.speed > 0f) {
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${obstacle.speed.toInt()}m/s",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFFF9800)
                            )
                        }
                    }
                }
            }
        }
    }
}

// ============================================================================
// 决策层 UI 组件
// ============================================================================

/**
 * 四级危险等级评估指示器 - 决策层
 * 低风险(蓝)/中风险(黄)/高风险(橙)/紧急风险(红)
 */
@Composable
fun DangerLevelIndicator(
    level: DangerLevel,
    modifier: Modifier = Modifier
) {
    // 紧急风险时闪烁动画
    val infiniteTransition = rememberInfiniteTransition(label = "danger_blink")
    val bgAlpha by if (level == DangerLevel.CRITICAL) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.3f,
            animationSpec = infiniteRepeatable(
                animation = tween(300, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "danger_alpha"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = level.color.copy(alpha = 0.15f * bgAlpha)
        )
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 危险等级指示灯
            Box(
                modifier = Modifier
                    .size(16.dp)
                    .clip(CircleShape)
                    .background(level.color.copy(alpha = bgAlpha)),
                contentAlignment = Alignment.Center
            ) {
                if (level == DangerLevel.CRITICAL) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(10.dp)
                    )
                }
            }
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = level.label,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = level.color.copy(alpha = bgAlpha)
            )
            Spacer(modifier = Modifier.width(8.dp))
            // 等级描述
            Text(
                text = when (level) {
                    DangerLevel.LOW -> "远距离静态障碍物"
                    DangerLevel.MEDIUM -> "缓慢移动障碍物"
                    DangerLevel.HIGH -> "快速接近障碍物"
                    DangerLevel.CRITICAL -> "即将碰撞！请立即停下"
                },
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 智能过街决策面板 - 决策层
 * 根据斑马线和信号灯状态给出过街建议
 */
@Composable
fun CrossingDecisionPanel(
    status: CrossingStatus,
    modifier: Modifier = Modifier
) {
    // 紧急避险时红色闪烁
    val infiniteTransition = rememberInfiniteTransition(label = "crossing_blink")
    val flashAlpha by if (status == CrossingStatus.DANGER) {
        infiniteTransition.animateFloat(
            initialValue = 1f,
            targetValue = 0.2f,
            animationSpec = infiniteRepeatable(
                animation = tween(200, easing = LinearEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "crossing_flash"
        )
    } else {
        remember { mutableStateOf(1f) }
    }

    val bgColor = when (status) {
        CrossingStatus.CAN_CROSS -> Color(0xFF4CAF50).copy(alpha = 0.15f)
        CrossingStatus.WAIT -> Color(0xFFF44336).copy(alpha = 0.15f)
        CrossingStatus.DANGER -> Color(0xFFFF9800).copy(alpha = 0.3f * flashAlpha)
        CrossingStatus.NONE -> Color.Transparent
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.Info,
                contentDescription = null,
                tint = status.color.copy(alpha = flashAlpha),
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column {
                Text(
                    text = "过街决策",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = status.label,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = status.color.copy(alpha = flashAlpha)
                )
            }
            Spacer(modifier = Modifier.weight(1f))
            // 状态说明
            Text(
                text = when (status) {
                    CrossingStatus.CAN_CROSS -> "斑马线+绿灯"
                    CrossingStatus.WAIT -> "斑马线+红灯"
                    CrossingStatus.DANGER -> "过街中+车辆逼近"
                    CrossingStatus.NONE -> "未检测到路口"
                },
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ============================================================================
// 引导层 UI 组件
// ============================================================================

/**
 * 分层语音引导状态面板 - 引导层
 * 显示当前语音引导优先级和最新播报内容
 */
@Composable
fun VoiceGuidancePanel(
    currentPriority: VoiceGuidancePriority,
    lastMessage: String,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Notifications,
                    contentDescription = null,
                    tint = currentPriority.color,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "语音引导",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                // 当前优先级标签
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .background(currentPriority.color.copy(alpha = 0.2f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = currentPriority.label,
                        fontSize = 11.sp,
                        color = currentPriority.color,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = lastMessage,
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(modifier = Modifier.height(6.dp))
            // 优先级说明
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                VoiceGuidancePriority.entries.forEach { priority ->
                    val isSelected = priority == currentPriority
                    Text(
                        text = "${priority.label}${if (isSelected) " ●" else ""}",
                        fontSize = 10.sp,
                        color = if (isSelected) priority.color
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                    )
                }
            }
        }
    }
}

/**
 * 分级震动反馈控制面板 - 引导层
 * 短震100ms / 长震500ms / 连续快震3x100ms
 */
@Composable
fun VibrationControlPanel(
    currentPattern: VibrationPattern,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "震动反馈",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                VibrationPattern.entries.forEach { pattern ->
                    val isSelected = pattern == currentPattern
                    FilterChip(
                        selected = isSelected,
                        onClick = { /* 切换震动模式 */ },
                        label = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = pattern.label,
                                    fontSize = 13.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                                Text(
                                    text = pattern.description,
                                    fontSize = 9.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF4CAF50)
                        )
                    )
                }
            }
        }
    }
}

/**
 * 音频输出模式切换 - 引导层
 * 骨传导 / 单耳 / 双耳
 */
@Composable
fun AudioOutputSelector(
    currentMode: AudioOutputMode,
    onModeChange: (AudioOutputMode) -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Settings,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "音频输出",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                AudioOutputMode.entries.forEach { mode ->
                    val isSelected = mode == currentMode
                    FilterChip(
                        selected = isSelected,
                        onClick = { onModeChange(mode) },
                        label = { Text(mode.label, fontSize = 13.sp) },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFF4CAF50).copy(alpha = 0.2f),
                            selectedLabelColor = Color(0xFF4CAF50)
                        )
                    )
                }
            }
        }
    }
}

// ============================================================================
// 交互层 UI 组件
// ============================================================================

/**
 * 语音指令快捷面板 - 交互层
 * 提供常用语音指令按钮
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceCommandPanel(
    isListening: Boolean,
    lastCommand: String?,
    onVoiceCommand: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    val voiceCommands = listOf(
        "开始导航到XXX",
        "我在哪",
        "附近有什么",
        "我要过马路",
        "暂停导航",
        "继续导航",
        "结束导航"
    )

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Star,
                    contentDescription = null,
                    tint = if (isListening) Color(0xFFF44336) else Color(0xFF4CAF50),
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "语音指令",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.weight(1f))
                if (isListening) {
                    // 录音中动画
                    val infiniteTransition = rememberInfiniteTransition(label = "mic_blink")
                    val micAlpha by infiniteTransition.animateFloat(
                        initialValue = 1f,
                        targetValue = 0.3f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(400, easing = LinearEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "mic_alpha"
                    )
                    Text(
                        "录音中...",
                        fontSize = 12.sp,
                        color = Color(0xFFF44336).copy(alpha = micAlpha)
                    )
                }
            }

            if (lastCommand != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "最近指令: $lastCommand",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                voiceCommands.forEach { cmd ->
                    AssistChip(
                        onClick = { onVoiceCommand(cmd) },
                        label = { Text(cmd, fontSize = 11.sp) },
                        leadingIcon = {
                            Icon(
                                Icons.Default.Star,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }
    }
}

/**
 * 实体按键操作说明 - 交互层
 * 双击音量上键 = 重复播报
 * 长按音量下键 = 紧急求助
 * 双击电源键 = 开关导航
 */
@Composable
fun HardwareKeyGuide(modifier: Modifier = Modifier) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "实体按键操作",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            HardwareKeyItem(
                action = "双击 音量上键",
                description = "重复播报当前导航指令"
            )
            Spacer(modifier = Modifier.height(4.dp))
            HardwareKeyItem(
                action = "长按 音量下键",
                description = "紧急求助（发送位置与求助信息）"
            )
            Spacer(modifier = Modifier.height(4.dp))
            HardwareKeyItem(
                action = "双击 电源键",
                description = "开始/关闭导航"
            )
        }
    }
}

@Composable
private fun HardwareKeyItem(action: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(Color(0xFF4CAF50).copy(alpha = 0.15f))
                .padding(horizontal = 8.dp, vertical = 4.dp)
        ) {
            Text(
                text = action,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF4CAF50)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = description,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// ============================================================================
// 保留的原有组件
// ============================================================================

/**
 * 安全预警条（保留原有）
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
 * 盲道状态指示器（保留原有，兼容新 SidewalkStatus）
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
            text = if (isOnSidewalk) "正在盲道上" else "已偏离盲道",
            color = Color.White,
            fontWeight = FontWeight.Bold,
            fontSize = 14.sp
        )
    }
}

/**
 * 导航模式选择器（保留原有）
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
            NavigationMode.entries.forEach { mode ->
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
 * 导航信息面板（保留原有 + 扩展四层架构组件）
 */
@OptIn(ExperimentalLayoutApi::class)
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
    viewModel: VoiceInteractionViewModel,
    // 感知层参数
    trafficLightState: TrafficLightState,
    trafficCountdown: Int,
    sidewalkStatus: SidewalkStatus,
    surfaceChangeAlerts: List<SurfaceChangeInfo>,
    detectedObstacles: List<ObstacleInfo>,
    // 决策层参数
    dangerLevel: DangerLevel,
    crossingStatus: CrossingStatus,
    offRouteThreshold: Float,
    // 引导层参数
    currentVoicePriority: VoiceGuidancePriority,
    lastVoiceMessage: String,
    currentVibrationPattern: VibrationPattern,
    audioOutputMode: AudioOutputMode,
    onAudioOutputModeChange: (AudioOutputMode) -> Unit,
    // 交互层参数
    isListening: Boolean,
    lastVoiceCommand: String?,
    onVoiceCommand: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .weight(0.55f)
            .padding(12.dp)
            .verticalScroll(rememberScrollState())
    ) {
        if (!uiState.isRunning && !isPlanning) {
            // ========== 目的地输入（保留原有）==========
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

                    // 常用目的地（保留原有）
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

        } else if (uiState.isRunning) {
            // ========== 导航运行中 ==========
            // 当前导航指令（保留原有）
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF4CAF50).copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    val hasSteps = uiState.routeSteps.isNotEmpty()
                    val stepIdx = if (hasSteps)
                        uiState.currentStepIndex.coerceIn(0, uiState.routeSteps.size - 1)
                    else 0
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
                                if (hasSteps) "第 ${stepIdx + 1}/${uiState.routeSteps.size} 步" else "路线规划中...",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                if (hasSteps) uiState.routeSteps[stepIdx].instruction else "等待路线",
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
                        NavInfoItem("距离", if (uiState.routeSteps.isNotEmpty()) uiState.routeSteps[stepIdx].distance else "--")
                        NavInfoItem("预计", if (uiState.routeSteps.isNotEmpty()) uiState.routeSteps[stepIdx].duration else "--")
                        NavInfoItem("全程", uiState.totalDistance.ifEmpty { "--" })
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 决策层：四级危险等级评估 ----
            DangerLevelIndicator(level = dangerLevel)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 决策层：智能过街决策面板 ----
            CrossingDecisionPanel(status = crossingStatus)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 感知层：盲道连续性监测 ----
            SidewalkMonitor(status = sidewalkStatus)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- PRD V2.0 第二期：盲道实时引导面板 ----
            BlindPathGuidancePanel(
                isBlindPathVisible = sidewalkStatus.isOnSidewalk,
                confidence = sidewalkStatus.confidence
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- PRD V2.0 第二期：弱光检测与屏幕补光 ----
            LowLightScreenLightPanel()

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 感知层：动态障碍物检测面板 ----
            ObstacleDetectionPanel(obstacles = detectedObstacles)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 引导层：语音引导状态 ----
            VoiceGuidancePanel(
                currentPriority = currentVoicePriority,
                lastMessage = lastVoiceMessage
            )

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 引导层：震动反馈控制 ----
            VibrationControlPanel(currentPattern = currentVibrationPattern)

            Spacer(modifier = Modifier.height(8.dp))

            // ---- 引导层：音频输出模式切换 ----
            AudioOutputSelector(
                currentMode = audioOutputMode,
                onModeChange = onAudioOutputModeChange
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 语音播报（保留原有）
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

            // 偏航提示（保留原有，阈值已改为10米）
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
                        Column {
                            Text(
                                "您已偏离路线（阈值${offRouteThreshold.toInt()}m），正在为您重新规划...",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F)
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            // 操作按钮（保留原有）
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

            // 路线步骤列表（保留原有）
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

// ============================================================================
// 保留的原有辅助组件
// ============================================================================

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

// ============================================================================
// PRD V2.0 第二期：盲道实时引导面板
// ============================================================================

/**
 * 盲道实时引导面板
 *
 * 显示当前盲道检测状态和引导指令：
 * - 盲道可见时：绿色标识 + 当前引导指令（直行/微调/转弯）
 * - 盲道丢失时：红色预警 + "未检测到盲道"提示
 * - 置信度条：显示检测置信度
 */
@Composable
private fun BlindPathGuidancePanel(
    isBlindPathVisible: Boolean,
    confidence: Float
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (isBlindPathVisible)
                Color(0xFF4CAF50).copy(alpha = 0.1f)
            else
                Color(0xFFFFEBEE)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                "盲道实时引导",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .background(
                            color = if (isBlindPathVisible) Color(0xFF4CAF50) else Color(0xFFF44336),
                            shape = CircleShape
                        )
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    if (isBlindPathVisible) "盲道检测中" else "未检测到盲道",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = if (isBlindPathVisible) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // 置信度条
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("置信度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(modifier = Modifier.width(8.dp))
                LinearProgressIndicator(
                    progress = confidence,
                    modifier = Modifier.weight(1f).height(4.dp),
                    color = if (isBlindPathVisible) Color(0xFF4CAF50) else Color(0xFFF44336)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    "${(confidence * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

// ============================================================================
// PRD V2.0 第二期：弱光检测与屏幕补光面板
// ============================================================================

/**
 * 弱光检测与屏幕补光面板
 *
 * 当检测到弱光环境时：
 * - 显示弱光警告
 * - 自动调节屏幕亮度到最大
 * - 显示白色补光面板（利用屏幕光源照亮前方环境）
 *
 * 当光线恢复正常时自动关闭
 */
@Composable
private fun LowLightScreenLightPanel() {
    val viewModel: NavigationViewModel = hiltViewModel()
    val lowLightState by viewModel.lowLightState.collectAsStateWithLifecycle()

    if (lowLightState.isLowLight) {
        // 弱光环境 → 显示补光面板
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0))
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Info,
                        contentDescription = null,
                        tint = Color(0xFFFF9800),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "弱光环境已开启屏幕补光",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFE65100)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "平均亮度：${lowLightState.averageBrightness.toInt()}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
