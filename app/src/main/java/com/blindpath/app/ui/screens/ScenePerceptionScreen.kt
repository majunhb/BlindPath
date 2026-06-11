package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Face
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
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
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import timber.log.Timber
import java.util.concurrent.Executors

/**
 * 场景感知屏幕 - 技术方案 v3.0 实现
 * 
 * 核心功能：
 * 1. 通用环境全景语义描述
 * 2. 全品类物体与文字识别解析
 * 3. 人物与社交场景智能感知
 * 4. 细分特殊场景定制化感知服务
 * 5. 全场景统一多模态交互体系
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScenePerceptionScreen(
    obstacleRepository: ObstacleRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()

    // 权限状态
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED
        )
    }

    // 检测状态
    var isDetecting by remember { mutableStateOf(false) }
    var isModelLoading by remember { mutableStateOf(false) }
    var currentSceneDescription by remember { mutableStateOf("请授权相机权限后开始场景感知") }
    var detectedObjects by remember { mutableStateOf<List<DetectedObjectInfo>>(emptyList()) }
    var detectedPeople by remember { mutableStateOf(0) }
    var environmentType by remember { mutableStateOf("未知环境") }
    var lastAnnouncement by remember { mutableStateOf("") }
    var lastAlertTime by remember { mutableStateOf(0L) }

    // 功能模式
    var currentMode by remember { mutableStateOf(ScenePerceptionMode.GENERAL) }

    // 帧处理
    var frameSkipCounter by remember { mutableStateOf(0) }
    val processEveryNFrames = 5 // 场景感知跳帧更多，降低功耗

    val frameChannel = remember { Channel<Bitmap>(Channel.CONFLATED) }
    val detectionScope = rememberCoroutineScope()

    // 权限请求
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            currentSceneDescription = "相机权限已获取，点击开始场景感知"
            viewModel.speak("相机权限已获取")
        } else {
            currentSceneDescription = "相机权限被拒绝，无法进行场景感知"
            viewModel.speak("需要相机权限才能使用场景感知功能")
        }
    }

    // 模拟场景检测（实际应接入AI模型）
    LaunchedEffect(isDetecting) {
        if (isDetecting) {
            isModelLoading = true
            currentSceneDescription = "正在加载场景感知模型..."
            viewModel.speak("正在加载场景感知模型")
            
            delay(1500) // 模拟模型加载
            isModelLoading = false
            
            currentSceneDescription = "场景感知已启动，正在分析周围环境"
            viewModel.speak("场景感知已启动，正在分析周围环境")
            
            // 模拟定期场景描述
            while (isDetecting) {
                delay(8000)
                if (isDetecting) {
                    val description = generateMockSceneDescription(currentMode)
                    currentSceneDescription = description
                    detectedObjects = generateMockObjects()
                    detectedPeople = (0..3).random()
                    environmentType = listOf("城市人行道", "商场内部", "公园", "地铁站", "餐厅").random()
                    
                    viewModel.speak(description)
                }
            }
        } else {
            currentSceneDescription = "场景感知已停止"
            detectedObjects = emptyList()
            detectedPeople = 0
            viewModel.speak("场景感知已停止")
        }
    }

    // 清理
    DisposableEffect(Unit) {
        onDispose {
            detectionScope.cancel()
            frameChannel.close()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "场景感知", 
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFFFF9800)
                    ) 
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            isDetecting = false
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
                    if (!hasCameraPermission) {
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("授权相机")
                        }
                    } else {
                        Button(
                            onClick = { isDetecting = !isDetecting },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (isDetecting) MaterialTheme.colorScheme.error
                                else Color(0xFFFF9800)
                            )
                        ) {
                            Text(if (isDetecting) "停止" else "开始")
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF9800).copy(alpha = 0.1f)
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // 功能模式选择
            SceneModeSelector(
                currentMode = currentMode,
                onModeChange = { currentMode = it }
            )

            // 相机预览区域
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .semantics {
                        contentDescription = "相机预览区域，显示场景画面"
                    },
                contentAlignment = Alignment.Center
            ) {
                if (hasCameraPermission) {
                    AndroidView(
                        factory = { ctx ->
                            PreviewView(ctx).apply {
                                scaleType = PreviewView.ScaleType.FILL_CENTER
                                implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                        update = { previewView ->
                            val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
                            cameraProviderFuture.addListener({
                                val cameraProvider = cameraProviderFuture.get()

                                val preview = Preview.Builder().build().also {
                                    it.setSurfaceProvider(previewView.surfaceProvider)
                                }

                                val imageAnalysis = ImageAnalysis.Builder()
                                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                                    .setTargetResolution(android.util.Size(480, 640))
                                    .build()
                                    .also { analysis ->
                                        analysis.setAnalyzer(
                                            Executors.newSingleThreadExecutor()
                                        ) { imageProxy ->
                                            frameSkipCounter++
                                            if (frameSkipCounter % processEveryNFrames != 0) {
                                                imageProxy.close()
                                                return@setAnalyzer
                                            }
                                            imageProxy.close()
                                        }
                                    }

                                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
                                try {
                                    cameraProvider.unbindAll()
                                    cameraProvider.bindToLifecycle(
                                        lifecycleOwner, cameraSelector, preview, imageAnalysis
                                    )
                                } catch (e: Exception) {
                                    Timber.e(e, "相机绑定失败")
                                }
                            }, ContextCompat.getMainExecutor(context))
                        }
                    )

                    // 环境类型叠加层
                    if (isDetecting) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 32.dp)
                                .background(
                                    Color(0xFFFF9800).copy(alpha = 0.9f),
                                    RoundedCornerShape(16.dp)
                                )
                                .padding(horizontal = 32.dp, vertical = 16.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Search,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.width(12.dp))
                                Text(
                                    text = environmentType,
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 28.sp
                                )
                            }
                        }
                    }

                    // 模型加载中
                    if (isModelLoading) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(12.dp))
                                .padding(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                CircularProgressIndicator(color = Color.White)
                                Spacer(modifier = Modifier.height(12.dp))
                                Text("正在加载AI模型...", color = Color.White, fontSize = 16.sp)
                            }
                        }
                    }
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.background(Color.Black)
                    ) {
                        Text(text = "需要相机权限", color = Color.White, fontSize = 24.sp)
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                            Text("授权相机权限")
                        }
                    }
                }
            }

            // 场景描述面板
            SceneDescriptionPanel(
                sceneDescription = currentSceneDescription,
                detectedObjects = detectedObjects,
                detectedPeople = detectedPeople,
                environmentType = environmentType,
                isDetecting = isDetecting,
                currentMode = currentMode
            )
        }
    }
}

/**
 * 场景感知模式选择器
 */
