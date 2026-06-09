# BlindPath APP 测试问题诊断报告

## 一、问题概述

| 问题 | 现象 | 严重程度 |
|------|------|----------|
| 定位播报 | 播报 GPS 精度（"GPS 精度 2.5 米"），而非街道名称 | 高 |
| 障碍物检测 | 撞墙无报警，无语音、无震动 | 高 |
| 反馈缺失 | 检测到时没有语音播报和震动反馈 | 高 |

---

## 二、问题 1：定位播报 — GPS 精度 vs 街道名称

### 2.1 根因分析

**当前播报内容：**
- `"GPS 精度 2.5 米，信号优秀，可安全导航"` — GPS 质量播报
- `"直行 100 米"`、`"前方左转"` — 导航转向指令

**缺失内容：**
- **完全不播报当前所在街道/道路名称**
- 视障人员不知道"我在哪条路"，只知道"GPS 信号好不好"

**技术原因：**

1. **高德定位已返回地址信息，但未被使用**
   - `NavigationRepositoryImpl.kt` 第 398 行已配置 `isNeedAddress = true`
   - AMap 返回的 `address`、`road`、`street`、`poiName` 字段已有值
   - 但这些字段存入 `LocationInfo` 后，**NavigationService 从未读取用于播报**

2. **NavigationService 设计为导航导向，非位置描述**
   - 只关注"怎么走"（转向指令）和"信号好不好"（GPS 质量）
   - 没有"我在哪"的位置播报逻辑

3. **LocationScreen 有完整实现但未复用**
   - `LocationScreen.kt` 已实现街道名称播报（`road > street > poiName > aoiName` 优先级）
   - 但这是一个独立 UI 界面，其逻辑未集成到 NavigationService

### 2.2 关键代码位置

| 文件 | 功能 | 行号 |
|------|------|------|
| `NavigationRepositoryImpl.kt` | AMap 定位，获取地址但未播报 | 372-425（定位配置）、465-474（地址提取） |
| `NavigationService.kt` | 导航服务，核心播报逻辑 | 122-157（状态收集）、190-213（GPS 质量播报） |
| `LocationScreen.kt` | 定位界面，有街道名称播报参考实现 | 247-261、294-309（文本构建） |

### 2.3 修复方案

**修改 1：在 NavigationService 中增加位置地址播报**

```kotlin
// NavigationService.kt，在 startNavigation() 的 collectLatest 块中

// 播报当前位置街道名称（新增，带 30 秒节流）
state.currentLocation?.let { location ->
    if (location.address.isNotBlank() || location.poiName.isNotBlank()) {
        announceLocationAddress(location)
    }
}

private var lastLocationAddress: String? = null
private var lastAddressAnnounceTime = 0L
private val ADDRESS_ANNOUNCE_INTERVAL_MS = 30000L

private fun announceLocationAddress(location: LocationInfo) {
    val now = System.currentTimeMillis()
    if (now - lastAddressAnnounceTime < ADDRESS_ANNOUNCE_INTERVAL_MS) return

    val addressText = buildString {
        append("当前位置：")
        when {
            location.address.isNotBlank() -> append(location.address)
            location.poiName.isNotBlank() -> append(location.poiName)
            else -> append("未知位置")
        }
    }

    if (addressText != lastLocationAddress) {
        lastLocationAddress = addressText
        lastAddressAnnounceTime = now
        voiceRepository.speak(addressText, queueMode = true)
    }
}
```

**修改 2：扩展 LocationInfo 增加 road/street 字段（可选，更精确）**

```kotlin
// NavigationModels.kt
data class LocationInfo(
    val latitude: Double,
    val longitude: Double,
    val accuracy: Float,
    val speed: Float,
    val bearing: Float,
    val timestamp: Long,
    val address: String = "",
    val poiName: String = "",
    val road: String = "",      // 新增：道路名称
    val street: String = ""     // 新增：街道名称
)
```

在 `NavigationRepositoryImpl.onLocationReceived()` 中填充：
```kotlin
road = aMapLocation.road ?: "",
street = aMapLocation.street ?: ""
```

---

## 三、问题 2：障碍物检测 — 撞墙无报警

### 3.1 根因分析

**核心结论：AI 模型和辅助检测均不支持"墙壁"类别识别**

#### 原因 1：COCO 模型无墙壁类别（最根本原因）

