# BlindPath 语音助手唤醒失败 — 全面诊断报告

> 诊断时间：2026-05-31 16:28
> 项目：BlindPath (智行助盲) - Android 视障出行辅助 App
> 症状：有欢迎词播放，但唤醒词"小智小智"无任何回应

---

## 一、系统架构概览

```
┌─────────────────────────────────────────────────────────────┐
│                      用户层 (UI)                             │
│  MainScreen → VoiceInteractionViewModel                      │
├─────────────────────────────────────────────────────────────┤
│                   交互管理层                                 │
│  VoiceInteractionManagerImpl                                  │
│    ├─ TTS 播报 (VoiceRepositoryImpl → Android TextToSpeech) │
│    └─ ASR 识别 (VoiceCommandRepositoryImpl → SpeechRecognizer)│
├─────────────────────────────────────────────────────────────┤
│                   唤醒引擎层                                 │
│  WakeWordService (前台服务)                                   │
│    ├─ BaiduWakeWordDetector (百度语音唤醒 SDK)               │
│    ├─ XfWakeWordDetector (科大讯飞 AIKit 唤醒)              │
│    └─ EnergyWakeWordDetector (能量检测降级)                  │
├─────────────────────────────────────────────────────────────┤
│                    广播桥接层                                 │
│  WakeWordReceiver → triggerWakeWordDetected()                │
└─────────────────────────────────────────────────────────────┘
```

## 二、症状复现路径

```
App 启动
  → VoiceInteractionViewModel.initialize()
    → VoiceInteractionManagerImpl.initialize()
      → TTS 初始化成功 ✅
      → SpeechRecognizer 初始化成功 ✅
    → speakWelcome() 
      → TTS 播报欢迎词 ✅  ← 用户听到"欢迎语"
      → setWakeWordEnabled(true)
        → startContinuousListening()
          → SpeechRecognizer.startListening() ❓
  → 用户说"小智小智"
    → 无任何响应 ❌  ← 唤醒词不响应
```

## 三、根因分析（共发现 6 个问题）

### 🔴 根因1（致命）：WakeWordService 从未启动

**严重程度：P0 — 致命缺陷**

**问题描述**：`WakeWordService` 是百度/讯飞唤醒引擎的宿主服务，但在整个代码库中 **没有任何地方调用 `startService()` 启动它**。

**代码证据**：

| 检查项 | 结果 |
|--------|------|
| AndroidManifest 中声明了 Service | ✅ 有 |
| WakeWordService.onStartCommand() 实现 | ✅ 完整 |
| 凭证文件 credentials.properties 存在 | ✅ 在 module_voice/src/main/assets/ |
| **某处调用了 startService/WakeWordService | ❌ **完全不存在** |

**影响链**：
```
WakeWordService 未启动
  → BaiduWakeWordDetector 未创建
  → XfWakeWordDetector 未创建
  → 只有 VoiceCommandRepositoryImpl 的 SpeechRecognizer 在工作
  → SpeechRecognizer 在国产设备上不可靠（见根因3）
  → 用户说唤醒词 → 无响应
```

**相关文件**：
- `module_voice/.../service/WakeWordService.kt` — 服务定义完整，但无人启动
- `app/.../ui/screens/MainScreen.kt` — 只初始化 ViewModel，未启动 WakeWordService
- `module_voice/.../viewmodel/VoiceInteractionViewModel.kt` — initialize() 中未启动服务

---

### 🔴 根因2（严重）：MainScreen 欢迎词绕过了 speakWelcome() 流程

**严重程度：P0 — 严重**

**问题描述**：`MainScreen.kt` 第 170-176 行在检测到 `isInitialized=true` 时，直接调用 `viewModel.speak()` 播放自定义欢迎词，**绕过了 `speakWelcome()` 的完整流程**。

