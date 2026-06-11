/**
 * BlindPath - 瑙嗛殰浜哄＋鍑鸿杈呭姪搴旂敤
 * 
 * 鏂囦欢锛歁ainScreen.kt
 * 璺緞锛歛pp/src/main/java/com/blindpath/app/ui/screens/
 * 
 * 鐗堟湰 v3.0 - 棣栭〉閲嶆瀯
 * 
 * 閲嶆瀯鍐呭锛? * 1. 绉婚櫎"鐜鎰熺煡"鍏ュ彛
 * 2. 棣栭〉鐩存帴鏄剧ず涓夊ぇ妯″潡锛氬鍐呮劅鐭ャ€佸嚭琛屽鑸€佸満鏅劅鐭? * 3. 姣忎釜妯″潡鐙珛鍏ュ彛锛屽姛鑳芥洿鍔犳竻鏅? * 
 * 涓夊ぇ鏍稿績妯″潡锛? * - 瀹ゅ唴鎰熺煡锛氬鍐呭鑸€侀殰纰嶇墿妫€娴嬨€佺┖闂寸悊瑙? * - 鍑鸿瀵艰埅锛氬澶栧鑸€佺洸閬撳紩瀵笺€佷氦閫氳緟鍔? * - 鍦烘櫙鎰熺煡锛氱墿浣撹瘑鍒€佸満鏅弿杩般€佹枃瀛楁湕璇? */

package com.blindpath.app.ui.screens

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.blindpath.base.common.AlertLevel
import com.blindpath.base.tts.VibrationHelper
import com.blindpath.base.power.DeviceOrientationCalculator
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_obstacle.domain.ItemSearchManager
import com.blindpath.module_obstacle.domain.BusGuideManager
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_navigation.domain.model.LocationInfo
import com.blindpath.module_settings.ui.SettingsScreen
import com.blindpath.module_community.ui.CommunityScreen
import com.blindpath.module_trip_assist.ui.TripAssistScreen
import com.blindpath.module_voice.domain.model.VoiceCommand
import com.blindpath.module_voice.domain.model.VoiceGuidance
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import kotlin.math.abs

/**
 * 涓荤晫闈?- 瑙嗛殰鍙嬪ソ鏋佺畝璁捐 v6.0
 * 
 * 璁捐鍘熷垯锛? * 1. 棣栭〉涓夊ぇ妯″潡鍏ュ彛锛氬鍐呮劅鐭?/ 鍑鸿瀵艰埅 / 鍦烘櫙鎰熺煡
 * 2. 姣忎釜妯″潡鍔熻兘鐙珛娓呮櫚
 * 3. 璇煶鎸囦护椹卞姩
 * 4. 楂樺姣斿害锛氳摑鐧戒富璋冿紝绾㈣壊璀︾ず
 */
