# BlindPath 自定义模型训练指南

## 问题背景

BlindPath 当前使用 YOLOv8n + COCO 80 类数据集进行障碍物检测。COCO 数据集是为通用目标检测设计的，
**不包含视障导航所需的关键障碍物类别**，导致以下物体无法识别：

| 需求 | COCO 支持 | 说明 |
|------|-----------|------|
| 台阶/楼梯 | ❌ | COCO 无此类别 |
| 路沿/马路牙子 | ❌ | COCO 无此类别 |
| 水坑 | ❌ | COCO 无此类别 |
| 坑洼/井盖 | ❌ | COCO 无此类别 |
| 电线杆 | ❌ | COCO 无此类别 |
| 垃圾桶 | ❌ | COCO 无此类别 |
| 施工围挡 | ❌ | COCO 无此类别 |
| 石墩/隔离桩 | ❌ | COCO 无此类别 |
| 树木/灌木 | ❌ | COCO 无此类别 |
| 猫/狗（宠物） | ✅ | COCO class 15/16 |
| 红绿灯 | ✅ | COCO class 9 |
| 自行车/汽车 | ✅ | COCO class 1/2 |

## 解决方案

在 COCO 预训练基础上微调（Fine-tune）YOLOv8，添加视障导航专用类别。

### 目标类别（40 类）

```
地面障碍 (6): step, stairs, curb, puddle, pothole, manhole
交通工具 (6): car, bus, truck, bicycle, motorcycle, e-scooter
行人相关 (3): person, wheelchair, stroller
交通设施 (7): traffic_light, traffic_sign, fire_hydrant, electric_pole,
             trash_bin, bench, bollard
安全设施 (4): barrier, handrail, construction_cone, barricade, scaffold
宠物动物 (2): dog, cat
绿化植物 (3): tree, bush, potted_plant
路面物品 (9): skateboard, umbrella, suitcase, backpack, chair, table, bottle, pole
```

---

## 训练流程

### 第一步：准备数据集

#### 目录结构

```
dataset/
├── blindpath.yaml          # 数据集配置（脚本自动生成）
├── classes.txt             # 类别标签（脚本自动生成）
├── images/
│   ├── train/              # 训练图片 (80%)
│   │   ├── img001.jpg
│   │   └── ...
│   ├── val/                # 验证图片 (10%)
│   └── test/               # 测试图片 (10%)
└── labels/
    ├── train/              # 训练标签 (YOLO 格式)
    │   ├── img001.txt
    │   └── ...
    ├── val/
    └── test/
```

#### YOLO 标签格式

每张图片对应一个 `.txt` 文件，每行一个目标：

```
# 格式: class_id center_x center_y width height (归一化到 0-1)
# class_id 对应 train_custom_model.py 中 BLINDPATH_CLASSES 的键值

# 示例：图片中有 1 辆汽车和 1 个行人
2 0.5 0.6 0.3 0.4      # car (class_id=2)
12 0.2 0.4 0.1 0.5     # person (class_id=12)
```

#### 数据来源