- `ObstacleClassifier.kt` 第 24-48 行的 COCO 80 类映射中，**完全没有 `WALL` 类别**
- 包含的类别：人、车、家具、动物等可移动物体
- **不包含**：墙壁、门、玻璃幕墙等建筑结构

当用户面对墙壁时：
```
AI 模型检测不到任何匹配物体
  → postProcess() 返回空列表
  → calculateAlertLevel() 返回 AlertLevel.SAFE
  → 播报："前方道路畅通，未检测到障碍物"
```

#### 原因 2：辅助检测也无法检测墙壁

`AssistedDetector.kt` 三种辅助方法：
- `detectMotion()`：帧间差分，检测**运动物体**（墙壁静止，无法检测）
- `detectEdges()`：检测水平边缘（台阶/道牙），只扫描底部 1/3 区域
- `detectWithMLKit()`：ML Kit 对象检测，同样**不支持墙壁类别**

#### 原因 3：远距离置信度阈值过高

```kotlin
// ObstacleClassifier.kt 第 101-103 行
const val CONF_DANGER = 0.28f    // < 0.5m
const val CONF_WARNING = 0.45f   // 0.5-2m
const val CONF_IGNORE = 0.70f    // > 2m
```

墙壁距离 > 2 米时，需要置信度 > 0.70。对于 COCO 未训练的墙壁，模型输出置信度通常很低，容易被过滤。

#### 原因 4：跳帧可能漏检突发危险

```kotlin
// ObstacleRepositoryImpl.kt 第 118 行
private var frameSkipRatio = AppConfig.AIDetection.FRAME_SKIP  // 默认 = 3
```

每 3 帧处理 1 帧。用户快速靠近墙壁时，可能在处理间隔内就已经撞上。

### 3.2 关键代码位置

| 文件 | 功能 | 行号 |
|------|------|------|
| `ObstacleClassifier.kt` | COCO 类别映射、置信度阈值 | 24-48（类别映射）、101-103（阈值） |
| `AIDetector.kt` | AI 模型检测后处理 | 232-302（postProcess） |
| `AssistedDetector.kt` | 辅助检测（运动/边缘/ML Kit） | 全部 |
| `ObstacleRepositoryImpl.kt` | 检测管线、跳帧处理 | 118-119（跳帧）、448-518（处理流程） |
| `ObstacleService.kt` | 预警触发（语音+震动） | 169-207（handleAlert） |

### 3.3 修复方案

**修复 1：添加墙壁/建筑结构检测能力（最重要）**

```kotlin
// ObstacleClassifier.kt
enum class ObstacleType(val displayName: String, val priority: Int, val dangerLevel: Int) {
    WALL("墙壁", 3, 1),           // 新增
    GLASS_WALL("玻璃墙", 3, 1),   // 新增
    DOOR("门", 2, 3),             // 新增
    // ... 现有类别
}
```

**修复 2：在 AssistedDetector 中添加墙壁检测**

```kotlin
// AssistedDetector.kt
fun detectWalls(bitmap: Bitmap): List<DetectedObstacle> {
    val results = mutableListOf<DetectedObstacle>()
    val width = bitmap.width
    val height = bitmap.height
    val centerX = width / 2
    val centerY = height / 2
    var verticalEdgeCount = 0

    // 检测中央区域的垂直边缘密度
    for (x in (centerX - width/4)..(centerX + width/4) step 4) {
        var prevBrightness = -1
        var colEdges = 0
        for (y in (centerY - height/3)..(centerY + height/3) step 4) {
            val pixel = bitmap.getPixel(x.coerceIn(0, width-1), y.coerceIn(0, height-1))
            val brightness = (Color.red(pixel) + Color.green(pixel) + Color.blue(pixel)) / 3
            if (prevBrightness >= 0 && kotlin.math.abs(brightness - prevBrightness) > 25) {
                colEdges++
            }
            prevBrightness = brightness
        }
        if (colEdges >= 3) verticalEdgeCount++
    }

    // 中央区域有大量垂直边缘 → 可能是墙壁
    if (verticalEdgeCount > width / 16) {
        val coverageRatio = verticalEdgeCount.toFloat() / (width / 2)
        val distance = when {
            coverageRatio > 0.8f -> 0.3f
            coverageRatio > 0.5f -> 1.0f
            else -> 2.5f
        }
        results.add(DetectedObstacle(
            type = ObstacleType.WALL,
            confidence = minOf(0.6f, 0.3f + coverageRatio * 0.3f),
            distance = distance,
            direction = Direction.CENTER,
            boundingBox = BoundingBox(0.2f, 0.1f, 0.8f, 0.9f)
        ))
    }
    return results
}
```

