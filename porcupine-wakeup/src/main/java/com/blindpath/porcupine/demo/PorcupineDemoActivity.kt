package com.blindpath.porcupine.demo

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.blindpath.porcupine.PorcupineConfig
import com.blindpath.porcupine.PorcupineWakeWordEngine
import com.blindpath.audio.AudioRecorder
import com.blindpath.voice.RecognitionResult
import com.blindpath.voice.VoiceInteractionManager
import kotlinx.coroutines.*
import timber.log.Timber

/**
 * Porcupine 语音唤醒演示 Activity
 * 
 * 功能演示：
 * 1. 离线唤醒词检测（Porcupine）
 * 2. 唤醒后指令识别（SpeechRecognizer）
 * 3. 指令执行和反馈
 * 
 * 使用流程：
 * 1. 启动 Activity → 自动请求录音权限
 * 2. 权限获取后 → 开始监听唤醒词
 * 3. 说 "Hey Assistant" → 触发唤醒
 * 4. 说指令（如"导航"、"回家"） → 执行操作
 * 5. 完成后 → 自动回到唤醒监听状态
 */
class PorcupineDemoActivity : ComponentActivity() {

    // 语音交互管理器
    private var voiceManager: VoiceInteractionManager? = null
    
    // 协程作用域
    private val activityScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    
    // 状态
    private var isListening = false
    private var lastCommand = ""

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            startVoiceInteraction()
        } else {
            Toast.makeText(this, "需要录音权限才能使用语音唤醒功能", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("PorcupineDemoActivity: onCreate")
        
        // 初始化语音交互
        initializeVoiceInteraction()
    }

    override fun onStart() {
        super.onStart()
        Timber.d("PorcupineDemoActivity: onStart")
        
        // 检查并请求权限
        checkAndRequestPermission()
    }

    override fun onStop() {
        super.onStop()
        Timber.d("PorcupineDemoActivity: onStop")
        
        // 停止语音交互
        voiceManager?.stop()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("PorcupineDemoActivity: onDestroy")
        
        // 释放资源
        voiceManager?.release()
        activityScope.cancel()
    }

    /**
     * 初始化语音交互系统
     */
    private fun initializeVoiceInteraction() {
        // 从 BuildConfig 获取 Access Key
        val accessKey = com.blindpath.porcupine.BuildConfig.PORCUPINE_ACCESS_KEY
        
        if (accessKey.isBlank()) {
            Timber.e("PorcupineDemoActivity: PORCUPINE_ACCESS_KEY 未配置")
            Toast.makeText(
                this,
                "请配置 PORCUPINE_ACCESS_KEY（从 https://picovoice.ai/console/ 获取）",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 配置 Porcupine
        val config = PorcupineConfig(
            accessKey = accessKey,
            keywordAssetPath = "keywords/hey-assistant_android.ppn"
        )

        // 创建语音交互管理器
        voiceManager = VoiceInteractionManager(this, config)

        // 初始化
        val initialized = voiceManager!!.initialize()
        if (!initialized) {
            Toast.makeText(this, "语音唤醒初始化失败", Toast.LENGTH_LONG).show()
            return
        }

        // 设置回调
        setupCallbacks()

        Timber.i("PorcupineDemoActivity: 语音交互初始化成功")
    }

    /**
     * 设置语音交互回调
     */
    private fun setupCallbacks() {
        voiceManager?.onWakeWordDetected = {
            Timber.i("PorcupineDemoActivity: 唤醒词检测成功！")
            isListening = true
            
            // UI 反馈
            Toast.makeText(this, "我在，请说指令", Toast.LENGTH_SHORT).show()
            
            // 可以在这里播放提示音或震动反馈
            playWakeUpFeedback()
        }

        voiceManager?.onCommandRecognized = { command ->
            Timber.i("PorcupineDemoActivity: 指令识别成功 - $command")
            lastCommand = command
            isListening = false
            
            // 处理指令
            handleCommand(command)
        }

        voiceManager?.onError = { error ->
            Timber.e("PorcupineDemoActivity: 错误 - $error")
            isListening = false
            
            Toast.makeText(this, "错误: $error", Toast.LENGTH_SHORT).show()
        }

        voiceManager?.onStateChanged = { state ->
            Timber.d("PorcupineDemoActivity: 状态变化 - $state")
            // 可以在这里更新 UI 状态指示器
        }
    }

    /**
     * 检查并请求录音权限
     */
    private fun checkAndRequestPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            startVoiceInteraction()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    /**
     * 启动语音交互
     */
    private fun startVoiceInteraction() {
        val started = voiceManager?.start() ?: false
        
        if (started) {
            Timber.i("PorcupineDemoActivity: 开始监听唤醒词")
            Toast.makeText(this, "正在监听，请说 \"Hey Assistant\"", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "启动语音监听失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 处理识别到的指令
     * 
     * @param command 识别到的语音指令文本
     */
    private fun handleCommand(command: String) {
        // 指令匹配和处理
        when {
            command.contains("导航", ignoreCase = true) -> {
                Toast.makeText(this, "正在打开导航...", Toast.LENGTH_SHORT).show()
                // TODO: 启动导航功能
            }
            
            command.contains("回家", ignoreCase = true) -> {
                Toast.makeText(this, "正在规划回家路线...", Toast.LENGTH_SHORT).show()
                // TODO: 导航回家
            }
            
            command.contains("打电话", ignoreCase = true) -> {
                Toast.makeText(this, "正在打开拨号...", Toast.LENGTH_SHORT).show()
                // TODO: 打开拨号界面
            }
            
            command.contains("帮助", ignoreCase = true) -> {
                showHelp()
            }
            
            command.contains("停止", ignoreCase = true) ||
            command.contains("关闭", ignoreCase = true) -> {
                voiceManager?.stop()
                Toast.makeText(this, "已停止监听", Toast.LENGTH_SHORT).show()
            }
            
            else -> {
                Toast.makeText(this, "未识别的指令: $command", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 显示帮助信息
     */
    private fun showHelp() {
        val helpMessage = """
            可用指令：
            • "导航" - 打开导航
            • "回家" - 导航回家
            • "打电话" - 打开拨号
            • "帮助" - 显示帮助
            • "停止" - 停止监听
        """.trimIndent()
        
        Toast.makeText(this, helpMessage, Toast.LENGTH_LONG).show()
    }

    /**
     * 播放唤醒反馈（提示音或震动）
     */
    private fun playWakeUpFeedback() {
        // 简单震动反馈
        try {
            val vibrator = getSystemService(android.os.Vibrator.class)
            if (vibrator != null && vibrator.hasVibrator()) {
                vibrator.vibrate(100) // 短震动100ms
            }
        } catch (e: Exception) {
            Timber.w(e, "PorcupineDemoActivity: 震动反馈失败")
        }
    }

    /**
     * 获取当前状态（用于 UI 显示）
     */
    fun getCurrentState(): VoiceInteractionManager.State {
        return voiceManager?.getCurrentState() ?: VoiceInteractionManager.State.IDLE
    }

    /**
     * 获取最后识别的指令
     */
    fun getLastCommand(): String = lastCommand

    /**
     * 是否正在监听指令
     */
    fun isListeningForCommand(): Boolean = isListening
}