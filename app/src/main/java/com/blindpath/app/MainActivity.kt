package com.blindpath.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.blindpath.app.ui.screens.MainScreen
import com.blindpath.app.ui.theme.BlindPathTheme
import com.blindpath.base.sos.SosHelper
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_voice.domain.VoiceRepository
import com.blindpath.module_voice.domain.VoiceInteractionManager
import com.blindpath.module_voice.domain.model.VoiceCommand
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var voiceRepository: VoiceRepository

    @Inject
    lateinit var navigationRepository: NavigationRepository

    @Inject
    lateinit var obstacleRepository: ObstacleRepository

    @Inject
    lateinit var voiceInteractionManager: VoiceInteractionManager

    private var pendingAction: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            pendingAction?.let { performAction(it) }
        } else {
            Toast.makeText(this, "需要权限才能使用此功能", Toast.LENGTH_LONG).show()
            lifecycleScope.launch {
                voiceRepository.speak("需要相关权限才能使用此功能，请在设置中授权", queueMode = false)
            }
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 请求语音识别权限并初始化
        requestVoicePermissionsAndInitialize()

        setContent {
            BlindPathTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        obstacleRepository = obstacleRepository,
                        onObstacleDetectionClick = { requestPermissionAndAction("obstacle") },
                        onLocationClick = { requestPermissionAndAction("location") },
                        onSosClick = { requestPermissionAndAction("sos") }
                    )
                }
            }
        }
    }

    /**
     * 启动唤醒词检测服务
     */
    private fun startWakeWordService() {
        try {
            val intent = Intent(this, com.blindpath.module_voice.service.WakeWordService::class.java).apply {
                action = com.blindpath.module_voice.service.WakeWordService.ACTION_START
            }
            startForegroundService(intent)
            Timber.i("WakeWordService started from MainActivity")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start WakeWordService")
        }
    }

    /**
     * 请求语音识别权限并初始化语音交互
     */
    private fun requestVoicePermissionsAndInitialize() {
        val permissions = arrayOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.MODIFY_AUDIO_SETTINGS
        )

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            initializeVoiceInteraction()
        } else {
            voicePermissionLauncher.launch(permissions)
        }
    }

    private val voicePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            initializeVoiceInteraction()
        } else {
            Toast.makeText(this, "需要麦克风权限才能使用语音唤醒功能", Toast.LENGTH_LONG).show()
            Timber.w("Voice permissions denied")
        }
    }

    /**
     * 初始化语音交互系统
     */
    private fun initializeVoiceInteraction() {
        lifecycleScope.launch {
            try {
                // 设置指令执行器（必须在初始化前设置）
                voiceInteractionManager.setCommandExecutor(object : com.blindpath.module_voice.domain.VoiceCommandExecutor {
                    override suspend fun executeCommand(command: VoiceCommand): Boolean {
                        return handleVoiceCommand(command)
                    }
                })
                
                // 初始化语音交互管理器（会自动启动监听）
                val result = voiceInteractionManager.initialize()
                if (result.isSuccess) {
                    Timber.i("Voice interaction initialized successfully")
                    // 启动唤醒词检测服务（百度唤醒引擎）
                    startWakeWordService()
                    // 播报欢迎消息
                    voiceInteractionManager.speakWelcome()
                } else {
                    val errorMsg = (result as? com.blindpath.base.common.Result.Error)?.message ?: "未知错误"
                    Timber.e("Voice interaction initialization failed: $errorMsg")
                    voiceRepository.speak("语音交互初始化失败，请检查权限设置", queueMode = false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to initialize voice interaction")
                voiceRepository.speak("语音交互初始化异常", queueMode = false)
            }
        }
    }

    /**
     * 处理语音指令
     */
    private suspend fun handleVoiceCommand(command: VoiceCommand): Boolean {
        Timber.d("Handling voice command: ${command.name}")
        
        return when (command) {
            VoiceCommand.START_OBSTACLE_DETECTION -> {
                startObstacleDetection()
                true
            }
            VoiceCommand.STOP_OBSTACLE_DETECTION -> {
                stopObstacleDetection()
                true
            }
            VoiceCommand.START_NAVIGATION -> {
                startLocationService()
                true
            }
            VoiceCommand.STOP_NAVIGATION -> {
                stopLocationService()
                true
            }
            VoiceCommand.WHERE_AM_I -> {
                announceCurrentLocation()
                true
            }
            VoiceCommand.SOS, VoiceCommand.CALL_SOS -> {
                performSos()
                true
            }
            VoiceCommand.HELP -> {
                voiceInteractionManager.speakHelp()
                true
            }
            else -> {
                Timber.w("Unhandled command: ${command.name}")
                false
            }
        }
    }

    private fun stopObstacleDetection() {
        val intent = Intent(this, com.blindpath.module_obstacle.service.ObstacleService::class.java).apply {
            action = com.blindpath.module_obstacle.service.ObstacleService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "障碍物检测已关闭", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            voiceRepository.speak("障碍物检测已关闭", queueMode = false)
        }
    }

    private fun stopLocationService() {
        val intent = Intent(this, com.blindpath.module_navigation.service.NavigationService::class.java).apply {
            action = com.blindpath.module_navigation.service.NavigationService.ACTION_STOP
        }
        startService(intent)
        Toast.makeText(this, "导航服务已关闭", Toast.LENGTH_SHORT).show()
        lifecycleScope.launch {
            voiceRepository.speak("导航服务已关闭", queueMode = false)
        }
    }

    private fun announceCurrentLocation() {
        lifecycleScope.launch {
            try {
                val location = navigationRepository.getCurrentLocation()
                if (location != null) {
                    val message = "您当前位置：纬度${String.format("%.4f", location.latitude)}，经度${String.format("%.4f", location.longitude)}"
                    voiceRepository.speak(message, queueMode = false)
                } else {
                    voiceRepository.speak("无法获取当前位置，请检查定位权限", queueMode = false)
                }
            } catch (e: Exception) {
                Timber.e(e, "Failed to get current location")
                voiceRepository.speak("获取位置失败", queueMode = false)
            }
        }
    }

    private fun requestPermissionAndAction(action: String) {
        val permissions = when (action) {
            "obstacle" -> arrayOf(
                Manifest.permission.CAMERA,
                Manifest.permission.VIBRATE
            )
            "location" -> arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
            "sos" -> arrayOf(
                Manifest.permission.SEND_SMS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ACCESS_FINE_LOCATION
            )
            else -> emptyArray()
        }

        val allGranted = permissions.all {
            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED
        }

        if (allGranted) {
            performAction(action)
        } else {
            pendingAction = action
            permissionLauncher.launch(permissions)
        }
    }

    private fun performAction(action: String) {
        when (action) {
            "obstacle" -> startObstacleDetection()
            "location" -> startLocationService()
            "sos" -> performSos()
        }
    }

    private fun startObstacleDetection() {
        val intent = Intent(this, com.blindpath.module_obstacle.service.ObstacleService::class.java).apply {
            action = com.blindpath.module_obstacle.service.ObstacleService.ACTION_START
        }
        startForegroundService(intent)
        Toast.makeText(this, "障碍物检测已开启", Toast.LENGTH_SHORT).show()
    }

    private fun startLocationService() {
        val intent = Intent(this, com.blindpath.module_navigation.service.NavigationService::class.java).apply {
            action = com.blindpath.module_navigation.service.NavigationService.ACTION_START
        }
        startForegroundService(intent)
        Toast.makeText(this, "位置服务已开启", Toast.LENGTH_SHORT).show()
    }

    private fun performSos() {
        lifecycleScope.launch {
            voiceRepository.speak("正在发起紧急求助", queueMode = false)

            // 获取 GPS 位置
            val location = if (SosHelper.hasLocationPermission(this@MainActivity)) {
                navigationRepository.getCurrentLocation()
            } else {
                null
            }

            // 发送 SOS 短信
            SosHelper.sendSos(
                context = this@MainActivity,
                location = location,
                onSent = {
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, "求助短信已发送", Toast.LENGTH_SHORT).show()
                        // 打开拨号界面
                        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:110")
                        }
                        startActivity(dialIntent)
                    }
                },
                onError = { error ->
                    runOnUiThread {
                        Toast.makeText(this@MainActivity, error, Toast.LENGTH_LONG).show()
                        // 短信失败也打开拨号
                        val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                            data = Uri.parse("tel:110")
                        }
                        startActivity(dialIntent)
                    }
                }
            )
        }
    }
}
