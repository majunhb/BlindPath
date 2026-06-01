# BlindPath 架构评估报告

**评估人**: Archi（系统架构师）
**评估日期**: 2025-05-27
**项目版本**: 1.0
**项目路径**: `C:\Users\Administrator\WorkBuddy\BlindPath`

---

## 一、概述

BlindPath 是一个面向视障人士的 Android 辅助出行应用，采用 **多模块 Kotlin + Jetpack Compose + Hilt DI + MVVM** 架构。项目共 7 个 Gradle 模块，核心功能包括：AI 障碍物检测、GPS 导航、TTS 语音播报、社区互助及设置管理。

本报告从 **模块划分、依赖管理、Hilt DI 设计、Service 架构、接口抽象层、数据流设计** 六个维度进行评估，并识别已知问题与架构债。

---

## 二、模块依赖分析与架构视图

### 2.1 模块依赖关系图

```
                    ┌──────────────────┐
                    │      app         │ (入口 + DI组装 + UI编排)
                    └────────┬─────────┘
         ┌───────────────────┼───────────────────────┐
         │                   │                       │
         ▼                   ▼                       ▼
  ┌──────────────┐  ┌──────────────┐  ┌───────────────────────┐
  │module_obstacle│  │module_navig. │  │module_voice           │
  │  (CameraX    │  │(GPS/Loc.)    │  │  (TTS Abstraction)    │
  │   TFLite AI) │  │              │  │                       │
  └──────┬───────┘  └──────┬───────┘  └──────────┬────────────┘
         │                 │                      │
         └──────┬──────────┘                      │
                │                                 │
                ▼                                 ▼
         ┌───────────────────────────────────────────┐
         │              base (公共模块)               │
         │  Result.kt · UiModels.kt · SosHelper      │
         │  TtsManager · VibrationHelper              │
         └───────────────────────────────────────────┘

  module_community ──→ base, module_voice
  module_settings  ──→ base
```

### 2.2 评估结论：模块划分合理性

| 评估项 | 状态 | 说明 |
|--------|------|------|
| 职责边界清晰 | ✅ | 各模块功能领域划分合理 |
| 循环依赖 | ✅ **无** | 依赖关系为有向无环图 |
| 模块粒度 | ⚠️ 适中 | base 模块有膨胀趋势 |

**发现的问题**:
- **base 模块承载了 UI 模型**：`ObstacleAlert`、`NavigationInfo` 等 UI 数据类放在 base 中，导致 base 与 UI 层产生不应有的耦合
- **模块间依赖面过宽**：`module_community` 直接依赖 `module_voice`，社区功能不应依赖具体语音模块，应通过 base 层接口交互

---

## 三、依赖管理

### 3.1 严重问题：无统一版本目录（version catalog）

**问题描述**：项目未使用 Gradle Version Catalog（`libs.versions.toml`），所有依赖版本分散在 8 个 `build.gradle.kts` 文件中硬编码。

**重复版本实例**：

| 依赖 | 出现次数 | 版本值 |
|------|---------|--------|
| `hilt-android:2.48` | 7次 | 2.48 |
| `core-ktx:1.12.0` | 7次 | 1.12.0 |
| `timber:5.0.1` | 4次 | 5.0.1 |
| `kotlinx-coroutines-core:1.7.3` | 3次 | 1.7.3 |
| `kotlinx-coroutines-android:1.7.3` | 4次 | 1.7.3 |
| `junit:4.13.2` | 7次 | 4.13.2 |
| `mockk:1.13.8` | 7次 | 1.13.8 |
| `kotlinx-coroutines-test:1.7.3` | 7次 | 1.7.3 |
| `core-testing:2.2.0` | 7次 | 2.2.0 |
| `lifecycle-runtime-ktx:2.6.2` | 7次 | 2.6.2 |

**严重度**: 🔴 **高** — 版本升级时需逐个修改 7 个文件，极易引入版本不一致问题

**建议**：
1. 创建 `gradle/libs.versions.toml`
2. 将版本号统一管理
3. 使用 `libs.*` 引用替换硬编码字符串

