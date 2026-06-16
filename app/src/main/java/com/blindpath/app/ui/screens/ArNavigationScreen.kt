package com.blindpath.app.ui.screens

import android.graphics.Bitmap
import androidx.camera.view.PreviewView
import androidx.compose.foundation.layout.*
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.blindpath.app.ui.camera.CameraXManager
import com.blindpath.app.ui.components.ArNavigationOverlay
import com.blindpath.app.ui.components.DangerLevel
import com.blindpath.app.ui.viewmodel.ArNavigationViewModel
import com.blindpath.app.ui.viewmodel.NavigationViewModel
import com.blindpath.module_voice.domain.model.VoiceType
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import com.blindpath.module_voice.domain.VoiceRepository
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * AR 实景导航屏幕
 *
 * Phase 2 重构：使用 ArNavigationViewModel 统一管理状态，
 * 使用 CameraXManager 统一管理摄像头。
 *
 * 对应 PRD 模块：
 * - 模块一：CameraX 实时画面 + 障碍物识别 + 包围框 + 盲道叠加
 * - 模块二：语音播报（优先级打断机制）
 * - 模块三：高对比度 UI（黑底黄字/白字）
 *
 * 核心理念：不做花哨的界面，只做保命的功能
 */
@Composable
fun ArNavigationScreen(
    viewModel: ArNavigationViewModel = hiltViewModel(),
    navigationViewModel: NavigationViewModel = hiltViewModel(),
    cameraXManager: CameraXManager,
    onNavigateBack: () -> Unit = {},
    onEmergencyCall: () -> Unit = {},
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val navigationState by navigationViewModel.uiState.collectAsState()
    val uiState by viewModel.uiState.collectAsState()
    val obstacleState by viewModel.obstacleState.collectAsState()
    val tactilePavingResult by viewModel.tactilePavingResult.collectAsState()

    // 通过 Hilt EntryPoint 获取 VoiceRepository（导航播报用）
    val voiceRepository = remember {
        EntryPointAccessors.fromApplication(
            context.applicationContext,
            ArNavigationEntryPoint::class.java
        ).voiceRepository()
    }

    // ============================================================
    // 初始化
    // ============================================================
    LaunchedEffect(Unit) {
        viewModel.initialize()
        viewModel.startStatusReport()
    }

    // ============================================================
    // 导航指令播报
    // ============================================================
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

    // ============================================================
    // 导航信息
    // ============================================================
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

    // ============================================================
    // UI 布局
    // ============================================================
    Box(modifier = Modifier.fillMaxSize()) {
        // CameraX 预览 + AR 叠加层
        if (uiState.isArActive) {
            AndroidView(
                factory = { ctx ->
                    PreviewView(ctx).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        implementationMode = PreviewView.ImplementationMode.PERFORMANCE
                    }
                },
                modifier = Modifier.fillMaxSize(),
                update = { previewView ->
                    if (!cameraXManager.isActive()) {
                        cameraXManager.bind(lifecycleOwner, previewView)
                    }
                }
            )
        }

        // AR 叠加层
        ArNavigationOverlay(
            modifier = Modifier.fillMaxSize(),
            isActive = uiState.isArActive,
            onFrameProcessed = { bitmap ->
                viewModel.processFrame(bitmap)
            },
            obstacles = obstacleState?.detectedObstacles ?: emptyList(),
            dangerLevel = uiState.dangerLevel,
            warningText = uiState.warningText,
            navigationDirection = navInstruction,
            remainingDistance = navRemaining,
            // Phase 2: 盲道叠加层参数
            pavingOffset = tactilePavingResult?.offsetFromCenter ?: 0f,
            pavingDirection = tactilePavingResult?.direction ?: 0f,
            pavingVisible = tactilePavingResult?.detected == true,
            onGestureTap = {
                viewModel.onSingleTap()
            },
            onGestureDoubleTap = {
                viewModel.onDoubleTap()
            },
            onGestureLongPress = {
                onEmergencyCall()
            }
        )

        // 加载指示器
        if (!uiState.isInitialized) {
            CircularProgressIndicator(
                modifier = Modifier.align(Alignment.Center),
                color = Color.White,
                strokeWidth = 3.dp
            )
        }
    }

    // ============================================================
    // 生命周期管理
    // ============================================================
    DisposableEffect(Unit) {
        // 订阅 CameraX 帧流
        val job = scope.launch {
            cameraXManager.frameFlow.collectLatest { bitmap ->
                if (uiState.isArActive) {
                    viewModel.processFrame(bitmap)
                }
            }
        }

        onDispose {
            viewModel.stopStatusReport()
            job.cancel()
            cameraXManager.unbind()
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