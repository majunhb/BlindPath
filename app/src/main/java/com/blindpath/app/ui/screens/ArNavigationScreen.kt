package com.blindpath.app.ui.screens

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.blindpath.app.ui.components.ArNavigationOverlay
import com.blindpath.app.ui.components.DangerLevel
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import timber.log.Timber
import kotlinx.coroutines.delay
import java.io.ByteArrayOutputStream

/**
 * AR 实景导航屏幕
 *
 * 对应 PRD 模块一/二/三：
 * - 模块一：CameraX 实时画面 + 障碍物识别 + 包围框
 * - 模块二：语音播报（优先级打断机制）
 * - 模块三：高对比度 UI（黑底黄字/白字）
 *
 * 核心理念：不做花哨的界面，只做保命的功能
 */
@Composable
fun ArNavigationScreen(
    viewModel: NavigationViewModel = hiltViewModel(),
    obstacleRepository: ObstacleRepository,
    onNavigateBack: () -> Unit = {},
    onEmergencyCall: () -> Unit = {},
) {
    val context = LocalContext.current
    val navigationState by viewModel.navigationState.collectAsState()
    val obstacleState by obstacleRepository.obstacleState.collectAsState()
    var isArActive by remember { mutableStateOf(true) }
    var lastWarningText by remember { mutableStateOf("") }

    // 通过 Hilt EntryPoint 获取 VoiceRepository
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ArNavigationEntryPoint::class.java
        ).voiceRepository()
    }

    // 初始化障碍物检测
    LaunchedEffect(Unit) {
        try {
            val initResult = obstacleRepository.initialize()
            if (initResult is com.blindpath.base.common.Result.Success) {
                obstacleRepository.loadModel()
                obstacleRepository.startDetection()
                Timber.i("ArNav: Obstacle detection initialized")
                voiceRepository.speak("AR实景导航已启动，正在识别障碍物")
            }
        } catch (e: Exception) {
            Timber.e(e, "ArNav: Failed to init obstacle detection")
        }
    }

    // 导航指令播报
    val lastInstruction = remember { mutableStateOf("") }
    LaunchedEffect(navigationState) {
        val state = navigationState
        if (state.isRunning && state.currentStepIndex < state.routeSteps.size) {
            val step = state.routeSteps[state.currentStepIndex]
            val instruction = step.instruction
            if (instruction.isNotEmpty() && instruction != lastInstruction.value) {
                lastInstruction.value = instruction
                voiceRepository.announce(instruction, VoiceType.NAVIGATION_TURN)
            }
        }
    }

    // 到达播报
    val wasNavigating = remember { mutableStateOf(false) }
    LaunchedEffect(navigationState.isRunning) {
        if (wasNavigating.value && !navigationState.isRunning && navigationState.isRoutePlanned) {
            voiceRepository.announce("已到达目的地", VoiceType.NAVIGATION_ARRIVE)
        }
        wasNavigating.value = navigationState.isRunning
    }

    // 监听障碍物检测结果，播报高危预警
    LaunchedEffect(obstacleState) {
        val state = obstacleState
        val currentAlert = state?.currentAlert
        if (currentAlert != null && currentAlert.level == com.blindpath.base.common.AlertLevel.DANGER) {
            val warning = currentAlert.message.ifEmpty { "前方有障碍物，请注意安全" }
            if (warning != lastWarningText) {
                lastWarningText = warning
                voiceRepository.announce(warning, VoiceType.OBSTACLE_DANGER)
            }
        }
    }

    // 每10秒播报一次状态
    LaunchedEffect(Unit) {
        while (isArActive) {
            delay(10_000)
            val state = navigationState
            if (state.isRunning && state.currentStepIndex < state.routeSteps.size) {
                val step = state.routeSteps[state.currentStepIndex]
                voiceRepository.announce(
                    "继续前行，${step.distance} ${step.instruction}",
                    VoiceType.NAVIGATION_TURN
                )
            }
        }
    }

    // 导航信息
    val navInstruction = remember(navigationState) {
        if (navigationState.isRunning && navigationState.currentStepIndex < navigationState.routeSteps.size) {
            navigationState.routeSteps[navigationState.currentStepIndex].instruction
        } else ""
    }
    val navRemaining = remember(navigationState) {
        if (navigationState.isRunning) {
            "剩余 ${navigationState.totalDistance}"
        } else if (navigationState.isRoutePlanned) "已到达"
        else ""
    }

    Box(modifier = Modifier.fillMaxSize()) {
        ArNavigationOverlay(
            modifier = Modifier.fillMaxSize(),
            isActive = isArActive,
            onFrameProcessed = { bitmap ->
                processFrame(bitmap, obstacleRepository)
            },
            obstacles = obstacleState?.detectedObstacles ?: emptyList(),
            dangerLevel = obstacleState?.let { state ->
                when (state.currentAlert?.level) {
                    com.blindpath.base.common.AlertLevel.DANGER -> DangerLevel.CRITICAL
                    com.blindpath.base.common.AlertLevel.WARNING -> DangerLevel.HIGH
                    com.blindpath.base.common.AlertLevel.UNKNOWN -> DangerLevel.MEDIUM
                    else -> DangerLevel.LOW
                }
            } ?: DangerLevel.LOW,
            warningText = obstacleState?.currentAlert?.message ?: "",
            navigationDirection = navInstruction,
            remainingDistance = navRemaining,
            onGestureTap = {
                voiceRepository.speak("当前状态：" + (
                    obstacleState?.currentAlert?.message ?: "正常行驶"
                ))
            },
            onGestureDoubleTap = {
                isArActive = !isArActive
                voiceRepository.speak(if (isArActive) "AR模式已开启" else "AR模式已关闭")
            },
            onGestureLongPress = {
                onEmergencyCall()
            }
        )

        if (obstacleState == null) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            isArActive = false
        }
    }
}

/**
 * Hilt EntryPoint for AR Navigation Screen
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface ArNavigationEntryPoint {
    fun voiceRepository(): VoiceRepository
}

/**
 * 处理摄像头帧：调用障碍物检测流水线
 */
private fun processFrame(
    bitmap: Bitmap,
    obstacleRepository: ObstacleRepository
) {
    try {
        val stream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
        val bytes = stream.toByteArray()
        kotlinx.coroutines.runBlocking {
            obstacleRepository.processFrame(bytes, bitmap.width, bitmap.height)
        }
    } catch (e: Exception) {
        Timber.w(e, "ArNav: processFrame failed")
    }
}