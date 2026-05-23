package com.blindpath.app.ui.screens

import androidx.compose.foundation.layout.*
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
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.blindpath.module_voice.viewmodel.VoiceInteractionViewModel
import timber.log.Timber

/**
 * 启动页 - 语音引导版
 * 
 * 功能：
 * 1. 自动播报欢迎消息
 * 2. 提示用户使用语音指令
 * 3. 等待语音指令或手动点击进入主界面
 */
@Composable
fun SplashScreen(
    onNavigateToMain: () -> Unit,
    viewModel: VoiceInteractionViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    
    // 初始化语音交互
    LaunchedEffect(Unit) {
        Timber.d("SplashScreen: Initializing voice interaction")
        viewModel.initialize()
    }
    
    // 监听初始化完成
    LaunchedEffect(uiState.isInitialized) {
        if (uiState.isInitialized) {
            Timber.d("SplashScreen: Voice interaction initialized")
            // 延迟 2 秒后自动进入主界面
            kotlinx.coroutines.delay(2000)
            onNavigateToMain()
        }
    }
    
    // 监听错误
    LaunchedEffect(uiState.error) {
        uiState.error?.let { error ->
            Timber.e("SplashScreen: Error - $error")
            // 即使有错误，也允许进入主界面
            kotlinx.coroutines.delay(3000)
            onNavigateToMain()
        }
    }
    
    // UI
    Box(
        modifier = Modifier
            .fillMaxSize()
            .semantics {
                contentDescription = "智行视障导航系统启动页，正在初始化语音交互系统"
            },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // 应用名称
            Text(
                text = "智行视障导航",
                style = MaterialTheme.typography.displayLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.semantics {
                    contentDescription = "智行视障导航系统"
                }
            )
            
            // 状态提示
            Text(
                text = when {
                    uiState.isInitializing -> "正在初始化语音系统..."
                    uiState.isInitialized -> "语音系统已就绪"
                    uiState.error != null -> "初始化失败：${uiState.error}"
                    else -> "欢迎使用"
                },
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurface,
                textAlign = TextAlign.Center,
                modifier = Modifier.semantics {
                    contentDescription = when {
                        uiState.isInitializing -> "正在初始化语音系统，请稍候"
                        uiState.isInitialized -> "语音系统已就绪，即将进入主界面"
                        uiState.error != null -> "初始化失败，${uiState.error}，即将进入主界面"
                        else -> "欢迎使用智行视障导航系统"
                    }
                }
            )
            
            // 加载指示器
            if (uiState.isInitializing) {
                CircularProgressIndicator(
                    modifier = Modifier
                        .size(48.dp)
                        .semantics { contentDescription = "加载中" }
                )
            }
            
            // 手动进入按钮（备用）
            if (uiState.isInitialized || uiState.error != null) {
                Button(
                    onClick = onNavigateToMain,
                    modifier = Modifier
                        .fillMaxWidth(0.6f)
                        .height(80.dp)
                        .semantics {
                            contentDescription = "点击进入主界面，或说\"小智小智\"唤醒语音助手"
                        }
                ) {
                    Text(
                        text = "进入主界面",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
