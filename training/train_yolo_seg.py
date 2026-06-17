#!/usr/bin/env python3
"""
YOLO-seg 训练脚本 - 视障场景分割模型

使用方式：
1. 收集数据：使用 DatasetCollector 收集图像
2. 标注数据：使用 LabelImg 或 CVAT 标注盲道/斑马线等
3. 运行训练：python train_yolo_seg.py --data dataset.yaml --epochs 100

数据集格式（YOLO-seg）：
```
dataset/
  images/
    train/xxx.jpg
    val/xxx.jpg
  labels/
    train/xxx.txt      # 每行: class x1 y1 x2 y2 x3 y3 ... (归一化多边形)
    val/xxx.txt
  data.yaml
```
"""

import argparse
import yaml
from pathlib import Path
from ultralytics import YOLO

def prepare_dataset(config_path: str):
    """准备数据集配置"""
    with open(config_path, 'r') as f:
        config = yaml.safe_load(f)
    
    print(f"Dataset: {config['path']}")
    print(f"Classes: {config['names']}")
    return config

def train_model(
    data_yaml: str,
    model_size: str = 'n',  # n/s/m/l/x
    epochs: int = 100,
    imgsz: int = 640,
    batch: int = 16,
    device: str = '0'
):
    """训练 YOLO-seg 模型"""
    
    # 加载预训练模型
    model = YOLO(f'yolov8{model_size}-seg.pt')
    
    # 训练
    results = model.train(
        data=data_yaml,
        epochs=epochs,
        imgsz=imgsz,
        batch=batch,
        device=device,
        patience=20,  # 早停
        save=True,
        project='blindpath_seg',
        name=f'yolov8{model_size}_seg',
        
        # 数据增强（针对视障场景）
        hsv_h=0.015,  # 色调
        hsv_s=0.7,    # 饱和度
        hsv_v=0.4,    # 亮度
        degrees=5,    # 旋转
        translate=0.1,
        scale=0.5,
        shear=2,
        flipud=0.0,
        fliplr=0.5,
        mosaic=1.0,
        mixup=0.0,
        
        # 优化器
        optimizer='AdamW',
        lr0=0.001,
        lrf=0.01,
        
        # 损失权重
        box=7.5,
        cls=0.5,
        dfl=1.5,
        
        # 其他
        verbose=True,
        seed=42
    )
    
    print(f"Training completed. Best mAP: {results.results_dict['metrics/mAP50-95(B)']:.4f}")
    return results

def export_model(
    weights_path: str,
    format: str = 'tflite',
    int8: bool = True,
    imgsz: int = 320  # 移动端用更小分辨率
):
    """导出为移动端格式"""
    
    model = YOLO(weights_path)
    
    # 导出配置
    export_args = {
        'format': format,
        'imgsz': imgsz,
        'int8': int8,
        'data': 'data.yaml',  # 用于校准
    }
    
    if format == 'tflite':
        export_args['nms'] = True  # 包含NMS
    
    model.export(**export_args)
    
    print(f"Model exported to {format} format")
    print(f"Output: {Path(weights_path).parent / f'best.{format}'}")

def validate_model(weights_path: str, data_yaml: str):
    """验证模型性能"""
    model = YOLO(weights_path)
    metrics = model.val(data=data_yaml)
    
    print(f"Validation Results:")
    print(f"  mAP50: {metrics.box.map50:.4f}")
    print(f"  mAP50-95: {metrics.box.map:.4f}")
    print(f"  mAP75: {metrics.box.map75:.4f}")
    
    return metrics

def main():
    parser = argparse.ArgumentParser(description='Train YOLO-seg for blindpath')
    parser.add_argument('--data', type=str, required=True, help='Path to data.yaml')
    parser.add_argument('--model', type=str, default='n', choices=['n','s','m','l','x'])
    parser.add_argument('--epochs', type=int, default=100)
    parser.add_argument('--imgsz', type=int, default=640)
    parser.add_argument('--batch', type=int, default=16)
    parser.add_argument('--device', type=str, default='0')
    parser.add_argument('--export', action='store_true', help='Export after training')
    parser.add_argument('--validate', action='store_true', help='Validate only')
    
    args = parser.parse_args()
    
    # 准备数据集
    config = prepare_dataset(args.data)
    
    if args.validate:
        # 仅验证
        validate_model('best.pt', args.data)
    else:
        # 训练
        results = train_model(
            data_yaml=args.data,
            model_size=args.model,
            epochs=args.epochs,
            imgsz=args.imgsz,
            batch=args.batch,
            device=args.device
        )
        
        # 导出
        if args.export:
            export_model('best.pt', format='tflite', int8=True, imgsz=320)

if __name__ == '__main__':
    main()
