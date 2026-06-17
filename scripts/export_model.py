from ultralytics import YOLO
import os
import glob
import shutil

model_file = os.environ.get('MODEL_FILE', 'app/src/main/assets/yolov8n.tflite')

# 检查是否已有有效模型
if os.path.exists(model_file):
    size = os.path.getsize(model_file)
    if size > 1024:
        print(f"Model already exists: {size} bytes")
        exit(0)

# 导出 TFLite 模型
print("Exporting YOLOv8n to TFLite...")
model = YOLO('yolov8n')
model.export(format='tflite', imgsz=320)

# 找到导出的文件并复制
# Ultralytics 导出为 saved_model 格式，需要查找子目录
files = glob.glob('yolov8n_saved_model/*.tflite')
if not files:
    files = glob.glob('yolov8n*.tflite')  # 备用查找
if files:
    os.makedirs(os.path.dirname(model_file), exist_ok=True)
    shutil.copy(files[0], model_file)
    print(f"Exported {files[0]} to {model_file}")
else:
    print("Export failed")
    exit(1)
