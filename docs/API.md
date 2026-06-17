# BlindPath API 文档

## 概述

BlindPath 采用 Clean Architecture 架构模式，所有核心功能通过 Repository 接口暴露。

---

## 1. 核心接口

### 1.1 ObstacleRepository - 障碍物检测

```kotlin
interface ObstacleRepository {
    /** 检测状态流 */
    val state: StateFlow<ObstacleState>
    
    /** 初始化检测器 */
    suspend fun initialize(): Result<Boolean>
    
    /** 开始检测 */
    fun startDetection()
    
    /** 停止检测 */
    fun stopDetection()
    
    /** 释放资源 */
    fun release()
}
```

**状态定义**：
```kotlin
data class ObstacleState(
    val isRunning: Boolean = false,
    val isCameraReady: Boolean = false,
    val isModelLoaded: Boolean = false,
    val currentAlert: ObstacleAlert? = null,
    val detectedObstacles: List<DetectedObstacle> = emptyList(),
    val sceneRecognition: String? = null
)
```

**使用示例**：
```kotlin
@Composable
fun ObstacleScreen(viewModel: ObstacleViewModel) {
    val state by viewModel.state.collectAsState()
    
    LaunchedEffect(Unit) {
        viewModel.initialize()
        viewModel.startDetection()
    }
    
    // 监听预警
    LaunchedEffect(state.currentAlert) {
        state.currentAlert?.let { alert ->
            // 处理预警
        }
    }
}
```

---

### 1.2 NavigationRepository - 导航指引

```kotlin
interface NavigationRepository {
    /** 导航状态流 */
    val state: StateFlow<NavigationState>
    
    /** GPS 信号质量 */
    val gpsQuality: StateFlow<GpsQuality>
    
    /** 初始化导航 */
    suspend fun initialize(): Result<Boolean>
    
    /** 开始导航 */
    fun startNavigation()
    
    /** 停止导航 */
    fun stopNavigation()
}
```

**GPS 质量枚举**：
```kotlin
enum class GpsQuality {
    EXCELLENT,  // < 5m 精度
    GOOD,       // 5-10m 精度
    FAIR,       // 10-20m 精度
    POOR        // > 20m 精度
}
```

---

### 1.3 VoiceRepository - 语音播报

```kotlin
interface VoiceRepository {
    /** 播报状态 */
    val isSpeaking: StateFlow<Boolean>
    
    /** 初始化 TTS */
    suspend fun initialize(): Result<Boolean>
    
    /** 普通播报 */
    fun speak(text: String, queueMode: Boolean = false)
    
    /** 高优先级播报（打断当前） */
    fun speakObstacleAlert(text: String)
    
    /** 停止播报 */
    fun stop()
    
    /** 释放资源 */
    fun release()
}
```

**播报优先级**：
| 方法 | 优先级 | 行为 |
|------|--------|------|
| `speakObstacleAlert()` | 最高 | 立即打断，播放 |
| `speak(text, false)` | 高 | 清空队列，播放 |
| `speak(text, true)` | 普通 | 加入队列，顺序播放 |

---

## 2. 错误处理

### 2.1 BlindPathError

```kotlin
sealed class BlindPathError {
    object Camera : ErrorCategory() {
        object PERMISSION_DENIED : Camera()
        object DEVICE_NOT_FOUND : Camera()
        object INIT_FAILED : Camera()
    }
    
    object Location : ErrorCategory() {
        object PERMISSION_DENIED : Location()
        object GPS_DISABLED : Location()
        object TIMEOUT : Location()
    }
    
    object AI : ErrorCategory() {
        object MODEL_NOT_FOUND : AI()
        object MODEL_LOAD_FAILED : AI()
        object INFERENCE_FAILED : AI()
    }
    
    object Network : ErrorCategory() {
        object NO_CONNECTION : Network()
        object TIMEOUT : Network()
    }
}
```

### 2.2 统一结果封装

