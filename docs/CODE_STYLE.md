# BlindPath 代码规范

## 1. 命名规范

### 1.1 类和接口

- **PascalCase** 首字母大写
- **示例**：`ObstacleService`, `VoiceRepository`, `MainViewModel`

### 1.2 函数和变量

- **camelCase** 驼峰命名
- **示例**：`startObstacle()`, `isRunning`, `voiceRepository`

### 1.3 常量

- **UPPER_SNAKE_CASE** 全大写下划线分隔
- **示例**：`NOTIFICATION_ID`, `CHANNEL_OBSTACLE`, `MAX_RETRY_COUNT`

### 1.4 Kotlin 特定

| 类型 | 规范 | 示例 |
|------|------|------|
| 包名 | 全小写 | `com.blindpath.module_obstacle` |
| 枚举 | UPPER_SNAKE_CASE | `AlertLevel.WARNING` |
| 扩展函数 | camelCase | `fun View.dpToPx()` |
| 协程作用域 | `scope` 后缀 | `serviceScope`, `viewModelScope` |

---

## 2. 注释规范

### 2.1 KDoc 格式（推荐用于公共 API）

```kotlin
/**
 * 描述这个函数的作用
 *
 * @param param1 第一个参数说明
 * @param param2 第二个参数说明
 * @return 返回值说明
 * @throws ExceptionType 当发生某种情况时抛出
 */
fun myFunction(param1: String, param2: Int): Boolean
```

### 2.2 单行注释

```kotlin
// 单行注释用于解释复杂逻辑

// TODO: 未来需要优化
// FIXME: 已知问题需要修复
```

### 2.3 文档注释（用于文档生成）

```kotlin
/**
 * ## 避障前台服务
 *
 * 在后台持续运行摄像头和AI检测，
 * 发现障碍物时通过语音+振动提醒视障用户。
 *
 * ### 使用方式
 * ```kotlin
 * val intent = Intent(context, ObstacleService::class.java).apply {
 *     action = ObstacleService.ACTION_START
 * }
 * context.startForegroundService(intent)
 * ```
 */
class ObstacleService : Service()
```

---

## 3. 架构规范

### 3.1 模块划分

```
BlindPath/
├── app/              # 主应用模块（入口）
├── base/             # 基础共享模块（通用工具、扩展）
├── module_obstacle/  # 障碍物检测功能模块
├── module_navigation/# 导航功能模块
├── module_voice/     # 语音播报功能模块
├── module_settings/  # 设置功能模块
└── module_community/ # 社区功能模块
```

### 3.2 包结构（按模块）

```
com.blindpath.[module_name]/
├── di/                    # Hilt 依赖注入模块
├── domain/                # 领域层（接口定义）
│   ├── model/            # 数据模型
│   └── repository/       # Repository 接口
├── data/                  # 数据层（接口实现）
│   └── repository/       # Repository 实现
├── service/               # 服务层（前台服务）
└── ui/                    # UI 层（Compose/ViewModel）
```

### 3.3 MVVM 架构

```
┌─────────────────────────────────────────────────┐
│                      UI Layer                   │
│  ┌─────────────┐     ┌────────────────────────┐ │
│  │  Compose    │ ←── │      ViewModel         │ │
│  │    UI       │     │  (StateFlow/State)     │ │
│  └─────────────┘     └────────────────────────┘ │
└─────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────┐
│                  Domain Layer                   │
│  ┌──────────────────────────────────────────┐  │
│  │            Repository (接口)              │  │
│  └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
                          ↓
┌─────────────────────────────────────────────────┐
│                   Data Layer                    │
│  ┌──────────────────────────────────────────┐  │
│  │         Repository (实现)                │  │
│  └──────────────────────────────────────────┘  │
└─────────────────────────────────────────────────┘
```

### 3.4 依赖注入原则

- **使用 Hilt** 进行依赖注入
- Repository 层使用 `@Singleton`
- ViewModel 使用 `@HiltViewModel`
- Activity/Service 使用 `@AndroidEntryPoint`

---

## 4. 协程使用规范

### 4.1 协程作用域

| 场景 | 应使用 |
|------|--------|
| ViewModel | `viewModelScope` |
| Service | `serviceScope` (自定义) |
| Fragment/Activity | `lifecycleScope` |

