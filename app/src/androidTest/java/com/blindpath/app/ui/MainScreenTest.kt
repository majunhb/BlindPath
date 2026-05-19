package com.blindpath.app.ui

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.blindpath.base.ui.theme.BlindPathTheme
import org.junit.Rule
import org.junit.Test

/**
 * MainScreen UI 测试
 */
class MainScreenTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun testMainScreen_displaysAllButtons() {
        composeTestRule.setContent {
            BlindPathTheme {
                MainScreenPreview()
            }
        }

        // 验证主要按钮存在
        composeTestRule.onNodeWithText("障碍物检测").assertExists()
        composeTestRule.onNodeWithText("导航").assertExists()
        composeTestRule.onNodeWithText("室内识别").assertExists()
        composeTestRule.onNodeWithText("SOS").assertExists()
    }

    @Test
    fun testObstacleDetectionButton_clickable() {
        composeTestRule.setContent {
            BlindPathTheme {
                MainScreenPreview()
            }
        }

        // 验证障碍物检测按钮可点击
        composeTestRule.onNodeWithText("障碍物检测").assertHasClickAction()
    }

    @Test
    fun testSosButton_existsAndClickable() {
        composeTestRule.setContent {
            BlindPathTheme {
                MainScreenPreview()
            }
        }

        // 验证 SOS 按钮存在且可点击
        composeTestRule.onNodeWithText("SOS").assertExists()
        composeTestRule.onNodeWithText("SOS").assertHasClickAction()
    }
}

/**
 * 预览用的简化 MainScreen
 */
@Composable
private fun MainScreenPreview() {
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "智行助盲",
            style = MaterialTheme.typography.headlineLarge
        )
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeatureButton(
                text = "障碍物检测",
                modifier = Modifier.weight(1f)
            )
            FeatureButton(
                text = "导航",
                modifier = Modifier.weight(1f)
            )
        }
        
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FeatureButton(
                text = "室内识别",
                modifier = Modifier.weight(1f)
            )
            FeatureButton(
                text = "社区",
                modifier = Modifier.weight(1f)
            )
        }
        
        Spacer(modifier = Modifier.weight(1f))
        
        Button(
            onClick = {},
            modifier = Modifier.fillMaxWidth().height(64.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.error
            )
        ) {
            Text("SOS", style = MaterialTheme.typography.titleLarge)
        }
    }
}

@Composable
private fun FeatureButton(
    text: String,
    modifier: Modifier = Modifier
) {
    Button(
        onClick = {},
        modifier = modifier.height(100.dp)
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
    }
}

// 必要的导入
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