**修复 3：为墙壁类降低置信度阈值**

```kotlin
// AIDetector.kt postProcess 中
val confThreshold = when {
    obstacleType == ObstacleType.WALL -> 0.20f  // 墙壁专用低阈值
    distance < DANGER_DISTANCE -> CONF_DANGER
    distance < WARNING_DISTANCE -> CONF_WARNING
    else -> CONF_IGNORE
}
```

**修复 4：危险时减少跳帧**

```kotlin
// ObstacleRepositoryImpl.kt processDetections 中
val hasDanger = filteredObstacles.any { it.distance < config.dangerDistance }
if (hasDanger && frameSkipRatio > 1) {
    frameSkipRatio = 1  // 危险时逐帧处理
}
```

**修复 5：训练自定义模型（长期方案）**

使用 `training/train_yolo_seg.py` 训练包含墙壁、门、玻璃墙类别的自定义模型。

---

## 四、问题 3：语音/震动反馈缺失

### 4.1 根因分析

**语音和震动反馈的触发链路：**

```
ObstacleRepositoryImpl.processImage()
  → 检测到障碍物 → 更新 _obstacleState
    → ObstacleService 监听 obstacleState
      → handleAlert() 触发语音 + 震动
```

**问题：由于障碍物检测返回空列表（原因见问题 2），_obstacleState 中没有 alert，handleAlert() 从未被调用。**

即：**不是语音/震动功能坏了，而是检测层根本没检测到东西，所以没触发反馈。**

验证：语音和震动代码本身是正常的：
- `VoiceRepositoryImpl.kt` 第 349-376 行：TTS 播报实现正常
- `ObstacleService.kt` 第 169-207 行：handleAlert() 中同时调用语音和震动

### 4.2 修复方案

修复障碍物检测（问题 2）后，语音和震动反馈会自动恢复。

如需增强反馈，可修改：

```kotlin
// ObstacleService.kt handleAlert() 中增强播报
private fun handleAlert(alert: ObstacleAlert) {
    // ... 现有冷却检查 ...
    
    // 增强：根据危险级别调整播报 urgency
    val voiceType = when (alert.level) {
        AlertLevel.DANGER -> VoiceType.ALERT_URGENT
        AlertLevel.WARNING -> VoiceType.ALERT
        else -> VoiceType.NAVIGATION
    }
    
    val request = VoiceRequest(
        text = alert.description,
        type = voiceType,
        interruptCurrent = alert.level == AlertLevel.DANGER  // 危险时打断当前播报
    )
    voiceRepository.announce(request)
    
    // 震动：危险时增强震动模式
    if (alert.level != AlertLevel.SAFE) {
        when (alert.level) {
            AlertLevel.DANGER -> VibrationHelper.vibrateSOS(context)  // SOS 震动模式
            AlertLevel.WARNING -> VibrationHelper.vibrate(context, AlertLevel.WARNING)
            else -> VibrationHelper.vibrate(context, AlertLevel.SAFE)
        }
    }
}
```

---

## 五、修复优先级

| 优先级 | 问题 | 修复内容 | 预计工作量 |
|--------|------|----------|-----------|
| **P0** | 撞墙不报警 | 添加墙壁检测（AssistedDetector + 阈值调整） | 1-2 天 |
| **P0** | 无语音/震动 | 随障碍物检测修复自动解决 | - |
| **P1** | 定位播报街道名 | NavigationService 增加地址播报逻辑 | 0.5 天 |
| **P1** | 跳帧漏检 | 危险时动态减少跳帧 | 0.5 天 |
| **P2** | 模型训练 | 训练含墙壁类别的自定义 YOLO 模型 | 1-2 周 |

---

## 六、测试验证建议

修复后应按以下场景验证：

| 场景 | 预期结果 |
|------|----------|
| 面对墙壁站立 | 语音播报"前方检测到墙壁，距离约 X 米" + 震动 |
| 走向墙壁 | 距离减少时播报更新 + 震动频率增加 |
| 正常行走 | 播报当前所在街道名称（如"当前位置：中山路"） |
| GPS 信号弱 | 播报"GPS 信号弱" + 继续播报最后已知位置 |
| 开阔地带 | 播报"前方道路畅通" |