### 4.2 错误示例

```kotlin
// ❌ 错误：创建独立的作用域，不会随生命周期销毁
CoroutineScope(Dispatchers.Main).launch { ... }

// ❌ 错误：在主线程执行 IO 操作
val data = api.fetchData() // 网络请求在主线程
```

### 4.3 正确示例

```kotlin
// ✅ 正确：使用 lifecycleScope
lifecycleScope.launch {
    val data = withContext(Dispatchers.IO) {
        api.fetchData() // IO 操作在 IO 线程
    }
}

// ✅ 正确：Service 中使用 serviceScope
private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
```

### 4.4 Dispatcher 选择

| 操作类型 | Dispatcher |
|----------|------------|
| UI 更新 | `Dispatchers.Main` |
| 网络请求 | `Dispatchers.IO` |
| CPU 密集型 | `Dispatchers.Default` |

---

## 5. Git 提交规范

### 5.1 提交信息格式

```
<类型>: <简短描述>

[可选的详细说明]
```

### 5.2 提交类型

| 类型 | 说明 |
|------|------|
| `feat` | 新功能 |
| `fix` | Bug 修复 |
| `docs` | 文档变更 |
| `style` | 代码格式（不影响功能） |
| `refactor` | 重构（不修复 bug） |
| `test` | 测试相关 |
| `chore` | 构建/工具变更 |

### 5.3 示例

```
feat: 添加障碍物检测服务

- 实现 ObstacleService 前台服务
- 集成 TensorFlow Lite 进行实时检测
- 添加语音+振动预警反馈

Closes #12
```

---

## 6. 测试规范

### 6.1 测试文件位置

```
module_name/src/test/java/.../
├── repository/        # Repository 测试
├── service/          # Service 测试
└── ui/               # ViewModel 测试
```

### 6.2 测试命名

```kotlin
@Test
fun `should speak when instruction changes`() { ... }

@Test
fun `handleAlert should not repeat same alert within interval`() { ... }
```

### 6.3 测试覆盖率目标

| 模块 | 最低覆盖率 |
|------|-----------|
| ViewModel | 80% |
| Repository | 70% |
| Service | 60% |

---

## 7. Lint 检查规范

### 7.1 必须修复的 Lint 错误

- `LintError` - 编译错误
- `CorrectnessError` - 正确性问题
- `SecurityError` - 安全问题

### 7.2 警告级别

- `Warning` - 建议修复
- `Informational` - 信息提示

### 7.3 在 CI 中强制检查

```bash
./gradlew lintDebug
# abortOnError = true
```

---

## 8. 资源文件规范

### 8.1 字符串资源

```xml
<!-- ✅ 正确：使用清晰的前缀 -->
<string name="obstacle_notification_title">避障功能运行中</string>
<string name="navigation_distance_format">距离目标%d米</string>

<!-- ❌ 错误：缺少前缀，容易冲突 -->
<string name="title">主界面</string>
```

### 8.2 颜色资源

```xml
<!-- ✅ 正确：使用语义化命名 -->
<color name="alert_danger">#FF0000</color>
<color name="status_active">#4CAF50</color>
```

---

## 9. 性能规范

### 9.1 避免内存泄漏

- 使用 `lifecycleScope` 替代独立 `CoroutineScope`
- Service 销毁时调用 `scope.cancel()`
- 避免在 `onDestroy()` 后持有 Context 引用

### 9.2 前台服务规范

- 必须显示前台通知
- 使用 `START_STICKY` 确保服务被杀死后重启
- 及时停止不需要的服务

---

## 10. 安全规范

### 10.1 权限声明

```xml
<!-- ✅ 正确：声明所有需要的权限 -->
<uses-permission android:name="android.permission.CAMERA" />
<uses-permission android:name="android.permission.ACCESS_FINE_LOCATION" />
```

### 10.2 运行时权限检查

```kotlin
// ✅ 正确：在使用权限前检查
if (checkSelfPermission(CAMERA) == PERMISSION_GRANTED) {
    // 使用相机
} else {
    // 请求权限
}
```

---

*最后更新：2026-05-16*
