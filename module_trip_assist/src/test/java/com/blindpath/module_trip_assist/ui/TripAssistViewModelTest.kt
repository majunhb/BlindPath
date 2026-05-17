package com.blindpath.module_trip_assist.ui

import com.blindpath.base.common.Result
import com.blindpath.module_trip_assist.domain.TripAssistRepository
import com.blindpath.module_trip_assist.domain.TripAssistState
import com.blindpath.module_trip_assist.domain.TripAssistTab
import com.blindpath.module_trip_assist.domain.model.*
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class TripAssistViewModelTest {

    private lateinit var repository: TripAssistRepository
    private lateinit var viewModel: TripAssistViewModel

    private val testWeather = WeatherInfo(
        cityName = "北京",
        temperature = 25f,
        feelsLike = 27f,
        humidity = 60,
        windSpeed = 3.0f,
        windDirection = "东南风",
        visibility = 10f,
        condition = WeatherCondition.CLEAR,
        description = "晴",
        sunriseTime = "06:00",
        sunsetTime = "18:30",
        aqi = 45,
        aqiLevel = "优"
    )

    private val testRoute = RoutePlan(
        origin = "天安门",
        destination = "故宫",
        totalDistance = 1500f,
        totalDuration = 1200,
        transportMode = TransportMode.SUBWAY,
        steps = listOf(
            RouteStep(
                instruction = "步行至地铁站",
                voiceInstruction = "步行至地铁站",
                distance = 300f,
                duration = 240,
                transportMode = TransportMode.WALKING,
                isAccessible = true
            ),
            RouteStep(
                instruction = "乘坐1号线",
                voiceInstruction = "乘坐1号线",
                distance = 800f,
                duration = 600,
                transportMode = TransportMode.SUBWAY,
                lineNumber = "1号线",
                stationCount = 3,
                isAccessible = true
            )
        ),
        isAccessibleRoute = true
    )

    @Before
    fun setup() {
        Dispatchers.setMain(StandardTestDispatcher())
        repository = mockk(relaxed = true)
        every { repository.tripAssistState } returns flowOf(TripAssistState())
        viewModel = TripAssistViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `switchTab updates activeTab`() = runTest {
        viewModel.switchTab(TripAssistTab.ROUTE)
        assertEquals(TripAssistTab.ROUTE, viewModel.uiState.value.activeTab)
    }

    @Test
    fun `fetchAndAnnounceWeather calls repository and announces`() = runTest {
        coEvery { repository.getWeather(any(), any()) } returns Result.Success(testWeather)
        coEvery { repository.announceWeather(any()) } returns Result.Success(true)

        viewModel.fetchAndAnnounceWeather(39.9, 116.4)

        coVerify { repository.getWeather(39.9, 116.4) }
        coVerify { repository.announceWeather(testWeather) }
    }

    @Test
    fun `fetchWeatherByCity calls repository with city name`() = runTest {
        coEvery { repository.getWeatherByCity("上海") } returns Result.Success(testWeather)
        coEvery { repository.announceWeather(any()) } returns Result.Success(true)

        viewModel.fetchWeatherByCity("上海")

        coVerify { repository.getWeatherByCity("上海") }
    }

    @Test
    fun `fetchWeatherByCity with blank name does not call repository`() = runTest {
        viewModel.fetchWeatherByCity("")

        coVerify(exactly = 0) { repository.getWeatherByCity(any()) }
    }

    @Test
    fun `planRouteAndAnnounce validates inputs`() = runTest {
        viewModel.planRouteAndAnnounce()

        coVerify(exactly = 0) { repository.planRoute(any(), any(), any()) }
    }

    @Test
    fun `planRouteAndAnnounce calls repository with valid inputs`() = runTest {
        coEvery { repository.planRoute(any(), any(), any()) } returns Result.Success(testRoute)
        coEvery { repository.announceRouteOverview(any()) } returns Result.Success(true)

        viewModel.originText.value = "天安门"
        viewModel.destinationText.value = "故宫"
        viewModel.planRouteAndAnnounce()

        coVerify { repository.planRoute("天安门", "故宫", TransportMode.SUBWAY) }
        coVerify { repository.announceRouteOverview(testRoute) }
    }

    @Test
    fun `announceNextStep increments step index`() = runTest {
        // 模拟有当前路线的状态
        every { repository.tripAssistState } returns flowOf(
            TripAssistState(currentRoute = testRoute, currentStepIndex = 0)
        )
        coEvery { repository.announceRouteStep(any()) } returns Result.Success(true)

        val viewModelWithRoute = TripAssistViewModel(repository)
        viewModelWithRoute.announceNextStep()

        coVerify { repository.announceRouteStep(1) }
    }

    @Test
    fun `announcePreviousStep decrements step index`() = runTest {
        every { repository.tripAssistState } returns flowOf(
            TripAssistState(currentRoute = testRoute, currentStepIndex = 1)
        )
        coEvery { repository.announceRouteStep(any()) } returns Result.Success(true)

        val viewModelWithRoute = TripAssistViewModel(repository)
        viewModelWithRoute.announcePreviousStep()

        coVerify { repository.announceRouteStep(0) }
    }

    @Test
    fun `searchAndAnnounceFacilities calls repository`() = runTest {
        val facilities = listOf(
            AccessibleFacility(
                name = "测试盲道",
                type = FacilityType.TACTILE_PAVING,
                distance = 50f,
                direction = "前方",
                address = "测试地址"
            )
        )
        coEvery { repository.searchNearbyFacilities(any(), any(), any(), any()) } returns Result.Success(facilities)
        coEvery { repository.announceNearbyFacilities(any()) } returns Result.Success(true)

        viewModel.searchAndAnnounceFacilities(39.9, 116.4)

        coVerify { repository.searchNearbyFacilities(39.9, 116.4, any(), any()) }
        coVerify { repository.announceNearbyFacilities(facilities) }
    }

    @Test
    fun `setTransportMode updates selected mode`() = runTest {
        viewModel.setTransportMode(TransportMode.BUS)
        assertEquals(TransportMode.BUS, viewModel.selectedTransportMode.value)
    }

    @Test
    fun `clearError removes error message`() = runTest {
        // 模拟有错误的状态
        every { repository.tripAssistState } returns flowOf(
            TripAssistState(error = "测试错误")
        )
        val viewModelWithError = TripAssistViewModel(repository)
        assertEquals("测试错误", viewModelWithError.uiState.value.error)

        viewModelWithError.clearError()
        assertNull(viewModelWithError.uiState.value.error)
    }

    @Test
    fun `WeatherInfo toVoiceText generates correct text`() {
        val voiceText = testWeather.toVoiceText()
        assertTrue(voiceText.contains("北京"))
        assertTrue(voiceText.contains("25度"))
        assertTrue(voiceText.contains("晴"))
    }

    @Test
    fun `WeatherInfo needsTravelWarning returns false for good weather`() {
        assertFalse(testWeather.needsTravelWarning())
    }

    @Test
    fun `WeatherInfo needsTravelWarning returns true for storm`() {
        val stormWeather = testWeather.copy(condition = WeatherCondition.STORM)
        assertTrue(stormWeather.needsTravelWarning())
    }

    @Test
    fun `RoutePlan toOverviewVoiceText contains key info`() {
        val overview = testRoute.toOverviewVoiceText()
        assertTrue(overview.contains("地铁"))
        assertTrue(overview.contains("天安门"))
        assertTrue(overview.contains("故宫"))
        assertTrue(overview.contains("无障碍"))
    }

    @Test
    fun `RoutePlan toStepVoiceTexts returns correct count`() {
        val stepTexts = testRoute.toStepVoiceTexts()
        assertEquals(2, stepTexts.size)
        assertTrue(stepTexts[0].contains("第1步"))
        assertTrue(stepTexts[1].contains("第2步"))
    }

    @Test
    fun `AccessibleFacility toVoiceText generates correct text`() {
        val facility = AccessibleFacility(
            name = "测试电梯",
            type = FacilityType.ACCESSIBLE_ELEVATOR,
            distance = 100f,
            direction = "右前方",
            address = "测试地址"
        )
        val voiceText = facility.toVoiceText()
        assertTrue(voiceText.contains("右前方"))
        assertTrue(voiceText.contains("100米"))
        assertTrue(voiceText.contains("无障碍电梯"))
        assertTrue(voiceText.contains("测试电梯"))
    }

    @Test
    fun `TransportMode getRecommendedModes returns subway first`() {
        val modes = TransportMode.getRecommendedModes()
        assertEquals(TransportMode.SUBWAY, modes.first())
    }

    @Test
    fun `FacilityType getPriorityFacilities returns tactile paving first`() {
        val facilities = FacilityType.getPriorityFacilities()
        assertEquals(FacilityType.TACTILE_PAVING, facilities.first())
    }
}
