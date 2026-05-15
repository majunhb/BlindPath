package com.blindpath.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 涓荤晫闈?- 瑙嗛殰鍙嬪ソ璁捐
 * - 澶ф寜閽紙渚夸簬瑙︽懜锛? * - 楂樺姣斿害棰滆壊
 * - 鎵€鏈夊厓绱犳湁璇煶鏍囩
 */
@Composable
fun MainScreen(
    onObstacleDetectionClick: () -> Unit = {},
    onLocationClick: () -> Unit = {},
    onSosClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 搴旂敤鏍囬
        Text(
            text = "鏅鸿鍔╃洸",
            style = MaterialTheme.typography.displayMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics {
                contentDescription = "鏅鸿鍔╃洸锛岃闅滀汉澹嚭琛岃緟鍔╁簲鐢?
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "BlindPath",
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.semantics {
                contentDescription = "BlindPath 鐗堟湰 1.0"
            }
        )

        Spacer(modifier = Modifier.height(48.dp))

        // 鍔熻兘鎸夐挳鍖哄煙
        FeatureButton(
            icon = Icons.Default.Visibility,
            label = "闅滅鐗╂娴?,
            description = "寮€鍚憚鍍忓ご锛屽疄鏃舵娴嬪墠鏂归殰纰嶇墿",
            onClick = onObstacleDetectionClick,
            containerColor = MaterialTheme.colorScheme.primary
        )

        Spacer(modifier = Modifier.height(24.dp))

        FeatureButton(
            icon = Icons.Default.LocationOn,
            label = "浣嶇疆鎾姤",
            description = "鎾姤褰撳墠浣嶇疆鍜屽懆杈瑰湴鏍?,
            onClick = onLocationClick,
            containerColor = MaterialTheme.colorScheme.secondary
        )

        Spacer(modifier = Modifier.height(24.dp))

        FeatureButton(
            icon = Icons.Default.Warning,
            label = "绱ф€ユ眰鍔?,
            description = "涓€閿仈绯荤揣鎬ヨ仈绯讳汉",
            onClick = onSosClick,
            containerColor = MaterialTheme.colorScheme.error
        )

        Spacer(modifier = Modifier.height(48.dp))

        // 鐘舵€佹彁绀?        Text(
            text = "杞昏Е鎸夐挳鑾峰彇璇煶鎻愮ず",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.semantics {
                contentDescription = "鎻愮ず锛氳交瑙︽寜閽幏鍙栬闊虫彁绀?
            }
        )
    }
}

/**
 * 鍔熻兘鎸夐挳缁勪欢 - 瑙嗛殰鍙嬪ソ璁捐
 */
@Composable
fun FeatureButton(
    icon: ImageVector,
    label: String,
    description: String,
    onClick: () -> Unit,
    containerColor: androidx.compose.ui.graphics.Color
) {
    val combinedDescription = "$label锛?description"

    Button(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .semantics {
                contentDescription = combinedDescription
            },
        shape = RoundedCornerShape(16.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = containerColor
        ),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 16.dp)
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null, // 鍥炬爣璇存槑鐢卞灞傛寜閽彁渚?            modifier = Modifier.size(32.dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        Column {
            Text(
                text = label,
                style = MaterialTheme.typography.titleLarge,
                fontSize = 20.sp
            )
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f)
            )
        }
    }
}