**代码位置** (`MainScreen.kt:170-176`)：
```kotlin
LaunchedEffect(uiState.isInitialized) {
    if (uiState.isInitialized && !hasAnnouncedWelcome) {
        hasAnnouncedWelcome = true
        // ⚠️ 直接调用 speak()，不经过 speakWelcome()！
        viewModel.speak("已进入主界面，请说\"小智小智\"唤醒语音助手...")
    }
}
```

**问题**：
1. `viewModel.initialize()` 内部已经会调用 `interactionManager.speakWelcome()`
2. `speakWelcome()` 的流程是：播欢迎 → 等 TTS 完成 → **启动持续监听**
3. MainScreen 又额外调用了一次 `viewModel.speak()`，两次 TTS 并发执行
4. 可能导致 TTS 状态混乱，`waitForTtsComplete()` 超时或状态错乱
5. 最终影响 `setWakeWordEnabled(true)` 的正常执行

---

### 🟠 根因3（高）：SpeechRecognizer 连续监听在国产设备上不可靠

**严重程度：P1 — 高**

**问题描述**：内置唤醒词检测依赖 Android `SpeechRecognizer` + `ACTION_RECOGNIZE_SPEECH` 的连续监听模式。该方案存在以下致命问题：

| 问题类型 | 说明 |
|----------|------|
| **Google 服务依赖** | 需要 Google Play Services + Google App，国内设备大多缺失 |
| **OEM 替换** | 华为/小米/OPPO/vivo 等替换为自有识别引擎，行为不一致 |
| **超时限制** | 大多数 OEM 引擎在 5-10 秒内自动停止，不支持真正连续监听 |
| **ERROR_RECOGNIZER_BUSY** | 快速 restart 时极易触发此错误，陷入重启循环 |
| **UI 弹窗** | 部分 OEM 识别器会弹出系统 UI，打断用户体验 |
| **无回调** | `onResults()` 永远不被调用（静默失败），只触发 `onError()` |

**当前重试逻辑的问题** (`VoiceCommandRepositoryImpl:129-151`)：
```kotlin
override fun onError(error: Int) {
    if (isContinuousListeningEnabled) {
        retryCount++
        if (retryCount <= maxRetries) {  // maxRetries = 5
            val delayMs = minOf(1000L * retryCount, 5000L)
            scope.launch {
                delay(delayMs)
                startListening()  // 可能再次失败 → 死循环
            }
        }
    }
}
```
- 5 次重试后 retryCount 归零，但此时可能已过 15+ 秒
- Health check 每 8 秒检测一次不活跃就重启，但根本问题是识别器本身不工作

---

### 🟡 根因4（中）：TTS/ASR 协调时序问题

**严重程度：P2 — 中**

**问题描述**：从 TTS 播完到 ASR 开始监听之间有 **约 1.9 秒的空白期**。

时间线：
```
0ms     TTS 开始播报欢迎词
~2000ms TTS 播报完成
0ms     notifyTtsStop() 被调用
600ms   ttsResumeJob 延迟 → 尝试 startListening()
500ms   startContinuousListening 内部延迟
500ms   listeningJob 内部延迟
======
~1600ms 总延迟（用户在这段时间说唤醒词无效）
```

此外 `speakWelcome()` 还会在启动监听后**再播报一句唤醒词提示**：
```kotlin
// 启动持续监听后...
commandRepository.notifyTtsStart()
speak(promptText, VoiceType.SYSTEM_STATUS)  // "请说小智小智唤醒"
waitForTtsComplete()
commandRepository.notifyTtsStop()  // 又一次停止→恢复循环
```
这意味着实际监听要等**两轮 TTS 播报全部完成后**才稳定运行。

---

### 🟡 根因5（中）：BuildConfig 凭证为空导致 WakeWordService 自我终止

**严重程度：P2 — 中**

**问题描述**：即使 WakeWordService 被启动，由于 `build.gradle.kts` 从 Gradle properties 读取凭证：

```kotlin
val baiduAppId = project.findProperty("BAIDU_APP_ID") as String? ?: ""
```