@Composable
fun MainScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    onObstacleDetectionClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onSosClick: () -> Unit = {},
    viewModel: VoiceInteractionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    var showSettings by remember { mutableStateOf(false) }
    var showCommunity by remember { mutableStateOf(false) }
    var showTripAssist by remember { mutableStateOf(false) }
    var showIndoorPerception by remember { mutableStateOf(false) }
    var showOutdoorNavigation by remember { mutableStateOf(false) }
    var showScenePerception by remember { mutableStateOf(false) }

    // 鏀堕泦闅滅鐗╃姸鎬?    val obstacleState by obstacleRepository.obstacleState.collectAsStateWithLifecycle(
        initialValue = ObstacleState()
    )

    val commandScope = rememberCoroutineScope()

    // 璁剧疆璇煶鎸囦护澶勭悊鍣?    LaunchedEffect(Unit) {
        viewModel.setCommandHandler { command ->
            Timber.d("MainScreen: Handling voice command - ${command.name}")
            when (command) {
                VoiceCommand.START_INDOOR_PERCEPTION -> {
                    showIndoorPerception = true
                    viewModel.speak("瀹ゅ唴鎰熺煡宸插惎鍔?)
                    true
                }
                VoiceCommand.STOP_INDOOR_PERCEPTION -> {
                    showIndoorPerception = false
                    viewModel.speak("瀹ゅ唴鎰熺煡宸插仠姝?)
                    true
                }
                VoiceCommand.START_OUTDOOR_NAVIGATION -> {
                    showOutdoorNavigation = true
                    viewModel.speak("鍑鸿瀵艰埅宸插惎鍔?)
                    true
                }
                VoiceCommand.STOP_OUTDOOR_NAVIGATION -> {
                    showOutdoorNavigation = false
                    viewModel.speak("鍑鸿瀵艰埅宸插仠姝?)
                    true
                }
                VoiceCommand.START_SCENE_PERCEPTION -> {
                    showScenePerception = true
                    viewModel.speak("鍦烘櫙鎰熺煡宸插惎鍔?)
                    true
                }
                VoiceCommand.STOP_SCENE_PERCEPTION -> {
                    showScenePerception = false
                    viewModel.speak("鍦烘櫙鎰熺煡宸插仠姝?)
                    true
                }
                VoiceCommand.WHERE_AM_I -> {
                    onLocationClick()
                    true
                }
                VoiceCommand.SOS, VoiceCommand.CALL_SOS -> {
                    onSosClick()
                    viewModel.speak(VoiceGuidance.SOS_TRIGGERED)
                    true
                }
                else -> false
            }
        }
    }

    // 椤甸潰璺敱
    when {
        showSettings -> {
            SettingsScreen(
                onBack = { showSettings = false },
                modifier = Modifier.fillMaxSize()
            )
            return
        }
        showCommunity -> {
            CommunityScreen(
                onBack = { showCommunity = false },
                modifier = Modifier.fillMaxSize()
            )
            return
        }
        showTripAssist -> {
            TripAssistScreen(
                onBack = { showTripAssist = false },
                modifier = Modifier.fillMaxSize()
            )
            return
        }
        showIndoorPerception -> {
            IndoorPerceptionScreen(
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository,
                onBack = { showIndoorPerception = false },
                viewModel = viewModel
            )
            return
        }
        showOutdoorNavigation -> {
            OutdoorNavigationScreen(
                obstacleRepository = obstacleRepository,
                navigationRepository = navigationRepository,
                onBack = { showOutdoorNavigation = false },
                viewModel = viewModel
            )
            return
        }
        showScenePerception -> {
            ScenePerceptionScreen(
                obstacleRepository = obstacleRepository,
                onBack = { showScenePerception = false },
                viewModel = viewModel
            )
            return
        }
    }

    // 涓荤晫闈?    MainScreenContent(
        uiState = uiState,
        obstacleState = obstacleState,
        onIndoorPerceptionClick = { showIndoorPerception = true },
        onOutdoorNavigationClick = { showOutdoorNavigation = true },
        onScenePerceptionClick = { showScenePerception = true },
        onSettingsClick = { showSettings = true },
        onCommunityClick = { showCommunity = true },
        onTripAssistClick = { showTripAssist = true },
        onSosClick = onSosClick,
        onStartListening = { viewModel.startListening() },
        onStopListening = { viewModel.stopListening() },
        viewModel = viewModel
    )
}

/**
 * 涓荤晫闈㈠唴瀹?- 涓夊ぇ妯″潡鍏ュ彛
 */
@Composable
private fun MainScreenContent(
    uiState: VoiceInteractionUiState,
    obstacleState: ObstacleState,
    onIndoorPerceptionClick: () -> Unit,
    onOutdoorNavigationClick: () -> Unit,
    onScenePerceptionClick: () -> Unit,
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit,
    onTripAssistClick: () -> Unit,
    onSosClick: () -> Unit,
    onStartListening: () -> Unit,
    onStopListening: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    
    // 鏉冮檺鐘舵€?    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == 
            PackageManager.PERMISSION_GRANTED
        )
    }
    
    val cameraPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            viewModel.speak("鐩告満鏉冮檺宸茶幏鍙?)
        } else {
            viewModel.speak("闇€瑕佺浉鏈烘潈闄愭墠鑳戒娇鐢ㄨ瑙夊姛鑳?)
        }
    }

    // 瀵艰埅鐘舵€?    val navState by navigationRepository.state.collectAsStateWithLifecycle()

    Scaffold(
        topBar = {
            MainTopBar(
                onSettingsClick = onSettingsClick,
                onCommunityClick = onCommunityClick
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .padding(horizontal = 16.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(16.dp))
            
            // 娆㈣繋璇?            Text(
                text = "鎮ㄥソ锛屾垜鏄皬鏅?,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A3E)
            )
            Text(
                text = "璇疯\"灏忔櫤灏忔櫤\"鍞ら啋鎴?,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666),
                modifier = Modifier.padding(top = 4.dp)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 涓夊ぇ鏍稿績妯″潡鍏ュ彛
            Text(
                text = "閫夋嫨鍔熻兘妯″潡",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A3E),
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            // 妯″潡 1锛氬鍐呮劅鐭?            ModuleCard(
                title = "瀹ゅ唴鎰熺煡",
                subtitle = "瀹ゅ唴瀵艰埅 路 闅滅鐗╂娴?路 绌洪棿鐞嗚В",
                icon = Icons.Default.Home,
                backgroundColor = Color(0xFF1E90FF),
                onClick = onIndoorPerceptionClick,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 妯″潡 2锛氬嚭琛屽鑸?            ModuleCard(
                title = "鍑鸿瀵艰埅",
                subtitle = "璺嚎瑙勫垝 路 鐩查亾寮曞 路 浜ら€氳緟鍔?,
                icon = Icons.Default.Navigation,
                backgroundColor = Color(0xFF4CAF50),
                onClick = onOutdoorNavigationClick,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            // 妯″潡 3锛氬満鏅劅鐭?            ModuleCard(
                title = "鍦烘櫙鎰熺煡",
                subtitle = "鐗╀綋璇嗗埆 路 鍦烘櫙鎻忚堪 路 鏂囧瓧鏈楄",
                icon = Icons.Default.Visibility,
                backgroundColor = Color(0xFFFF9800),
                onClick = onScenePerceptionClick,
                modifier = Modifier.fillMaxWidth()
            )
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 蹇€熷姛鑳?            Text(
                text = "蹇€熷姛鑳?,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF1A1A3E),
                modifier = Modifier.align(Alignment.Start)
            )
            
            Spacer(modifier = Modifier.height(12.dp))
            
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                QuickActionButton(
                    icon = Icons.Default.LocationOn,
                    label = "鎴戠殑浣嶇疆",
                    onClick = { /* TODO */ },
                    modifier = Modifier.weight(1f)
                )
                QuickActionButton(
                    icon = Icons.Default.Phone,
                    label = "绱ф€ユ眰鍔?,
                    onClick = onSosClick,
                    backgroundColor = Color(0xFFE53935),
                    modifier = Modifier.weight(1f)
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
            
            // 璇煶鍞ら啋鎸夐挳
            VoiceWakeUpButton(
                isListening = uiState.isListening,
                onClick = { if (uiState.isListening) onStopListening() else onStartListening() },
                modifier = Modifier.size(80.dp)
            )
            
            Spacer(modifier = Modifier.height(16.dp))
            
            Text(
                text = if (uiState.isListening) "姝ｅ湪鑱嗗惉..." else "鐐瑰嚮鍞ら啋",
                style = MaterialTheme.typography.bodyMedium,
                color = if (uiState.isListening) Color(0xFF4CAF50) else Color(0xFF666666)
            )
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

/**
 * 妯″潡鍗＄墖
 */
@Composable
private fun ModuleCard(
    title: String,
    subtitle: String,
    icon: ImageVector,
    backgroundColor: Color,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(100.dp)
            .semantics { 
                contentDescription = "$title锛?subtitle"
            },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor.copy(alpha = 0.1f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 鍥炬爣
            Box(
                modifier = Modifier
                    .size(56.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(backgroundColor),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
            
            Spacer(modifier = Modifier.width(16.dp))
            
            // 鏂囧瓧
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF1A1A3E)
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF666666)
                )
            }
            
            // 绠ご
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = backgroundColor,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

/**
 * 蹇€熸搷浣滄寜閽? */
@Composable
private fun QuickActionButton(
    icon: ImageVector,
    label: String,
    onClick: () -> Unit,
    backgroundColor: Color = Color(0xFF1E90FF),
    modifier: Modifier = Modifier
) {
    Card(
        onClick = onClick,
        modifier = modifier
            .height(80.dp)
            .semantics { contentDescription = label },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = backgroundColor.copy(alpha = 0.1f))
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = backgroundColor,
                modifier = Modifier.size(32.dp)
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = backgroundColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

/**
 * 璇煶鍞ら啋鎸夐挳
 */
@Composable
private fun VoiceWakeUpButton(
    isListening: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) 1.1f else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(1000, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scale"
    )
    
    Box(
        modifier = modifier
            .scale(scale)
            .clip(CircleShape)
            .background(
                if (isListening) Color(0xFF4CAF50) else Color(0xFF1E90FF)
            )
            .clickable(onClick = onClick)
            .semantics { contentDescription = if (isListening) "姝ｅ湪鑱嗗惉锛岀偣鍑诲仠姝? else "鐐瑰嚮鍞ら啋璇煶鍔╂墜" },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(40.dp)
        )
    }
}

/**
 * 椤堕儴鏍? */
@Composable
private fun MainTopBar(
    onSettingsClick: () -> Unit,
    onCommunityClick: () -> Unit
) {
    TopAppBar(
        title = {
            Text(
                text = "鏅鸿鍔╃洸",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
        },
        actions = {
            IconButton(onClick = onCommunityClick) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = "绀惧尯"
                )
            }
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Default.Settings,
                    contentDescription = "璁剧疆"
                )
            }
        }
    )
}

// ==================== 涓夊ぇ妯″潡灞忓箷 ====================

/**
 * 瀹ゅ唴鎰熺煡灞忓箷
 */
@Composable
private fun IndoorPerceptionScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    // TODO: 瀹炵幇瀹ゅ唴鎰熺煡鍔熻兘
    ModuleScreenTemplate(
        title = "瀹ゅ唴鎰熺煡",
        onBack = onBack,
        viewModel = viewModel
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Home,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFF1E90FF)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "瀹ゅ唴鎰熺煡鍔熻兘",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "瀹ゅ唴瀵艰埅 路 闅滅鐗╂娴?路 绌洪棿鐞嗚В",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666)
            )
        }
    }
}