@Composable
private fun SceneModeSelector(
    currentMode: ScenePerceptionMode,
    onModeChange: (ScenePerceptionMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScenePerceptionMode.values().forEach { mode ->
            FilterChip(
                selected = currentMode == mode,
                onClick = { onModeChange(mode) },
                label = { Text(mode.chineseName) },
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * 场景感知模式枚举
 */
enum class ScenePerceptionMode(val chineseName: String) {
    GENERAL("全景描述"),
    OBJECT("物体识别"),
    TEXT("文字朗读"),
    SOCIAL("社交感知")
}

/**
 * 检测到的物体信息
 */
data class DetectedObjectInfo(
    val name: String,
    val category: String,
    val distance: Float,
    val confidence: Float
)

/**
 * 生成模拟场景描述（实际应接入AI模型）
 */
private fun generateMockSceneDescription(mode: ScenePerceptionMode): String {
    return when (mode) {
        ScenePerceptionMode.GENERAL -> {
            listOf(
                "当前位于城市人行道，前方10米为十字路口，右侧为社区便利店，左侧为绿化步道，路面平整无障碍",
                "当前位于商场内部，前方为自动扶梯，右侧为服装区，左侧为餐饮区，环境明亮",
                "当前位于公园，前方为步行道，两侧有树木和长椅，环境安静，适合休息",
                "当前位于地铁站台，前方为列车轨道，请站在黄色安全线后等待"
            ).random()
        }
        ScenePerceptionMode.OBJECT -> {
            listOf(
                "检测到前方3米处有垃圾桶，右侧2米处有长椅，左侧有路灯",
                "检测到前方5米处有自动售货机，右侧为商店入口",
                "检测到前方有台阶，共5级，请小心慢行",
                "检测到前方有自行车停放，请从左侧绕行"
            ).random()
        }
        ScenePerceptionMode.TEXT -> {
            listOf(
                "前方招牌：麦当劳，24小时营业",
                "前方路牌：朝阳路，向东500米",
                "前方标识：地铁站A出口，无障碍通道",
                "前方告示：施工区域，请绕行"
            ).random()
        }
        ScenePerceptionMode.SOCIAL -> {
            listOf(
                "前方2米处有行人正向你走来，请减速避让",
                "右侧有人驻足停留，前方通道畅通",
                "前方人群聚集，约有10人，请小心通过",
                "未检测到附近有人，环境安静"
            ).random()
        }
    }
}

/**
 * 生成模拟检测物体
 */
private fun generateMockObjects(): List<DetectedObjectInfo> {
    val objects = listOf(
        DetectedObjectInfo("垃圾桶", "公共设施", 3.0f, 0.85f),
        DetectedObjectInfo("长椅", "家具", 2.5f, 0.78f),
        DetectedObjectInfo("路灯", "基础设施", 5.0f, 0.92f),
        DetectedObjectInfo("自行车", "交通工具", 4.0f, 0.65f),
        DetectedObjectInfo("商店招牌", "标识", 8.0f, 0.88f),
        DetectedObjectInfo("台阶", "障碍物", 2.0f, 0.95f),
        DetectedObjectInfo("自动门", "设施", 6.0f, 0.72f),
        DetectedObjectInfo("消防栓", "安全设施", 3.5f, 0.80f)
    )
    return objects.shuffled().take((2..5).random())
}

/**
 * 场景描述面板
 */
@Composable
private fun SceneDescriptionPanel(
    sceneDescription: String,
    detectedObjects: List<DetectedObjectInfo>,
    detectedPeople: Int,
    environmentType: String,
    isDetecting: Boolean,
    currentMode: ScenePerceptionMode
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        // 场景描述卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = Color(0xFFFF9800).copy(alpha = 0.1f)
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "场景描述",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    if (isDetecting) {
                        Text(
                            text = environmentType,
                            style = MaterialTheme.typography.labelMedium,
                            color = Color(0xFFFF9800)
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = sceneDescription,
                    style = MaterialTheme.typography.bodyLarge
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 人物检测
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (detectedPeople > 0)
                    MaterialTheme.colorScheme.primaryContainer
                else
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Row(
                modifier = Modifier.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Face,
                    contentDescription = null,
                    tint = if (detectedPeople > 0) Color(0xFFFF9800) else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(32.dp)
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = "人物检测",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (detectedPeople > 0) "检测到 $detectedPeople 人" else "未检测到人物",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // 检测到的物体列表
        Text(
            text = "检测到的物体 (${detectedObjects.size})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(8.dp))

        if (detectedObjects.isEmpty()) {
            Text(
                text = if (isDetecting) "正在扫描场景..." else "未开始检测",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            Column {
                detectedObjects.forEach { obj ->
                    DetectedObjectItem(obj)
                    Spacer(modifier = Modifier.height(8.dp))
                }
            }
        }
    }
}

/**
 * 检测到的物体项
 */
@Composable
private fun DetectedObjectItem(obj: DetectedObjectInfo) {
    val categoryColor = when (obj.category) {
        "障碍物" -> Color.Red
        "安全设施" -> Color(0xFF4CAF50)
        "公共设施" -> Color(0xFF2196F3)
        else -> Color(0xFFFF9800)
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = categoryColor.copy(alpha = 0.1f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(categoryColor.copy(alpha = 0.2f), RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = obj.name.first().toString(),
                    fontWeight = FontWeight.Bold,
                    color = categoryColor,
                    fontSize = 18.sp
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = obj.name,
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Text(
                    text = "类别: ${obj.category} | 距离: ${obj.distance.toInt()}米 | 置信度: ${(obj.confidence * 100).toInt()}%",
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/**
 * 旋转 Bitmap
 */
private fun rotateBitmap(bitmap: Bitmap, degrees: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degrees)
    return Bitmap.createBitmap(
        bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true
    )
}