1. **开源数据集**（推荐）：
   - [BDD100K](https://bdd-data.berkeley.edu/) - 包含交通场景，有丰富的交通设施标注
   - [Cityscapes](https://www.cityscapes-dataset.com/) - 城市街景，有语义分割和实例标注
   - [Roboflow Universe](https://universe.roboflow.com/) - 搜索 "stairs", "pothole", "curb" 等关键词
   - [Kaggle](https://www.kaggle.com/) - 搜索 "obstacle detection", "stair detection" 等

2. **自行采集**（补充）：
   - 使用手机摄像头拍摄实际道路场景
   - 使用 [Label Studio](https://labelstud.io/) 或 [CVAT](https://github.com/opencv/cvat) 标注
   - 每个类别建议至少 200 张图片，关键类别（台阶、坑洼）建议 500+

3. **数据增强**（自动）：
   - 训练脚本已内置 mosaic、mixup、色彩抖动等增强
   - 地面障碍（台阶、坑洼）禁用了垂直翻转（flipud=0），因为方向有意义

### 第二步：生成训练配置

```bash
python train_custom_model.py prepare --data-dir dataset
```

这会生成：
- `dataset/blindpath.yaml` - YOLO 数据集配置
- `dataset/classes.txt` - 类别标签列表
- `dataset/ClassMapping.kt` - Android 端使用的 Kotlin 映射代码

### 第三步：训练模型

```bash
# 基础训练（推荐先试一轮）
python train_custom_model.py train --data dataset/blindpath.yaml --epochs 50

# 完整训练（GPU 环境）
python train_custom_model.py train \
    --data dataset/blindpath.yaml \
    --size s \               # 使用 YOLOv8s（比 nano 更精确，适合训练）
    --epochs 100 \
    --batch 16 \
    --device 0               # 使用 GPU

# 使用更大模型（精度更高，推理更慢）
python train_custom_model.py train \
    --data dataset/blindpath.yaml \
    --size m \
    --epochs 150 \
    --batch 8                # 大模型减小 batch
```

**模型大小选择**：
| 模型 | 参数量 | TFLite 大小 | 推理速度 (手机) | 推荐场景 |
|------|--------|-------------|----------------|---------|
| YOLOv8n | 3.2M | ~6MB | 最快 (~30ms) | 实时检测首选 |
| YOLOv8s | 11.2M | ~18MB | 快 (~60ms) | 精度与速度平衡 |
| YOLOv8m | 25.9M | ~50MB | 中等 (~120ms) | 高精度需求 |

**训练环境要求**：
- Python 3.8+
- pip install ultralytics
- 推荐：NVIDIA GPU + CUDA（CPU 训练很慢，约 50x 差距）
- Google Colab 免费 GPU 也是一个好选择

### 第四步：验证模型

```bash
python train_custom_model.py val \
    --model runs/train/blindpath_v8s_XXXXXXXX/weights/best.pt \
    --data dataset/blindpath.yaml
```

关注指标：
- **mAP50** > 0.7 为可用，> 0.85 为优秀
- **Recall** > 0.8 为可用（对于安全关键应用更重要，宁可误报不可漏报）
- **Precision** > 0.6 为可用

### 第五步：导出 TFLite

```bash
python train_custom_model.py export \
    --model runs/train/blindpath_v8s_XXXXXXXX/weights/best.pt \
    --imgsz 320              # 320x320 平衡精度和速度
```

导出后文件位于 `runs/export/` 目录，将其复制到 `app/src/main/assets/blindpath_custom.tflite`。

### 第六步：更新 Android 代码

1. 将 `dataset/ClassMapping.kt` 中生成的映射代码替换到 `AIDetector.kt` 中
2. 修改 `AppConfig.AIDetection.MODEL_NAME` 为 `blindpath_custom.tflite`
3. 修改 `postProcess()` 中的输出维度：
   - COCO 模型：`Array(1) { Array(84) { FloatArray(8400) } }` (80类 + 4坐标)
   - 自定义模型：`Array(1) { Array(44) { FloatArray(8400) } }` (40类 + 4坐标)

---

## 常见问题

### Q: 没有标注数据，能直接用吗？
A: 代码层面的改进（COCO 映射扩展 + 未映射类降级上报）已经提升了体验。
自定义模型是进阶方案，需要准备标注数据。

### Q: 训练需要多少数据？
A: 微调模式下（基于 COCO 预训练），每个新增类别 100-200 张即可达到可用水平。
总计建议 3000-5000 张图片。

### Q: 如何在 Google Colab 训练？
A: 上传数据集到 Google Drive，挂载 Drive 后直接运行训练命令。
Colab 免费 T4 GPU 训练 YOLOv8s 100 轮约 2-3 小时。

### Q: 手机端推理速度够快吗？
A: YOLOv8n + 320x320 + INT8 量化在主流 Android 手机上约 20-30ms/帧，
满足实时检测需求（>30fps）。YOLOv8s 约 50-80ms，也基本可用。
