# Porcupine 离线语音唤醒系统 - 完整部署指南

## 【基础信息】

### 技术栈&环境
| 类别 | 技术 | 版本 |
|:---|:---|:---|
| **语言** | Kotlin | 1.9+ |
| **框架** | Android SDK | 33+ |
| **UI** | Jetpack Compose | 1.5+ |
| **唤醒引擎** | Picovoice Porcupine | 3.0.1 |
| **最低系统** | Android 8.0 | API 26 |

### 项目模块
| 模块 | 作用 | 文件 |
|:---|:---|:---|
| `porcupine-wakeup` | 离线唤醒引擎核心 | `PorcupineWakeWordEngine.kt` |
| `audio-recorder` | 音频采集 | `AudioRecorder.kt` |
| `voice-processor` | 交互管理 | `VoiceInteractionManager.kt` |
| `demo` | 示例应用 | `PorcupineDemoActivity.kt` |

---

## 【依赖清单】

### Gradle 依赖
```kotlin
// porcupine-wakeup/build.gradle.kts
dependencies {
    // Porcupine SDK
    implementation("ai.picovoice:porcupine-android:3.0.1")
    
    // Kotlin 协程
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    
    // 日志
    implementation("com.jakewharton.timber:timber:5.0.1")
    
    // AndroidX
    implementation("androidx.core:core-ktx:1.12.0")
}
```

### 权限配置
```xml
<!-- AndroidManifest.xml -->
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.INTERNET" />
```

---

## 【安装步骤】

### 1. 获取 Porcupine Access Key

1. 访问 **Picovoice Console**: https://picovoice.ai/console/
2. 注册账号并登录
3. 创建新项目
4. 复制 **Access Key**

### 2. 配置 Access Key

**方法 A：local.properties（推荐）**
```properties
# local.properties
PORCUPINE_ACCESS_KEY=YOUR_ACCESS_KEY_HERE
```

**方法 B：assets/credentials.properties**
```properties
# module_voice/src/main/assets/credentials.properties
PORCUPINE_ACCESS_KEY=YOUR_ACCESS_KEY_HERE
```

### 3. 下载唤醒词模型

1. 在 Picovoice Console 选择唤醒词（如 "Hey Assistant"）
2. 下载 **Android 平台**的 `.ppn` 文件
3. 放入 `porcupine-wakeup/src/main/assets/keywords/`

### 4. 添加模块到项目

```kotlin
// settings.gradle.kts
include(":porcupine-wakeup")
```

```kotlin
// app/build.gradle.kts
dependencies {
    implementation(project(":porcupine-wakeup"))
}
```

---

## 【启动流程】

### 代码示例

```kotlin
class MainActivity : AppCompatActivity() {
    private lateinit var voiceManager: VoiceInteractionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 配置
        val config = PorcupineConfig(
            accessKey = "YOUR_ACCESS_KEY",
            keywordAssetPath = "keywords/hey-assistant_android.ppn"
        )
        
        // 2. 初始化
        voiceManager = VoiceInteractionManager(this, config)
        voiceManager.initialize()
        
        // 3. 设置回调
        voiceManager.onWakeWordDetected = {
            // 唤醒成功
            speak("我在，请说指令")
        }
        
        voiceManager.onCommandRecognized = { command ->
            // 指令识别成功
            handleCommand(command)
        }
        
        // 4. 开始监听
        voiceManager.start()
    }
    
    private fun handleCommand(command: String) {
        when {
            command.contains("导航") -> startNavigation()
            command.contains("回家") -> navigateHome()
            else -> speak("没听清")
        }
    }
    
    override fun onDestroy() {
        voiceManager.release()
        super.onDestroy()
    }
}
```

---

## 【测试流程】

### 1. 单元测试

