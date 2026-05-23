# YOLOv8 模型文件下载说明

## 重要提示

**如果模型文件缺失，应用会自动使用 ML Kit Object Detection 作为回退方案**，仍然可以进行障碍物检测，但检测精度和速度可能略低于 YOLOv8。

## 下载地址

YOLOv8n TFLite 模型（约 6MB）：

### 方式 1：官方地址（推荐）
- GitHub: https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite
- Hugging Face: https://huggingface.co/Ultralytics/YOLOv8/resolve/main/yolov8n.tflite

### 方式 2：国内镜像（如果官方地址无法访问）
- 清华镜像: https://mirrors.tuna.tsinghua.edu.cn/github-release/ultralytics/assets/
- 阿里云镜像: https://npmmirror.com/mirrors/yolov8/

### 方式 3：使用代理或 VPN
如果网络受限，可以使用代理或 VPN 访问官方地址

## 安装步骤

1. 下载 `yolov8n.tflite` 文件
2. 将文件复制到此目录（`app/src/main/assets/`）
3. 确保文件名为 `yolov8n.tflite`（小写）
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
D/AIDetector: YOLOv8 model loaded from assets
```

如果模型加载失败但 ML Kit 回退成功，日志会显示：
```
W/AIDetector: Model not found in assets, will try to download
W/AIDetector: TFLite模型文件无法加载，尝试ML Kit回退
D/AIDetector: 使用ML Kit目标检测作为回退方案
```

## 模型说明

- **名称**: YOLOv8 Nano
- **格式**: TensorFlow Lite
- **大小**: ~6MB
- **输入尺寸**: 320x320（项目配置）
- **用途**: 实时目标检测，识别障碍物
- **支持类别**: 80类 COCO 数据集（人物、车辆、交通标志等）

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

## 故障排查

### 问题 1：模型下载失败
- 检查网络连接
- 尝试使用国内镜像地址
- 使用代理或 VPN

### 问题 2：模型加载失败
- 确认文件名正确（`yolov8n.tflite`，小写）
- 确认文件大小约 6MB
- 检查文件是否损坏（重新下载）

### 问题 3：检测不到障碍物
- 检查日志确认使用的是 YOLOv8 还是 ML Kit
- ML Kit 置信度阈值已调整为 0.3（原 0.5）
- 确保摄像头权限已授予
- 确保光线充足

## 性能对比

| 特性 | YOLOv8 | ML Kit |
|------|--------|--------|
| 检测速度 | 快 | 中等 |
| 检测精度 | 高 | 中等 |
| 模型大小 | 6MB | 内置 |
| 网络依赖 | 首次下载 | 无 |
| 支持类别 | 80类 | 约20类 |
