# BlindPath AI Models

This directory contains AI models used by the BlindPath application for obstacle detection and scene recognition.

## Models

| Model | File | Size | Purpose | Status |
|-------|------|------|---------|--------|
| YOLOv8n TFLite | `yolov8n.tflite` | ~6 MB | Real-time obstacle detection | ⚠️ Requires download |

## Quick Start

### Option 1: Automatic Download (Recommended)

**Using Python:**
```bash
cd /path/to/BlindPath
python download_model.py
```

**Using Shell:**
```bash
cd /path/to/BlindPath
chmod +x download_model.sh
./download_model.sh
```

### Option 2: Manual Download

1. Download from one of these sources:
   - **Primary**: [GitHub Ultralytics Assets](https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite)
   - **Backup**: [Hugging Face](https://huggingface.co/Ultralytics/YOLOv8/resolve/main/yolov8n.tflite)

2. Place the downloaded file in this directory:
   ```
   BlindPath/app/src/main/assets/yolov8n.tflite
   ```

### Option 3: Export from PyTorch

If you have PyTorch installed, you can export the model yourself:

```bash
pip install ultralytics
yolo export model=yolov8n.pt format=tflite
mv yolov8n.tflite app/src/main/assets/
```

## Model Details

### YOLOv8n TFLite

| Property | Value |
|----------|-------|
| **Architecture** | YOLOv8 Nano |
| **Format** | TensorFlow Lite |
| **Input Size** | 320 × 320 pixels (optimized for speed) |
| **Output** | Bounding boxes + class labels |
| **Classes** | COCO 80 classes (mapped to obstacle types) |
| **Inference Time** | ~30-50ms on modern Android devices |
| **Source** | [Ultralytics YOLOv8](https://github.com/ultralytics/ultralytics) |

#### Obstacle Type Mapping

The model detects 80 COCO object classes, which are mapped to blind-relevant obstacle types:

| COCO Class | BlindPath Type | Severity |
|------------|----------------|----------|
| Person (0) | PERSON | Medium |
| Car (2) | VEHICLE | High |
| Bicycle (1) | BICYCLE | Medium |
| Traffic Light (9) | TRAFFIC_LIGHT | Medium |
| Bench (12) | BENCH | Low |
| ... | ... | ... |

See `module_obstacle/data/detection/AIDetector.kt` for the complete mapping.

## Build Integration

The model is automatically included in the APK when placed in this directory.

### Gradle Task (Optional)

Add this to `app/build.gradle.kts` to download the model during build:

```kotlin
tasks.register("downloadModel") {
    doLast {
        val assetsDir = file("src/main/assets")
        val modelFile = File(assetsDir, "yolov8n.tflite")
        
        if (!modelFile.exists()) {
            assetsDir.mkdirs()
            ant.invokeMethod("get", mapOf(
                "src" to "https://github.com/ultralytics/assets/releases/download/v8.2.0/yolov8n.tflite",
                "dest" to modelFile
            ))
            println("Model downloaded to ${modelFile.absolutePath}")
        } else {
            println("Model already exists at ${modelFile.absolutePath}")
        }
    }
}

tasks.named("preBuild") {
    dependsOn("downloadModel")
}
```

## Troubleshooting

### Model Not Loading

If the app shows "AI model loading failed":

1. **Verify the model file exists**:
   ```bash
   ls -la app/src/main/assets/yolov8n.tflite
   ```
   
2. **Check file size** (should be ~6 MB):
   ```bash
   du -h app/src/main/assets/yolov8n.tflite
   ```

3. **Re-download the model**:
   ```bash
   python download_model.py --force
   ```

### Slow Detection

If obstacle detection is slow:

- The model uses 320×320 input for speed optimization
- Consider reducing `numThreads` in `AIDetector.kt` if device overheats
- GPU acceleration is enabled by default (if available)

### Detection Accuracy Issues

To improve detection accuracy:

1. Adjust confidence threshold in `AIDetector.kt`:
   ```kotlin
   private val confidenceThreshold = 0.4f  // Lower = more detections
   ```

2. Adjust IoU threshold for overlapping boxes:
   ```kotlin
   private val iouThreshold = 0.5f
   ```

## Fallback Behavior

If the TFLite model fails to load, the app will automatically fall back to:
1. ML Kit Object Detection (built-in)
2. Demo mode (camera works, but no AI detection)

## License

The YOLOv8 model is licensed under [AGPL-3.0](https://github.com/ultralytics/ultralytics/blob/main/LICENSE).

For commercial use, consider:
- Using a different model with permissive license
- Purchasing an Ultralytics Enterprise license
