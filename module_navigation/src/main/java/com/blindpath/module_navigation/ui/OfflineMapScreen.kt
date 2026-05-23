package com.blindpath.module_navigation.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.blindpath.module_navigation.data.OfflineMapManager
import com.blindpath.module_navigation.domain.model.OfflineMapCityInfo

/**
 * 离线地图管理界面
 * 
 * 功能：
 * 1. 查看已下载的离线地图
 * 2. 下载新城市离线地图
 * 3. 删除离线地图
 * 4. 检查更新
 * 
 * 视障友好设计：
 * - 大按钮，高对比度
 * - 详细语音标签
 * - 下载进度语音播报
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OfflineMapScreen(
    offlineMapManager: OfflineMapManager,
    onBackClick: () -> Unit
) {
    val state by offlineMapManager.state.collectAsState()
    var showCityList by remember { mutableStateOf(false) }
    
    // 已下载的离线地图
    val downloadedMaps = remember { mutableStateListOf<OfflineMapCityInfo>() }
    
    // 可下载的城市列表
    val availableCities = remember { mutableStateListOf<OfflineMapCityInfo>() }

    // 加载数据
    LaunchedEffect(Unit) {
        // 初始化离线地图管理器
        offlineMapManager.initialize()
        
        // 加载已下载的地图
        val downloaded = offlineMapManager.getDownloadedMaps()
        downloadedMaps.clear()
        downloadedMaps.addAll(downloaded.map { city ->
            OfflineMapCityInfo(
                cityCode = city.code,
                cityName = city.city,
                province = city.province,
                size = city.size.toLong(),
                isDownloaded = true
            )
        })
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        "离线地图管理",
                        fontWeight = FontWeight.Bold
                    ) 
                },
                navigationIcon = {
                    TextButton(
                        onClick = onBackClick,
                        modifier = Modifier.semantics {
                            contentDescription = "返回导航界面按钮"
                        }
                    ) {
                        Icon(
                            Icons.Default.ArrowBack,
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("返回", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                },
                actions = {
                    IconButton(
                        onClick = { offlineMapManager.checkForUpdates() },
                        modifier = Modifier.semantics {
                            contentDescription = "检查离线地图更新"
                        }
                    ) {
                        Icon(Icons.Default.Refresh, contentDescription = null)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
        ) {
            // 下载状态卡片
            if (state.isDownloading) {
                DownloadProgressCard(
                    cityName = state.currentDownloadCity ?: "未知城市",
                    progress = state.downloadProgress
                )
                Spacer(modifier = Modifier.height(16.dp))
            }

            // 离线地图总大小
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "离线地图存储",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "已下载 ${downloadedMaps.size} 个城市",
                        style = MaterialTheme.typography.bodyLarge
                    )
                    Text(
                        text = "总大小：${formatSize(offlineMapManager.getTotalOfflineMapSize())}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 已下载的离线地图列表
            Text(
                text = "已下载的城市",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            if (downloadedMaps.isEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
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
                            Icons.Default.Map,
                            contentDescription = null,
                            modifier = Modifier.size(48.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "暂无离线地图",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "下载离线地图后可在无网络环境下使用导航",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            } else {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(downloadedMaps) { city ->
                        DownloadedMapItem(
                            city = city,
                            onDelete = {
                                offlineMapManager.deleteCityMap(city.cityName)
                                downloadedMaps.remove(city)
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // 添加新城市按钮
            Button(
                onClick = { showCityList = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .semantics {
                        contentDescription = "下载新城市离线地图"
                    },
                shape = MaterialTheme.shapes.medium
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text("下载新城市", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    // 城市选择对话框
    if (showCityList) {
        CitySelectionDialog(
            onDismiss = { showCityList = false },
            onCitySelected = { cityName ->
                offlineMapManager.downloadCityMap(cityName)
                showCityList = false
            }
        )
    }
}

/**
 * 下载进度卡片
 */
@Composable
private fun DownloadProgressCard(
    cityName: String,
    progress: Int
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Download,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.secondary
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "正在下载：$cityName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(12.dp))
            LinearProgressIndicator(
                progress = progress / 100f,
                modifier = Modifier.fillMaxWidth(),
                color = MaterialTheme.colorScheme.secondary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "$progress%",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * 已下载地图项
 */
@Composable
private fun DownloadedMapItem(
    city: OfflineMapCityInfo,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.LocationCity,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = city.cityName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = city.province,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = city.getFormattedSize(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onDelete,
                modifier = Modifier.semantics {
                    contentDescription = "删除${city.cityName}离线地图"
                }
            ) {
                Icon(
                    Icons.Default.Delete,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

/**
 * 城市选择对话框
 */
@Composable
private fun CitySelectionDialog(
    onDismiss: () -> Unit,
    onCitySelected: (String) -> Unit
) {
    // 常用城市列表（简化版，实际应从 OfflineMapManager 获取）
    val cities = listOf(
        "北京市", "上海市", "广州市", "深圳市",
        "杭州市", "南京市", "武汉市", "成都市",
        "西安市", "重庆市", "天津市", "苏州市"
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("选择城市") },
        text = {
            LazyColumn {
                items(cities) { city ->
                    TextButton(
                        onClick = { onCitySelected(city) },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(city, fontSize = 16.sp)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("取消")
            }
        }
    )
}

/**
 * 格式化文件大小
 */
private fun formatSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) {
        String.format("%.1f GB", mb / 1024)
    } else {
        String.format("%.1f MB", mb)
    }
}
