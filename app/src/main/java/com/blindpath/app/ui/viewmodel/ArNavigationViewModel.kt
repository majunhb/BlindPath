package com.blindpath.app.ui.viewmodel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.app.ui.components.DangerLevel
import com.blindpath.module_obstacle.data.detection.TactilePavingDetector
import com.blindpath.module_obstacle.data.detection.TactilePavingResult
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_obstacle.domain.model.DetectedObstacle
import com.blindpath.module_obstacle.domain.model.ObstacleState
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.model.VoiceType
import dagger.hilt.android.lifecycle.HiltViewModel
import timber.log.Timber
import java.io.ByteArrayOutputStream
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs

/**
 * AR 导航统一 ViewModel
 *
 * 整合障碍物检测 + 盲道检测 + 导航 + 语音播报，作为 AR 导航屏幕的单一状态源。
 *
 * 职责：
 * - 管理摄像头帧处理管线
 * - 协调障碍物检测与盲道检测
 * - 合并障碍物状态与盲道状态
 * - 管理语音播报优先级
 * - 管理 AR 模式切换
 */
@HiltViewModel
class ArNavigationViewModel @Inject constructor(
    private val obstacleRepository: ObstacleRepository,
    private val voiceRepository: VoiceRepository,
    private val tactilePavingDetector: TactilePavingDetector
) : ViewModel() {

    // ============================================================
    // 状态管理
    // ============================================================

    private val _uiState = MutableStateFlow(ArNavigationUiState())
    val uiState: StateFlow<ArNavigationUiState> = _uiState.asStateFlow()

    private val _obstacleState = MutableStateFlow<ObstacleState?>(null)
    val obstacleState: StateFlow<ObstacleState?> = _obstacleState.asStateFlow()

    private val _tactilePavingResult = MutableStateFlow<TactilePavingResult?>(null)
    val tactilePavingResult: StateFlow<TactilePavingResult?> = _tactilePavingResult.asStateFlow()

    private var lastWarningText = ""
    private var lastPavingWarning = ""
    private var frameCount = 0L
    private var statusReportJob: Job? = null

    // ============================================================
    // 初始化
    // ============================================================

    init {
        // 监听障碍物检测状态
        viewModelScope.launch {
            obstacleRepository.obstacleState.collectLatest { state ->
                _obstacleState.value = state
                handleObstacleAlert(state)
            }
        }
    }

    /**
     * 初始化 AR 导航
     */
    fun initialize() {
        viewModelScope.launch {
            try {
                val initResult = obstacleRepository.initialize()
                if (initResult is com.blindpath.base.common.Result.Success) {
                    obstacleRepository.loadModel()
                    obstacleRepository.startDetection()
                    _uiState.value = _uiState.value.copy(isInitialized = true)
                    voiceRepository.speak("AR实景导航已启动")
                    Timber.i("ArNavViewModel: initialized")
                }
            } catch (e: Exception) {
                Timber.e(e, "ArNavViewModel: init failed")
                _uiState.value = _uiState.value.copy(
                    errorMessage = "初始化失败: ${e.message}"
                )
            }
        }
    }

    // ============================================================
    // 帧处理
    // ============================================================

    /**
     * 处理摄像头帧
     * 同时执行障碍物检测和盲道检测
     */
    fun processFrame(bitmap: Bitmap) {
        frameCount++

        // 每 5 帧处理一次障碍物检测（节省算力）
        if (frameCount % 5 == 0L) {
            try {
                val stream = ByteArrayOutputStream()
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, stream)
                val bytes = stream.toByteArray()
                viewModelScope.launch {
                    obstacleRepository.processFrame(bytes, bitmap.width, bitmap.height)
                }
            } catch (e: Exception) {
                Timber.w(e, "ArNavViewModel: processFrame failed")
            }
        }

        // 每 3 帧处理一次盲道检测
        if (frameCount % 3 == 0L) {
            try {
                val result = tactilePavingDetector.detect(bitmap)
                _tactilePavingResult.value = result
                handlePavingAlert(result)
            } catch (e: Exception) {
                Timber.w(e, "ArNavViewModel: tactile paving detection failed")
            }
        }
    }

    // ============================================================
    // 障碍物预警处理
    // ============================================================

    private fun handleObstacleAlert(state: ObstacleState?) {
        val alert = state?.currentAlert ?: return

        if (alert.level == com.blindpath.base.common.AlertLevel.DANGER) {
            val warning = alert.description.ifEmpty { "前方有障碍物，请注意安全" }
            if (warning != lastWarningText) {
                lastWarningText = warning
                viewModelScope.launch {
                    voiceRepository.announce(warning, VoiceType.OBSTACLE_DANGER)
                }
                _uiState.value = _uiState.value.copy(
                    warningText = warning,
                    dangerLevel = DangerLevel.CRITICAL
                )
            }
        } else if (alert.level == com.blindpath.base.common.AlertLevel.WARNING) {
            _uiState.value = _uiState.value.copy(
                warningText = alert.description,
                dangerLevel = DangerLevel.HIGH
            )
        }
    }

    // ============================================================
    // 盲道预警处理
    // ============================================================

    private fun handlePavingAlert(result: TactilePavingResult?) {
        if (result == null || !result.detected) {
            if (lastPavingWarning.isNotEmpty()) {
                lastPavingWarning = ""
                _uiState.value = _uiState.value.copy(pavingWarning = null)
            }
            return
        }

        val offset = result.offsetFromCenter
        val warning = when {
            offset < -0.4f -> "请向右偏移，回到盲道中心"
            offset > 0.4f -> "请向左偏移，回到盲道中心"
            offset < -0.2f -> "偏右一点，请在盲道中心行走"
            offset > 0.2f -> "偏左一点，请在盲道中心行走"
            else -> null
        }

        if (warning != null && warning != lastPavingWarning) {
            lastPavingWarning = warning
            viewModelScope.launch {
                voiceRepository.speak(warning)
            }
            _uiState.value = _uiState.value.copy(
                pavingWarning = PavingWarning(
                    message = warning,
                    offsetFromCenter = offset,
                    direction = result.direction
                )
            )
        } else if (warning == null) {
            lastPavingWarning = ""
            _uiState.value = _uiState.value.copy(pavingWarning = null)
        }
    }

    // ============================================================
    // 状态播报
    // ============================================================

    /**
     * 启动定期状态播报（每 10 秒）
     */
    fun startStatusReport() {
        statusReportJob?.cancel()
        statusReportJob = viewModelScope.launch {
            while (true) {
                delay(10_000)
                reportCurrentStatus()
            }
        }
    }

    /**
     * 停止定期状态播报
     */
    fun stopStatusReport() {
        statusReportJob?.cancel()
        statusReportJob = null
    }

    /**
     * 播报当前状态
     */
    suspend fun reportCurrentStatus() {
        val state = _uiState.value
        val obstacles = _obstacleState.value?.detectedObstacles ?: emptyList()
        val paving = _tactilePavingResult.value

        val statusParts = mutableListOf<String>()

        // 盲道状态
        if (paving != null && paving.detected) {
            if (abs(paving.offsetFromCenter) < 0.2f) {
                statusParts.add("正在盲道上")
            } else {
                statusParts.add("盲道在${if (paving.offsetFromCenter < 0) "右侧" else "左侧"}")
            }
        }

        // 最近障碍物
        val nearest = obstacles.minByOrNull { it.distance }
        if (nearest != null && nearest.distance < 5f) {
            statusParts.add("${nearest.direction.getChineseName()}${nearest.distance.toInt()}米有${nearest.type.chineseName}")
        }

        if (statusParts.isEmpty()) {
            statusParts.add("前方安全")
        }

        voiceRepository.announce(statusParts.joinToString("，"), VoiceType.NAVIGATION_TURN)
    }

    /**
     * 单击手势：播报当前状态
     */
    fun onSingleTap() {
        viewModelScope.launch {
            reportCurrentStatus()
        }
    }

    /**
     * 双击手势：切换 AR 模式
     */
    fun onDoubleTap() {
        val current = _uiState.value.isArActive
        _uiState.value = _uiState.value.copy(isArActive = !current)
        viewModelScope.launch {
            voiceRepository.speak(if (!current) "AR模式已开启" else "AR模式已关闭")
        }
    }

    // ============================================================
    // 生命周期
    // ============================================================

    override fun onCleared() {
        super.onCleared()
        stopStatusReport()
        tactilePavingDetector.reset()
    }
}

/**
 * AR 导航 UI 状态
 */
data class ArNavigationUiState(
    val isInitialized: Boolean = false,
    val isArActive: Boolean = true,
    val warningText: String = "",
    val dangerLevel: DangerLevel = DangerLevel.LOW,
    val pavingWarning: PavingWarning? = null,
    val errorMessage: String? = null
)

/**
 * 盲道偏离警告
 */
data class PavingWarning(
    val message: String,
    val offsetFromCenter: Float,
    val direction: Float
)