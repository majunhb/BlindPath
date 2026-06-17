package com.blindpath.module_community.ui

import com.blindpath.module_community.data.*
import io.mockk.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class CommunityViewModelTest {

    private val testDispatcher = StandardTestDispatcher()
    private lateinit var repository: CommunityRepository
    private lateinit var userFlow: MutableStateFlow<CommunityUser>
    private lateinit var viewModel: CommunityViewModel

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
        userFlow = MutableStateFlow(CommunityUser())
        repository = mockk(relaxed = true) {
            every { communityUser } returns userFlow
        }
        viewModel = CommunityViewModel(repository)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state should show loading with empty data`() {
        val state = viewModel.uiState.value
        assertTrue("Initial state should be loading", state.isLoading)
        assertEquals(emptyList<AccompanyRequest>(), state.requests)
        assertEquals(CommunityUser(), state.currentUser)
        assertNull(state.showSuccessMessage)
    }

    @Test
    fun `after initialization, loading should be false and volunteers populated`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse("Loading should be false after init", state.isLoading)
        assertTrue("Mock volunteers should be loaded", state.volunteers.isNotEmpty())
        assertEquals(3, state.volunteers.size)
    }

    @Test
    fun `updateUserProfile should call repository and show success`() = runTest {
        viewModel.updateUserProfile("张三", "13800138000", "北京")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.updateUserProfile("张三", "13800138000", "北京") }
        assertNotNull(viewModel.uiState.value.showSuccessMessage)
    }

    @Test
    fun `registerAsVolunteer should call repository and show success`() = runTest {
        viewModel.registerAsVolunteer("周末 9:00-18:00")
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.registerAsVolunteer("周末 9:00-18:00") }
        assertNotNull(viewModel.uiState.value.showSuccessMessage)
    }

    @Test
    fun `switchToBlindUser should delegate to repository`() = runTest {
        viewModel.switchToBlindUser()
        testDispatcher.scheduler.advanceUntilIdle()

        coVerify(exactly = 1) { repository.switchToBlindUser() }
    }

    @Test
    fun `requestAccompany should add to requests and show success`() = runTest {
        viewModel.requestAccompany("北京西站", "北京站", "30分钟")
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals(1, state.requests.size)
        assertEquals("北京西站", state.requests.first().startLocation)
        assertEquals("北京站", state.requests.first().endLocation)
        assertEquals(AccompanyRequestStatus.PENDING, state.requests.first().status)
        assertNotNull(state.showSuccessMessage)
    }

    @Test
    fun `cancelRequest should update status to CANCELLED`() = runTest {
        viewModel.requestAccompany("起点", "终点", "1小时")
        testDispatcher.scheduler.advanceUntilIdle()

        val requestId = viewModel.uiState.value.requests.first().id
        viewModel.cancelRequest(requestId)

        val cancelled = viewModel.uiState.value.requests.first { it.id == requestId }
        assertEquals(AccompanyRequestStatus.CANCELLED, cancelled.status)
    }

    @Test
    fun `completeRequest should update status and increment service count`() = runTest {
        viewModel.requestAccompany("起点", "终点", "1小时")
        testDispatcher.scheduler.advanceUntilIdle()

        val requestId = viewModel.uiState.value.requests.first().id
        viewModel.completeRequest(requestId)
        testDispatcher.scheduler.advanceUntilIdle()

        val completed = viewModel.uiState.value.requests.first { it.id == requestId }
        assertEquals(AccompanyRequestStatus.COMPLETED, completed.status)
        coVerify(exactly = 1) { repository.incrementServiceCount() }
    }

    @Test
    fun `dismissMessage should clear success message`() = runTest {
        viewModel.requestAccompany("起点", "终点", "1小时")
        testDispatcher.scheduler.advanceUntilIdle()
        assertNotNull(viewModel.uiState.value.showSuccessMessage)

        viewModel.dismissMessage()
        assertNull(viewModel.uiState.value.showSuccessMessage)
    }

    @Test
    fun `mock volunteers should have valid data`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val volunteers = viewModel.uiState.value.volunteers

        volunteers.forEach { volunteer ->
            assertTrue("Name should not be empty", volunteer.name.isNotEmpty())
            assertTrue("Phone should match mask pattern", volunteer.phone.contains("****"))
            assertEquals("北京", volunteer.city)
            assertTrue("Service count should be non-negative", volunteer.serviceCount >= 0)
            assertTrue("Rating should be between 0 and 5", volunteer.rating in 0f..5f)
        }
    }

    @Test
    fun `user flow update should reflect in ui State`() = runTest {
        testDispatcher.scheduler.advanceUntilIdle()
        val updated = CommunityUser(
            name = "张三",
            phone = "13800138000",
            city = "北京"
        )
        userFlow.value = updated
        testDispatcher.scheduler.advanceUntilIdle()

        val state = viewModel.uiState.value
        assertEquals("张三", state.currentUser.name)
        assertEquals("13800138000", state.currentUser.phone)
        assertEquals("北京", state.currentUser.city)
    }
}

class CommunityModelsTest {

    @Test
    fun `default CommunityUser should have BLIND_USER role`() {
        val user = CommunityUser()
        assertEquals(UserRole.BLIND_USER, user.role)
        assertNotNull(user.id)
    }

    @Test
    fun `AccompanyRequest should default to PENDING status`() {
        val request = AccompanyRequest(
            userId = "user1",
            userName = "张三",
            userPhone = "138****0000",
            startLocation = "起点",
            endLocation = "终点",
            estimatedDuration = "30分钟"
        )
        assertEquals(AccompanyRequestStatus.PENDING, request.status)
        assertNotNull(request.id)
        assertTrue(request.createdAt > 0)
    }

    @Test
    fun `AccompanyRequest copy should update status correctly`() {
        val request = AccompanyRequest(
            userId = "user1",
            userName = "张三",
            userPhone = "138****0000",
            startLocation = "起点",
            endLocation = "终点",
            estimatedDuration = "30分钟"
        )
        val cancelled = request.copy(status = AccompanyRequestStatus.CANCELLED)
        assertEquals(AccompanyRequestStatus.CANCELLED, cancelled.status)
        assertEquals(request.id, cancelled.id)
    }

    @Test
    fun `Volunteer default rating should be 5 point 0`() {
        val volunteer = Volunteer(
            name = "张三",
            phone = "138****0000",
            city = "北京",
            availableTime = "周末"
        )
        assertEquals(5.0f, volunteer.rating)
        assertEquals(0, volunteer.serviceCount)
    }
}