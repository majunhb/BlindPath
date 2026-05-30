# Porcupine 唤醒词模型文件目录

此目录存放 Porcupine 唤醒词模型文件（.ppn 格式）。

## 如何获取唤醒词模型

### 方法1：使用预训练模型（推荐）

访问 Picovoice Console：https://picovoice.ai/console/

1. 注册账号并登录
2. 创建新项目
3. 在 "Wake Word" 页面选择预训练唤醒词：
   - "Hey Assistant"（推荐）
   - "Porcupine"
   - "Bumblebee"
   - "Alexa"
   - "Computer"
   - "Jarvis"
4. 下载 Android 平台的 .ppn 文件
5. 将文件放入此目录

### 方法2：训练自定义唤醒词

在 Picovoice Console 中：
1. 选择 "Custom Wake Word"
2. 输入唤醒词（如 "Xiao Zhi"）
3. 选择语言（英文效果最佳）
4. 训练并下载 .ppn 文件

## 文件命名规范

```
keywords/
├── hey-assistant_android.ppn    # Hey Assistant（推荐）
├── porcupine_android.ppn        # Porcupine
└── custom_xiaozhi_android.ppn   # 自定义唤醒词
```

## 注意事项

- Porcupine 对纯中文支持有限，建议使用英文唤醒词
- 每个唤醒词模型文件约 100KB-500KB
- 模型文件与 Access Key 绑定，需在 Console 中获取

## 免费额度

- 每月 10,000 次 Porcupine 请求
- 个人/小项目完全足够