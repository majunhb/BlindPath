# Porcupine 离线语音唤醒系统

基于 Picovoice Porcupine 的离线语音唤醒解决方案，专为华为等无 Google Play 服务设备设计。

## 系统架构

```
┌─────────────────────────────────────────────────────────┐
│                    VoiceInteractionManager                │
│  ┌─────────────┐      ┌─────────────┐      ┌──────────┐ │
│  │AudioRecorder│─────►│   Porcupine │─────►│  Speech  │ │
│  │  (16kHz)    │      │   唤醒引擎   │      │Recognizer│ │
│  └─────────────┘      └─────────────┘      └──────────┘ │
│         │                    │                   │       │
│         ▼                    ▼                   ▼       │
│    持续采集音频          本地检测唤醒词         云端识别指令 │
│    （离线）              （离线）              （联网）    │
└─────────────────────────────────────────────────────────┘
```

## 模块说明

| 模块 | 文件 | 功能 |
|:---|:---|:---|
| 音频采集 | `AudioRecorder.kt` | 16kHz PCM 音频采集，环形缓冲区 |
| 唤醒引擎 | `PorcupineWakeWordEngine.kt` | Porcupine 离线唤醒，<100ms 延迟 |
| 交互管理 | `VoiceInteractionManager.kt` | 整合唤醒+识别，状态管理 |

## 集成步骤

### 1. 添加依赖

```kotlin
// build.gradle.kts
dependencies {
    implementation("ai.picovoice:porcupine-android:3.0.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.jakewharton.timber:timber:5.0.1")
}
```

### 2. 获取 Access Key

1. 访问 https://picovoice.ai/console/
2. 注册账号
3. 创建项目，获取 Access Key
4. **免费额度**：每月 10,000 次请求

### 3. 准备唤醒词模型

```bash
# 使用 Picovoice Console 训练自定义唤醒词
# 或下载预训练模型（如 "Hey Assistant"）

# 将 .ppn 文件放入 assets/keywords/
```

### 4. 初始化使用

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var voiceManager: VoiceInteractionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 配置
        val config = PorcupineConfig(
            accessKey = "YOUR_ACCESS_KEY_HERE",
            keywordAssetPath = "keywords/hey-assistant_android.ppn"
        )
        
        // 初始化
        voiceManager = VoiceInteractionManager(this, config)
        voiceManager.initialize()
        
        // 设置回调
        voiceManager.onWakeWordDetected = {
            // 唤醒词检测成功
            Toast.makeText(this, "我在，请说指令", Toast.LENGTH_SHORT).show()
        }
        
        voiceManager.onCommandRecognized = { command ->
            // 指令识别成功
            handleCommand(command)
        }
        
        // 开始监听
        voiceManager.start()
    }
    
    private fun handleCommand(command: String) {
        when {
            command.contains("导航") -> startNavigation()
            command.contains("回家") -> navigateHome()
            command.contains("打电话") -> makeCall()
            else -> speak("没听清，请再说一遍")
        }
    }
    
    override fun onDestroy() {
        voiceManager.release()
        super.onDestroy()
    }
}
```

## 权限配置

```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

## 唤醒词选择

| 唤醒词 | 语言 | 说明 |
|:---|:---|:---|
| "Hey Assistant" | 英文 | 预训练模型，识别率高 |
| "Xiao Zhi" | 拼音 | 中文近似音 |
| 自定义 | 英文 | 使用 Picovoice Console 训练 |

**注意**：Porcupine 对纯中文支持有限，建议使用英文唤醒词。

## 性能指标

| 指标 | 数值 |
|:---|:---|
| 唤醒延迟 | < 100ms |
| 功耗 | 低（本地计算） |
| 网络依赖 | 仅指令识别需要 |
| 华为兼容 | ✅ 完美支持 |

## 与 BlindPath 集成

替换原有的 `VoiceCommandRepositoryImpl`：

```kotlin
// 原方案：SpeechRecognizer 持续监听（华为不可用）
// 新方案：Porcupine 唤醒 + SpeechRecognizer 单次识别

class VoiceCommandRepositoryImpl(
    private val context: Context
) : VoiceCommandRepository {
    
    private val voiceManager = VoiceInteractionManager(
        context,
        PorcupineConfig(
            accessKey = BuildConfig.PORCUPINE_ACCESS_KEY,
            keywordAssetPath = "keywords/hey-assistant_android.ppn"
        )
    )
    
    override fun initialize() {
        voiceManager.initialize()
        voiceManager.onWakeWordDetected = {
            _commandFlow.tryEmit(VoiceCommand.WAKEWORD_DETECTED)
        }
        voiceManager.onCommandRecognized = { command ->
            parseCommand(command)
        }
    }
    
    override fun startListening() {
        voiceManager.start()
    }
}
```

## 免费额度说明

- **每月 10,000 次** Porcupine 请求
- 对于唤醒场景：每次启动 App 后持续监听，算 1 次请求
- 正常使用完全足够

## 故障排查

| 问题 | 原因 | 解决 |
|:---|:---|:---|
| 初始化失败 | Access Key 无效 | 检查 Key 是否正确 |
| 无法唤醒 | 模型文件路径错误 | 确认 .ppn 文件在 assets 中 |
| 识别率低 | 环境噪音大 | 调整麦克风位置 |
| 指令识别失败 | 无网络连接 | 检查网络或降级处理 |

## 许可证

Porcupine SDK 遵循 Apache 2.0 许可证。
