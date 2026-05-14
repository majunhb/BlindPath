# 智行助盲 (BlindPath)

专为视障人士打造的智能出行导航APP

## 功能特性

- **AI视觉避障**：实时识别障碍物，厘米级测距，分级预警
- **高德地图导航**：集成高德步行导航SDK，精准定位
- **全语音交互**：无需视觉操作，语音控制一切
- **离线可用**：端侧AI推理，无网络也能避障

## 技术架构

```
├── app/                 # 主APP模块（壳）
├── base/               # 公共基础模块
├── module_obstacle/    # AI视觉避障模块（核心）
├── module_navigation/  # 导航模块
├── module_voice/       # 语音交互模块
├── module_settings/    # 设置模块（预留）
└── module_community/  # 社区模块（预留）
```

## 开发环境

- **Android Studio**: Hedgehog (2023.1.1) 或更高版本
- **JDK**: 17+
- **Android SDK**: 34
- **Gradle**: 8.2+

## 快速开始

### 1. 克隆项目

```bash
git clone <repository_url>
cd BlindPath
```

### 2. 配置API密钥

编辑 `app/build.gradle.kts` 中的占位符：

```kotlin
manifestPlaceholders["AMAP_KEY"] = "你的高德地图Key"
```

编辑 `AndroidManifest.xml` 中的百度语音密钥：

```xml
<meta-data
    android:name="com.baidu.tts.appId"
    android:value="你的百度AppId" />
<meta-data
    android:name="com.baidu.tts.appKey"
    android:value="你的百度AppKey" />
<meta-data
    android:name="com.baidu.tts.secretKey"
    android:value="你的百度SecretKey" />
```

### 3. 添加AI模型

将YOLOv8模型文件放入：

```
app/src/main/assets/yolov8n.tflite
```

可从以下地址下载：
- https://github.com/ultralytics/ultralytics

## CI/CD 自动构建

本项目配置了 GitHub Actions，push 或 pull request 到 main 分支时会自动构建 APK。

### 1. 创建 Git 仓库

```bash
cd BlindPath
git init
git add .
git commit -m "Initial commit"
git remote add origin https://github.com/YOUR_USERNAME/BlindPath.git
git push -u origin main
```

### 2. 查看构建结果

1. 访问 https://github.com/YOUR_USERNAME/BlindPath/actions
2. 点击最新的 workflow 运行
3. 下载 Artifacts 中的 `app-debug` APK

### 3. 本地编译

```bash
./gradlew assembleDebug
```

或使用 Android Studio 打开项目，点击 Run。

## 获取API密钥

### 高德地图SDK
1. 访问 https://console.amap.com/dev/key/app
2. 创建应用，获取Web服务Key
3. 启用"Android平台"并绑定包名

### 百度语音
1. 访问 https://console.bce.baidu.com/
2. 创建语音应用，获取AppId、AppKey、SecretKey

## 权限说明

| 权限 | 用途 |
|------|------|
| CAMERA | 采集实时画面用于AI识别 |
| ACCESS_FINE_LOCATION | 精准定位用于导航 |
| RECORD_AUDIO | 语音指令识别 |
| INTERNET | 在线语音识别、地图数据 |
| VIBRATE | 震动反馈预警 |
| FOREGROUND_SERVICE | 后台持续运行 |

## 模块说明

### module_obstacle（核心模块）
- CameraX摄像头采集
- TensorFlow Lite YOLOv8目标检测
- 单目视觉测距算法
- 分级预警逻辑

### module_navigation
- 高德地图SDK集成
- 北斗/GPS定位融合
- 路径规划和导航

### module_voice
- 百度TTS语音合成
- 语音指令解析
- 语音唤醒

## 后续扩展

预留模块接口，可轻松添加：
- `module_medical` - 跌倒检测/一键求助
- `module_transport` - 公共交通识别
- `module_familiar` - 熟悉路线学习
- `module_ai_chat` - AI助手问答

## License

MIT License
