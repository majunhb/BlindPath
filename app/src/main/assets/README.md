# YOLOv8n TFLite Model Placeholder

This is a placeholder file for the YOLOv8n TFLite model.

## To Get the Actual Model:

1. Visit: https://github.com/ultralytics/ultralytics
2. Download yolov8n.tflite from releases
3. Replace this file with the actual model

Or use the following command:
```bash
# Install ultralytics first
pip install ultralytics

# Export to TFLite
yolo export model=yolov8n.pt format=tflite
```

The model should be approximately 6MB in size.
