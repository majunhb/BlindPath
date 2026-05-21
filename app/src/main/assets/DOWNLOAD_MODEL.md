# YOLOv8 模型文件下载说明

由于网络原因，模型文件未能自动下载。请手动下载并放置到此目录。

## 下载地址

YOLOv8n TFLite 模型（约 6MB）：
- GitHub: https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite
- 备用地址: https://huggingface.co/Ultralytics/YOLOv8/resolve/main/yolov8n.tflite

## 安装步骤

1. 下载 `yolov8n.tflite` 文件
2. 将文件复制到此目录（`app/src/main/assets/`）
3. 确保文件名为 `yolov8n.tflite`（小写）

## 验证

文件放置后，运行构建命令验证：
```bash
./gradlew assembleDebug
```

如果模型加载成功，AI 避障功能将正常工作。

## 模型说明

- **名称**: YOLOv8 Nano
- **格式**: TensorFlow Lite
- **大小**: ~6MB
- **输入尺寸**: 320x320（项目配置）
- **用途**: 实时目标检测，识别障碍物
