# 生成 YOLOv8n.tflite 模型文件
# 需要 Python 3.8+ 和 ultralytics 包

import sys
from ultralytics import YOLO

def generate_tflite_model():
    """下载 YOLOv8n 模型并转换为 TFLite 格式"""
    
    print("正在下载 YOLOv8n 模型...")
    
    # 加载预训练的 YOLOv8n 模型
    model = YOLO('yolov8n.pt')
    
    print("模型下载完成，正在转换为 TFLite 格式...")
    
    # 导出为 TFLite 格式
    model.export(format='tflite', imgsz=640)
    
    print("转换完成！")
    print("生成的文件: yolov8n.tflite")
    print("请将此文件复制到: BlindPath/app/src/main/assets/")

if __name__ == '__main__':
    try:
        generate_tflite_model()
    except Exception as e:
        print(f"错误: {e}")
        print("\n请确保已安装 ultralytics: pip install ultralytics")
        sys.exit(1)
