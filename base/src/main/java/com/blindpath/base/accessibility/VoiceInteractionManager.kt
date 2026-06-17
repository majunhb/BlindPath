package com.blindpath.base.accessibility

import android.content.Context
import android.speech.SpeechRecognizer
import timber.log.Timber
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * 语音交互管理器
 * 提供语音输入、语音命令识别功能
 */
class VoiceInteractionManager(
    private val context: Context,
    private val onCommand: (VoiceCommand) -> Unit
) {
    
    /**
     * 语音命令
     */
    sealed class VoiceCommand {
        // 导航相关
        object StartNavigation : VoiceCommand()
        object StopNavigation : VoiceCommand()
        object RepeatNavigation : VoiceCommand()
        data class SearchDestination(val query: String) : VoiceCommand()
        
        // 检测相关
        object StartDetection : VoiceCommand()
        object StopDetection : VoiceCommand()
        object PauseDetection : VoiceCommand()
        
        // 语音控制
        object Mute : VoiceCommand()
        object Unmute : VoiceCommand()
        data class SetVolume(val level: Int) : VoiceCommand()
        object RepeatLastAnnouncement : VoiceCommand()
        
        // 设置相关
        object OpenSettings : VoiceCommand()
        object CloseSettings : VoiceCommand()
        
        // SOS
        object SOS : VoiceCommand()
        object CancelSOS : VoiceCommand()
        
        // 通用
        object Help : VoiceCommand()
        object Cancel : VoiceCommand()
        data class Unknown(val text: String) : VoiceCommand()
    }
    
    private var isListening = false
    private val commandHistory = ConcurrentLinkedQueue<VoiceCommand>()
    private var lastAnnouncement: String? = null
    
    // 语音识别器
    private var speechRecognizer: SpeechRecognizer? = null
    
    /**
     * 开始监听语音
     */
    fun startListening() {
        if (isListening) return
        
        try {
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
                speechRecognizer?.setRecognitionListener(VoiceRecognitionListener())
            }
            
            isListening = true
            // speechRecognizer?.startListening(createIntent())
            Timber.d("Voice listening started")
        } catch (e: Exception) {
            Timber.e(e, "Failed to start voice listening")
            isListening = false
        }
    }
    
    /**
     * 停止监听语音
     */
    fun stopListening() {
        isListening = false
        speechRecognizer?.stopListening()
        Timber.d("Voice listening stopped")
    }
    
    /**
     * 释放资源
     */
    fun destroy() {
        isListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
    }
    
    /**
     * 保存最后的播报内容
     */
    fun saveLastAnnouncement(announcement: String) {
        lastAnnouncement = announcement
    }
    
    /**
     * 获取最后的播报内容
     */
    fun getLastAnnouncement(): String? = lastAnnouncement
    
    /**
     * 获取命令历史
     */
    fun getCommandHistory(): List<VoiceCommand> {
        return commandHistory.toList()
    }
    
    /**
     * 清除命令历史
     */
    fun clearHistory() {
        commandHistory.clear()
    }
    
    /**
     * 解析语音文本为命令
     */
    fun parseVoiceCommand(text: String): VoiceCommand {
        val lowerText = text.lowercase().trim()
        
        return when {
            // 导航命令
            lowerText.contains("开始导航") || lowerText.contains("导航") -> VoiceCommand.StartNavigation
            lowerText.contains("停止导航") || lowerText.contains("结束导航") -> VoiceCommand.StopNavigation
            lowerText.contains("重复导航") || lowerText.contains("再说一遍") -> VoiceCommand.RepeatNavigation
            lowerText.contains("去") || lowerText.contains("导航到") -> {
                val destination = extractDestination(lowerText)
                VoiceCommand.SearchDestination(destination)
            }
            
            // 检测命令
            lowerText.contains("开始检测") || lowerText.contains("检测") -> VoiceCommand.StartDetection
            lowerText.contains("停止检测") || lowerText.contains("关闭检测") -> VoiceCommand.StopDetection
            lowerText.contains("暂停检测") -> VoiceCommand.PauseDetection
            
            // 语音控制
            lowerText.contains("静音") || lowerText.contains("关闭声音") -> VoiceCommand.Mute
            lowerText.contains("取消静音") || lowerText.contains("打开声音") -> VoiceCommand.Unmute
            lowerText.contains("音量") -> {
                val level = extractVolumeLevel(lowerText)
                VoiceCommand.SetVolume(level)
            }
            lowerText.contains("重复") || lowerText.contains("再说") -> VoiceCommand.RepeatLastAnnouncement
            
            // 设置
            lowerText.contains("设置") || lowerText.contains("偏好") -> VoiceCommand.OpenSettings
            lowerText.contains("关闭设置") -> VoiceCommand.CloseSettings
            
            // SOS
            lowerText.contains("求救") || lowerText.contains("报警") || lowerText.contains("sos") -> VoiceCommand.SOS
            lowerText.contains("取消求救") -> VoiceCommand.CancelSOS
            
            // 通用
            lowerText.contains("帮助") -> VoiceCommand.Help
            lowerText.contains("取消") -> VoiceCommand.Cancel
            
            else -> VoiceCommand.Unknown(text)
        }.also { command ->
            commandHistory.add(command)
            Timber.d("Voice command parsed: $command from '$text'")
        }
    }
    
    private fun extractDestination(text: String): String {
        // 提取目的地
        val patterns = listOf("去", "导航到", "前往", "到")
        for (pattern in patterns) {
            if (text.contains(pattern)) {
                val index = text.indexOf(pattern) + pattern.length
                return text.substring(index).trim()
            }
        }
        return text
    }
    
    private fun extractVolumeLevel(text: String): Int {
        // 提取音量级别 (1-10)
        val numbers = listOf(
            "一" to 1, "二" to 2, "三" to 3, "四" to 4, "五" to 5,
            "六" to 6, "七" to 7, "八" to 8, "九" to 9, "十" to 10,
            "最大" to 10, "最小" to 1, "大" to 8, "小" to 3
        )
        
        for ((word, level) in numbers) {
            if (text.contains(word)) {
                return level
            }
        }
        
        // 尝试提取数字
        val regex = "\\d+".toRegex()
        val match = regex.find(text)
        return match?.value?.toIntOrNull()?.coerceIn(1, 10) ?: 5
    }
    
    /**
     * 语音识别监听器
     */
    private inner class VoiceRecognitionListener : android.speech.RecognitionListener {
        override fun onReadyForSpeech(params: android.os.Bundle?) {
            Timber.d("Ready for speech")
        }
        
        override fun onBeginningOfSpeech() {
            Timber.d("Speech beginning")
        }
        
        override fun onRmsChanged(rmsdB: Float) {}
        
        override fun onBufferReceived(buffer: ByteArray?) {}
        
        override fun onEndOfSpeech() {
            Timber.d("Speech ended")
            isListening = false
        }
        
        override fun onError(error: Int) {
            Timber.e("Speech recognition error: $error")
            isListening = false
        }
        
        override fun onResults(results: android.os.Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            if (!matches.isNullOrEmpty()) {
                val text = matches[0]
                val command = parseVoiceCommand(text)
                onCommand(command)
            }
        }
        
        override fun onPartialResults(partialResults: android.os.Bundle?) {}
        
        override fun onEvent(eventType: Int, params: android.os.Bundle?) {}
    }
}

/**
 * 语音命令帮助信息
 */
object VoiceCommandHelp {
    
    val COMMANDS = listOf(
        "开始导航" to "开始GPS导航功能",
        "停止导航" to "停止当前导航",
        "去[地点]" to "导航到指定地点，如'去北京站'",
        "开始检测" to "开启障碍物检测",
        "停止检测" to "关闭障碍物检测",
        "静音" to "关闭语音播报",
        "取消静音" to "恢复语音播报",
        "音量[1-10]" to "调整播报音量",
        "重复" to "重复最后一条播报",
        "求救" to "发送SOS求救信号",
        "帮助" to "获取帮助信息",
        "设置" to "打开设置页面"
    )
    
    fun getHelpText(): String {
        return "可用的语音命令：\n" + COMMANDS.joinToString("\n") { (cmd, desc) ->
            "• $cmd - $desc"
        }
    }
}
