package com.blindpath.module_trip_assist.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ArrowForward
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.blindpath.module_trip_assist.domain.TripAssistTab
import com.blindpath.module_trip_assist.domain.model.FacilityType
import com.blindpath.module_trip_assist.domain.model.TransportMode
import com.blindpath.module_trip_assist.domain.model.WeatherCondition

/**
 * 出行辅助主界面
 * 包含三个 Tab：天气播报、路线规划、无障碍设施
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TripAssistScreen(
    onBackClick: () -> Unit,
    viewModel: TripAssistViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "出行辅助",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.semantics {
                            contentDescription = "出行辅助页面"
                        }
                    )
                },
                navigationIcon = {
                    // 增强返回按钮视觉效果
                    TextButton(
                        onClick = onBackClick,
                        modifier = Modifier.semantics {
                            contentDescription = "返回主界面按钮"
                        }
                    ) {
                        Icon(
                            Icons.Filled.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("返回", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer,
                    titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                )
            )
        }
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // 开发中提示横幅
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFFFFA500))
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "开发中 - 数据仅供测试",
                    color = Color.White,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            // Tab 切换
            TabRow(
                selectedTabIndex = uiState.activeTab.ordinal,
                modifier = Modifier.semantics {
                    contentDescription = "功能选择标签页"
                }
            ) {
                TripAssistTab.entries.forEach { tab ->
                    Tab(
                        selected = uiState.activeTab == tab,
                        onClick = { viewModel.switchTab(tab) },
                        text = {
                            Text(
                                text = tab.displayName,
                                fontSize = 16.sp,
                                fontWeight = if (uiState.activeTab == tab) FontWeight.Bold else FontWeight.Normal
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "${tab.displayName}标签页"
                        }
                    )
                }
            }

            // 加载指示器
            if (uiState.error != null) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = uiState.error ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // Tab 内容
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            ) {
                if (uiState.isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .align(Alignment.Center)
                            .semantics {
                                contentDescription = "正在加载"
                            }
                    )
                } else {
                    when (uiState.activeTab) {
                        TripAssistTab.WEATHER -> WeatherTabContent(viewModel)
                        TripAssistTab.ROUTE -> RouteTabContent(viewModel)
                        TripAssistTab.FACILITY -> FacilityTabContent(viewModel)
                    }
                }
            }
        }
    }
}

// ==================== 天气播报 Tab ====================

@Composable
private fun WeatherTabContent(viewModel: TripAssistViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    var cityName by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 天气查询输入
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = cityName,
                    onValueChange = { cityName = it },
                    label = { Text("城市名称") },
                    placeholder = { Text("如：北京") },
                    singleLine = true,
                    modifier = Modifier
                        .weight(1f)
                        .semantics {
                            contentDescription = "输入城市名称查询天气"
                        }
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = {
                        if (cityName.isNotBlank()) {
                            viewModel.fetchWeatherByCity(cityName)
                        } else {
                            viewModel.fetchAndAnnounceWeather()
                        }
                    },
                    modifier = Modifier.semantics {
                        contentDescription = "查询天气按钮"
                    }
                ) {
                    Icon(Icons.Default.Search, contentDescription = null)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("查询")
                }
            }
        }

        // 天气信息卡片
        uiState.weatherInfo?.let { weather ->
            item {
                WeatherInfoCard(
                    weather = weather,
                    onReplayClick = { viewModel.replayWeather() }
                )
            }

            // 出行建议
            item {
                TravelAdviceCard(weather = weather)
            }
        }

        // 快捷操作
        item {
            Spacer(modifier = Modifier.height(8.dp))
            Button(
                onClick = { viewModel.fetchAndAnnounceWeather() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics {
                        contentDescription = "获取当前位置天气并语音播报"
                    },
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Filled.Notifications, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("获取当前位置天气", fontSize = 16.sp)
            }
        }
    }
}

@Composable
private fun WeatherInfoCard(
    weather: com.blindpath.module_trip_assist.domain.model.WeatherInfo,
    onReplayClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "天气信息卡片"
            },
        colors = CardDefaults.cardColors(
            containerColor = when (weather.condition) {
                com.blindpath.module_trip_assist.domain.model.WeatherCondition.CLEAR,
                com.blindpath.module_trip_assist.domain.model.WeatherCondition.CLOUDY ->
                    Color(0xFFE3F2FD)
                com.blindpath.module_trip_assist.domain.model.WeatherCondition.MODERATE_RAIN,
                com.blindpath.module_trip_assist.domain.model.WeatherCondition.HEAVY_RAIN,
                com.blindpath.module_trip_assist.domain.model.WeatherCondition.STORM ->
                    Color(0xFFFFEBEE)
                com.blindpath.module_trip_assist.domain.model.WeatherCondition.FOG,
                com.blindpath.module_trip_assist.domain.model.WeatherCondition.HAZE ->
                    Color(0xFFFFF8E1)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${weather.cityName} · ${weather.condition.displayName}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                IconButton(
                    onClick = onReplayClick,
                    modifier = Modifier.semantics {
                        contentDescription = "重新播报天气"
                    }
                ) {
                    Icon(Icons.Filled.Notifications, contentDescription = null)
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetailItem("温度", "${weather.temperature.toInt()}°C")
                WeatherDetailItem("体感", "${weather.feelsLike.toInt()}°C")
                WeatherDetailItem("湿度", "${weather.humidity}%")
                WeatherDetailItem("风速", "${weather.windSpeed}m/s")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                WeatherDetailItem("风向", weather.windDirection)
                WeatherDetailItem("能见度", "${weather.visibility}km")
                if (weather.aqi > 0) {
                    WeatherDetailItem("空气质量", "${weather.aqi} ${weather.aqiLevel}")
                }
            }
        }
    }
}

@Composable
private fun WeatherDetailItem(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = value,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun TravelAdviceCard(
    weather: com.blindpath.module_trip_assist.domain.model.WeatherInfo
) {
    val bgColor = if (weather.needsTravelWarning()) {
        MaterialTheme.colorScheme.errorContainer
    } else {
        MaterialTheme.colorScheme.primaryContainer
    }
    val textColor = if (weather.needsTravelWarning()) {
        MaterialTheme.colorScheme.onErrorContainer
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = bgColor)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = if (weather.needsTravelWarning()) "出行警告" else "出行建议",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = textColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = weather.condition.voiceDescription,
                style = MaterialTheme.typography.bodyMedium,
                color = textColor
            )
        }
    }
}

// ==================== 路线规划 Tab ====================

@Composable
private fun RouteTabContent(viewModel: TripAssistViewModel) {
    val uiState by viewModel.uiState.collectAsState()
    val origin by viewModel.originText.collectAsState()
    val destination by viewModel.destinationText.collectAsState()
    val selectedMode by viewModel.selectedTransportMode.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 起终点输入
        item {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(modifier = Modifier.padding(16.dp)) {
                    OutlinedTextField(
                        value = origin,
                        onValueChange = { viewModel.originText.value = it },
                        label = { Text("起点") },
                        placeholder = { Text("输入起点名称或地址") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "输入路线起点"
                            }
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = destination,
                        onValueChange = { viewModel.destinationText.value = it },
                        label = { Text("终点") },
                        placeholder = { Text("输入终点名称或地址") },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .semantics {
                                contentDescription = "输入路线终点"
                            }
                    )
                }
            }
        }

        // 交通方式选择
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                TransportMode.getRecommendedModes().forEach { mode ->
                    FilterChip(
                        selected = selectedMode == mode,
                        onClick = { viewModel.setTransportMode(mode) },
                        label = {
                            Text(
                                text = mode.displayName,
                                fontSize = 14.sp
                            )
                        },
                        modifier = Modifier.semantics {
                            contentDescription = "选择${mode.displayName}方式"
                        }
                    )
                }
            }
        }

        // 规划按钮
        item {
            Button(
                onClick = { viewModel.planRouteAndAnnounce() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics {
                        contentDescription = "开始规划路线并语音播报"
                    },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.primary
                )
            ) {
                Icon(Icons.Default.List, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("规划路线", fontSize = 16.sp)
            }
        }

        // 路线结果
        uiState.currentRoute?.let { route ->
            item {
                RouteOverviewCard(
                    route = route,
                    onReplayClick = { viewModel.replayRouteOverview() }
                )
            }

            // 无障碍提示
            if (route.accessibilityNotes.isNotEmpty()) {
                item {
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.secondaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                text = "无障碍提示",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                            route.accessibilityNotes.forEach { note ->
                                Text(
                                    text = "· $note",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSecondaryContainer
                                )
                            }
                        }
                    }
                }
            }

            // 步骤导航
            item {
                StepNavigationCard(
                    currentStepIndex = uiState.currentStepIndex,
                    totalSteps = route.steps.size,
                    onPreviousClick = { viewModel.announcePreviousStep() },
                    onNextClick = { viewModel.announceNextStep() }
                )
            }

            // 步骤列表
            itemsIndexed(route.steps) { index, step ->
                RouteStepCard(
                    step = step,
                    index = index,
                    isActive = index == uiState.currentStepIndex,
                    onClick = { viewModel.announceStep(index) }
                )
            }
        }
    }
}

@Composable
private fun RouteOverviewCard(
    route: com.blindpath.module_trip_assist.domain.model.RoutePlan,
    onReplayClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${route.transportMode.displayName}路线",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                IconButton(onClick = onReplayClick) {
                    Icon(Icons.Filled.Notifications, contentDescription = "重新播报")
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDistance(route.totalDistance),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text("总距离", style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = formatDuration(route.totalDuration),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text("预计时间", style = MaterialTheme.typography.bodySmall)
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${route.steps.size}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text("换乘次数", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun StepNavigationCard(
    currentStepIndex: Int,
    totalSteps: Int,
    onPreviousClick: () -> Unit,
    onNextClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically
    ) {
        OutlinedButton(
            onClick = onPreviousClick,
            enabled = currentStepIndex > 0,
            modifier = Modifier.semantics { contentDescription = "上一步" }
        ) {
            Icon(Icons.Default.ArrowBack, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("上一步")
        }

        Text(
            text = "${currentStepIndex + 1} / $totalSteps",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )

        OutlinedButton(
            onClick = onNextClick,
            enabled = currentStepIndex < totalSteps - 1,
            modifier = Modifier.semantics { contentDescription = "下一步" }
        ) {
            Text("下一步")
            Spacer(modifier = Modifier.width(4.dp))
            Icon(Icons.Default.ArrowForward, contentDescription = null)
        }
    }
}

@Composable
private fun RouteStepCard(
    step: com.blindpath.module_trip_assist.domain.model.RouteStep,
    index: Int,
    isActive: Boolean,
    onClick: () -> Unit
) {
    val bgColor = if (isActive) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "第${index + 1}步，${step.instruction}"
            },
        colors = CardDefaults.cardColors(containerColor = bgColor),
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "步骤 ${index + 1}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(8.dp))
                if (step.isAccessible) {
                    Text(
                        text = "无障碍",
                        style = MaterialTheme.typography.labelSmall,
                        color = Color(0xFF4CAF50),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = step.instruction,
                style = MaterialTheme.typography.bodyMedium
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = formatDistance(step.distance),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDuration(step.duration),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (step.lineNumber.isNotBlank()) {
                    Text(
                        text = step.lineNumber,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }
    }
}

// ==================== 无障碍设施 Tab ====================

@Composable
private fun FacilityTabContent(viewModel: TripAssistViewModel) {
    val uiState by viewModel.uiState.collectAsState()

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 搜索按钮
        item {
            Button(
                onClick = { viewModel.searchAndAnnounceFacilities() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .semantics {
                        contentDescription = "搜索附近无障碍设施并语音播报"
                    },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.secondary
                )
            ) {
                Icon(Icons.Default.Person, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("搜索附近设施", fontSize = 16.sp)
            }
        }

        // 设施列表
        itemsIndexed(uiState.nearbyFacilities) { index, facility ->
            FacilityCard(
                facility = facility,
                onClick = { viewModel.announceFacilityDetail(index) }
            )
        }

        // 空状态
        if (uiState.nearbyFacilities.isEmpty() && !uiState.isLoading) {
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 48.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        Icons.Default.LocationOn,
                        contentDescription = null,
                        modifier = Modifier.size(48.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "点击上方按钮搜索附近无障碍设施",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
private fun FacilityCard(
    facility: com.blindpath.module_trip_assist.domain.model.AccessibleFacility,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .semantics {
                contentDescription = "${facility.type.displayName}，${facility.direction}" +
                        "${formatDistance(facility.distance)}，${facility.name}"
            },
        onClick = onClick
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = facility.type.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = "${facility.direction} ${formatDistance(facility.distance)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = facility.name,
                style = MaterialTheme.typography.bodyMedium
            )
            if (facility.description.isNotBlank()) {
                Text(
                    text = facility.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// ==================== 工具函数 ====================

private fun formatDistance(meters: Float): String = when {
    meters < 1000f -> "${meters.toInt()}米"
    else -> String.format("%.1f公里", meters / 1000)
}

private fun formatDuration(seconds: Int): String {
    val hours = seconds / 3600
    val minutes = (seconds % 3600) / 60
    return when {
        hours > 0 && minutes > 0 -> "${hours}小时${minutes}分钟"
        hours > 0 -> "${hours}小时"
        minutes > 0 -> "${minutes}分钟"
        else -> "${seconds}秒"
    }
}
