# BlindPath 智行助盲

<p align="center">
  <img src="docs/images/app_icon.png" alt="BlindPath Logo" width="120">
</p>

<p align="center">
  <strong>帮助视障人士安全出行的智能助手</strong>
</p>

<p align="center">
  <a href="https://github.com/majunhb/BlindPath/actions">
    <img src="https://github.com/majunhb/BlindPath/workflows/Android%20CI/badge.svg" alt="Build Status">
  </a>
  <a href="https://github.com/majunhb/BlindPath/releases">
    <img src="https://img.shields.io/github/v/release/majunhb/BlindPath" alt="Latest Release">
  </a>
  <a href="LICENSE">
    <img src="https://img.shields.io/badge/License-MIT-blue.svg" alt="License">
  </a>
</p>

---

## 📱 应用介绍

**智行助盲 (BlindPath)** 是一款专为视障人士设计的智能出行辅助应用。通过 AI 技术实时检测前方障碍物，结合语音播报和导航指引，帮助视障用户安全、独立地出行。

### ✨ 核心功能

| 功能模块 | 说明 | 状态 |
|---------|------|------|
| 🎯 **障碍物检测** | 实时检测前方障碍物（行人、车辆、台阶等）并语音预警 | ✅ 已实现 |
| 🗣️ **语音播报** | 全程语音引导，支持语速调节 | ✅ 已实现 |
| 🧭 **导航指引** | 步行导航，播报转向和距离 | ✅ 已实现 |
| 🌤️ **天气播报** | 实时天气信息，出行建议 | ✅ 已实现 |
| 🆘 **紧急求助** | 一键 SOS，发送位置短信 | ✅ 已实现 |
| 👥 **社区互助** | 寻找志愿者陪伴出行 | ✅ 已实现 |
| 🗺️ **路线规划** | 无障碍路线规划，支持公共交通 | ✅ 已实现 |

---

## 🎨 设计特点

### 视障友好设计
- **大按钮设计** - 便于触摸操作
- **高对比度颜色** - 弱视力用户也能看清
- **全程语音反馈** - 每个操作都有语音提示
- **简洁界面** - 减少认知负担

### 技术架构
```
BlindPath/
├── 📱 app/                    # 主应用模块
├── 🔧 base/                   # 基础模块（公共组件、工具类）
├── 🚧 module_obstacle/        # 障碍物检测模块
├── 🧭 module_navigation/      # 导航指引模块
├── 🎙️ module_voice/           # 语音播报模块
├── 👥 module_community/       # 社区功能模块
├── ⚙️ module_settings/        # 设置模块
└── 🛤️ module_trip_assist/     # 行程辅助模块
```

---

## 🛠️ 技术栈

- **语言**: Kotlin
- **UI 框架**: Jetpack Compose
- **架构**: MVVM + Clean Architecture
- **依赖注入**: Hilt
- **异步处理**: Kotlin Coroutines + Flow
- **设计规范**: Material Design 3
- **构建工具**: Gradle Kotlin DSL

---

## 📥 下载安装

### 方式一：GitHub Releases
前往 [Releases 页面](https://github.com/majunhb/BlindPath/releases) 下载最新 APK

### 方式二：自行构建

#### 环境要求
- Android Studio Hedgehog (2023.1.1) 或更高版本
- JDK 17
- Android SDK 34

#### 构建步骤
```bash
# 克隆仓库
git clone https://github.com/majunhb/BlindPath.git
cd BlindPath

# 构建 Debug 版本
./gradlew assembleDebug

# 构建 Release 版本
./gradlew assembleRelease
```

构建完成后，APK 文件位于：
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release: `app/build/outputs/apk/release/app-release.apk`

---

## 🚀 快速开始

### 1. 首次启动
- 授予必要的权限（相机、位置、麦克风）
- 应用会自动初始化语音引擎
- 听到"智行助盲应用已启动"表示准备就绪

### 2. 主要功能使用

#### 障碍物检测
1. 点击主界面"障碍物检测"按钮
2. 将手机摄像头对准前方
3. 应用会自动检测并语音播报障碍物

#### 导航指引
1. 点击"位置播报"获取当前位置
2. 在出行辅助中输入目的地
3. 跟随语音指引前行

#### 紧急求助
1. 点击"紧急求助"按钮
2. 应用会自动发送包含位置的短信给紧急联系人
3. 同时打开拨号界面便于电话求助

---

## 📋 权限说明

| 权限 | 用途 | 是否必需 |
|-----|------|---------|
| CAMERA | 障碍物检测 | 是 |
| ACCESS_FINE_LOCATION | 导航定位 | 是 |
| ACCESS_COARSE_LOCATION | 粗略定位 | 是 |
| RECORD_AUDIO | 语音唤醒与交互 | **是** |
| SEND_SMS | 紧急求助 | 否 |
| CALL_PHONE | 紧急拨号 | 否 |
| VIBRATE | 震动反馈 | 否 |

---

## 🧪 测试

### 运行单元测试
```bash
./gradlew testDebugUnitTest
```

### 运行 Android 测试
```bash
./gradlew connectedAndroidTest
```

### 代码质量检查
```bash
./gradlew lintDebug
```

---

## 🤝 贡献指南

欢迎提交 Issue 和 Pull Request！

### 提交规范
- 使用 [Conventional Commits](https://www.conventionalcommits.org/)
- 代码遵循 [Kotlin Coding Conventions](https://kotlinlang.org/docs/coding-conventions.html)
- 提交前运行 `./gradlew ktlintCheck`

---

## 📄 开源协议

本项目采用 [MIT License](LICENSE) 开源协议。

---

## 🙏 致谢

感谢所有为视障人士出行安全做出贡献的开发者和志愿者！

---

<p align="center">
  Made with ❤️ for accessibility
</p>


## 🚀 快速克隆（1分钟内完成）

由于仓库托管在 GitHub，国内用户直接克隆可能较慢。推荐使用 shallow clone：

### Linux/Mac
```bash
# 方式1：使用快速克隆脚本（自动尝试多个镜像）
curl -sL https://raw.githubusercontent.com/majunhb/BlindPath/main/scripts/fast-clone.sh | bash

# 方式2：手动 shallow clone（只下载最新版本，约10-30秒）
git clone --depth=1 https://github.com/majunhb/BlindPath.git

# 方式3：使用国内镜像
git clone --depth=1 https://ghfast.top/https://github.com/majunhb/BlindPath.git
```

### Windows
```powershell
# 使用 shallow clone（只下载最新版本）
git clone --depth=1 https://github.com/majunhb/BlindPath.git

# 或使用快速克隆脚本
.\scripts\fast-clone.bat
```

### 完整历史（可选）
```bash
cd BlindPath
git fetch --unshallow  # 拉取完整提交历史
```

**仓库大小说明**：
- 代码：约 5MB
- 模型文件：`yolov8n.tflite` 仅 9 字节（LFS 占位符），APP 首次运行自动下载真实模型（约 6MB）
- 总克隆时间（shallow）：**10-30 秒**（国内镜像）