---

## 四、Hilt DI 设计评估

### 4.1 AppModule 分析

**当前实现**: `app/src/main/java/com/blindpath/app/di/AppModule.kt`

```kotlin
@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {
    @Binds @Singleton
    abstract fun bindObstacleRepository(impl: ObstacleRepositoryImpl): ObstacleRepository
    
    @Binds @Singleton
    abstract fun bindNavigationRepository(impl: NavigationRepositoryImpl): NavigationRepository
    
    @Binds @Singleton
    abstract fun bindVoiceRepository(impl: VoiceRepositoryImpl): VoiceRepository
}
```

### 4.2 严重问题：绕过接口抽象层的直接注入

**问题位置**:
| 位置 | 注入内容 | 应注入 |
|------|---------|--------|
| `MainViewModel.kt:23-24` | `NavigationRepositoryImpl` | `NavigationRepository` |
| `ObstacleService.kt:27` | `ObstacleRepositoryImpl` | `ObstacleRepository` |
| `NavigationService.kt:32` | `NavigationRepositoryImpl` | `NavigationRepository` |
| `MainActivity.kt:32-35` | `VoiceRepository`, `NavigationRepository` | ✅ 正确 |

**严重度**: 🔴 **高** — 接口抽象的设计意图被打破，Impl 直接暴露给消费者

**影响**：
- 无法通过 DI 替换实现（如 Mock/Stub 测试）
- 违反"依赖倒置原则"
- 单元测试时只能针对 Impl 而非接口 mock

### 4.3 @Singleton 作用域分析

所有 Repository 都绑定为 `@Singleton`。这在当前阶段合理，但随着功能扩展，`ObstacleRepositoryImpl` 内部持有摄像头、TFLite 等重型资源，单例可避免重复创建。**建议保留**。

---

## 五、Service 架构评估

### 5.1 CoroutineScope 管理问题

**问题**: 两个前台 Service 均使用自建 CoroutineScope

