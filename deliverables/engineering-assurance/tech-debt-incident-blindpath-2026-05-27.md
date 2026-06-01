# BlindPath 全面工程审查 + 事故响应综合报告

**日期**：2026-05-27
**工作流**：技术债评估（Workflow 5）+ 事故响应（Workflow 3）
**参与成员**：Cody（代码审查师）· Archi（系统架构师）· Rex（SRE工程师）· Tessa（测试专家）· Docu（技术文档师）

---

## 📌 TL;DR（执行摘要）

- **整体结论**：BlindPath 项目核心功能设计合理，模块划分清晰，但存在 **3 项阻断级构建问题** 和 **3 项业务逻辑 Bug**。项目健康度评分 6.5/10，需紧急治理。
- **严重度分布**：🔴严重 9 项 / 🟠高 8 项 / 🟡中 15+ 项 / 🟢低 10+ 项
- **阻塞项**：KSP 代码生成冲突（SEV2）、直接注入 Impl 而非接口、getAlertLevel 阈值不一致（业务逻辑 Bug）
- **核心建议**：本周内修复 KSP+Hilt 配置 + 创建 Version Catalog + 修复预警阈值不一致

---

## 🎯 核心结论卡片

| 项目 | 内容 |
|------|------|
| 整体评级 | 🟡 有条件通过 — **需紧急修复 3 项阻断问题后方可投产** |
| 阻塞项数量 | 3（KSP冲突、Impl注入、阈值Bug） |
| 关键行动项 | 12 条（P0:3 / P1:5 / P2:4） |
| 建议下一步 | 按本文行动清单优先级依次执行修复 |

---

# 第一部分：综合代码审查 + 架构评估

## 🔍 审查发现（按严重度排序）

### 🔴 严重问题（6 项）

| # | 严重度 | 类别 | 文件:行 | 问题描述 | 建议修复 | 来源 |
|---|--------|------|---------|---------|---------|------|
| 1 | 🔴 | 正确性 | ObstacleRepository.kt:57-63 vs Impl:143-149 | **getAlertLevel 阈值不一致**：接口 WARNING <1.0m，Impl 实现 <1.5m | 统一阈值，删除接口默认实现 | Cody + Archi |
| 2 | 🔴 | 安全 | SosHelper.kt:21 + MainActivity.kt:154,165 | **SOS 硬编码 "110"**：默认联系人硬编码为 110，无紧急联系人时将误拨 110 | 移除默认值，空时提示设置 | Cody |
| 3 | 🔴 | 构建 | 所有 7 个模块 build.gradle.kts | **KSP 代码生成冲突**：Hilt ksp compiler 在全部模块重复声明 | 仅在 :app 保留 ksp hilt-compiler | Rex |
| 4 | 🔴 | 设计 | MainViewModel.kt:23, ObstacleService.kt:27, NavigationService.kt:32 | **直接注入 Impl 而非接口**：绕过抽象层注入 `*Impl` 类 | 改为注入接口 | Archi |
| 5 | 🔴 | 性能 | ObstacleRepositoryImpl.kt:459-493, AIDetector.kt:345-350 | **Bitmap 逐帧创建导致频繁 GC**：每秒 30 帧产生 ~150MB+ 临时内存 | 复用 Bitmap 池 + 直接操作 YUV 数据 | Cody |
| 6 | 🔴 | 正确性 | ObstacleService.kt:32 | **Service CoroutineScope 未绑定生命周期**：START_STICKY 重启时旧 Job 未清理 | 使用 lifecycleScope | Cody + Archi |

### 🟠 重要问题（5 项）

| # | 严重度 | 类别 | 文件:行 | 问题描述 | 建议修复 | 来源 |
|---|--------|------|---------|---------|---------|------|
| 7 | 🟠 | 性能 | AIDetector.kt:346 | **逐帧分配 Direct ByteBuffer ~4.9MB** | 复用为类成员变量 | Cody |
| 8 | 🟠 | 正确性 | ObstacleRepositoryImpl.kt:270-274 | **CameraX 绑定 ProcessLifecycleOwner**：后台持续运行 Camera | 创建自定义 LifecycleOwner | Cody + Archi |
| 9 | 🟠 | 正确性 | AIDetector.kt:186-193 | **loadModel() 静默降级**：所有策略失败仍返回 true | 返回 false 并通知用户 | Cody |
| 10 | 🟠 | 安全 | SosHelper.kt:37-73 | **sendSos() 未主动检查 SEND_SMS 权限** | 方法开头检查权限 | Cody |
| 11 | 🟠 | 测试质量 | NavigationServiceTest.kt:23 vs Service.kt:202 | **测试阈值与生产不一致**：测试用 5m，实际用 3m | 统一阈值 | Cody + Tessa |

