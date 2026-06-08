# YOLOv8 模型文件下载说明

## 重要提示

**如果模型文件缺失，应用会自动使用 ML Kit Object Detection 作为回退方案**，仍然可以进行障碍物检测，但检测精度和速度可能略低于 YOLOv8。

## 模型文件概览

BlindPath 支持多模式感知，每种模式对应一个专属模型文件：

| 模型文件 | 感知模式 | 用途 | 必需程度 |
|----------|---------|------|---------|
| `yolov8n.tflite` | 通用回退 | 80类COCO通用目标检测 | 可选（有专属模型时不需要） |
| `yolo_indoor.tflite` | 室内感知 (INDOOR) | 微观环境防碰撞与找东西 | 推荐 |
| `yolo_traffic.tflite` | 出行导航 (NAVIGATION) | 宏观路径方向感与交通规则 | 推荐 |
| `yolo_scene.tflite` | 场景识别 (SCENE) | 环境语义认知与地标识别 | 推荐 |

> **AUTO 模式**会根据传感器数据自动在室内/导航/场景三个模式间切换，因此建议下载全部三个专属模型以获得最佳体验。

## 下载地址

### 方式 1：GitHub Releases（推荐）

所有模型文件均从 BlindPath GitHub Releases 下载：

- 基础地址：https://github.com/majunhb/BlindPath/releases/download/models/
- 直接下载链接：
  - `yolov8n.tflite`（通用模型，约 6MB）
  - `yolo_indoor.tflite`（室内专属模型）
  - `yolo_traffic.tflite`（导航专属模型）
  - `yolo_scene.tflite`（场景专属模型）

### 方式 2：国内镜像（如果官方地址无法访问）

- ghfast.top 镜像：https://ghfast.top/https://github.com/majunhb/BlindPath/releases/download/models/
- ghproxy 镜像：https://mirror.ghproxy.com/https://github.com/majunhb/BlindPath/releases/download/models/

### 方式 3：使用代理或 VPN
如果网络受限，可以使用代理或 VPN 访问官方地址

## 安装步骤

1. 下载所需的模型文件（推荐全部下载）
2. 将文件复制到此目录（`app/src/main/assets/`）
3. 确保文件名正确（小写）：
   - `yolov8n.tflite`
   - `yolo_indoor.tflite`
   - `yolo_traffic.tflite`
   - `yolo_scene.tflite`
4. 重新构建应用：
   ```bash
   ./gradlew clean assembleDebug
   ```

## 验证

文件放置后，运行构建命令验证：
```bash
./gradlew assembleDebug
```

如果模型加载成功，日志会显示：
```
D/AIDetector: Model loaded: yolo_indoor.tflite from /data/...
D/AIDetector: Model loaded: yolo_traffic.tflite from /data/...
D/AIDetector: Model loaded: yolo_scene.tflite from /data/...
```

如果模型加载失败但 ML Kit 回退成功，日志会显示：
```
W/AIDetector: Model not found in assets, will try to download
W/AIDetector: TFLite模型文件无法加载，尝试ML Kit回退
D/AIDetector: 使用ML Kit目标检测作为回退方案
```

## 模型说明

### 通用模型 - yolov8n.tflite
- **名称**: YOLOv8 Nano
- **格式**: TensorFlow Lite
- **大小**: ~6MB
- **输入尺寸**: 320x320（项目配置）
- **用途**: 实时目标检测，识别障碍物
- **支持类别**: 80类 COCO 数据集（人物、车辆、交通标志等）

### 室内专属模型 - yolo_indoor.tflite
- **感知模式**: INDOOR（室内感知）
- **用途**: 微观环境防碰撞与找东西
- **置信度阈值**: 0.45
- **NMS阈值**: 0.50
- **语音播报冷却**: 2500ms
- **检测白名单**: 台阶、楼梯、椅子、桌子、沙发、床、盆栽、水槽、冰箱、微波炉、烤箱、手机、背包、杯子、瓶子、书、键盘、猫、狗、人等

### 导航专属模型 - yolo_traffic.tflite
- **感知模式**: NAVIGATION（出行导航）
- **用途**: 宏观路径方向感与交通规则
- **置信度阈值**: 0.40
- **NMS阈值**: 0.45
- **语音播报冷却**: 2000ms
- **检测白名单**: 红绿灯、交通标志、斑马线、人、自行车、摩托车、汽车、公交车、卡车、路缘、水坑、井盖、柱子、电线杆、长椅、扶手等

### 场景识别模型 - yolo_scene.tflite
- **感知模式**: SCENE（场景识别）
- **用途**: 环境语义认知与地标识别
- **置信度阈值**: 0.40
- **NMS阈值**: 0.45
- **语音播报冷却**: 4000ms
- **检测白名单**: 长椅、扶手、交通标志、人、车辆等

## ML Kit 回退方案

如果 YOLOv8 模型无法加载，应用会自动使用 Google ML Kit Object Detection 作为回退方案：

**ML Kit 优势**：
- 无需额外下载模型文件
- 自动集成在应用中
- 支持常见物体检测（人物、车辆、家具等）

**ML Kit 限制**：
- 检测精度略低于 YOLOv8
- 不支持自定义模型
- 检测速度可能较慢
- 不区分室内/导航/场景模式

## 故障排查

### 问题 1：模型下载失败
- 检查网络连接
- 尝试使用国内镜像地址
- 使用代理或 VPN

### 问题 2：模型加载失败
- 确认文件名正确（小写，如 `yolo_indoor.tflite`）
- 确认文件大小合理（非 0 字节）
- 检查文件是否损坏（重新下载）

### 问题 3：模式切换后检测不到目标
- 确认对应模式的模型文件已放置（如室内模式需要 `yolo_indoor.tflite`）
- 检查日志确认模型是否成功加载
- 如果专属模型缺失，应用会尝试下载或回退到 ML Kit

### 问题 4：AUTO 模式频繁切换
- AUTO 模式基于传感器数据（GPS、光线、加速度计）自动推断场景
- 内置平滑滤波和稳定性检查，连续3次相同推断才会切换模式
- 确保手机传感器正常工作

## 性能对比

| 特性 | YOLOv8 专属模型 | YOLOv8 通用模型 | ML Kit |
|------|----------------|----------------|--------|
| 检测速度 | 快 | 快 | 中等 |
| 检测精度 | 高（针对场景优化） | 高（通用） | 中等 |
| 模型大小 | ~6MB/个 | ~6MB | 内置 |
| 网络依赖 | 首次下载 | 首次下载 | 无 |
| 模式适配 | 精确匹配 | 通用 | 无 |
| 白名单过滤 | 支持 | 不支持 | 不支持 |
