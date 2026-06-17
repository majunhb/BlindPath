# BlindPath PR Review Checklist

> **使用方式**：在 GitHub 仓库 `.github/PULL_REQUEST_TEMPLATE.md` 放置此文件，
> 每次创建 PR 时会自动填充。

## 基础检查

- [ ] 代码通过本地编译 `./gradlew assembleDebug`
- [ ] Detekt 无新增 error `./gradlew detekt`
- [ ] ktlint 格式正确 `./gradlew ktlintCheck`
- [ ] 没有遗留 `TODO()`、`FIXME:` 或 `println` 调试语句
- [ ] 日志使用 Timber，无 `android.util.Log` 直接调用

---

## 架构与设计

- [ ] 新增类/函数遵循单一职责原则
- [ ] SDK 调用（高德/TFLite）封装在 Repository 层，未直接暴露给 ViewModel
- [ ] 协程 Dispatcher 通过 Hilt 注入，未使用 `GlobalScope`
- [ ] Flow 收集在合适的 LifecycleScope，不存在潜在内存泄漏
- [ ] 新增 Hilt 依赖注入遵循已有模块结构

---

## 障碍检测 / 导航安全（视障用户安全关键）

> 任何涉及检测结果处理、语音播报、导航路径的变更必须通过以下检查

- [ ] 置信度阈值修改有明确理由说明（当前 confidence=0.4，IoU=0.5）
- [ ] **漏检（false negative）比误报（false positive）更危险**，确认修改倾向于多报而非少报
- [ ] 语音播报触发逻辑变更已验证延迟 ≤ 500ms
- [ ] 弱网/无网/模型未加载场景有明确的降级处理
- [ ] 没有静默吞掉异常（空 catch 块）
- [ ] 相机/传感器资源在 onStop/onDestroy 时正确释放

---

## 代码质量

- [ ] 方法行数 ≤ 50 行（Detekt LongMethod 规则）
- [ ] 类行数 ≤ 400 行（Detekt LargeClass 规则）
- [ ] 没有硬编码魔法数字，常量已定义在 companion object 或顶层常量
- [ ] 变量/函数命名清晰，无缩写（除行业公认缩写如 tts、asr、map）
- [ ] 新增公共 API 有 KDoc 注释

---

## 测试（鼓励但不强制，未来会加门禁）

- [ ] 新增业务逻辑是否可以添加单元测试？（若是，建议补充）
- [ ] 现有测试仍然通过 `./gradlew test`
- [ ] 关键边界场景（空列表、网络超时、权限拒绝）是否已考虑

---

## 提交说明

**变更类型：**
- [ ] feat：新功能
- [ ] fix：Bug 修复
- [ ] refactor：重构（不影响功能）
- [ ] perf：性能优化
- [ ] test：测试相关
- [ ] ci：CI/CD 配置
- [ ] docs：文档更新

**变更摘要（一句话）：**


**测试方式：**


**相关 Issue（如有）：** #
