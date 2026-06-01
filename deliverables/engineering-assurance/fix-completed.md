# BlindPath P0 修复完成

## 执行修复（4 项全部完成）

| 修复 | 状态 | 涉及文件 |
|------|:----:|---------|
| ① getAlertLevel 阈值统一 (1.5m → 1.0m) | ✅ | ObstacleRepositoryImpl.kt |
| ② SOS 硬编码 110 移除 | ✅ | SosHelper.kt, MainActivity.kt |
| ③ KSP 配置冲突修复 | ✅ | 6 个 library 模块 build.gradle.kts |
| ④ Gradle Version Catalog 迁移 | ✅ | 新建 libs.versions.toml + 8 个构建文件 |

## 变更清单

- **1 个 Kotlin 源文件**：ObstacleRepositoryImpl.kt（阈值修正）
- **2 个 Kotlin 源文件**：SosHelper.kt（默认联系人改空）, MainActivity.kt（移除 110 硬编码）
- **6 个构建文件**：移除多余的 ksp hilt-compiler 声明
- **1 个新文件**：gradle/libs.versions.toml（完整版本目录）
- **8 个构建文件**：全部改为 libs.* 引用方式

**注意**：Version Catalog 和 KSP 配置变更需在 Android Studio 中通过 Gradle Sync 验证。