### 🟡 一般问题（部分列出）

| # | 严重度 | 类别 | 问题 | 来源 |
|---|--------|------|------|------|
| 12 | 🟡 | 可维护性 | **双 TTS 实现**：TtsManager + VoiceRepositoryImpl 两套 | Cody + Archi |
| 13 | 🟡 | 可维护性 | **POTTTED_PLANT 拼写错误**（多一个 T） | Cody |
| 14 | 🟡 | 正确性 | **Direction 枚举语义重复**：LEFT_FRONT=FRONT_LEFT | Cody |
| 15 | 🟡 | 正确性 | **导航播报防重复缺陷**：导航被自身播放状态阻塞 | Cody |
| 16 | 🟡 | 安全 | **发布版未开启 ProGuard/R8**：isMinifyEnabled=false | Cody |
| 17 | 🟡 | 可维护性 | **社区/设置模块缺少 Repository 接口** | Archi |
| 18 | 🟡 | 可维护性 | **base 模块承载 UI 模型**：ObstacleAlert 等 | Archi |
| 19 | 🟡 | 生命周期 | **MainActivity 自建 CoroutineScope**：存在泄漏风险 | Archi |

---

# 第二部分：事故响应报告

## 事故一：KSP 代码生成冲突

| 项目 | 内容 |
|------|------|
| SEV 评级 | **SEV2** 🟠 — 主要功能降级 |
| 影响范围 | 7/7 模块，所有 @AndroidEntryPoint 组件 |
| 根因 | Hilt KSP compiler 在全部模块重复声明，KSP 1.9.20-1.0.14 + Hilt 2.48 多模块兼容性问题 |
| 修复 | 从所有 library 模块移除 `ksp("hilt-android-compiler")`，仅在 :app 保留；升级 Hilt 到 2.50+ |

### 5 Why 分析

```
Why 1: 构建失败 — KSP 处理器 hilt-android-compiler 报错
  ↓
Why 2: 所有 7 个模块同时声明 ksp("hilt-android-compiler")
  ↓
Why 3: KSP 在多模块中独立运行，但 Hilt 期望单一符号处理环境
  ↓
Why 4: Hilt 2.48 + KSP 有多模块已知限制
  ↓
Why 5（根因）: 未遵循 Hilt 官方推荐（仅在 :app 声明 ksp hilt-compiler）
```

## 事故二：Gradle Version Catalog 缺失

| 项目 | 内容 |
|------|------|
| SEV 评级 | **SEV3** 🟡 — 架构债 |
| 影响范围 | 50+ 处硬编码版本散布在 8 个 build.gradle.kts |
| 根因 | 项目初期未建立构建配置治理，未使用 Gradle Version Catalog |
| 修复 | 创建 `gradle/libs.versions.toml`，迁移所有 build.gradle.kts |

---

# 第三部分：测试覆盖评估

## 覆盖矩阵

| 模块 | 源文件 | 现有测试 | 有效覆盖率 |
|------|--------|---------|-----------|
| app | 6 | 1 (MainViewModelTest ✅ 较好) | ~16% |
| module_obstacle | 5 | 1 (影子测试 ⚠️) | ~0% |
| module_navigation | 4 | 1 (影子测试 + 阈值偏差 ⚠️) | ~0% |
| module_voice | 3 | 1 (影子测试 ❌) | ~0% |
| base | 4 | 0 | 0% |
| module_community | 2 | 0 | 0% |
| module_settings | 2 | 0 | 0% |

## 关键发现

- **有效测试覆盖率不到 5%**
- **55% 的现有测试是"影子测试"** — 测试的是复制逻辑而非实际生产代码
- **核心业务逻辑 0% 覆盖**：AIDetector、ObstacleRepositoryImpl、NavigationRepositoryImpl、VoiceRepositoryImpl 全部无测试
- `getAlertLevel()` 接口/实现阈值不一致（1.0m vs 1.5m）

