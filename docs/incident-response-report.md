# BlindPath 项目事故响应报告

**报告人**：雷克斯（Rex）· SRE 工程师
**日期**：$(date +%Y-%m-%d)
**状态**：完整事故响应流程

---

## 目录

1. [事故一：KSP 代码生成冲突](#事故一ksp-代码生成冲突)
2. [事故二：仓库批量依赖版本缺失（Gradle Version Catalog）](#事故二仓库批量依赖版本缺失gradle-version-catalog)
3. [项目总体健康度评估](#项目总体健康度评估)

---

# 事故一：KSP 代码生成冲突

## 阶段 1: 分诊

### SEV 评级：**SEV2 — 主要功能降级**

| 维度 | 评估 |
|------|------|
| 严重性 | SEV2 |
| 评级依据 | 影响 Hilt 依赖注入生成（@AndroidEntryPoint），导致 ObstacleService 及其他所有 @AndroidEntryPoint 组件可能在构建时失败；不影响已构建的运行时（如果跳过增量构建可工作），但任何干净构建或新增 DI 组件都会阻塞 |
| 影响范围 | **7/7 模块**（全部模块都应用了 Hilt + KSP 插件组合） |
| 受影响用户 | **所有开发者** — 任何需要构建或修改 DI 组件的开发任务都会受阻 |
| 业务影响 | 障碍物检测（ObstacleService）、导航服务、社区、设置等所有需要 Hilt 注入的功能 |

### 受影响组件清单

| 模块 | 插件组合 | KSP 处理器 |
|------|----------|------------|
| :app | Hilt + KSP | `hilt-android-compiler:2.48` |
| :base | Hilt + KSP | `hilt-android-compiler:2.48` |
| :module_obstacle | Hilt + KSP | `hilt-android-compiler:2.48` |
| :module_navigation | Hilt + KSP | `hilt-android-compiler:2.48` |
| :module_voice | Hilt + KSP | `hilt-android-compiler:2.48` |
| :module_community | Hilt + KSP | `hilt-android-compiler:2.48` |
| :module_settings | Hilt + KSP | `hilt-android-compiler:2.48` |

---

## 阶段 2: 根因分析（5 Why）

```
Why 1: 构建失败 — KSP 处理器 hilt-android-compiler 在代码生成阶段报错，生成的 Hilt 组件类缺失或不兼容

  ↓

Why 2: 所有 7 个模块同时应用了 ksp 和 hilt-android 插件，且各自声明了 ksp("hilt-android-compiler:2.48")，
       导致 KSP 在处理注解时出现重复注册或多处理器冲突

  ↓

Why 3: KSP 1.9.20-1.0.14 在处理多个模块中的 Hilt 注解时，每个模块的 ksp 配置独立运行，
       但 Hilt 的 @HiltAndroidApp/@AndroidEntryPoint 注解处理器期望在单一符号处理环境中运行
       ——跨模块的 Hilt 组件依赖关系导致 KSP 符号解析冲突

  ↓

Why 4: 项目使用了 KSP（而非传统的 kapt）来处理 Hilt 注解，但 KSP 对于 Hilt 的支持在 2.48 版本中
       仍存在已知限制——Hilt KSP 处理器在设计时更适用于单体应用而非多模块项目，
       且当多个模块各自声明 ksp 处理器时，代码生成产物可能出现命名冲突

  ↓

Why 5（根因）: 

  根因 A：项目架构决策 —— 选择了 KSP 路线但未遵循 Hilt 官方推荐的 KSP 配置模式
           （Hilt 官方推荐：仅在 app module 使用 ksp hilt compiler，library 模块用 kapt 或
           在 library 模块中避免声明 ksp hilt compiler）

  根因 B：版本兼容性 —— KSP 1.9.20-1.0.14 与 Hilt 2.48 的搭配在社区中被报告存在
           多模块构建的兼容性问题（Hilt 2.50+ 才完全稳定支持 KSP 多模块场景）

  根因 C：配置冗余 —— 每个模块重复声明 `ksp("hilt-android-compiler")`，
           正确的做法是仅在 app module 或单一入口模块声明一次
```

---

## 阶段 3: 时间线（已知信息重建）

| 时间 | 事件 |
|------|------|
| 项目初始化 | 创建 root build.gradle.kts，声明 KSP 1.9.20-1.0.14 插件 |
| 模块创建 | 7 个模块逐一创建，每个模块同时添加 `hilt` 和 `ksp` 插件 |
| 依赖声明 | 每个模块的 dependencies 块中重复声明 `ksp("hilt-android-compiler:2.48")` |
| 首次构建 | 构建系统报告 KSP 处理器冲突警告或错误 |
| @AndroidEntryPoint 添加 | ObstacleService.kt 添加 `@AndroidEntryPoint` 后，Hilt 代码生成失败 |
| 事故发现 | 构建时出现 KSP 代码生成错误：生成的 Hilt 组件类（如 `ObstacleService_HiltComponents`）缺失或编译错误 |

---

## 阶段 4: 行动项（修复步骤）

### 🔴 立即修复（优先级 P0）

| # | 行动项 | 所属模块 | 难度 | 预期效果 |
|---|--------|----------|------|----------|
| 1 | 将 `ksp("hilt-android-compiler:2.48")` 从所有 **library 模块** 的 dependencies 中移除，**仅在 :app 模块保留** | 所有 library 模块 | 低 | 消除 KSP 多模块重复注册冲突 |
| 2 | 在 :app 模块添加依赖：`ksp("com.google.dagger:hilt-android-compiler:2.48")`（如已存在则保留） | :app | 低 | 确保 app 模块生成完整的 Hilt 组件树 |

### 🟡 中期修复（优先级 P1）

| # | 行动项 | 难度 | 预期效果 |
|---|--------|------|----------|
| 3 | 升级 Hilt 到 **2.50+**（推荐 2.51），KSP 同步升级到对应版本 | 中 | 获得 Hilt KSP 的多模块完整支持 |
| 4 | 升级 Kotlin 到 **1.9.22+**，KSP 升级到 **1.9.22-1.0.17+** | 中 | 解决已知的 KSP 兼容性 Bug |
| 5 | 验证构建：在所有模块上执行 `./gradlew clean assembleDebug` | - | 确认修复生效 |

### 🟢 长期优化（优先级 P2）

| # | 行动项 | 难度 | 预期效果 |
|---|--------|------|----------|
| 6 | 考虑迁移到 kapt 作为备选方案（如 KSP 仍不稳定） | 低 | 更成熟的 Hilt 注解处理路径 |
| 7 | 编写 CI 构建检查，防止未来再次出现 KSP 处理器配置错误 | 中 | 预防复发 |

---

## 阶段 5: 预防措施

| # | 预防措施 | 责任人 | 验收标准 |
|---|---------|--------|----------|
| 1 | 在 CI Pipeline 中添加 `assembleDebug` 作为预合并检查 | SRE | 每次 PR 自动构建 |
| 2 | 创建 `docs/build-guide.md`，记录 KSP/Hilt 配置注意事项 | Tech Writer | 文档审核通过 |
| 3 | 引入 Gradle Version Catalog（见事故二）统一管理版本，避免版本号分散 | SRE + Developer | libs.versions.toml 就绪 |
| 4 | 建立 KSP 处理器依赖审计清单，记录每个模块的 KSP 处理器用途 | Architect | 清单审核通过 |

---

## 阶段 6: 修复参考代码

### 修改后 :app/build.gradle.kts（仅 app 模块保留 ksp hilt-compiler）

```kotlin
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")     // ← 保留
}

dependencies {
    // Hilt — KSP 处理器仅在 app 模块声明
    implementation("com.google.dagger:hilt-android:2.48")
    ksp("com.google.dagger:hilt-android-compiler:2.48")     // ← 保留（仅此处）
    implementation("androidx.hilt:hilt-navigation-compose:1.1.0")
    // ... 其他依赖
}
```

### 修改后 library 模块（如 :module_obstacle/build.gradle.kts）

```kotlin
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("com.google.dagger.hilt.android")
    // id("com.google.devtools.ksp")   // ← 如果该模块不需要其他 KSP 处理器，移除 ksp 插件
}

dependencies {
    // Hilt — 仅保留 implementation，移除 ksp 处理器声明
    implementation("com.google.dagger:hilt-android:2.48")
    // ksp("com.google.dagger:hilt-android-compiler:2.48")  // ← 移除
    // ... 其他依赖
}
```

---

# 事故二：仓库批量依赖版本缺失（Gradle Version Catalog）

## 阶段 1: 分诊

### SEV 评级：**SEV3 — 次要功能问题/架构债**

| 维度 | 评估 |
|------|------|
| 严重性 | SEV3 |
| 评级依据 | 不影响当前构建功能，但存在版本不一致的风险；随模块增多，版本漂移将导致更难排查的运行时错误 |
| 影响范围 | **8 个 build.gradle.kts 文件**，横跨根项目和 7 个模块 |
| 风险等级 | **高** — 已发现多处重复声明但版本号一致的依赖，未来一旦某处更新而其他处遗漏，将产生难以调试的不兼容问题 |
| 长期影响 | 新开发者上手成本高、版本升级需逐个文件修改、容易引入二进制兼容性问题 |

---

## 阶段 2: 影响范围分析

### 发现的重复硬编码版本（已确认的版本漂移风险点）

| 依赖名称 | 出现次数 | 当前统一版本 | 风险等级 |
|----------|---------|-------------|----------|
| `com.google.dagger:hilt-android` | 7 | 2.48 | 🔴 高 — 7 处声明 |
| `hilt-android-compiler` | 7 | 2.48 | 🔴 高 — 7 处声明 |
| `androidx.core:core-ktx` | 7 | 1.12.0 | 🔴 高 — 7 处声明 |
| `lifecycle-runtime-ktx` | 6 | 2.6.2 | 🟡 中 — 6 处声明 |
| `junit:junit` | 7 | 4.13.2 | 🟢 低 |
| `io.mockk:mockk` | 7 | 1.13.8 | 🟢 低 |
| `kotlinx-coroutines-test` | 6 | 1.7.3 | 🟡 中 |
| `arch.core:core-testing` | 5 | 2.2.0 | 🟢 低 |
| `timber:timber` | 3 | 5.0.1 | 🟢 低 |
| `kotlinx-coroutines-android` | 3 | 1.7.3 | 🟢 低 |
| `kotlinx-coroutines-core` | 2 | 1.7.3 | 🟢 低 |
| `compose-bom` | 3 | 2023.10.01 | 🟡 中 — 3 处声明 |
| Android compileSdk | 7 | 34 | 🟢 低（常与 AGP 版本绑定） |
| Android minSdk | 7 | 26 | 🟢 低 |

### 当前架构评估

```
现状：
  根 build.gradle.kts       → 插件版本硬编码
  app/build.gradle.kts      → 依赖版本硬编码 + 插件引用
  base/build.gradle.kts     → 依赖版本硬编码 + 插件引用
  module_*/build.gradle.kts → 依赖版本硬编码 + 插件引用（×5）
  ---------------------------------------------------------------
  无 libs.versions.toml     → 版本管理缺失
  无版本目录                → 版本分散在 8 个文件中
```

---

## 阶段 3: 根因分析（5 Why）

```
Why 1: 依赖版本号直接在每个模块的 build.gradle.kts 中写死，
       没有统一管理

  ↓

Why 2: 项目初始化时未采用 Gradle Version Catalog（libs.versions.toml）
       作为标准实践，团队按 Android 早期方式在模块中硬编码版本

  ↓

Why 3: 项目基于较旧的模板生成或团队成员不熟悉 Version Catalog 的最佳实践；
       缺少项目级别的构建配置规范

  ↓

Why 4: 没有在项目初期建立 Gradle 构建规范的审查机制，
       且 Version Catalog 是 Gradle 7.0+ 引入、7.4+ 推荐的功能，
       而项目 Gradle 版本为 8.2，完全支持但未利用

  ↓

Why 5（根因）:
   
   根因：项目构建配置缺少标准化治理。
         - 未设置 Gradle Version Catalog（libs.versions.toml）
         - 未在 CI 中加入构建配置审查
         - 团队缺少版本管理规范的文档约束
         - 项目早期的技术选型未考虑版本一致性治理
```

---

## 阶段 4: 时间线（已知信息重建）

| 时间 | 事件 |
|------|------|
| 项目初始化 | 创建 root build.gradle.kts，声明各插件版本 |
| 模块创建 | 每创建一个新模块，开发者在 build.gradle.kts 中手动写入依赖版本号 |
| 模块递增 | 从 1 个模块增长到 7 个模块，版本声明散布到 8 个文件 |
| 版本管理缺失 | 至今未创建 `gradle/libs.versions.toml` |
| 事故评估 | 硬编码版本累计 **50+ 处**，依赖版本管理模式不可持续 |

---

## 阶段 5: 行动项（修复步骤）

### 🔴 立即修复（优先级 P0）

| # | 行动项 | 文件/位置 | 难度 | 预期效果 |
|---|--------|----------|------|----------|
| 1 | 创建 `gradle/libs.versions.toml`，定义所有版本目录 | 新文件 | 中 | 建立版本统一管理入口 |

### 🟡 中期修复（优先级 P1）

| # | 行动项 | 难度 | 预期效果 |
|---|--------|------|----------|
| 2 | 修改 **根 build.gradle.kts**，使用 version catalog 替换插件版本硬编码 | 低 | 统一插件版本管理 |
| 3 | 修改 **所有模块 build.gradle.kts**，使用 `libs.` 引用替换 dependencies 中的硬编码版本 | 中 | 消除版本分散风险 |
| 4 | 构建验证：执行 `./gradlew build` 确认依赖解析正常 | 低 | 确认迁移正确 |

### 🟢 长期优化（优先级 P2）

| # | 行动项 | 难度 | 预期效果 |
|---|--------|------|----------|
| 5 | 配置 Gradle 构建扫描（Build Scan）自动检测版本不一致 | 中 | 主动告警 |
| 6 | 在 CONTRIBUTING.md 中增加构建配置规范章节 | 低 | 知识沉淀 |
| 7 | 考虑引入 Renovate/Dependabot 自动升级依赖版本 | 中 | 自动化版本管理 |

---

## 阶段 6: 参考实现 — libs.versions.toml

### 新建 `gradle/libs.versions.toml`

```toml
[versions]
# Android
agp = "8.2.0"
compileSdk = "34"
minSdk = "26"
targetSdk = "34"

# Kotlin
kotlin = "1.9.20"
kotlin-compiler-extension = "1.5.5"

# Hilt
hilt = "2.48"
hilt-navigation-compose = "1.1.0"

# KSP
ksp = "1.9.20-1.0.14"

# AndroidX Core
core-ktx = "1.12.0"
appcompat = "1.6.1"
activity-compose = "1.8.1"

# Lifecycle
lifecycle = "2.6.2"

# Compose
compose-bom = "2023.10.01"
material-icons-extended = "1.5.4"

# Coroutines
coroutines = "1.7.3"

# CameraX
camerax = "1.3.0"

# ML Kit
mlkit-object-detection = "17.0.0"

# TensorFlow
tensorflow-lite = "2.13.0"
tensorflow-lite-support = "0.4.3"

# Location
play-services-location = "21.0.1"

# DataStore
datastore = "1.0.0"

# Logging
timber = "5.0.1"

# Test
junit = "4.13.2"
mockk = "1.13.8"
arch-core-testing = "2.2.0"
espresso-core = "3.5.1"

[libraries]
# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-android-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-navigation-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "hilt-navigation-compose" }

# AndroidX Core
core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "core-ktx" }
appcompat = { group = "androidx.appcompat", name = "appcompat", version.ref = "appcompat" }
activity-compose = { group = "androidx.activity", name = "activity-compose", version.ref = "activity-compose" }

# Lifecycle
lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
lifecycle-service = { group = "androidx.lifecycle", name = "lifecycle-service", version.ref = "lifecycle" }
lifecycle-process = { group = "androidx.lifecycle", name = "lifecycle-process", version.ref = "lifecycle" }
lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
lifecycle-runtime-testing = { group = "androidx.lifecycle", name = "lifecycle-runtime-testing", version.ref = "lifecycle" }

# Compose
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "compose-bom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-tooling-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons-extended = { group = "androidx.compose.material", name = "material-icons-extended", version.ref = "material-icons-extended" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
compose-ui-test-junit4 = { group = "androidx.compose.ui", name = "ui-test-junit4" }

# Coroutines
coroutines-core = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-core", version.ref = "coroutines" }
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }
coroutines-test = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-test", version.ref = "coroutines" }

# CameraX
camerax-core = { group = "androidx.camera", name = "camera-core", version.ref = "camerax" }
camerax-camera2 = { group = "androidx.camera", name = "camera-camera2", version.ref = "camerax" }
camerax-lifecycle = { group = "androidx.camera", name = "camera-lifecycle", version.ref = "camerax" }
camerax-view = { group = "androidx.camera", name = "camera-view", version.ref = "camerax" }

# ML Kit
mlkit-object-detection = { group = "com.google.mlkit", name = "object-detection", version.ref = "mlkit-object-detection" }

# TensorFlow
tensorflow-lite = { group = "org.tensorflow", name = "tensorflow-lite", version.ref = "tensorflow-lite" }
tensorflow-lite-support = { group = "org.tensorflow", name = "tensorflow-lite-support", version.ref = "tensorflow-lite-support" }

# Location
play-services-location = { group = "com.google.android.gms", name = "play-services-location", version.ref = "play-services-location" }

# DataStore
datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Logging
timber = { group = "com.jakewharton.timber", name = "timber", version.ref = "timber" }

# Test
junit = { group = "junit", name = "junit", version.ref = "junit" }
mockk = { group = "io.mockk", name = "mockk", version.ref = "mockk" }
arch-core-testing = { group = "androidx.arch.core", name = "core-testing", version.ref = "arch-core-testing" }
espresso-core = { group = "androidx.test.espresso", name = "espresso-core", version.ref = "espresso-core" }

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
hilt-android = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
```

---

# 项目总体健康度评估

## 总体评分：**6.5/10 — 需紧急治理**

| 评估维度 | 评分 | 说明 |
|---------|------|------|
| 构建可靠性 | 5/10 | KSP 冲突可能导致构建失败，版本分散增加维护成本 |
| 架构规范性 | 5/10 | 缺少 Version Catalog、KSP 配置不符合最佳实践 |
| 文档完整性 | 7/10 | 代码中有较好的注释和功能说明 |
| 可测试性 | 6/10 | 有测试依赖声明但未验证测试覆盖率 |
| 可维护性 | 4/10 | 版本管理不可持续，8 个文件散落 50+ 硬编码版本 |

## 关键建议汇总

1. **本周内**：修复 KSP 配置（仅在 app 模块保留 ksp hilt-compiler），验证构建通过
2. **两周内**：创建 libs.versions.toml 并迁移所有 build.gradle.kts
3. **一个月内**：升级 Hilt 到 2.50+ 和 Kotlin/KSP 到兼容版本
4. **长期**：引入 Renovate/Dependabot + CI 构建检查

---

*报告结束 — Rex (SRE Engineer)*