```kotlin
// ObstacleService.kt:32
private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())

// NavigationService.kt:37
private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

| 问题点 | 严重度 | 说明 |
|--------|--------|------|
| 未使用 Lifecycle coroutineScope | 🟡 中 | Service 有 `lifecycleScope` 可用 |
| `serviceScope.cancel()` 在 `onDestroy()` 调用 | 🟢 低 | 基本正确，但 Job 未与生命周期绑定 |
| `Dispatchers.Default` 用于 TTS 操作 | 🟡 中 | TTS 是 UI 相关操作，应使用 Main |

**建议**: 使用 `lifecycleScope` 或在 Service 中注入 `@ApplicationScope` 的 CoroutineScope。

### 5.2 START_STICKY 风险

```kotlin
// 两个 Service 都返回 START_STICKY
return START_STICKY
```

**问题**: Service 被系统杀死后重启时，`onStartCommand` 会收到 `null intent`，`intent?.action` 为 null，两个分支都不会执行，Service 处于"空转"状态。

**严重度**: 🟡 **中**

**建议**: 在 `onStartCommand` 中处理 null intent 情况，尝试恢复上一次状态。

### 5.3 Notification Channel 创建位置

`ObstacleService.kt` 在 `startObstacle()` 方法中创建通知渠道，Android 8.0+ 最佳实践应在 `onCreate()` 中创建。

**严重度**: 🟢 **低**

### 5.4 CameraX 生命周期绑定风险

```kotlin
// ObstacleRepositoryImpl.kt:270-273
cameraProvider?.bindToLifecycle(
    androidx.lifecycle.ProcessLifecycleOwner.get(),  // ← 使用进程级生命周期
    cameraSelector,
    imageAnalysis
)
```

**严重度**: 🟡 **中**

**分析**: 使用 `ProcessLifecycleOwner` 意味着摄像头在应用进入后台后仍持续运行。对于视障辅助应用，这可能是设计意图（后台持续检测），但会显著增加电量消耗。建议提供配置选项。

---

## 六、接口抽象层评估

### 6.1 Repository 模式使用情况

| 模块 | 接口 | 实现 | 评估 |
|------|------|------|------|
| module_obstacle | `ObstacleRepository` ✅ | `ObstacleRepositoryImpl` | ✅ 良好 |
| module_navigation | `NavigationRepository` ✅ | `NavigationRepositoryImpl` | ✅ 良好 |
| module_voice | `VoiceRepository` ✅ | `VoiceRepositoryImpl` | ✅ 良好 |
| module_community | ❌ 无接口 | `CommunityRepository` | 🟡 缺少接口 |
| module_settings | ❌ 无接口 | `SettingsRepository` | 🟡 缺少接口 |

### 6.2 重复的 getAlertLevel 实现 → BUG

**ObstacleRepository 接口** (默认实现):
```kotlin
// domain/ObstacleRepository.kt:57
distance < 0.5f -> AlertLevel.DANGER
distance < 1.0f -> AlertLevel.WARNING
```

**ObstacleRepositoryImpl** (重写):
```kotlin
// data/ObstacleRepositoryImpl.kt:143
distance < 0.5f -> AlertLevel.DANGER
distance < 1.5f -> AlertLevel.WARNING  // ← 1.5 vs 1.0
```

**严重度**: 🔴 **高** — 两个实现阈值不一致，运行时使用 Impl 版本，接口文档与行为不符

### 6.3 同一个文件中定义多个顶层类型

`NavigationRepositoryImpl.kt` 在同一文件中定义了 `LatLonPoint` 和 `LocationUtils` 顶层类。

**严重度**: 🟢 **低** — 建议拆分为独立文件

---

## 七、数据流设计评估

### 7.1 StateFlow 使用模式

所有模块统一使用 `MutableStateFlow` + 对外暴露 `StateFlow` + `asStateFlow()`，**模式正确**。

```kotlin
private val _state = MutableStateFlow(ObstacleState())
override val obstacleState: StateFlow<ObstacleState> = _state.asStateFlow()
```

### 7.2 Service 中使用 collectLatest 的风险

```kotlin
// ObstacleService.kt:83
obstacleRepository.obstacleState.collectLatest { state ->
    // 处理状态...
}

