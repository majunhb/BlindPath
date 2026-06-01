# BlindPath P1 修复完成

## 4 项 P1 修复，6 个文件

| 修复 | 文件 | 变更说明 |
|------|------|---------|
| ① Impl 注入修复 | MainViewModel.kt, ObstacleService.kt, NavigationService.kt | `*Impl` → 接口 |
| ② LifecycleScope | ObstacleService.kt, NavigationService.kt | 继承 LifecycleService，移除自建 CoroutineScope |
| ③ Bitmap GC | AIDetector.kt, ObstacleRepositoryImpl.kt | 复用 ByteBuffer + 直接 YUV->RGB |
| ④ 测试阈值 | NavigationServiceTest.kt | 5m → 3m 对齐生产代码 |

## GitHub
- P1 commit: `d60d0b9`
- 总 changelog: P0(17 files) + P1(6 files) = 23 files
