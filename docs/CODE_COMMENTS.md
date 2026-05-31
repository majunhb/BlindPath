# 代码注释规范

> **要求**：开发过程中，需随编码同步完成注释编写。

## 目录

- [1. 类、接口、方法注释](#1-类接口方法注释)
- [2. 关键代码逻辑注释](#2-关键代码逻辑注释)
- [3. 特殊变量与临时代码注释](#3-特殊变量与临时代码注释)
- [4. 提交前自查](#4-提交前自查)
- [5. 注释示例](#5-注释示例)

---

## 1. 类、接口、方法注释

### 1.1 类注释

每个类必须添加 KDoc 注释，注明：
- 功能用途
- 作者（可选）
- 创建日期（可选）

```kotlin
/**
 * 障碍物检测服务
 * 
 * 功能：通过 CameraX 获取摄像头预览帧，使用 TFLite 模型进行障碍物检测，
 *       并通过 TextToSpeech 播报检测结果。
 * 
 * @author majunhb
 * @since 2024-01-01
 */
class ObstacleService : Service() {
    // ...
}
```

### 1.2 接口注释

公共接口必须添加 KDoc 注释，注明：
- 功能用途
- 接口约束（可选）

```kotlin
/**
 * 导航仓库接口
 * 
 * 功能：提供导航相关的数据操作，包括路径规划、位置更新、导航指令等。
 * 实现类：[NavigationRepositoryImpl]
 */
interface NavigationRepository {
    // ...
}
```

### 1.3 自定义方法注释

每个自定义方法必须添加 KDoc 注释，注明：
- 功能用途
- 入参（@param）
- 返回值（@return）
- 异常（@throws，如有）

```kotlin
/**
 * 开始障碍物检测
 * 
 * 功能：初始化 CameraX，绑定摄像头预览，启动 TFLite 模型进行实时检测。
 * 
 * @param cameraSelector 摄像头选择器（前置/后置）
 * @param rotation 屏幕旋转角度（Surface.ROTATION_0/90/180/270）
 * @return Boolean true=启动成功，false=启动失败
 * @throws SecurityException 缺少摄像头权限时抛出
 */
fun startDetection(cameraSelector: CameraSelector, rotation: Int): Boolean {
    // ...
}
```

---

## 2. 关键代码逻辑注释

### 2.1 业务逻辑注释

复杂的业务逻辑必须添加注释说明。

```kotlin
// 业务流程：检查权限 → 初始化摄像头 → 加载模型 → 开始检测
fun startObstacleDetection() {
    // 步骤1：检查摄像头权限
    if (!hasCameraPermission()) {
        requestCameraPermission()
        return
    }
    
    // 步骤2：初始化 CameraX
    // 说明：使用 ProcessCameraProvider 获取相机服务
    val cameraProvider = ProcessCameraProvider.getInstance(this).get()
    
    // 步骤3：加载 TFLite 模型
    // 说明：YOLOv8n 模型用于障碍物检测，输入尺寸 320x320
    interpreter = loadTFLiteModel("yolov8n.tflite")
    
    // 步骤4：绑定摄像头预览并启动检测
    bindCameraPreview(cameraProvider)
}
```

### 2.2 条件判断注释

复杂的条件判断必须添加注释说明判断意图。

```kotlin
// 判断是否需要播报：距离 < 3米 且 上次播报时间 > 3秒
// 目的：避免频繁播报干扰用户
if (distance < 3.0f && (System.currentTimeMillis() - lastSpeakTime) > 3000) {
    speak("前方 ${distance.toInt()} 米有障碍物")
    lastSpeakTime = System.currentTimeMillis()
}
```

### 2.3 异常捕获注释

每个 catch 块必须注释说明：
- 捕获什么异常
- 为什么捕获
- 如何处理

```kotlin
try {
    // 尝试加载 TFLite 模型
    interpreter = Interpreter(modelFile)
} catch (e: IOException) {
    // 捕获 IO 异常：模型文件不存在或损坏
    // 处理：记录错误日志，通知用户重新下载模型
    Timber.e(e, "Failed to load TFLite model")
    notifyUser("模型文件损坏，请重新下载")
} catch (e: IllegalArgumentException) {
    // 捕获参数异常：模型输入尺寸不匹配
    // 处理：记录错误日志，使用默认尺寸重新加载
    Timber.e(e, "Model input shape mismatch")
    interpreter = Interpreter(modelFile, options)
}
```

### 2.4 兼容处理注释

涉及版本兼容、设备兼容的代码必须注释说明兼容范围。

```kotlin
// 兼容处理：Android 12+ 需要精确闹钟权限
// 参见：https://developer.android.com/reference/android/provider/AlarmClock#EXTRA_ALARM_SEARCH_MODE
if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
    // Android 12+：检查 SCHEDULE_EXACT_ALARM 权限
    val alarmManager = getSystemService(ALARM_SERVICE) as AlarmManager
    if (!alarmManager.canScheduleExactAlarms()) {
        // 引导用户授予精确闹钟权限
        requestExactAlarmPermission()
    }
}
```

---

## 3. 特殊变量与临时代码注释

### 3.1 特殊变量注释

魔法数字、特殊含义的变量必须添加行内注释。

```kotlin
private const val DETECTION_THRESHOLD = 0.5f  // 检测置信度阈值（50%）
private const val SPEAK_INTERVAL = 3000L      // 播报间隔（毫秒）：避免频繁播报
private const val MODEL_INPUT_SIZE = 320       // TFLite 模型输入尺寸（像素）

// 特殊变量：记录上次播报时间，用于防重复播报
private var lastSpeakTime: Long = 0
```

### 3.2 临时代码注释

临时调试代码、待删除代码必须添加 `// TODO:` 或 `// TEMP:` 注释。

```kotlin
// TODO: 2026-05-31 majunhb 待实现：根据不同的障碍物类型使用不同的播报语音
// TEMP: 2026-05-31 majunhb 临时调试代码，上线前删除
// Timber.d("Detection result: $result")
```

### 3.3 优化改造点注释

待优化、待重构的代码必须添加 `// OPTIMIZE:` 注释。

```kotlin
// OPTIMIZE: 2026-05-31 majunhb 待优化：将同步检测改为异步，避免阻塞摄像头预览帧
// fun detectObstacle(bitmap: Bitmap): DetectionResult {
//     return runInBackground { interpreter.run(bitmap) }
// }
fun detectObstacle(bitmap: Bitmap): DetectionResult {
    return interpreter.run(bitmap)  // 当前为同步执行
}
```

---

## 4. 提交前自查

### 4.1 自查清单

提交代码前，开发者必须自查以下项目：

- [ ] 所有类、接口、自定义方法都已添加 KDoc 注释
- [ ] 所有方法注释都包含 @param、@return（如有）
- [ ] 所有复杂业务逻辑都已添加注释说明
- [ ] 所有条件判断都已注释判断意图
- [ ] 所有 catch 块都已注释异常类型和处理方式
- [ ] 所有兼容处理代码都已注释兼容范围
- [ ] 所有魔法数字都已定义为常量并添加注释
- [ ] 所有临时代码都已添加 `// TODO:` 或 `// TEMP:` 注释
- [ ] 所有待优化代码都已添加 `// OPTIMIZE:` 注释
- [ ] 注释与代码逻辑一致，无过时注释
- [ ] 注释语言统一（中文/英文，根据项目规范）

### 4.2 审查规则

**无注释、注释混乱的代码不予合并。**

Code Review 时，审查者必须检查：
1. 注释完整性（是否符合本规范）
2. 注释准确性（是否与代码逻辑一致）
3. 注释可读性（是否清晰易懂）

---

## 5. 注释示例

### 5.1 完整类示例

```kotlin
/**
 * 百度语音唤醒检测器
 * 
 * 功能：使用百度语音识别 SDK 实现离线唤醒词检测（"小度小度"）。
 * 
 * 实现原理：
 * 1. 使用 EventManagerFactory 创建唤醒词事件管理器
 * 2. 设置 EventListener 监听唤醒事件
 * 3. 唤醒成功后通过回调通知调用者
 * 
 * 注意事项：
 * - 必须使用进程内模式（useRemote=false），避免 AIDL 异步绑定导致的 NPE
 * - 必须添加 try-catch 保护，避免 SDK 内部异常导致 APP 崩溃
 * 
 * @author majunhb
 * @since 2024-01-01
 * @see EventManagerFactory
 */
class BaiduWakeWordDetector(
    private val context: Context,
    private val onWakeWordDetected: () -> Unit
) {
    // ...
}
```

### 5.2 完整方法示例

```kotlin
/**
 * 初始化唤醒词检测器
 * 
 * 功能：创建百度语音唤醒事件管理器，设置事件监听器。
 * 
 * 流程：
 * 1. 创建 EventManager（进程内模式）
 * 2. 设置 EventListener
 * 3. 发送 "wp.start" 指令开始检测
 * 
 * @param useRemote 是否使用远程服务（必须传 false，避免 AIDL 竞态条件）
 * @return Boolean true=初始化成功，false=初始化失败
 */
fun init(useRemote: Boolean = false): Boolean {
    return try {
        // 创建唤醒词事件管理器
        // 注意：useRemote=false 使用进程内模式，避免 AIDL 异步绑定导致的 NPE
        wp = EventManagerFactory.create(context, "wp", useRemote)
        
        // 设置事件监听器
        wp?.setEventListener(eventListener)
        
        // 发送开始指令
        wp?.send("wp.start", "{\"url\":\"...\"}", null, 0, 0)
        
        Timber.d("BaiduWakeWordDetector: Initialized successfully")
        true
    } catch (e: Exception) {
        // 捕获异常：SDK 初始化失败（可能是 so 库加载失败）
        Timber.e(e, "BaiduWakeWordDetector: Failed to initialize")
        false
    }
}
```

### 5.3 复杂业务逻辑示例

```kotlin
/**
 * 处理导航指令播报
 * 
 * 功能：根据当前导航指令，判断是否需要进行语音播报。
 * 
 * 播报规则：
 * 1. 直行/到达目的地：只播报一次
 * 2. 转向指令：3秒内不重复播报
 * 3. 偏航提醒：立即播报
 * 
 * @param instruction 导航指令（如 "左转"、"直行"）
 */
fun speakNavigationInstruction(instruction: String) {
    // 规则1：直行/到达目的地，只播报一次
    if (instruction.contains("直行") || instruction.contains("到达")) {
        if (!hasSpoken(instruction)) {
            tts.speak(instruction)
            markAsSpoken(instruction)
        }
        return
    }
    
    // 规则2：转向指令，3秒内不重复播报
    // 目的：避免频繁播报干扰用户
    if (instruction.contains("转") || instruction.contains("弯")) {
        val now = System.currentTimeMillis()
        if ((now - lastSpeakTime) < 3000) {
            Timber.d("Skip speaking: $instruction (interval < 3s)")
            return
        }
        tts.speak(instruction)
        lastSpeakTime = now
        return
    }
    
    // 规则3：偏航提醒，立即播报
    if (instruction.contains("偏航")) {
        tts.speak("已偏航，重新规划路线")
        return
    }
    
    // 其他指令：直接播报
    tts.speak(instruction)
}
```

---

## 6. 工具支持

### 6.1 Android Studio 配置

启用自动生成 KDoc 注释：
1. **File → Settings → Editor → Code Style → Kotlin**
2. 勾选 **"Generate documentation comments"**

使用快捷键生成注释：
- 在类/方法上方输入 `/**` 然后按 Enter，自动生成 KDoc 模板

### 6.2 Lint 检查

项目已配置 Lint 规则，检查以下注释问题：
- 公开方法缺少 KDoc 注释
- @param 标签与实际参数不匹配
- @return 标签与实际返回值不匹配

运行 Lint 检查：
```bash
./gradlew lintDebug
```

---

## 7. 参考资源

- [Kotlin 官方文档 - KDoc](https://kotlinlang.org/docs/kotlin-doc.html)
- [Android Kotlin 风格指南](https://developer.android.com/kotlin/style-guide)
- [Effective Kotlin - 文档与注释](https://kt.academy/book/effectivekotlin)

---

**最后更新**：2026-05-31  
**维护者**：majunhb
