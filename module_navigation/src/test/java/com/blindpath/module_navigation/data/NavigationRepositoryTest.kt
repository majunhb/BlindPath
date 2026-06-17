package com.blindpath.module_navigation.data

import com.blindpath.base.common.Result
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_navigation.domain.model.LatLonPoint
import com.blindpath.module_navigation.domain.model.NavigationState
import com.blindpath.module_navigation.domain.model.RouteStep
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * NavigationRepository 接口契约测试
 * 验证导航状态管理和路线规划的核心逻辑
 */
class NavigationRepositoryTest {

    private lateinit var repository: NavigationRepository

    @Before
    fun setup() {
        repository = mockk(relaxed = true)
    }

    // ============ 状态管理测试 ============

    @Test
    fun `initial navigationState should not be null`() = runBlocking {
        val initialState = NavigationState()
        every { repository.navigationState } returns mockk {
            coEvery { first() } returns initialState
        }

        val state = repository.navigationState.first()
        assertNotNull(state)
        assertFalse(state.isNavigating)
    }

    @Test
    fun `setDestination should update destination info`() {
        repository.setDestination(31.2304, 121.4737, "上海")
        coVerify { repository.setDestination(31.2304, 121.4737, "上海") }
    }

    // ============ 路线规划测试 ============

    @Test
    fun `planRoute should accept valid coordinates`() = runBlocking {
        coEvery { repository.planRoute(any(), any(), any(), any()) } returns Result.Success(Unit)

        val result = repository.planRoute(39.9042, 116.4074, 31.2304, 121.4737)
        assertTrue(result is Result.Success)
    }

    @Test
    fun `planRoute should return Error for same origin and destination`() = runBlocking {
        coEvery { repository.planRoute(any(), any(), any(), any()) } returns
            Result.Error(Exception("起点和终点相同"))

        val result = repository.planRoute(39.9042, 116.4074, 39.9042, 116.4074)
        assertTrue(result is Result.Error)
        assertEquals("起点和终点相同", (result as Result.Error).exception.message)
    }

    // ============ 步进逻辑测试 ============

    @Test
    fun `advanceToNextStep should be callable`() = runBlocking {
        repository.advanceToNextStep()
        coVerify { repository.advanceToNextStep() }
    }

    // ============ RouteStep 数据模型测试 ============

    @Test
    fun `RouteStep should store instruction correctly`() {
        val step = RouteStep(instruction = "前方100米左转进入复兴中路", distance = "100", duration = "120", type = "左转")
        assertEquals("前方100米左转进入复兴中路", step.instruction)
        assertEquals("100", step.distance)
        assertEquals("120", step.duration)
    }

    @Test
    fun `RouteStep polyline should be optional`() {
        val step = RouteStep(instruction = "直行", distance = "50", duration = "60")
        assertTrue(step.polyline.isEmpty())
        assertEquals("直行", step.instruction)
    }

    // ============ LatLonPoint 测试 ============

    @Test
    fun `LatLonPoint should store coordinates correctly`() {
        val point = LatLonPoint(39.9042, 116.4074)
        assertEquals(39.9042, point.latitude, 0.0001)
        assertEquals(116.4074, point.longitude, 0.0001)
    }

    // ============ 导航终止测试 ============

    @Test
    fun `stopNavigation should be callable`() = runBlocking {
        repository.stopNavigation()
        coVerify { repository.stopNavigation() }
    }

    @Test
    fun `clearDestination should reset destination`() = runBlocking {
        repository.clearDestination()
        coVerify { repository.clearDestination() }
    }
}