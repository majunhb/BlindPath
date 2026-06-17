package com.blindpath.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 出行导航 - 功能说明引导页
 *
 * 从导航页面中独立出来，通过"?"按钮进入，
 * 避免说明文字占用导航主页面空间。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NavigationGuideScreen(onBack: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("出行导航 · 使用引导") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF4CAF50),
                    titleContentColor = Color.White,
                    navigationIconContentColor = Color.White
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // ============================================================
            // 1. 功能概述
            // ============================================================
            GuideSection(
                title = "功能概述",
                icon = Icons.Default.Info,
                content = {
                    Text(
                        "出行导航是为视障人士设计的户外出行辅助工具，通过高德地图路线规划 + AI 实时环境感知，"
                                + "帮助您安全、独立地到达目的地。",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "系统分为四个层次：感知层（识别环境）→ 决策层（评估风险）→ 引导层（语音指引）→ 交互层（按键操作）",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            )

            // ============================================================
            // 2. 核心功能
            // ============================================================
            GuideSection(
                title = "核心功能",
                icon = Icons.Default.Star,
                content = {
                    FeatureItem("无障碍动态路径规划", "优先规划盲道、平缓路段，避开台阶和施工区域")
                    FeatureItem("盲道全程守护", "实时识别盲道，偏离时自动预警")
                    FeatureItem("路口安全辅助", "识别斑马线、红绿灯，辅助过马路")
                    FeatureItem("环境障碍物检测", "AI 实时检测行人、车辆、障碍物，提前预警")
                    FeatureItem("智能安全防护", "紧急情况一键求助，发送位置信息")
                    FeatureItem("公共交通接驳", "公交、地铁站点引导")
                }
            )

            // ============================================================
            // 3. 使用步骤
            // ============================================================
            GuideSection(
                title = "使用步骤",
                icon = Icons.Default.Menu,
                content = {
                    StepItem("1", "输入目的地", "在顶部输入框中输入您要去的地方")
                    StepItem("2", "选择路线", "系统自动规划无障碍路线")
                    StepItem("3", "开始导航", "点击开始按钮，跟随语音指引行走")
                    StepItem("4", "实时感知", "手机后置摄像头自动检测环境，播报障碍物和道路信息")
                }
            )

            // ============================================================
            // 4. 实体按键操作
            // ============================================================
            GuideSection(
                title = "实体按键操作",
                icon = Icons.Default.Settings,
                content = {
                    KeyItem("双击 音量上键", "重复播报当前导航指令")
                    KeyItem("长按 音量下键", "紧急求助（发送位置与求助信息）")
                    KeyItem("双击 电源键", "开始 / 关闭导航")
                }
            )

            // ============================================================
            // 5. 语音指令
            // ============================================================
            GuideSection(
                title = "语音指令",
                icon = Icons.Default.Notifications,
                content = {
                    VoiceItem("开始导航", "启动路线导航")
                    VoiceItem("停止导航", "停止当前导航")
                    VoiceItem("重复播报", "再次播报当前指令")
                    VoiceItem("我在哪里", "播报当前位置信息")
                    VoiceItem("附近有什么", "描述周围环境")
                }
            )

            // ============================================================
            // 6. 注意事项
            // ============================================================
            GuideSection(
                title = "注意事项",
                icon = Icons.Default.Warning,
                content = {
                    Text(
                        "• 需要开启 GPS 定位和位置权限",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• 需要授权相机权限（用于环境感知）",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• 优先规划盲道和平缓路段",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• 提供语音播报和振动提示",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• 偏离盲道时会自动预警",
                        style = MaterialTheme.typography.bodySmall
                    )
                    Text(
                        "• 路口提供红绿灯和斑马线辅助",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            )

            // 底部间距
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

// ================================================================
// 组合组件
// ================================================================

@Composable
private fun GuideSection(
    title: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    content: @Composable ColumnScope.() -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = Color(0xFF4CAF50),
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
private fun FeatureItem(title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50))
                .padding(top = 6.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun StepItem(number: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(Color(0xFF4CAF50)),
            contentAlignment = Alignment.Center
        ) {
            Text(
                number,
                color = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun KeyItem(action: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(Color(0xFFE8F5E9))
                .padding(horizontal = 10.dp, vertical = 6.dp)
        ) {
            Text(
                action,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF2E7D32)
            )
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun VoiceItem(command: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Default.Notifications,
            contentDescription = null,
            tint = Color(0xFF4CAF50),
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            "\"$command\"",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = Color(0xFF2E7D32)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            description,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}