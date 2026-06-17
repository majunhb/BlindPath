package com.blindpath.base.integration

import android.content.Context
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.blindpath.base.accessibility.FontSizeScale
import com.blindpath.base.cache.CacheManager
import com.blindpath.base.error.DegradationLevel
import com.blindpath.base.error.DegradationManager
import com.blindpath.base.i18n.Language
import com.blindpath.base.i18n.LanguageManager
import com.blindpath.base.network.NetworkMonitor
import com.blindpath.base.power.PowerManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Duration

/**
 * BlindPathIntegration 集成测试
 */
@RunWith(AndroidJUnit4::class)
class BlindPathIntegrationTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
    }

    @After
    fun tearDown() {
        BlindPathIntegration.release()
    }

    @Test
    fun testInitialize() = runBlocking {
        val result = BlindPathIntegration.initialize(context)
        
        assertTrue("Initialization should succeed", result.isSuccess)
        assertTrue("Should be marked as initialized", BlindPathIntegration.isInitialized)
    }

    @Test
    fun testStatusSummary() = runBlocking {
        BlindPathIntegration.initialize(context)
        
        val summary = BlindPathIntegration.getStatusSummary()
        
        assertTrue("Should be initialized", summary.isInitialized)
        assertNotNull("Degradation level should not be null", summary.degradationLevel)
        assertTrue("Battery level should be 0-100", summary.batteryLevel in 0..100)
        assertNotNull("Language should not be null", summary.currentLanguage)
    }

    @Test
    fun testMissingPermissions() {
        val missing = BlindPathIntegration.checkRequiredPermissions()
        // 在测试环境中，某些权限可能无法获取
        assertNotNull("Missing permissions list should not be null", missing)
    }
}
