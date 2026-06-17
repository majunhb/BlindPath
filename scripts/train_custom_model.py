#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
BlindPath 自定义模型训练脚本
===========================
在 COCO 预训练基础上微调 YOLOv8，添加视障导航专用障碍物类别。

使用方式:
    1. 准备数据集（见 README_training.md）
    2. pip install ultralytics
    3. python train_custom_model.py

目标类别（40类）:
    - 地面障碍: step, stairs, curb, puddle, pothole, manhole
    - 交通工具: car, bus, truck, bicycle, motorcycle, e-scooter
    - 行人: person, wheelchair, stroller
    - 街道设施: traffic_light, traffic_sign, fire_hydrant, electric_pole,
                trash_bin, bench, bollard, barrier, handrail
    - 施工区域: construction_cone, barricade, scaffold
    - 宠物动物: dog, cat
    - 绿化: tree, bush, potted_plant
    - 其他: skateboard, umbrella, suitcase
"""

import os
import sys
import yaml
import argparse
from pathlib import Path
from datetime import datetime

# ====== 配置 ======

# 视障导航专用类别（YAML 格式）
BLINDPATH_CLASSES = {
    0: "step",               # 台阶
    1: "stairs",             # 楼梯
    2: "curb",               # 路沿
    3: "puddle",             # 水坑
    4: "pothole",            # 坑洼
    5: "manhole",            # 井盖
    6: "car",                # 汽车
    7: "bus",                # 公交车
    8: "truck",              # 卡车
    9: "bicycle",            # 自行车
    10: "motorcycle",        # 摩托车
    11: "e_scooter",         # 电动滑板车
    12: "person",            # 行人
    13: "wheelchair",        # 轮椅
    14: "stroller",          # 婴儿车
    15: "traffic_light",     # 红绿灯
    16: "traffic_sign",      # 交通标志
    17: "fire_hydrant",      # 消防栓
    18: "electric_pole",     # 电线杆
    19: "trash_bin",         # 垃圾桶
    20: "bench",             # 长椅
    21: "bollard",           # 石墩/隔离桩
    22: "barrier",           # 围栏/护栏
    23: "handrail",          # 扶手
    24: "construction_cone", # 施工锥
    25: "barricade",         # 施工围挡
    26: "scaffold",          # 脚手架
    27: "dog",               # 狗
    28: "cat",               # 猫
    29: "tree",              # 树木
    30: "bush",              # 灌木/绿化带
    31: "potted_plant",      # 盆栽
    32: "skateboard",        # 滑板
    33: "umbrella",          # 雨伞
    34: "suitcase",          # 行李箱
    35: "backpack",          # 背包
    36: "chair",             # 椅子
    37: "table",             # 桌子
    38: "bottle",            # 瓶子
    39: "pole",              # 柱子（通用）
}

NUM_CLASSES = len(BLINDPATH_CLASSES)


def create_dataset_yaml(data_dir: str, output_path: str):
    """生成 YOLO 格式的数据集配置 YAML"""
    dataset_config = {
        "path": os.path.abspath(data_dir),
        "train": "images/train",
        "val": "images/val",
        "test": "images/test",
        "nc": NUM_CLASSES,
        "names": [BLINDPATH_CLASSES[i] for i in range(NUM_CLASSES)],
    }

    with open(output_path, "w", encoding="utf-8") as f:
        yaml.dump(dataset_config, f, allow_unicode=True, sort_keys=False)

    print(f"[OK] 数据集配置已生成: {output_path}")
    print(f"     类别数: {NUM_CLASSES}")
    return output_path


def train_model(
    data_yaml: str,
    model_size: str = "n",
    epochs: int = 100,
    batch_size: int = 16,
    imgsz: int = 640,
    pretrained: bool = True,
    device: str = "",
    output_dir: str = "runs/train",
):
    """
    训练自定义 YOLOv8 模型

    Args:
        data_yaml: 数据集配置文件路径
        model_size: 模型大小 n/s/m/l/x (nano/small/medium/large/xlarge)
        epochs: 训练轮数
        batch_size: 批次大小
        imgsz: 输入图像尺寸
        pretrained: 是否使用预训练权重
        device: 训练设备 (""=自动, "0"=GPU, "cpu"=CPU)
        output_dir: 输出目录
    """
    try:
        from ultralytics import YOLO
    except ImportError:
        print("[ERROR] 请先安装 ultralytics: pip install ultralytics")
        sys.exit(1)

    print(f"\n{'='*60}")
    print(f"  BlindPath 自定义模型训练")
    print(f"{'='*60}")
    print(f"  模型大小:      YOLOv8{model_size}")
    print(f"  类别数:        {NUM_CLASSES}")
    print(f"  训练轮数:      {epochs}")
    print(f"  批次大小:      {batch_size}")
    print(f"  图像尺寸:      {imgsz}")
    print(f"  预训练:        {'是 (COCO)' if pretrained else '否'}")
    print(f"  数据集配置:    {data_yaml}")
    print(f"{'='*60}\n")

    # 加载模型
    if pretrained:
        model_path = f"yolov8{model_size}.pt"
        print(f"[1/4] 加载预训练模型: {model_path}")
    else:
        model_path = f"yolov8{model_size}.yaml"
        print(f"[1/4] 从零创建模型: {model_path}")

    model = YOLO(model_path)

    # 开始训练
    print(f"[2/4] 开始训练...")
    results = model.train(
        data=data_yaml,
        epochs=epochs,
        batch=batch_size,
        imgsz=imgsz,
        device=device if device else None,
        project=output_dir,
        name=f"blindpath_v8{model_size}_{datetime.now().strftime('%Y%m%d_%H%M%S')}",
        patience=20,           # 早停 patience
        save=True,
        save_period=10,        # 每10轮保存一次
        pretrained=pretrained,
        optimizer="AdamW",
        lr0=0.001,             # 初始学习率
        lrf=0.01,              # 最终学习率 (lr0 * lrf)
        momentum=0.937,
        weight_decay=0.0005,
        warmup_epochs=3,
        warmup_momentum=0.8,
        box=7.5,               # box loss 权重
        cls=0.5,               # classification loss 权重
        dfl=1.5,               # distribution focal loss 权重
        mosaic=1.0,            # mosaic 数据增强
        mixup=0.1,             # mixup 数据增强
        copy_paste=0.1,        # copy-paste 增强
        degrees=10.0,          # 旋转增强
        translate=0.1,         # 平移增强
        scale=0.5,             # 缩放增强
        fliplr=0.5,            # 水平翻转
        flipud=0.0,            # 不垂直翻转（地面障碍方向有意义）
        hsv_h=0.015,           # 色调增强
        hsv_s=0.7,             # 饱和度增强
        hsv_v=0.4,             # 明度增强
        verbose=True,
    )

    print(f"\n[3/4] 训练完成!")
    best_model_path = results.save_dir / "weights" / "best.pt"
    print(f"  最佳模型: {best_model_path}")
    print(f"  mAP50:    {results.results_dict.get('metrics/mAP50(B)', 'N/A')}")
    print(f"  mAP50-95: {results.results_dict.get('metrics/mAP50-95(B)', 'N/A')}")

    return best_model_path


def export_to_tflite(
    model_path: str,
    imgsz: int = 320,
    output_dir: str = "runs/export",
    half: bool = False,
):
    """
    导出模型为 TFLite 格式（Android 端推理用）

    Args:
        model_path: 训练好的 .pt 模型路径
        imgsz: TFLite 输入尺寸 (320 平衡精度和速度)
        output_dir: 导出输出目录
        half: 是否使用 FP16 量化 (需 GPU 支持)
    """
    try:
        from ultralytics import YOLO
    except ImportError:
        print("[ERROR] 请先安装 ultralytics: pip install ultralytics")
        sys.exit(1)

    print(f"\n[4/4] 导出 TFLite 模型...")
    print(f"  输入尺寸: {imgsz}x{imgsz}")
    print(f"  量化: {'FP16' if half else 'INT8 (推荐)'}")

    model = YOLO(model_path)

    export_dir = model.export(
        format="tflite",
        imgsz=imgsz,
        half=half,
        int8=True,             # INT8 量化 (更快推理)
        data=None,             # 不需要校准数据（使用默认量化）
    )

    tflite_path = Path(export_dir)
    print(f"\n[OK] TFLite 模型已导出: {tflite_path}")

    # 复制到 Android assets 目录
    assets_dir = Path("app/src/main/assets")
    if assets_dir.exists():
        import shutil
        dest = assets_dir / "blindpath_custom.tflite"
        shutil.copy2(tflite_path, dest)
        print(f"[OK] 已复制到 Android assets: {dest}")
    else:
        print(f"[INFO] Android assets 目录不存在: {assets_dir}")
        print(f"       请手动复制: {tflite_path} -> app/src/main/assets/blindpath_custom.tflite")

    return str(tflite_path)


def validate_model(model_path: str, data_yaml: str):
    """验证模型性能"""
    try:
        from ultralytics import YOLO
    except ImportError:
        print("[ERROR] 请先安装 ultralytics: pip install ultralytics")
        sys.exit(1)

    print(f"\n验证模型: {model_path}")
    model = YOLO(model_path)
    results = model.val(data=data_yaml, imgsz=640, batch=16, split="val")

    print(f"\n验证结果:")
    print(f"  mAP50:    {results.box.map50:.4f}")
    print(f"  mAP50-95: {results.box.map:.4f}")
    print(f"  Precision: {results.box.mp:.4f}")
    print(f"  Recall:    {results.box.mr:.4f}")

    return results


def generate_label_file(output_path: str):
    """生成类别标签文件（供 Android 端使用）"""
    with open(output_path, "w", encoding="utf-8") as f:
        for i in range(NUM_CLASSES):
            f.write(f"{BLINDPATH_CLASSES[i]}\n")
    print(f"[OK] 标签文件已生成: {output_path} ({NUM_CLASSES} 类)")


def generate_class_mapping_kotlin(output_path: str):
    """生成 Kotlin 类别映射代码（供 AIDetector.kt 使用）"""
    lines = [
        '// BlindPath 自定义模型类别映射',
        '// 由 train_custom_model.py 自动生成',
        '// DO NOT EDIT - 重新训练后重新生成',
        '',
        '// COCO 类别索引到 ObstacleType 的映射（自定义模型）',
        '// 注意：自定义模型的类别顺序与 COCO 不同，需要按模型输出顺序映射',
        'private val customModelToObstacle = mapOf(',
    ]

    # 生成映射代码
    custom_to_obstacle = {
        "step": "ObstacleType.STEP_UP",
        "stairs": "ObstacleType.STAIRS",
        "curb": "ObstacleType.CURB",
        "puddle": "ObstacleType.PUDDLE",
        "pothole": "ObstacleType.PIT",
        "manhole": "ObstacleType.MANHOLE",
        "car": "ObstacleType.VEHICLE",
        "bus": "ObstacleType.BUS",
        "truck": "ObstacleType.TRUCK",
        "bicycle": "ObstacleType.BICYCLE",
        "motorcycle": "ObstacleType.MOTORCYCLE",
        "e_scooter": "ObstacleType.ROAD_HAZARD",
        "person": "ObstacleType.PERSON",
        "wheelchair": "ObstacleType.PERSON",
        "stroller": "ObstacleType.ROAD_HAZARD",
        "traffic_light": "ObstacleType.TRAFFIC_LIGHT",
        "traffic_sign": "ObstacleType.TRAFFIC_SIGN",
        "fire_hydrant": "ObstacleType.PILLAR",
        "electric_pole": "ObstacleType.ELECTRIC_POLE",
        "trash_bin": "ObstacleType.ROAD_HAZARD",
        "bench": "ObstacleType.BENCH",
        "bollard": "ObstacleType.PILLAR",
        "barrier": "ObstacleType.ROAD_HAZARD",
        "handrail": "ObstacleType.HANDRAIL",
        "construction_cone": "ObstacleType.ROAD_HAZARD",
        "barricade": "ObstacleType.ROAD_HAZARD",
        "scaffold": "ObstacleType.ROAD_HAZARD",
        "dog": "ObstacleType.PET",
        "cat": "ObstacleType.PET",
        "tree": "ObstacleType.ROAD_HAZARD",
        "bush": "ObstacleType.ROAD_HAZARD",
        "potted_plant": "ObstacleType.POTTED_PLANT",
        "skateboard": "ObstacleType.ROAD_HAZARD",
        "umbrella": "ObstacleType.UMBRELLA",
        "suitcase": "ObstacleType.SUITCASE",
        "backpack": "ObstacleType.BACKPACK",
        "chair": "ObstacleType.CHAIR",
        "table": "ObstacleType.TABLE",
        "bottle": "ObstacleType.BOTTLE",
        "pole": "ObstacleType.PILLAR",
    }

    for i in range(NUM_CLASSES):
        class_name = BLINDPATH_CLASSES[i]
        obstacle_type = custom_to_obstacle.get(class_name, "ObstacleType.OBSTACLE")
        lines.append(f'    {i} to {obstacle_type},        // {class_name}')

    lines.append(')')

    with open(output_path, "w", encoding="utf-8") as f:
        f.write("\n".join(lines) + "\n")
    print(f"[OK] Kotlin 映射代码已生成: {output_path}")


def main():
    parser = argparse.ArgumentParser(description="BlindPath 自定义模型训练")
    subparsers = parser.add_subparsers(dest="command", help="可用命令")

    # train 命令
    train_parser = subparsers.add_parser("train", help="训练模型")
    train_parser.add_argument("--data", type=str, default="dataset/blindpath.yaml",
                              help="数据集 YAML 路径 (默认: dataset/blindpath.yaml)")
    train_parser.add_argument("--size", type=str, default="n", choices=["n", "s", "m", "l", "x"],
                              help="模型大小 (默认: n)")
    train_parser.add_argument("--epochs", type=int, default=100, help="训练轮数 (默认: 100)")
    train_parser.add_argument("--batch", type=int, default=16, help="批次大小 (默认: 16)")
    train_parser.add_argument("--imgsz", type=int, default=640, help="训练图像尺寸 (默认: 640)")
    train_parser.add_argument("--device", type=str, default="", help="训练设备")
    train_parser.add_argument("--no-pretrained", action="store_true", help="不从零训练")

    # export 命令
    export_parser = subparsers.add_parser("export", help="导出 TFLite")
    export_parser.add_argument("--model", type=str, required=True, help="模型路径 (.pt)")
    export_parser.add_argument("--imgsz", type=int, default=320, help="TFLite 输入尺寸 (默认: 320)")
    export_parser.add_argument("--half", action="store_true", help="FP16 量化")

    # val 命令
    val_parser = subparsers.add_parser("val", help="验证模型")
    val_parser.add_argument("--model", type=str, required=True, help="模型路径 (.pt)")
    val_parser.add_argument("--data", type=str, required=True, help="数据集 YAML")

    # prepare 命令
    prepare_parser = subparsers.add_parser("prepare", help="准备训练文件")
    prepare_parser.add_argument("--data-dir", type=str, default="dataset",
                               help="数据集目录 (默认: dataset)")

    args = parser.parse_args()

    if args.command == "train":
        train_model(
            data_yaml=args.data,
            model_size=args.size,
            epochs=args.epochs,
            batch_size=args.batch,
            imgsz=args.imgsz,
            pretrained=not args.no_pretrained,
            device=args.device,
        )

    elif args.command == "export":
        export_to_tflite(
            model_path=args.model,
            imgsz=args.imgsz,
            half=args.half,
        )

    elif args.command == "val":
        validate_model(model_path=args.model, data_yaml=args.data)

    elif args.command == "prepare":
        yaml_path = create_dataset_yaml(args.data_dir, os.path.join(args.data_dir, "blindpath.yaml"))
        generate_label_file(os.path.join(args.data_dir, "classes.txt"))
        generate_class_mapping_kotlin(os.path.join(args.data_dir, "ClassMapping.kt"))

    else:
        parser.print_help()
        print("\n快速开始:")
        print("  1. 准备数据集并标注（见 README_training.md）")
        print("  2. python train_custom_model.py prepare --data-dir dataset")
        print("  3. python train_custom_model.py train --data dataset/blindpath.yaml --epochs 100")
        print("  4. python train_custom_model.py export --model runs/train/best/weights/best.pt --imgsz 320")
        print("  5. 将生成的 .tflite 文件复制到 app/src/main/assets/")


if __name__ == "__main__":
    main()
