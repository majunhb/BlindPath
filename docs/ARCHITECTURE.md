# BlindPath 系统架构文档

## 1. 系统概览

BlindPath 是一款面向视障人士的辅助行走 Android 应用，通过摄像头实时检测障碍物、GPS 导航和语音播报帮助用户安全出行。

### 1.1 核心功能

| 功能 | 描述 | 技术实现 |
|------|------|----------|
| 障碍物检测 | 实时检测前方障碍物 | TensorFlow Lite + CameraX |
| 导航指引 | GPS 定位 + 语音导航 | Google Play Services Location |
| 语音播报 | 中文 TTS 语音反馈 | Android TextToSpeech |
| 振动反馈 | 多级别振动预警 | Android Vibration API |
| SOS 求救 | 位置分享求救 | 短信/网络 |

### 1.2 技术栈

| 分类 | 技术 | 版本 |
|------|------|------|
| 语言 | Kotlin | 1.9.20 |
| UI | Jetpack Compose + Material3 | BOM 2023.10.01 |
| DI | Hilt (KSP) | 2.48 |
| ML | TensorFlow Lite + ML Kit | 2.13.0 / 17.0.0 |
| Camera | CameraX | 1.3.0 |
| Async | Coroutines + Flow | 1.7.3 |
| Build | Gradle Kotlin DSL | AGP 8.2.0 |

---

## 2. 架构设计

### 2.1 模块架构

```
┌─────────────────────────────────────────────────────────────────┐
│                           app 模块                               │
│  主应用入口：MainActivity, MainViewModel, MainScreen            │
├─────────────────────────────────────────────────────────────────┤
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐  ┌──────────────────────┐  │
│  │ module_voice │  │module_obstacle│  │  module_navigation   │  │
│  │   语音播报    │  │   障碍物检测   │  │      导航指引        │  │
│  └──────────────┘  └──────────────┘  └──────────────────────┘  │
│                                                                   │
│  ┌──────────────┐  ┌──────────────┐                             │
│  │module_settings│  │module_community│                           │
│  │   设置管理    │  │    社区功能    │                             │
│  └──────────────┘  └──────────────┘                             │
│                                                                   │
├─────────────────────────────────────────────────────────────────┤
│                          base 模块                               │
│  公共组件：Result, AlertLevel, VibrationHelper, TTS Helper      │
└─────────────────────────────────────────────────────────────────┘
```

### 2.2 模块依赖关系

```
app
├── base
├── module_voice
├── module_obstacle
│   └── module_voice
├── module_navigation
│   └── module_voice
├── module_settings
│   └── base
└── module_community
    ├── base
    └── module_voice
```

### 2.3 层架构（每模块内）

```
┌─────────────────────────────────────────────────────────────┐
│                        UI 层                                 │
│   ViewModel (状态管理) ←→ Compose UI (界面展示)             │
└─────────────────────────────────────────────────────────────┘
                              ↕
┌─────────────────────────────────────────────────────────────┐
│                      Domain 层                               │
│   Repository 接口 ←→ Use Case (可选)                        │
└─────────────────────────────────────────────────────────────┘
                              ↕
┌─────────────────────────────────────────────────────────────┐
│                       Data 层                                │
│   Repository 实现 ←→ 数据源 (TTS, Camera, Location, etc.)   │
└─────────────────────────────────────────────────────────────┘
```

---

## 3. 核心模块详解

### 3.1 module_voice（语音播报）

**职责**：统一管理所有语音输出

**接口定义**：
```kotlin
interface VoiceRepository {
    val voiceState: StateFlow<VoiceState>
    
    suspend fun initialize(): Result<Boolean>
    suspend fun speak(text: String, queueMode: Boolean): Result<Boolean>
    suspend fun speakObstacleAlert(text: String)  // 高优先级
    suspend fun speakNavigation(text: String)     // 低优先级
    fun release()
}
```

**播报优先级**：
```
避障预警 (FLUSH) > 语音播报 (FLUSH) > 导航指令 (ADD)
                     ↑                    ↑
              立即打断当前播报      排队，不打断避障
```

### 3.2 module_obstacle（障碍物检测）

**职责**：实时检测障碍物并预警

**核心组件**：
- `ObstacleService` - 前台服务，持续运行
- `ObstacleRepository` - 管理 TFLite 模型和 CameraX
- `ObstacleDetector` - 实际检测逻辑

**数据流**：
```
CameraX → ImageAnalysis → TFLite → 障碍物检测 → AlertState
                                    ↓
                              语音+振动预警
```

### 3.3 module_navigation（导航指引）

**职责**：GPS 定位和导航播报

