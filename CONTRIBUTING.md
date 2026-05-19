# 贡献指南

感谢您对 BlindPath 项目的关注！我们欢迎所有形式的贡献。

## 目录

- [行为准则](#行为准则)
- [快速开始](#快速开始)
- [开发环境](#开发环境)
- [开发流程](#开发流程)
- [代码规范](#代码规范)
- [测试要求](#测试要求)
- [提交规范](#提交规范)
- [Pull Request 流程](#pull-request-流程)

---

## 行为准则

我们期望所有贡献者都遵守以下行为准则：

- **友好交流**：尊重他人，使用包容性语言
- **专业反馈**：建设性地提出意见
- **专注于项目**：关注对项目最有价值的工作
- **遵守规范**：遵循本指南中的所有规范

---

## 快速开始

### 1. Fork 项目

点击 GitHub 页面的 `Fork` 按钮，创建一个属于您自己的 Fork。

### 2. 克隆代码

```bash
git clone https://github.com/<your-username>/BlindPath.git
cd BlindPath
```

### 3. 添加上游仓库

```bash
git remote add upstream https://github.com/majunhb/BlindPath.git
```

### 4. 创建功能分支

```bash
git checkout -b feature/your-feature-name
```

---

## 开发环境

### 环境要求

| 工具 | 版本要求 |
|------|----------|
| JDK | 17+ |
| Android Gradle Plugin | 8.2.0 |
| Android SDK | API 34 |
| Kotlin | 1.9.20 |
| Node.js | 18+ (可选，用于文档构建) |

### 开发工具推荐

- **Android Studio** Hedgehog (2023.1.1) 或更高版本
- **IntelliJ IDEA** (社区版也支持)
- 安装 Kotlin 插件

### 环境变量

确保以下环境变量已配置：

```bash
export ANDROID_HOME=/path/to/android/sdk
export JAVA_HOME=/path/to/jdk17
```

---

## 开发流程

### 1. 保持同步

在开始新功能之前，确保您的分支与上游同步：

```bash
git checkout main
git fetch upstream
git merge upstream/main
git push origin main
```

### 2. 创建功能分支

```bash
git checkout -b feature/your-feature-name
```

### 3. 开发

按照代码规范进行开发。

### 4. 运行测试

```bash
# 运行所有单元测试
./gradlew testDebugUnitTest

# 运行特定模块的测试
./gradlew :module_obstacle:testDebugUnitTest
```

### 5. 运行 Lint

```bash
./gradlew lintDebug
```

### 6. 提交代码

遵循 [提交规范](#提交规范) 提交代码。

### 7. 推送并创建 PR

```bash
git push origin feature/your-feature-name
```

然后在 GitHub 上创建 Pull Request。

---

## 代码规范

请在提交代码前确保符合以下规范：

### Kotlin 编码规范

- 使用 Kotlin 官方编码规范
- 遵循本项目的 [CODE_STYLE.md](docs/CODE_STYLE.md)
- 代码格式化：使用 `Ctrl+Alt+L` (Windows/Linux) 或 `Cmd+Option+L` (macOS)

### 文件组织

```
模块/src/main/java/com/blindpath/<module_name>/
├── di/                    # 依赖注入模块
├── domain/                # 领域层（接口）
│   ├── model/            # 数据模型
│   └── repository/       # Repository 接口
├── data/                  # 数据层（实现）
│   └── repository/       # Repository 实现
├── service/               # 服务层（前台服务）
└── ui/                    # UI 层
    ├── viewmodel/        # ViewModel
    └── screen/           # Compose Screen
```

### 命名规范

| 类型 | 规范 | 示例 |
|------|------|------|
| 类/接口 | PascalCase | `ObstacleService` |
| 函数/变量 | camelCase | `startDetection()` |
| 常量 | UPPER_SNAKE_CASE | `NOTIFICATION_ID` |
| 包名 | 全小写 | `com.blindpath.base` |

---

## 测试要求

### 测试覆盖率要求

| 模块类型 | 最低覆盖率 |
|----------|-----------|
| ViewModel | 80% |
| Repository | 70% |
| Service | 60% |
| 其他 | 50% |

### 编写测试

```kotlin
// 正确的测试命名
@Test
fun `should speak when instruction changes`() {
    // Given
    val instruction = "左转"
    
    // When
    val result = shouldSpeak(instruction)
    
    // Then
    assertTrue(result)
}

// 测试类和文件名对应
class MyClassTest {
    @Test
    fun `method should do something`() { ... }
}
```

### Mock 使用

```kotlin
@ExtendWith(MockKExtension::class)
class MyServiceTest {
    @MockK
    lateinit var myRepository: MyRepository
    
    @BeforeEach
    fun setup() {
        MockKAnnotations.init(this)
    }
    
    @Test
    fun `test scenario`() {
        // Given
        every { myRepository.getData() } returns mockData()
        
        // When
        val result = service.process()
        
        // Then
        assertEquals(expected, result)
        verify { myRepository.getData() }
    }
}
```

---

## 提交规范

### 提交信息格式

```
<类型>: <简短描述>

[可选的详细说明]

[可选的 Footer]
```

### 类型

| 类型 | 说明 | 示例 |
|------|------|------|
| `feat` | 新功能 | `feat: 添加障碍物检测功能` |
| `fix` | Bug 修复 | `fix: 修复导航播报重复问题` |
| `docs` | 文档更新 | `docs: 更新架构文档` |
| `style` | 代码格式 | `style: 格式化代码` |
| `refactor` | 重构 | `refactor: 优化 Service 结构` |
| `test` | 测试相关 | `test: 添加 ViewModel 测试` |
| `chore` | 构建/工具 | `chore: 更新依赖版本` |

### 示例

```
feat: 添加避障预警防重复逻辑

- 实现 3 秒间隔内的预警防重复
- 添加测试用例验证防重复逻辑

Closes #12
```

### 规则

1. 标题不超过 72 字符
2. 使用中文描述
3. 详细说明使用多行描述
4. 引用相关 Issue

---

## Pull Request 流程

### PR 前检查清单

- [ ] 代码符合代码规范
- [ ] 所有测试通过
- [ ] Lint 检查通过
- [ ] 添加了必要的测试
- [ ] 更新了相关文档
- [ ] 提交信息符合规范

### PR 描述模板

```markdown
## 描述
<!-- 简要描述这个 PR 的内容 -->

## 改动
<!-- 列出主要的改动点 -->

## 测试
<!-- 描述如何测试这些改动 -->

## 截图/录屏
<!-- 如果有 UI 改动，添加截图 -->

## 相关 Issue
<!-- 引用相关的 Issue -->
Fixes #<issue-number>
```

### Code Review

1. 等待 CI 通过
2. 等待维护者 Code Review
3. 根据反馈进行调整
4. 获得 Approval 后合并

---

## 问题反馈

如果您发现 Bug 或有新功能建议：

1. 搜索现有 Issue，确保不是重复
2. 创建新 Issue，描述详细
3. 使用 Issue 模板

---

## 许可

通过贡献代码，您同意您的代码将按照项目的 MIT 许可证授权。

---

感谢您的贡献！🙏
