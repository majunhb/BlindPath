package com.blindpath.app.ui.theme

import androidx.compose.ui.graphics.Color

// ===== 高对比无障碍配色（WCAG AAA 7:1+） =====

// --- 亮色主题 ---
val LightBackground = Color(0xFFF5F5FA)
val LightSurface = Color(0xFFFFFFFF)
val LightOnSurface = Color(0xFF1A1A2E)       // 深色文字，对比度 15:1
val LightOnSurfaceVariant = Color(0xFF4A4A60) // 次要文字，对比度 8:1
val LightPrimary = Color(0xFF0066CC)          // 主强调色
val LightSurfaceVariant = Color(0xFFEDEDF4)

// --- 暗色主题（视障用户核心场景）---
val DarkBackground = Color(0xFF0D0D18)        // 极深底色
val DarkSurface = Color(0xFF1A1A2E)           // 卡片/面板底色
val DarkOnSurface = Color(0xFFFFFFFF)         // 纯白文字，对比度 18:1
val DarkOnSurfaceVariant = Color(0xFFB8B8D0)  // 辅助文字，对比度 10:1
val DarkPrimary = Color(0xFF4FC3F7)           // 亮天蓝强调
val DarkSurfaceVariant = Color(0xFF252540)    // 升级表面

// --- 功能模块色（高饱和，辨识度高）---
val ModuleIndoor = Color(0xFF2196F3)          // 室内感知 - 明亮蓝
val ModuleIndoorBg = Color(0xFF0D2744)        // 室内卡片底
val ModuleNavigation = Color(0xFF4CAF50)      // 出行导航 - 明亮绿
val ModuleNavigationBg = Color(0xFF0D2E12)    // 导航卡片底
val ModuleScene = Color(0xFFFF9800)           // 场景感知 - 明亮橙
val ModuleSceneBg = Color(0xFF2E1E05)         // 场景卡片底
val ModuleSos = Color(0xFFFF3D00)             // 紧急求助 - 鲜红
val ModuleSosBg = Color(0xFF3A0A00)           // SOS卡片底

// --- 语义色 ---
val DangerColor = Color(0xFFFF5252)
val WarningColor = Color(0xFFFFA726)
val SafeColor = Color(0xFF66BB6A)