**核心组件**：
- `NavigationService` - 前台服务
- `NavigationRepository` - 管理定位和导航逻辑
- `LocationProvider` - 封装 Google Play Services Location

**防重复播报规则**：
- 指令变化时播报
- 距离变化 ≥ 5 米时播报

---

## 4. 前台服务架构

### 4.1 服务生命周期

```
┌──────────────┐     ┌───────────────┐     ┌──────────────┐
│   onCreate   │ →   │ onStartCommand│  →  │ startForeground│
└──────────────┘     └───────────────┘     └──────────────┘
                                                    ↓
┌──────────────┐     ┌───────────────┐     ┌──────────────┐
│   onDestroy  │ ←   │   stopSelf    │ ←   │    stop...   │
└──────────────┘     └───────────────┘     └──────────────┘
```

### 4.2 服务间通信

```
┌─────────────────────────────────────────────────┐
│                   MainActivity                   │
│                                                  │
│   ┌─────────────┐   ┌─────────────────────────┐ │
│   │ MainViewModel│ ←→ │      Compose UI        │ │
│   └─────────────┘   └─────────────────────────┘ │
│          ↓                                        │
│   ┌────────────────────────────────────────────┐ │
│   │              Intent (Action)               │ │
│   └────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────┘
          ↓                              ↓
┌─────────────────┐            ┌─────────────────┐
│ ObstacleService │            │NavigationService │
│                 │            │                  │
│ - CameraX       │            │ - GPS Location   │
│ - TFLite        │            │ - Navigation API │
│ - Voice Alert   │            │ - Voice Guide    │
└─────────────────┘            └─────────────────┘
```

---

## 5. 状态管理

### 5.1 ViewModel 状态流

```kotlin
data class MainUiState(
    val isObstacleRunning: Boolean = false,
    val isNavigationRunning: Boolean = false,
    val pendingAction: String? = null,
    val errorMessage: String? = null
)

// 在 ViewModel 中
private val _uiState = MutableStateFlow(MainUiState())
val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()
```

### 5.2 Service 状态流

```kotlin
data class ObstacleState(
    val isDetecting: Boolean = false,
    val currentAlert: AlertInfo? = null,
    val detectedObjects: List<DetectedObject> = emptyList()
)

// 在 Repository 中
val obstacleState: StateFlow<ObstacleState> = _state.asStateFlow()
```

---

## 6. 权限管理

### 6.1 权限列表

| 权限 | 用途 | 危险级别 |
|------|------|----------|
| CAMERA | 障碍物检测 | dangerous |
| ACCESS_FINE_LOCATION | 精确定位 | dangerous |
| ACCESS_COARSE_LOCATION | 粗略定位 | dangerous |
| VIBRATE | 振动反馈 | normal |
| FOREGROUND_SERVICE | 前台服务 | normal |
| FOREGROUND_SERVICE_CAMERA | 相机前台服务 | normal |
| FOREGROUND_SERVICE_LOCATION | 定位前台服务 | normal |
| POST_NOTIFICATIONS | 通知 | dangerous (API 33+) |

### 6.2 权限检查流程

```
请求权限 → 检查结果
    ↓
GRANTED → 执行操作
    ↓
DENIED → 显示引导UI / 再次请求
    ↓
NEVER_ASK_AGAIN → 打开设置
```

---

## 7. 构建配置

### 7.1 模块构建变体

```
debug    → 测试/开发
release  → 生产发布
```

### 7.2 关键构建配置

```kotlin
android {
    namespace = "com.blindpath.app"
    compileSdk = 34
    defaultConfig {
        minSdk = 26          // Android 8.0
        targetSdk = 34       // Android 14
    }
}
```

---

## 8. CI/CD 流程

```
┌─────────────────────────────────────────────────────┐
│                    Git Push/PR                      │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│                   CI Pipeline                        │
│                                                      │
│   1. Checkout code                                  │
│   2. Setup JDK 17                                   │
│   3. Setup Android SDK                              │
│   4. Cache Gradle                                    │
│   ↓                                                  │
│   5. Run unit tests (testDebugUnitTest)              │
│   6. Run lint (lintDebug)                           │
│   7. Build APK (assembleDebug)                       │
│   ↓                                                  │
│   8. Upload artifacts                               │
│      - APK                                           │
│      - Test reports                                  │
│      - Lint reports                                  │
└─────────────────────────────────────────────────────┘
                        ↓
┌─────────────────────────────────────────────────────┐
│              Quality Gates                          │
│                                                      │
│   - All tests pass?                                 │
│   - Lint errors < threshold?                        │
│   - Build successful?                                │
└─────────────────────────────────────────────────────┘
```

---

*文档版本：1.0*
*最后更新：2026-05-16*
