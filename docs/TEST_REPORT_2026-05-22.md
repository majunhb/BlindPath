# BlindPath 测试报告与修复记录

**日期**: 2026年5月22日  
**版本**: v1.0-beta  
**测试人员**: 用户实测

---

## 一、首次测试反馈

### 1.1 问题汇总

| 序号 | 功能模块 | 问题描述 | 严重程度 |
|------|---------|---------|---------|
| 1 | 障碍物检测 | 无法正常识别检测 | 严重 |
| 2 | 相机授权 | 授权时出现闪退 | 严重 |
| 3 | 实时定位 | 定位失效，连接失败 | 严重 |
| 4 | 智能导航 | 仅为虚拟界面，无真实导航 | 严重 |
| 5 | 紧急救助 | 运行稳定，可正常触发 | 正常 |
| 6 | 社区/出行板块 | 虚拟页面，无真实业务 | 中等 |

### 1.2 整体评价

产品成熟度偏低，核心实用功能基本瘫痪，暂不具备实际落地使用条件。

---

## 二、问题诊断与修复

### 2.1 相机授权闪退

**根因分析**:
1. 缺少摄像头硬件存在性检查
2. 缺少摄像头可用性验证
3. 异常捕获不完善
4. 缺少详细日志输出

**修复方案**:
- 添加 `PackageManager.FEATURE_CAMERA` 检查
- 添加 `ProcessCameraProvider.hasCamera()` 验证
- 增强异常捕获和错误提示
- 添加 Timber 日志输出

**修复文件**: `ObstacleRepositoryImpl.kt`

**修复代码**:
```kotlin
// 检查摄像头硬件是否存在
if (!packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)) {
    Timber.e("No camera hardware available")
    _state.update { it.copy(lastError = "设备没有摄像头硬件") }
    return false
}

// 验证摄像头是否存在
val hasCamera = provider.hasCamera(cameraSelector)
if (!hasCamera) {
    Timber.e("Camera not available")
    _state.update { it.copy(lastError = "${if (useFrontCamera) "前置" else "后置"}摄像头不可用") }
    return false
}
```

---

### 2.2 实时定位失效

**根因分析**:
1. 高德 SDK 未初始化隐私合规（Android 11+ 必需）
2. 缺少详细的错误码解析
3. 错误提示不明确
4. 定位超时时间过短

**修复方案**:
- 在 `BlindPathApp` 中添加隐私合规初始化
- 添加详细的错误码解析（错误码 4/5/6/7/12/13）
- 提供具体的错误原因和解决建议
- 增加定位超时时间（10s → 15s）

**修复文件**: `BlindPathApp.kt`, `LocationScreen.kt`, `NavigationScreen.kt`

**修复代码**:
```kotlin
// BlindPathApp.kt
private fun initAMapSDK() {
    try {
        AMapLocationClient.updatePrivacyShow(this, true, true)
        AMapLocationClient.updatePrivacyAgree(this, true)
        Timber.d("AMap SDK initialized successfully")
    } catch (e: Exception) {
        Timber.e(e, "Failed to initialize AMap SDK")
    }
}

// LocationScreen.kt
val errorMsg = when (errorCode) {
    4 -> "网络连接失败，请检查网络设置"
    5 -> "GPS未开启，请在设置中开启GPS"
    6 -> "定位权限被拒绝，请在设置中授权"
    7 -> "定位失败，请到开阔区域重试"
    12 -> "缺少定位权限，请在设置中授权"
    13 -> "定位服务未开启，请在设置中开启定位"
    else -> "定位失败($errorCode)：$errorInfo"
}
```

---

### 2.3 障碍物检测无法识别

**根因分析**:
1. YOLOv8 TFLite 模型文件缺失
2. GitHub 下载链接失效（404）
3. 代码中有重复的 `initialize()` 方法

**修复方案**:
- 使用 ML Kit Object Detection 作为回退方案
- 修复重复的 `initialize()` 方法定义
- 改进模型加载失败时的处理逻辑
- 添加 `isModelLoaded()` 检查