/**
 * 鍑鸿瀵艰埅灞忓箷
 */
@Composable
private fun OutdoorNavigationScreen(
    obstacleRepository: ObstacleRepository,
    navigationRepository: NavigationRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    // TODO: 瀹炵幇鍑鸿瀵艰埅鍔熻兘
    ModuleScreenTemplate(
        title = "鍑鸿瀵艰埅",
        onBack = onBack,
        viewModel = viewModel
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Navigation,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFF4CAF50)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "鍑鸿瀵艰埅鍔熻兘",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "璺嚎瑙勫垝 路 鐩查亾寮曞 路 浜ら€氳緟鍔?,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666)
            )
        }
    }
}

/**
 * 鍦烘櫙鎰熺煡灞忓箷
 */
@Composable
private fun ScenePerceptionScreen(
    obstacleRepository: ObstacleRepository,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel
) {
    // TODO: 瀹炵幇鍦烘櫙鎰熺煡鍔熻兘
    ModuleScreenTemplate(
        title = "鍦烘櫙鎰熺煡",
        onBack = onBack,
        viewModel = viewModel
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                imageVector = Icons.Default.Visibility,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = Color(0xFFFF9800)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "鍦烘櫙鎰熺煡鍔熻兘",
                style = MaterialTheme.typography.headlineSmall
            )
            Text(
                text = "鐗╀綋璇嗗埆 路 鍦烘櫙鎻忚堪 路 鏂囧瓧鏈楄",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF666666)
            )
        }
    }
}

/**
 * 妯″潡灞忓箷妯℃澘
 */
@Composable
private fun ModuleScreenTemplate(
    title: String,
    onBack: () -> Unit,
    viewModel: VoiceInteractionViewModel,
    content: @Composable () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "杩斿洖")
                    }
                }
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            content()
        }
    }
}