```kotlin
sealed class Result<out T> {
    data class Success<T>(val data: T) : Result<T>()
    data class Error(val code: Int, val message: String) : Result<Nothing>()
    object Loading : Result<Nothing>()
    
    inline fun onSuccess(action: (T) -> Unit): Result<T>
    inline fun onError(action: (Int, String) -> Unit): Result<T>
    inline fun <R> map(transform: (T) -> R): Result<R>
}
```

**使用示例**：
```kotlin
repository.initialize()
    .onSuccess { Log.d("TAG", "初始化成功") }
    .onError { code, msg -> 
        val userMsg = ErrorMessageResolver.resolve(BlindPathError.from(code))
        showToast(userMsg)
    }
```

---

## 3. 降级管理

### 3.1 DegradationManager

```kotlin
object DegradationManager {
    /** 当前降级级别 */
    val currentLevel: DegradationLevel
    
    /** 应用降级策略 */
    fun degrade(level: DegradationLevel)
    
    /** 恢复到更高级别 */
    fun promote(level: DegradationLevel)
    
    /** 检查功能是否可用 */
    fun isFeatureAvailable(feature: Feature): Boolean
}
```

**降级级别**：
```kotlin
enum class DegradationLevel {
    NORMAL,      // 全功能运行
    LOW_POWER,   // 低电量模式：降低帧率
    OFFLINE,     // 离线模式：禁用网络功能
    MINIMAL      // 最小模式：仅核心功能
}
```

**降级策略示例**：
```kotlin
// 电量低于 15% 时自动降级
if (batteryLevel < 15) {
    DegradationManager.degrade(DegradationLevel.LOW_POWER)
    // 自动：帧率降至 15 FPS，关闭后台任务
}

// 检查功能可用性
if (DegradationManager.isFeatureAvailable(Feature.AI_DETECTION)) {
    aiDetector.start()
}
```

---

## 4. 缓存管理

### 4.1 CacheManager

```kotlin
object CacheManager {
    /** 存储数据 */
    fun <T> put(key: String, data: T, ttl: Duration? = null)
    
    /** 获取数据 */
    fun <T> get(key: String): T?
    
    /** 检查是否存在 */
    fun exists(key: String): Boolean
    
    /** 清除缓存 */
    fun clear()
    
    /** 清除过期缓存 */
    fun clearExpired()
}
```

**使用示例**：
```kotlin
// 缓存导航数据（24小时有效）
CacheManager.put("last_route", routeData, Duration.ofHours(24))

// 获取缓存
val cachedRoute = CacheManager.get<RouteData>("last_route")
```

---

## 5. 性能监控

### 5.1 PerformanceMonitor

```kotlin
object PerformanceMonitor {
    /** 记录帧时间 */
    fun recordFrameTime(frameTimeMs: Long)
    
    /** 记录操作耗时 */
    fun <T> measureOperation(name: String, block: () -> T): T
    
    /** 获取性能报告 */
    fun getReport(): PerformanceReport
}
```

**使用示例**：
```kotlin
// 测量 AI 推理耗时
val result = PerformanceMonitor.measureOperation("ai_inference") {
    aiDetector.detect(bitmap)
}

// 检测卡顿
if (frameTimeMs > 33) { // 超过 30 FPS
    PerformanceMonitor.recordFrameTime(frameTimeMs)
}
```

---

## 6. 安全存储

### 6.1 SecureStorage

```kotlin
object SecureStorage {
    /** 安全存储数据（加密） */
    suspend fun save(key: String, value: String)
    
    /** 读取加密数据 */
    suspend fun get(key: String): String?
    
    /** 删除数据 */
    suspend fun delete(key: String)
    
    /** 检查是否存在 */
    suspend fun exists(key: String): Boolean
}
```

**使用示例**：
```kotlin
// 安全存储 API Key
SecureStorage.save("api_key", "secret_key_123")

// 读取
val apiKey = SecureStorage.get("api_key")
```

---

## 7. 国际化

### 7.1 LanguageManager

