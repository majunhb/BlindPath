package com.blindpath.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.blindpath.porcupine.PorcupineWakeService
import com.blindpath.ui.theme.BlindPathTheme
import kotlinx.coroutines.launch
import timber.log.Timber

/**
 * Porcupine 语音唤醒主界面
 * 
 * 功能演示：
 * 1. 初始化 Porcupine 唤醒服务
 * 2. 显示当前监听状态
 * 3. 显示唤醒词检测日志
 * 4. 支持开始/停止监听控制
 * 
 * 使用流程：
 * 1. 启动 APP → 自动请求录音权限
 * 2. 权限获取后 → 自动初始化并启动监听
 * 3. 说 "porcupine" → 触发唤醒
 * 4. 显示唤醒成功提示
 */
class PorcupineMainActivity : ComponentActivity() {

    // Porcupine 唤醒服务
    private var wakeService: PorcupineWakeService? = null
    
    // UI 状态
    private var isListening by mutableStateOf(false)
    private var wakeWordCount by mutableStateOf(0)
    private var lastWakeTime by mutableStateOf("")
    private var statusText by mutableStateOf("等待初始化...")

    // 权限请求
    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            initializeAndStart()
        } else {
            statusText = "需要录音权限"
            Toast.makeText(this, "需要录音权限才能使用语音唤醒", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Timber.d("PorcupineMainActivity: onCreate")

        setContent {
            BlindPathTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    PorcupineWakeScreen(
                        isListening = isListening,
                        wakeWordCount = wakeWordCount,
                        lastWakeTime = lastWakeTime,
                        statusText = statusText,
                        onStartClick = { startListening() },
                        onStopClick = { stopListening() }
                    )
                }
            }
        }

        // 检查权限
        checkAndRequestPermission()
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("PorcupineMainActivity: onDestroy")
        wakeService?.release()
    }

    /**
     * 检查并请求录音权限
     */
    private fun checkAndRequestPermission() {
        val permission = Manifest.permission.RECORD_AUDIO
        
        if (ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED) {
            initializeAndStart()
        } else {
            permissionLauncher.launch(permission)
        }
    }

    /**
     * 初始化并启动唤醒服务
     */
    private fun initializeAndStart() {
        statusText = "正在初始化..."
        
        // 创建唤醒服务
        wakeService = PorcupineWakeService(this).apply {
            // 设置回调
            onWakeWordDetected = { keyword ->
                handleWakeWordDetected(keyword)
            }
            
            onError = { error ->
                statusText = "错误: $error"
                Timber.e("PorcupineMainActivity: $error")
            }
            
            onStateChanged = { listening ->
                isListening = listening
                statusText = if (listening) "正在监听..." else "已停止"
            }
        }

        // 从多个来源获取 Access Key
        val accessKey = wakeService?.getAccessKeyFromSources() ?: ""
        
        if (accessKey.isBlank()) {
            statusText = "Access Key 未配置"
            Toast.makeText(
                this,
                "请在 local.properties 中配置 PORCUPINE_ACCESS_KEY",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        // 初始化（使用内置关键词 "porcupine"）
        val initialized = wakeService?.initialize(accessKey, "porcupine") ?: false
        
        if (initialized) {
            // 启动监听
            val started = wakeService?.startListening() ?: false
            if (started) {
                statusText = "正在监听 'porcupine'..."
                Timber.i("PorcupineMainActivity: 初始化并启动成功")
            } else {
                statusText = "启动失败"
            }
        } else {
            statusText = "初始化失败"
        }
    }

    /**
     * 处理唤醒词检测
     */
    private fun handleWakeWordDetected(keyword: String) {
        wakeWordCount++
        lastWakeTime = java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())
        
        // UI 反馈
        Toast.makeText(this, "检测到唤醒词: $keyword", Toast.LENGTH_SHORT).show()
        
        // 震动反馈
        vibrate()
        
        Timber.i("PorcupineMainActivity: 唤醒词检测成功 - $keyword (第 $wakeWordCount 次)")
    }

    /**
     * 开始监听
     */
    private fun startListening() {
        val started = wakeService?.startListening() ?: false
        if (!started) {
            Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show()
        }
    }

    /**
     * 停止监听
     */
    private fun stopListening() {
        wakeService?.stopListening()
    }

    /**
     * 震动反馈
     */
    private fun vibrate() {
        try {
            val vibrator = getSystemService(VIBRATOR_SERVICE) as? android.os.Vibrator
            if (vibrator?.hasVibrator() == true) {
                vibrator.vibrate(100)
            }
        } catch (e: Exception) {
            Timber.w(e, "震动失败")
        }
    }
}

/**
 * Porcupine 唤醒界面
 */
@Composable
fun PorcupineWakeScreen(
    isListening: Boolean,
    wakeWordCount: Int,
    lastWakeTime: String,
    statusText: String,
    onStartClick: () -> Unit,
    onStopClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 标题
        Text(
            text = "Porcupine 语音唤醒",
            style = MaterialTheme.typography.headlineMedium
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 状态指示器
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (isListening) 
                    MaterialTheme.colorScheme.primaryContainer 
                else 
                    MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = if (isListening) "🎤 正在监听" else "⏸️ 已停止",
                    style = MaterialTheme.typography.titleLarge
                )
                
                Spacer(modifier = Modifier.height(8.dp))
                
                Text(
                    text = statusText,
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 统计信息
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly
        ) {
            StatCard(
                title = "唤醒次数",
                value = wakeWordCount.toString()
            )
            StatCard(
                title = "上次唤醒",
                value = lastWakeTime.ifEmpty { "--:--:--" }
            )
        }
        
        Spacer(modifier = Modifier.height(32.dp))
        
        // 控制按钮
        Row(
            horizontalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Button(
                onClick = onStartClick,
                enabled = !isListening
            ) {
                Text("开始监听")
            }
            
            Button(
                onClick = onStopClick,
                enabled = isListening
            ) {
                Text("停止监听")
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // 使用说明
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "使用说明",
                    style = MaterialTheme.typography.titleMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "1. 确保已授予录音权限\n" +
                           "2. 点击'开始监听'\n" +
                           "3. 说 'porcupine' 触发唤醒\n" +
                           "4. 观察唤醒次数和日志",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

/**
 * 统计卡片
 */
@Composable
fun StatCard(
    title: String,
    value: String
) {
    Card {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall
            )
        }
    }
}