CI/CD 或普通构建环境中 `local.properties` 不含这些 key，导致 BuildConfig 全部为空字符串。

虽然 `WakeWordService.startWakeWordDetection()` 有 assets 降级读取逻辑（`readCredentialsFromAllSources()`），且 `credentials.properties` 文件确实存在于 assets 中——**所以这个根因实际上被缓解了**，但如果 assets 文件打包丢失则仍会触发。

**当前状态**：⚠️ 凭证文件存在，此问题暂未触发，但是个隐患。

---

### 🟢 根因6（低）：唤醒词模型文件名不匹配

**严重程度：P3 — 低**

**问题描述**：配置中的默认唤醒词模型文件名与实际 assets 文件的关系：

| 配置项 | 值 |
|--------|-----|
| `WakeWordConfig.DEFAULT_WAKE_WORD` | `"小智同学"` |
| `WakeWordConfig.BAIDU_WAKE_WORD_ASSET` | `"WakeUp_xiaozhi.bin"` |
| 实际 assets 文件 | `WakeUp.bin`, `WakeUp_xiaozhi.bin` ✅ |
| `BaiduWakeWordDetector.wakeWordAssetPath` 默认值 | `"WakeUp.bin"` ❌ |

`BaiduWakeWordDetector` 构造函数参数 `wakeWordAssetPath` 默认值为 `"WakeUp.bin"`，但 `WakeWordEngineManager.createBaiduEngine()` 传入的是 `config.baiduWakeWordAsset` 即 `"WakeUp_xiaozhi.bin"`。**实际使用是正确的**，但默认值具有误导性。

---

## 四、修复优先级矩阵

| # | 根因 | 严重度 | 修复复杂度 | 建议 |
|---|------|--------|-----------|------|
| 1 | WakeWordService 未启动 | P0 致命 | 低（加几行代码） | **立即修复** |
| 2 | MainScreen 欢迎词冲突 | P0 严重 | 低（删除重复代码） | **立即修复** |
| 3 | SpeechRecognizer 不可靠 | P1 高 | 中（增强重试+降级） | **本次修复** |
| 4 | TTS/ASR 时序空白 | P2 中 | 低（减少延迟） | **本次修复** |
| 5 | BuildConfig 凭证空 | P2 中 | 已缓解（assets有兜底） | 记录备忘 |
| 6 | 模型文件名默认值误导 | P3 低 | 极低（改默认值） | 顺手修复 |

## 五、修复方案

### 修复 1：在 VoiceInteractionManagerImpl 中启动 WakeWordService

在 `initialize()` 方法中，完成 TTS 和 ASR 初始化后，启动 WakeWordService：

```kotlin
// VoiceInteractionManagerImpl.initialize() 末尾添加：
try {
    val intent = Intent(context, WakeWordService::class.java).apply {
        action = WakeWordService.ACTION_START
        putExtra("package", context.packageName)
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        context.startForegroundService(intent)
    } else {
        context.startService(intent)
    }
    Timber.i("VoiceInteraction: WakeWordService started")
} catch (e: Exception) {
    Timber.e(e, "VoiceInteraction: Failed to start WakeWordService, relying on built-in detection")
}
```

### 修复 2：移除 MainScreen 中的重复欢迎词

删除 MainScreen.kt 第 170-176 行的 `LaunchedEffect(uiState.isInitialized)` 块，让 `speakWelcome()` 统一管理欢迎词和监听启动。

### 修复 3：增强 SpeechRecognizer 可靠性
- 区分可恢复错误和不可恢复错误
- 对 ERROR_NO_MATCH 不做指数退避（这是正常行为）
- 对 ERROR_INSUFFICIENT_PERMISSIONS 直接放弃并提示用户
- 添加设备兼容性检测

### 修复 4：优化 TTS/ASR 协调时序
- 减少 notifyTtsStop 到 startListening 之间的延迟（600ms → 300ms）
- 合并 speakWelcome 中的二次播报为可选