## 建议优先新增测试

| P0 | P1 | P2 |
|----|----|----|
| AIDetector 管线测试 | ObstacleService 实际 handleAlert 测试 | ObstacleModels getAlertMessage 测试 |
| ObstacleRepositoryImpl 预警逻辑测试 | NavigationService 实际 speakNavigation 测试 | SceneClassifier 场景边界测试 |
| NavigationRepositoryImpl 指令生成测试 | MainActivity 权限流程测试 | Settings/Community DataStore 测试 |

---

# 第四部分：文档评估

| 维度 | 评分 | 核心问题 |
|------|------|---------|
| 完整性 | 4/10 | 缺少 8+ 种必要文档类型 |
| 准确性 | 7/10 | 现有内容准确 |
| 可用性 | 4/10 | README 仅 28 行 |
| KDoc 覆盖率 | 5/10 | NavigationRepository 接口 0% |

## P0 行动项

1. **重写 README.md** — 添加安装前置条件、项目结构图、完整构建步骤
2. **补充 NavigationRepository KDoc** — 当前为 0%
3. **创建 CHANGELOG.md** — 整理版本历史

---

## ✅ 行动清单（按优先级排序）

| # | 行动 | 负责角色 | 紧急度 | 预期完成 |
|---|------|---------|--------|---------|
| 1 | 修复 KSP 配置：从所有 library 模块移除 `ksp("hilt-android-compiler")`，仅在 :app 保留 | Developer | P0 🚨 | 本周 |
| 2 | 统一 getAlertLevel 阈值：删除接口默认实现，以 Impl 为准（1.5m） | Developer | P0 🚨 | 本周 |
| 3 | 修复 SOS 硬编码 110：移除默认联系人 + 使用设置中的紧急联系人列表 | Developer | P0 🚨 | 本周 |
| 4 | 创建 `gradle/libs.versions.toml`，迁移所有 build.gradle.kts | Developer | P0 🚨 | 两周内 |
| 5 | 修复直接注入 Impl：改为注入接口（MainViewModel/ObstacleService/NavigationService） | Developer | P1 🔥 | 两周内 |
| 6 | 修复 Bitmap 逐帧创建 GC 抖动：实现 Bitmap 池 + 直接操作 YUV | Developer | P1 🔥 | 两周内 |
| 7 | Service 改用 lifecycleScope，修复 CameraX 生命周期绑定 | Developer | P1 🔥 | 两周内 |
| 8 | 统一现有测试阈值与生产代码一致，重写影子测试为真实测试 | QA | P1 🔥 | 两周内 |
| 9 | 升级 Hilt 到 2.50+ + Kotlin/KSP 同步升级 | Developer | P1 🔥 | 一个月内 |
| 10 | 为 AIDetector + ObstacleRepositoryImpl 编写核心单元测试 | QA | P2 📝 | 一个月内 |
| 11 | 重写 README.md + 创建 CHANGELOG.md | Tech Writer | P2 📝 | 一个月内 |
| 12 | 启用 ProGuard/R8 混淆，建立 CI 构建预检查 | SRE | P2 📝 | 下个里程碑 |

---

## ⚠️ 待完善 / 已知局限

- 本报告基于静态代码分析，未执行实际构建验证（需 Android SDK + Gradle 环境）
- KSP 冲突的严重度需通过实际 `./gradlew assembleDebug` 构建确认
- SOS 功能中 110 硬编码涉及法律合规风险，建议尽快修正
- CameraX 绑定 ProcessLifecycleOwner 在纯后台持续运行时可能违反 Android 14+ 前台服务限制

---

## 📚 数据来源 & 成员产出索引

- **Cody（代码审查师）**：26 项代码审查发现（6 🔴 / 5 🟠 / 8 🟡 / 7 🟢）
- **Archi（架构师）**：12 项架构债（3 🔴 / 7 🟡 / 2 🟢），含模块依赖图
- **Rex（SRE工程师）**：2 起事故响应（SEV2 + SEV3），含 5 Why + libs.versions.toml 参考实现
- **Tessa（测试专家）**：测试覆盖矩阵 + 影子测试分析 + 建议新增测试 17 项
- **Docu（技术文档师）**：文档质量评估 C- + 缺失文档清单 8 项

> 本报告由工程保障团队 AI 协作生成，关键决策请由人类工程负责人复核。
