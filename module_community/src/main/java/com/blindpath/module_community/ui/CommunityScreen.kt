package com.blindpath.module_community.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.blindpath.module_community.data.*

/**
 * 社区页面 - 视障友好设计
 * 简化操作，突出核心功能
 */
@Composable
fun CommunityScreen(
    viewModel: CommunityViewModel = hiltViewModel(),
    onBackClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    var currentTab by remember { mutableStateOf(0) }

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp)
                .verticalScroll(rememberScrollState())
                .semantics { contentDescription = "社区互助页面" }
        ) {
            // 标题
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.People,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "社区互助",
                    style = MaterialTheme.typography.headlineMedium,
                    modifier = Modifier.semantics {
                        contentDescription = "社区互助页面"
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "志愿者陪伴，安全出行",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.semantics {
                    contentDescription = "志愿者陪伴，安全出行"
                }
            )

            Spacer(modifier = Modifier.height(24.dp))

            // 角色切换标签
            TabRow(
                selectedTabIndex = currentTab,
                modifier = Modifier.semantics {
                    contentDescription = "功能切换标签"
                }
            ) {
                Tab(
                    selected = currentTab == 0,
                    onClick = { currentTab = 0 },
                    text = {
                        Text(
                            "我的请求",
                            modifier = Modifier.semantics {
                                contentDescription = if (currentTab == 0) "我的请求，当前页面" else "我的请求"
                            }
                        )
                    }
                )
                Tab(
                    selected = currentTab == 1,
                    onClick = { currentTab = 1 },
                    text = {
                        Text(
                            "寻找志愿者",
                            modifier = Modifier.semantics {
                                contentDescription = if (currentTab == 1) "寻找志愿者，当前页面" else "寻找志愿者"
                            }
                        )
                    }
                )
                Tab(
                    selected = currentTab == 2,
                    onClick = { currentTab = 2 },
                    text = {
                        Text(
                            "成为志愿者",
                            modifier = Modifier.semantics {
                                contentDescription = if (currentTab == 2) "成为志愿者，当前页面" else "成为志愿者"
                            }
                        )
                    }
                )
            }

            Spacer(modifier = Modifier.height(24.dp))

            // 根据标签显示不同内容
            when (currentTab) {
                0 -> MyRequestsTab(
                    requests = uiState.requests,
                    onCancel = { viewModel.cancelRequest(it) },
                    onComplete = { viewModel.completeRequest(it) },
                    onNewRequest = { start, end, duration ->
                        viewModel.requestAccompany(start, end, duration)
                    }
                )
                1 -> FindVolunteersTab(volunteers = uiState.volunteers)
                2 -> BecomeVolunteerTab(
                    currentUser = uiState.currentUser,
                    onRegister = { viewModel.registerAsVolunteer(it) },
                    onSwitchBack = { viewModel.switchToBlindUser() }
                )
            }
        }

        // 成功消息提示
        uiState.showSuccessMessage?.let { message ->
            Snackbar(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
                    .semantics { contentDescription = message },
                action = {
                    TextButton(onClick = { viewModel.dismissMessage() }) {
                        Text("关闭")
                    }
                }
            ) {
                Text(message)
            }
        }
    }
}

@Composable
fun MyRequestsTab(
    requests: List<AccompanyRequest>,
    onCancel: (String) -> Unit,
    onComplete: (String) -> Unit,
    onNewRequest: (String, String, String) -> Unit
) {
    var showNewRequestDialog by remember { mutableStateOf(false) }

    Column {
        // 新建请求按钮
        Button(
            onClick = { showNewRequestDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .height(64.dp)
                .semantics { contentDescription = "发起新的陪伴请求按钮" },
            shape = RoundedCornerShape(16.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("发起陪伴请求", fontSize = 18.sp)
        }

        Spacer(modifier = Modifier.height(24.dp))

        // 请求列表
        if (requests.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Default.EventBusy,
                title = "暂无陪伴请求",
                description = "您还没有发起任何陪伴请求，点击上方按钮发起请求"
            )
        } else {
            requests.forEach { request ->
                RequestCard(
                    request = request,
                    onCancel = { onCancel(request.id) },
                    onComplete = { onComplete(request.id) }
                )
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }

    // 新建请求对话框
    if (showNewRequestDialog) {
        NewRequestDialog(
            onDismiss = { showNewRequestDialog = false },
            onConfirm = { start, end, duration ->
                onNewRequest(start, end, duration)
                showNewRequestDialog = false
            }
        )
    }
}

@Composable
fun RequestCard(
    request: AccompanyRequest,
    onCancel: () -> Unit,
    onComplete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "陪伴请求：从${request.startLocation}到${request.endLocation}，状态${request.status.displayName}"
            },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = request.status.displayName,
                    style = MaterialTheme.typography.labelMedium,
                    color = when (request.status) {
                        AccompanyRequestStatus.PENDING -> MaterialTheme.colorScheme.tertiary
                        AccompanyRequestStatus.ACCEPTED -> MaterialTheme.colorScheme.primary
                        AccompanyRequestStatus.COMPLETED -> MaterialTheme.colorScheme.outline
                        AccompanyRequestStatus.CANCELLED -> MaterialTheme.colorScheme.error
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.TripOrigin,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = request.startLocation, style = MaterialTheme.typography.bodyMedium)
            }

            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Flag,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(text = request.endLocation, style = MaterialTheme.typography.bodyMedium)
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "预计时长：${request.estimatedDuration}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // 志愿者信息（如果已接受）
            request.volunteerName?.let { volunteerName ->
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "志愿者：$volunteerName",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }

            // 操作按钮
            if (request.status == AccompanyRequestStatus.PENDING) {
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedButton(
                    onClick = onCancel,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("取消请求")
                }
            } else if (request.status == AccompanyRequestStatus.ACCEPTED) {
                Spacer(modifier = Modifier.height(12.dp))
                Button(
                    onClick = onComplete,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("确认完成")
                }
            }
        }
    }
}

