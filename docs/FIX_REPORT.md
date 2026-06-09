# BlindPath 语音唤醒修复报告 — v2026-05-31

> 修复时间：2026-05-31
> 修复范围：语音助手唤醒词"小智小智"无响应问题

---

## 一、问题现象

| 项目 | 详情 |
|------|------|
| 症状 | App 启动后播放欢迎词正常 ✅，但说"小智小智"无任何响应 ❌ |
| 平台 | Android |
| 唤醒词 | "小智小智"/"小智同学"（本地唤醒） |
| 历史修复 | 已经历多轮修复（见 Git log：27c0d9e, a9bc526, e9a6a5d 等） |

## 二、根因诊断结果（6 个问题）

### 🔴 P0-1：WakeWordService 从未启动（致命）
**文件**：`VoiceInteractionManagerImpl.kt`
**原因**：`WakeWordService` 是百度/讯飞低功耗唤醒引擎的宿主服务。代码定义完整、凭证齐全、AndroidManifest 声明正确，但 **整个代码库中没有任何一处调用 `startService()` 启动它**。
**修复**：在 `VoiceInteractionManagerImpl.initialize()` 末尾新增 `startWakeWordService()` 方法。

### 🔴 P0-2：MainScreen 欢迎词绕过 speakWelcome() 流程（严重）
**文件**：`MainScreen.kt:170-176`
**原因**：`MainScreen` 在检测到 `isInitialized=true` 时直接调用 `viewModel.speak()` 播放欢迎词，与 `ViewModel.initialize()` → `speakWelcome()` 并发执行，导致 TTS 队列冲突和 `setWakeWordEnabled(true)` 被干扰。
**修复**：移除 MainScreen 中的重复欢迎词代码（注释保留说明）。

### 🟠 P1：SpeechRecognizer 错误处理不当（高）
**文件**：`VoiceCommandRepositoryImpl.kt:112-151`
**原因**：
1. `ERROR_NO_MATCH`（正常行为）和 `ERROR_RECOGNIZER_BUSY`（需重建）使用相同的指数退避策略
2. 权限不足时仍反复重试而非直接停止
3. 重试耗尽后归零但可能已卡入不良状态
**修复**：按错误类型分类处理——可恢复错误快速重启、需重建错误重建实例、不可恢复错误停止监听。

### 🟡 P2：TTS/ASR 切换空白期过长（中）
**文件**：`VoiceCommandRepositoryImpl.kt` + `VoiceInteractionManagerImpl.kt`
**原因**：TTS 停止到 ASR 恢复之间累计延迟约 **1.9 秒**（600ms + 500ms + 800ms），此期间用户说唤醒词无效。
**修复**：三处延迟分别缩减至 300ms + 200ms + 300ms = **800ms**（减少 58% 空白期）。

### 🟡 P2-备用：BuildConfig 凭证为空（已缓解）
**文件**：`module_voice/build.gradle.kts`
**状态**：⚠️ BuildConfig 从 Gradle properties 读取（CI 中为空），但 `credentials.properties` 在 assets 中存在作为兜底。**当前不触发，但需注意 CI 配置。**

### 🟢 P3：BaiduWakeWordDetector 默认模型名误导（低）
**文件**：`BaiduWakeWordDetector.kt:39`
**修复**：默认值从 `"WakeUp.bin"` 改为引用 `WakeWordConfig.BAIDU_WAKE_WORD_ASSET`（`"WakeUp_xiaozhi.bin"`）。

---

## 三、修改文件清单

```
 module_voice/.../data/VoiceInteractionManagerImpl.kt   +53行 (新增 WakeWordService 启动 + Context 注入 + 时序优化)
 module_voice/.../data/VoiceCommandRepositoryImpl.kt    +87行 (错误分类处理 + 时序优化)
 module_voice/.../service/BaiduWakeWordDetector.kt      +2行  (默认模型名修正)
 app/.../ui/screens/MainScreen.kt                       -10行 (移除重复欢迎词)
```

**总计**：4 个文件修改，+125 行 / -35 行

---

## 四、修复后的交互流程（对比）

### 修复前
```
App 启动
  → initialize() → TTS ✅ ASR ✅
  → MainScreen 直接 speak("已进入主界面...")    ← 与下文冲突 ⚠️
  → speakWelcome()
    → speak(欢迎词)          ← 两路 TTS 并发
    → waitForTtsComplete()   ← 可能超时/错乱
    → setWakeWordEnabled(true) ← 可能未执行
    → SpeechRecognizer.startListening() ← 唯一唤醒方式，国产设备不可靠 ❌
  → 用户说"小智小智"
    → 无响应 ❌ (WakeWordService 未启动 + SpeechRecognizer 不工作)
```

### 修复后
```
App 启动
  → initialize()
    → TTS 初始化 ✅
    → ASR 初始化 ✅
    → ★ startWakeWordService()   ← 新增！百度引擎启动 ✅
    → startCommandProcessing()
  → speakWelcome()
    → speak(统一欢迎词)           ← 只有一路 TTS
    → waitForTtsComplete()        ← 无冲突
    → setWakeWordEnabled(true)    ← 正常执行
      → SpeechRecognizer 内置唤醒 ← 备用路径
    → speak(唤醒提示)
  → WakeWordService 后台运行       ← 百度/讯飞低功耗唤醒 ✅ ★
  → 用户说"小智小智"
    → 百度引擎检测到 ✅ ← OR → SpeechRecognizer 内置检测 ✅
    → "我在，请说指令"
```

---

## 五、测试验证步骤

### 1. 构建与安装
```bash
cd BlindPath
./gradlew assembleDebug
# 安装 app/build/outputs/apk/debug/app-debug.apk 到设备
```

### 2. 功能验证清单
- [ ] **欢迎词**：启动 App 后听到欢迎语音播报
- [ ] **唤醒提示**：欢迎词结束后听到"请说小智同学唤醒"
- [ ] **百度唤醒**：说"小智小智"或"小智同学"，应回复"我在，请说指令"
- [ ] **指令识别**：唤醒后说"开启障碍物检测"等指令，应正确执行
- [ ] **日志确认**：`adb logcat | grep BlindPath` 应看到：
  - `"WakeWordService started"` ← 新增日志
  - `"Wake word detected"` ← 唤醒成功
  - `"Command recognized"` ← 指令识别成功

### 3. 边界情况
- [ ] **无网络**：百度引擎离线模型仍可工作（WakeUp_xiaozhi.bin 已打包）
- [ ] **权限拒绝**：拒绝录音权限后应有明确提示
- [ ] **后台恢复**：切到后台再切回，唤醒仍可用（前台服务保持）

---

## 六、后续建议（非本次修复范围）

1. **CI 凭证配置**：在 GitHub Secrets 中配置 `BAIDU_APP_ID` 等，使 BuildConfig 也有值
2. **唤醒词训练**：当前 `WakeUp_xiaozhi.bin` 是通用模型，建议用百度平台针对用户声音定制训练
3. **SpeechRecognizer 兜底增强**：对于完全不支持 Google 语音服务的设备，可集成 Vosk/Whisper 离线识别引擎
4. **功耗监控**：WakeWordService 使用 PARTIAL_WAKE_LOCK，建议添加 CPU 运行时间监控
