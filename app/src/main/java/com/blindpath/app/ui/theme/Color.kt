package com.blindpath.app.ui.theme

import androidx.compose.ui.graphics.Color

// 主色调 - 视障用户需要高对比度
val Primary = Color(0xFF1565C0)          // 深蓝色（主要操作）
val PrimaryDark = Color(0xFF0D47A1)       // 深蓝（状态栏）
val Secondary = Color(0xFF00897B)        // 青色（次要信息）

// 功能色 - 高对比度警示色
val DangerColor = Color(0xFFD32F2F)       // 危险（红色）
val WarningColor = Color(0xFFFF8F00)       // 警告（橙色）
val SafeColor = Color(0xFF388E3C)         // 安全（绿色）

// 背景色 - 深色主题为主（省电+护眼）
val DarkBackground = Color(0xFF121212)
val DarkSurface = Color(0xFF1E1E1E)
val DarkSurfaceVariant = Color(0xFF2D2D2D)

// 浅色主题（可选）
val LightBackground = Color(0xFFF5F5F5)
val LightSurface = Color(0xFFFFFFFF)

// 文字色
val TextHighEmphasis = Color(0xFFFFFFFF)   // 主要文字
val TextMediumEmphasis = Color(0xB3FFFFFF) // 次要文字（70%透明度）
val TextDisabled = Color(0x61FFFFFF)      // 禁用文字（38%透明度）

// 无障碍强调色（满足WCAG AAA标准）
val AccessibleOrange = Color(0xFFFF9800)  // 橙色（最高对比度）
val AccessibleGreen = Color(0xFF4CAF50)  // 绿色（安全指示）