```kotlin
object LanguageManager {
    /** 当前语言 */
    val currentLanguage: StateFlow<Language>
    
    /** 设置语言 */
    fun setLanguage(language: Language)
    
    /** 获取支持的语音 TTS 语言 */
    fun getSupportedTtsLanguages(): List<Language>
}
```

### 7.2 字符串资源

```kotlin
object Strings {
    /** 获取本地化字符串 */
    fun get(key: StringKey): String
    
    /** 格式化字符串 */
    fun get(key: StringKey, vararg args: Any): String
}

// 使用示例
Strings.get(StringKey.OBSTACLE_AHEAD)  // "前方有障碍物"
Strings.get(StringKey.DISTANCE_METERS, 5)  // "5米"
```

---

## 8. 配置参数

### 8.1 AppConfig

```kotlin
object AppConfig {
    object AIDetection {
        const val MODEL_NAME = "yolov8n.tflite"
        const val INPUT_SIZE = 320
        const val NUM_THREADS = 8
        const val CONFIDENCE_THRESHOLD = 0.5f
        const val IOU_THRESHOLD = 0.5f
    }
    
    object Obstacle {
        const val ALERT_COOLDOWN_MS = 2000L
        const val SCENE_COOLDOWN_MS = 8000L
        const val MULTI_OBSTACLE_COOLDOWN_MS = 3000L
    }
    
    object Navigation {
        const val GPS_ACCURACY_EXCELLENT = 1f
        const val GPS_ACCURACY_GOOD = 3f
        const val GPS_ACCURACY_FAIR = 10f
    }
    
    object Voice {
        const val SPEECH_RATE = 0.9f
    }
    
    object Power {
        const val LOW_BATTERY_THRESHOLD = 15
        const val CRITICAL_BATTERY_THRESHOLD = 5
        const val LOW_POWER_FRAME_RATE = 15
        const val NORMAL_FRAME_RATE = 30
    }
}
```

---

## 9. 无障碍支持

### 9.1 AccessibilitySettings

```kotlin
object AccessibilitySettings {
    /** 字体缩放 */
    val fontSizeScale: StateFlow<FontSizeScale>
    
    /** 高对比度模式 */
    val highContrastEnabled: StateFlow<Boolean>
    
    /** 设置字体缩放 */
    fun setFontSizeScale(scale: FontSizeScale)
    
    /** 切换高对比度模式 */
    fun setHighContrastEnabled(enabled: Boolean)
    
    /** 获取缩放后的字体大小 */
    fun getScaledFontSize(baseSize: TextUnit): TextUnit
}
```

### 9.2 手势处理

```kotlin
class GestureHandler {
    /** 手势回调 */
    var onSingleTap: (() -> Unit)? = null
    var onDoubleTap: (() -> Unit)? = null
    var onLongPress: (() -> Unit)? = null
    var onSwipeUp: (() -> Unit)? = null
    var onSwipeDown: (() -> Unit)? = null
    var onSwipeLeft: (() -> Unit)? = null
    var onSwipeRight: (() -> Unit)? = null
    
    /** 获取 Modifier */
    fun Modifier.gestures(): Modifier
}
```

---

## 10. 事件分析

### 10.1 AnalyticsManager

```kotlin
object AnalyticsManager {
    /** 记录事件 */
    fun logEvent(name: String, params: Map<String, Any> = emptyMap())
    
    /** 记录屏幕访问 */
    fun logScreenView(screenName: String)
    
    /** 记录错误 */
    fun logError(error: Throwable)
    
    /** 设置用户属性 */
    fun setUserProperty(name: String, value: String)
}
```

**事件名称常量**：
```kotlin
object AnalyticsEvent {
    const val OBSTACLE_DETECTED = "obstacle_detected"
    const val NAVIGATION_STARTED = "navigation_started"
    const val SOS_TRIGGERED = "sos_triggered"
    const val VOICE_SPOKE = "voice_spoke"
    const val ERROR_OCCURRED = "error_occurred"
}
```
