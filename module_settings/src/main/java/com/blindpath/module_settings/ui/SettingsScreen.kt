package com.blindpath.module_settings.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.blindpath.module_settings.data.*

/**
 * 设置页面 - 视障友好设计
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    viewModel: SettingsViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState())
            .semantics { contentDescription = "设置页面" }
    ) {
        // 标题栏
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "设置",
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier.semantics {
                    contentDescription = "设置页面标题"
                }
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 紧急联系人设置
        SettingsSection(title = "紧急联系人") {
            EmergencyContactCard(
                settings = uiState.settings,
                onUpdate = { name, phone ->
                    viewModel.updateEmergencyContact(name, phone)
                }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 语音设置
        SettingsSection(title = "语音播报") {
            VoiceSettingsCard(
                settings = uiState.settings,
                onSpeechRateChange = { viewModel.updateSpeechRate(it) },
                onSpeechPitchChange = { viewModel.updateSpeechPitch(it) },
                onVoiceEnabledChange = { viewModel.updateVoiceEnabled(it) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 振动设置
        SettingsSection(title = "振动反馈") {
            VibrationSettingsCard(
                settings = uiState.settings,
                onEnabledChange = { viewModel.updateVibrationEnabled(it) },
                onIntensityChange = { viewModel.updateVibrationIntensity(it) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 检测设置
        SettingsSection(title = "障碍物检测") {
            DetectionSettingsCard(
                settings = uiState.settings,
                onSensitivityChange = { viewModel.updateDetectionSensitivity(it) },
                onDistanceChange = { viewModel.updateDetectionDistance(it) }
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 关于
        SettingsSection(title = "关于") {
            AboutCard()
        }

        Spacer(modifier = Modifier.height(48.dp))
    }
}

@Composable
fun SettingsSection(
    title: String,
    content: @Composable () -> Unit
) {
    Column {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.semantics {
                contentDescription = "$title 设置分组"
            }
        )
        Spacer(modifier = Modifier.height(12.dp))
        content()
    }
}

@Composable
fun EmergencyContactCard(
    settings: AppSettings,
    onUpdate: (String, String) -> Unit
) {
    var name by remember(settings) { mutableStateOf(settings.emergencyName) }
    var phone by remember(settings) { mutableStateOf(settings.emergencyContact) }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "紧急联系人设置卡片" },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 紧急联系人姓名
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("联系人姓名") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "紧急联系人姓名输入框，当前值：$name"
                    },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 紧急联系人电话
            OutlinedTextField(
                value = phone,
                onValueChange = { phone = it },
                label = { Text("联系电话") },
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "紧急联系电话输入框，当前值：$phone"
                    },
                singleLine = true
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onUpdate(name, phone) },
                modifier = Modifier
                    .align(Alignment.End)
                    .semantics {
                        contentDescription = "保存紧急联系人按钮"
                    }
            ) {
                Text("保存")
            }
        }
    }
}

@Composable
fun VoiceSettingsCard(
    settings: AppSettings,
    onSpeechRateChange: (Float) -> Unit,
    onSpeechPitchChange: (Float) -> Unit,
    onVoiceEnabledChange: (Boolean) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "语音播报设置卡片" },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 语音开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "语音播报开关，当前${if (settings.voiceEnabled) "开启" else "关闭"}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("开启语音播报")
                Switch(
                    checked = settings.voiceEnabled,
                    onCheckedChange = onVoiceEnabledChange
                )
            }

            if (settings.voiceEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                // 语速
                Text(
                    text = "语速：${String.format("%.1f", settings.speechRate)}x",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "语速设置，当前${String.format("%.1f", settings.speechRate)}倍"
                    }
                )
                Slider(
                    value = settings.speechRate,
                    onValueChange = onSpeechRateChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier.semantics {
                        contentDescription = "语速滑块，从0.5倍到2倍"
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 音调
                Text(
                    text = "音调：${String.format("%.1f", settings.speechPitch)}",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "音调设置，当前${String.format("%.1f", settings.speechPitch)}"
                    }
                )
                Slider(
                    value = settings.speechPitch,
                    onValueChange = onSpeechPitchChange,
                    valueRange = 0.5f..2.0f,
                    steps = 5,
                    modifier = Modifier.semantics {
                        contentDescription = "音调滑块，从0.5到2"
                    }
                )
            }
        }
    }
}

@Composable
fun VibrationSettingsCard(
    settings: AppSettings,
    onEnabledChange: (Boolean) -> Unit,
    onIntensityChange: (VibrationIntensity) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "振动反馈设置卡片" },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            // 振动开关
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics {
                        contentDescription = "振动反馈开关，当前${if (settings.vibrationEnabled) "开启" else "关闭"}"
                    },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("开启振动反馈")
                Switch(
                    checked = settings.vibrationEnabled,
                    onCheckedChange = onEnabledChange
                )
            }

            if (settings.vibrationEnabled) {
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "振动强度",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "振动强度选择"
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))

                // 振动强度选择
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    VibrationIntensity.entries.forEach { intensity ->
                        FilterChip(
                            selected = settings.vibrationIntensity == intensity,
                            onClick = { onIntensityChange(intensity) },
                            label = {
                                Text(
                                    intensity.displayName,
                                    fontSize = 14.sp,
                                    modifier = Modifier.semantics {
                                        contentDescription = intensity.displayName
                                    }
                                )
                            },
                            modifier = Modifier.semantics {
                                contentDescription = "${intensity.displayName}，${if (settings.vibrationIntensity == intensity) "当前选中" else ""}"
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun DetectionSettingsCard(
    settings: AppSettings,
    onSensitivityChange: (DetectionSensitivity) -> Unit,
    onDistanceChange: (Int) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "障碍物检测设置卡片" },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "检测灵敏度",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    contentDescription = "障碍物检测灵敏度设置"
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // 灵敏度选择
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                DetectionSensitivity.entries.forEach { sensitivity ->
                    FilterChip(
                        selected = settings.detectionSensitivity == sensitivity,
                        onClick = { onSensitivityChange(sensitivity) },
                        label = {
                            Text(
                                sensitivity.displayName,
                                fontSize = 12.sp,
                                modifier = Modifier.semantics {
                                    contentDescription = sensitivity.displayName
                                }
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "${sensitivity.displayName}，${if (settings.detectionSensitivity == sensitivity) "当前选中" else ""}"
                        }
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 检测距离
            Text(
                text = "预警距离：${settings.detectionDistance}米",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.semantics {
                    contentDescription = "预警距离设置，当前${settings.detectionDistance}米"
                }
            )
            Slider(
                value = settings.detectionDistance.toFloat(),
                onValueChange = { onDistanceChange(it.toInt()) },
                valueRange = 2f..10f,
                steps = 7,
                modifier = Modifier.semantics {
                    contentDescription = "预警距离滑块，从2米到10米"
                }
            )
        }
    }
}

@Composable
fun AboutCard() {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "关于应用信息卡片" },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "智行助盲",
                style = MaterialTheme.typography.titleLarge,
                modifier = Modifier.semantics {
                    contentDescription = "智行助盲应用"
                }
            )
            Text(
                text = "BlindPath",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "版本 1.0",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    contentDescription = "版本 1.0"
                }
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "帮助视障人士安全出行的智能助手",
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "帮助视障人士安全出行的智能助手"
                }
            )
        }
    }
}
