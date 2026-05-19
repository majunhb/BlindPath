package com.blindpath.app.e2e

import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.*
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.blindpath.app.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 端到端测试
 * 
 * 测试用户主要流程：
 * 1. 启动应用
 * 2. 授予权限
 * 3. 开始障碍物检测
 * 4. 停止检测
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class EndToEndTest {

    @get:Rule
    val activityRule = ActivityScenarioRule(MainActivity::class.java)

    @Test
    fun testAppLaunch_displaysMainScreen() {
        // 验证主屏幕显示
        onView(withText("智行助盲"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testNavigationToSettings() {
        // 点击设置
        onView(withContentDescription("设置"))
            .perform(click())

        // 验证设置屏幕显示
        onView(withText("无障碍设置"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun testSosButtonLongPress() {
        // 长按 SOS 按钮
        onView(withText("SOS"))
            .perform(longClick())

        // 验证确认对话框显示
        onView(withText("确认发送SOS？"))
            .check(matches(isDisplayed()))
    }
}