```kotlin
@Test
fun testPorcupineInitialization() {
    val engine = PorcupineWakeWordEngine(
        context = mockContext,
        accessKey = "test_key",
        keywordPath = "test.ppn"
    )
    
    // 测试初始化
    val result = engine.initialize()
    assertTrue(result)
}

@Test
fun testAudioRecorderStart() {
    val recorder = AudioRecorder(mockContext)
    
    // 测试启动
    val started = recorder.start()
    assertTrue(started)
    
    // 测试 PCM 流
    recorder.pcmFlow.first()
    recorder.stop()
}
```

### 2. 集成测试

```bash
# 安装测试 APK
adb install app-debug.apk

# 启动 Activity
adb shell am start -n com.blindpath.app/.PorcupineDemoActivity

# 查看日志
adb logcat -s PorcupineDemoActivity:* PorcupineEngine:* AudioRecorder:* VoiceManager:*
```

### 3. 功能测试

| 测试项 | 操作 | 预期结果 |
|:---|:---|:---|
| 权限请求 | 启动 APP | 弹出录音权限请求 |
| 唤醒检测 | 说 "Hey Assistant" | 显示"我在，请说指令" |
| 指令识别 | 说 "导航" | 打开导航界面 |
| 错误处理 | 无网络说指令 | 显示错误提示 |
| 状态恢复 | 指令完成后 | 自动回到唤醒监听 |

---

## 【部署说明】

### 1. 构建 APK

```bash
# 使用 Android Studio
Build → Build APK(s)

# 或使用 Gradle
./gradlew :app:assembleDebug
```

### 2. 安装到设备

```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

### 3. 验证部署

```bash
# 检查 Porcupine 初始化日志
adb logcat -d | grep "PorcupineEngine: 初始化成功"

# 预期输出：
# PorcupineEngine: 初始化成功，采样率=16000, 帧长=512
```

---

## 【故障排查】

| 问题 | 原因 | 解决方案 |
|:---|:---|:---|
| 初始化失败 | Access Key 无效 | 检查 Key 是否正确配置 |
| 无法唤醒 | 模型文件缺失 | 确认 .ppn 文件在 assets |
| 识别率低 | 环境噪音大 | 调整麦克风位置 |
| 指令识别失败 | 无网络 | 检查网络连接 |
| 权限被拒 | 用户拒绝 | 提示用户手动授权 |

---

## 【免费额度说明】

- **每月 10,000 次** Porcupine 请求
- 每次启动 APP 后持续监听，算 **1 次请求**
- 正常个人使用完全足够

---

## 【文件清单】

```
porcupine-wakeup/
├── build.gradle.kts                    # 模块构建配置
├── proguard-rules.pro                  # ProGuard 规则
├── README.md                           # 本文档
└── src/main/
    ├── assets/
    │   └── keywords/
    │       ├── README.md               # 唤醒词模型说明
    │       └── hey-assistant_android.ppn # 唤醒词模型（需下载）
    └── java/com/blindpath/
        ├── porcupine/
        │   └── PorcupineWakeWordEngine.kt  # 唤醒引擎核心
        ├── audio/
        │   └── AudioRecorder.kt            # 音频采集
        └── voice/
            ├── VoiceInteractionManager.kt  # 交互管理
            └── RecognitionResult.kt        # 识别结果封装
```

---

## 【设计思路总结】

### 架构设计

```
唤醒阶段（离线）          指令阶段（云端）
     ↓                        ↓
AudioRecorder           SpeechRecognizer
     ↓                        ↓
PorcupineEngine         → 指令解析 → 执行
     ↓                        ↓
  唤醒回调              → 回到唤醒监听
```

### 关键设计决策

1. **Porcupine 替代 SpeechRecognizer 持续监听**
   - 原因：华为设备无 Google Play 服务
   - 优势：完全离线、低延迟、低功耗

2. **保留 SpeechRecognizer 用于指令识别**
   - 原因：云端识别准确率高
   - 优势：仅唤醒后使用，网络依赖可控

3. **状态机管理交互流程**
   - IDLE → LISTENING → WAKEUP_DETECTED → RECOGNIZING → LISTENING
   - 自动状态转换，无需手动干预

4. **Flow 替代回调**
   - 响应式编程，易于 UI 绑定
   - 支持多订阅者，灵活扩展