**修复文件**: `ObstacleRepositoryImpl.kt`, `AIDetector.kt`

**修复代码**:
```kotlin
// AIDetector.kt - ML Kit 回退
try {
    val options = ObjectDetectorOptions.Builder()
        .setDetectorMode(ObjectDetectorOptions.SINGLE_IMAGE_MODE)
        .enableMultipleObjects()
        .build()
    mlKitDetector = ObjectDetection.getClient(options)
    useMlKit = true
    isLoaded = true
    Timber.d("使用ML Kit目标检测作为回退方案")
    return true
} catch (e: Exception) {
    Timber.e(e, "ML Kit回退也失败了")
    isLoaded = false
    return false
}
```

---

### 2.4 导航界面改进

**修复方案**:
- 添加使用说明卡片
- 改进错误提示信息
- 提供具体的排查建议

**修复文件**: `NavigationScreen.kt`

---

## 三、修复后的技术架构

### 3.1 障碍物检测流程

```
启动检测
  ↓
初始化 AI 检测器
  ↓
尝试加载 YOLOv8 TFLite 模型
  ├─ 成功 → 使用 TFLite 推理
  └─ 失败 → 回退到 ML Kit Object Detection
  ↓
启动摄像头
  ├─ 检查摄像头硬件
  ├─ 验证摄像头可用性
  └─ 绑定到生命周期
  ↓
实时检测障碍物
```

### 3.2 定位流程

```
应用启动
  ↓
初始化高德 SDK
  ├─ updatePrivacyShow()
  └─ updatePrivacyAgree()
  ↓
请求定位权限
  ↓
创建 AMapLocationClient
  ↓
配置定位参数
  ├─ 高精度模式（GPS + 北斗 + 网络）
  ├─ 超时时间 15s
  └─ 需要地址信息
  ↓
启动定位
  ↓
处理定位结果
  ├─ 成功 → 提取位置信息
  └─ 失败 → 解析错误码，提供解决建议
```

---

## 四、后续测试建议

### 4.1 功能测试清单

- [ ] **相机功能**
  - [ ] 授权时不闪退
  - [ ] 能正常显示预览画面
  - [ ] 切换前后摄像头正常

- [ ] **定位功能**
  - [ ] 能正常获取位置信息
  - [ ] 错误提示清晰明确
  - [ ] 在开阔区域定位成功率高

- [ ] **障碍物检测**
  - [ ] ML Kit 回退方案正常工作
  - [ ] 能检测到常见障碍物（人、车、自行车等）
  - [ ] 语音播报正常

- [ ] **导航功能**
  - [ ] 能正常规划路线
  - [ ] 语音导航播报正常
  - [ ] 地图显示正常

- [ ] **紧急救助**
  - [ ] SOS 功能正常
  - [ ] 紧急联系人拨打正常

### 4.2 性能测试

- [ ] CPU 占用率
- [ ] 内存占用
- [ ] 电池消耗
- [ ] 发热情况

### 4.3 兼容性测试

- [ ] Android 8.0 - 14
- [ ] 不同品牌手机（华为、小米、OPPO、vivo 等）
- [ ] 不同屏幕尺寸

---

## 五、已知限制

### 5.1 障碍物检测

- **ML Kit 回退方案**: 无法检测台阶、坑洼等地面障碍物
- **建议**: 后续可手动导出 YOLOv8 TFLite 模型以获得完整功能

### 5.2 社区/出行板块

- **前端已完成**: UI 界面和交互逻辑
- **需要后端支持**: 
  - 志愿者匹配服务
  - 天气 API（和风天气）
  - 无障碍设施数据库

---

## 六、构建与部署

### 6.1 构建状态

- **仓库**: https://github.com/majunhb/BlindPath
- **最新提交**: b87fb76
- **构建状态**: 进行中

### 6.2 下载 APK

1. 访问 GitHub Actions 页面
2. 等待构建完成（约 5-10 分钟）
3. 下载 `app-debug` artifact
4. 安装到测试设备

---

## 七、联系方式

如有问题或建议，请联系开发团队。

---

**更新时间**: 2026-05-22 18:30