@Composable
fun NewRequestDialog(
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Unit
) {
    var startLocation by remember { mutableStateOf("") }
    var endLocation by remember { mutableStateOf("") }
    var duration by remember { mutableStateOf("1小时以内") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                "发起陪伴请求",
                modifier = Modifier.semantics { contentDescription = "发起陪伴请求对话框" }
            )
        },
        text = {
            Column {
                OutlinedTextField(
                    value = startLocation,
                    onValueChange = { startLocation = it },
                    label = { Text("出发地点") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "出发地点输入框" },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                OutlinedTextField(
                    value = endLocation,
                    onValueChange = { endLocation = it },
                    label = { Text("目的地") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .semantics { contentDescription = "目的地输入框" },
                    singleLine = true
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "预计时长",
                    style = MaterialTheme.typography.bodyMedium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.semantics { contentDescription = "时长选择" }
                ) {
                    listOf("30分钟内", "1小时以内", "2小时以内", "半天").forEach { option ->
                        FilterChip(
                            selected = duration == option,
                            onClick = { duration = option },
                            label = { Text(option, fontSize = 12.sp) }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(startLocation, endLocation, duration) },
                enabled = startLocation.isNotBlank() && endLocation.isNotBlank()
            ) {
                Text("发起请求")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

@Composable
fun FindVolunteersTab(volunteers: List<Volunteer>) {
    Column {
        Text(
            text = "附近可用的爱心志愿者",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.semantics { contentDescription = "附近可用的爱心志愿者列表" }
        )

        Spacer(modifier = Modifier.height(16.dp))

        if (volunteers.isEmpty()) {
            EmptyStateCard(
                icon = Icons.Default.PersonSearch,
                title = "暂无可用志愿者",
                description = "稍后再试，或成为志愿者帮助他人"
            )
        } else {
            volunteers.forEach { volunteer ->
                VolunteerCard(volunteer = volunteer)
                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Composable
fun VolunteerCard(volunteer: Volunteer) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "志愿者：${volunteer.name}，${volunteer.city}，已服务${volunteer.serviceCount}次，评分${volunteer.rating}星"
            },
        shape = RoundedCornerShape(16.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(40.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = volunteer.name,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = volunteer.city,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                Column(horizontalAlignment = Alignment.End) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = String.format("%.1f", volunteer.rating),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                    Text(
                        text = "服务 ${volunteer.serviceCount} 次",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Schedule,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = volunteer.availableTime,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { /* TODO: 联系志愿者 */ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    imageVector = Icons.Default.Phone,
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("联系志愿者")
            }
        }
    }
}

@Composable
fun BecomeVolunteerTab(
    currentUser: CommunityUser,
    onRegister: (String) -> Unit,
    onSwitchBack: () -> Unit
) {
    Column {
        if (currentUser.role == UserRole.VOLUNTEER) {
            // 已注册志愿者
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "您已是爱心志愿者" },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        imageVector = Icons.Default.Favorite,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "感谢您成为志愿者！",
                        style = MaterialTheme.typography.titleLarge,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "您已帮助视障人士出行 ${currentUser.volunteerInfo?.serviceCount ?: 0} 次",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedButton(onClick = onSwitchBack) {
                        Text("切换为视障用户")
                    }
                }
            }
        } else {
            // 注册志愿者表单
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .semantics { contentDescription = "注册成为爱心志愿者" },
                shape = RoundedCornerShape(16.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.VolunteerActivism,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(48.dp)
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Column {
                            Text(
                                text = "成为爱心志愿者",
                                style = MaterialTheme.typography.titleLarge
                            )
                            Text(
                                text = "用您的爱心，照亮视障人士的出行之路",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    var availableTime by remember { mutableStateOf("") }

                    Text(
                        text = "可服务时间",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.semantics { contentDescription = "可服务时间选择" }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        listOf("周末", "工作日下班后", "随时可联系").forEach { option ->
                            FilterChip(
                                selected = availableTime == option,
                                onClick = { availableTime = option },
                                label = { Text(option, fontSize = 12.sp) }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = { onRegister(availableTime) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(56.dp)
                            .semantics { contentDescription = "确认注册成为志愿者按钮" },
                        enabled = availableTime.isNotBlank()
                    ) {
                        Icon(
                            imageVector = Icons.Default.Favorite,
                            contentDescription = null,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("确认注册", fontSize = 16.sp)
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = "成为志愿者后，您将收到视障人士的陪伴请求，并可选择接受帮助",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.semantics {
                            contentDescription = "成为志愿者说明：您将收到视障人士的陪伴请求，并可选择接受帮助"
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun EmptyStateCard(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    description: String
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics { contentDescription = "$title，$description" },
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(64.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}