// NavigationService.kt:98
navigationRepository.navigationState.collectLatest { state ->
    // 处理状态...
}
```

**分析**: `collectLatest` 会在新值到达时取消当前协程中的处理。对于状态更新密集型场景（如实时障碍物检测帧），可能导致处理被频繁取消。建议根据实际需求评估是否改用 `collect`。

### 7.3 UI 层与数据层的耦合

**base 模块包含 UI 模型**:

| 类名 | 位置 | 用途 |
|------|------|------|
| `ObstacleAlert` | `base/common/UiModels.kt` | UI 展示用 |
| `NavigationInfo` | `base/common/UiModels.kt` | UI 展示用 |
| `AlertLevel` | `base/common/UiModels.kt` | UI 展示用 |

这些模型在 `module_obstacle` 和 `module_navigation` 的 data/domain 层中被直接引用，导致 **数据层依赖 UI 模型**。

**严重度**: 🟡 **中**

---

## 八、其他架构问题

### 8.1 双 TTS 实现并存

| 文件 | 路径 | 状态 |
|------|------|------|
| `TtsManager.kt` | `base/tts/` | 🟡 遗留代码，未在模块中实际使用 |
| `VoiceRepositoryImpl.kt` | `module_voice/data/` | ✅ 活跃使用 |

`TtsManager` 可能是早期版本的 TTS 封装，当前各模块通过 `module_voice` 的 `VoiceRepository` 接口使用 TTS。`TtsManager` 应标记为 `@Deprecated` 或移除。

**严重度**: 🟢 **低**

### 8.2 MainActivity 的 CoroutineScope 泄漏

```kotlin
// MainActivity.kt:47
CoroutineScope(Dispatchers.Main).launch {
    voiceRepository.speak("需要相关权限才能使用此功能，请在设置中授权", queueMode = false)
}
```

**严重度**: 🟡 **中** — 应使用 `lifecycleScope`，自建 Scope 不会在 Activity 销毁时自动取消

### 8.3 SceneClassifier 缺少 OpenCV

`SceneClassifier` 使用纯 Kotlin 像素级操作进行场景识别（斑马线检测、人行道识别等），性能开销大且准确率有限。

**严重度**: 🟡 **中** — 建议集成 OpenCV 或 ML Kit 场景识别 API

### 8.4 社区模块缺少后端

`CommunityRepository` 和 `CommunityViewModel` 中的志愿者数据为硬编码 Mock，请求存储在内存中。生产环境需要后端 API 支持。

**严重度**: 🟢 **说明** — 当前阶段可接受，但应规划后端对接

---

## 九、架构债汇总

| # | 问题描述 | 严重度 | 类型 | 影响范围 |
|---|---------|--------|------|---------|
| 1 | **无版本目录（Version Catalog）** | 🔴 高 | 依赖管理债 | 所有模块 |
| 2 | **直接注入 Impl 而非接口** | 🔴 高 | 设计债 | MainViewModel, ObstacleService, NavigationService |
| 3 | **getAlertLevel 接口/实现阈值不一致** | 🔴 高 | 业务逻辑Bug | 障碍物预警 |
| 4 | **Service 自建 CoroutineScope** | 🟡 中 | 生命周期债 | ObstacleService, NavigationService |
| 5 | **START_STICKY 空intent未处理** | 🟡 中 | 可靠性债 | 两个前台Service |
| 6 | **CameraX 绑定 ProcessLifecycleOwner** | 🟡 中 | 资源管理债 | 电池消耗 |
| 7 | **社区/设置模块缺少 Repository 接口** | 🟡 中 | 设计债 | module_community, module_settings |
| 8 | **base 模块含 UI 模型** | 🟡 中 | 职责混淆 | 模块间耦合 |
| 9 | **MainActivity 自建 CoroutineScope** | 🟡 中 | 生命周期债 | 内存泄漏风险 |
| 10 | **SceneClassifier 缺少 OpenCV** | 🟡 中 | 性能债 | 实时视频处理 |
| 11 | **双 TTS 实现并存（TtsManager 遗留）** | 🟢 低 | 技术债 | base 模块 |
| 12 | **NavRepositoryImpl 多顶层类** | 🟢 低 | 代码组织 | module_navigation |

---

## 十、改进建议优先级

### 🔴 立即修复（影响正确性或开发效率）

1. **引入 Version Catalog**：创建 `gradle/libs.versions.toml`，统一版本管理
2. **修复 Impl 直接注入**：在 Service 和 ViewModel 中改为注入接口
3. **统一 getAlertLevel 阈值**：删除接口的默认实现或以 Impl 为准进行对齐

### 🟡 短期改进（1-2 个迭代）

4. **Service 使用 lifecycleScope**：改用 Android Lifecycle 的协程作用域
5. **处理 START_STICKY null intent**：记录上次状态并在重启时恢复
6. **社区/设置模块添加 Repository 接口**
7. **拆分 base 模块的 UI 模型到独立模块**
8. **MainActivity 改用 lifecycleScope**

### 🟢 中长期规划

9. **评估 SceneClassifier 的 OpenCV 升级**
10. **清理 TtsManager 遗留代码**
11. **拆分散落的多顶层类文件**
12. **社区模块对接后端 API**

---

## 十一、ADR 建议

### ADR-001: 统一版本管理

**决策**: 引入 Gradle Version Catalog (`libs.versions.toml`)

**理由**:
- 7 个模块中的重复版本达 10+ 个
- 单点修改所有模块版本
- 符合 Android 官方推荐的最佳实践

### ADR-002: 修复接口抽象层

**决策**: 所有依赖注入点使用接口而非实现类

**理由**:
- 恢复依赖倒置原则
- 支持 Mock 测试
- 允许未来切换实现（如 Mock → 真实 → 优化版）

---

*本文档由 Archi（系统架构师）基于 BlindPath v1.0 代码库评估生成。*
