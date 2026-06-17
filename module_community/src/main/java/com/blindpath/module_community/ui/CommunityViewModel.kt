package com.blindpath.module_community.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.blindpath.module_community.data.*
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CommunityUiState(
    val currentUser: CommunityUser = CommunityUser(),
    val requests: List<AccompanyRequest> = emptyList(),
    val volunteers: List<Volunteer> = emptyList(),
    val isLoading: Boolean = true,
    val showSuccessMessage: String? = null
)

@HiltViewModel
class CommunityViewModel @Inject constructor(
    private val communityRepository: CommunityRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(CommunityUiState())
    val uiState: StateFlow<CommunityUiState> = _uiState.asStateFlow()

    // 模拟志愿者数据（实际项目中应从服务器获取）
    private val mockVolunteers = listOf(
        Volunteer(
            name = "张爱心",
            phone = "138****1234",
            city = "北京",
            availableTime = "周末 9:00-18:00",
            serviceCount = 28,
            rating = 4.9f
        ),
        Volunteer(
            name = "李志愿",
            phone = "139****5678",
            city = "北京",
            availableTime = "工作日下班后",
            serviceCount = 15,
            rating = 4.8f
        ),
        Volunteer(
            name = "王助人",
            phone = "137****9012",
            city = "北京",
            availableTime = "随时可联系",
            serviceCount = 42,
            rating = 5.0f
        )
    )

    init {
        viewModelScope.launch {
            communityRepository.communityUser.collect { user ->
                _uiState.update {
                    it.copy(
                        currentUser = user,
                        volunteers = mockVolunteers,
                        isLoading = false
                    )
                }
            }
        }
    }

    fun updateUserProfile(name: String, phone: String, city: String) {
        viewModelScope.launch {
            communityRepository.updateUserProfile(name, phone, city)
            showSuccess("个人信息已保存")
        }
    }

    fun registerAsVolunteer(availableTime: String) {
        viewModelScope.launch {
            communityRepository.registerAsVolunteer(availableTime)
            showSuccess("已注册成为志愿者，感谢您的爱心！")
        }
    }

    fun switchToBlindUser() {
        viewModelScope.launch {
            communityRepository.switchToBlindUser()
        }
    }

    fun requestAccompany(
        startLocation: String,
        endLocation: String,
        estimatedDuration: String
    ) {
        viewModelScope.launch {
            val user = _uiState.value.currentUser
            val request = AccompanyRequest(
                userId = user.id,
                userName = user.name,
                userPhone = user.phone,
                startLocation = startLocation,
                endLocation = endLocation,
                estimatedDuration = estimatedDuration
            )
            
            // 添加到请求列表（实际项目中应发送到服务器）
            _uiState.update { state ->
                state.copy(requests = state.requests + request)
            }
            showSuccess("陪伴请求已发布，志愿者将尽快响应")
        }
    }

    fun cancelRequest(requestId: String) {
        _uiState.update { state ->
            state.copy(
                requests = state.requests.map { request ->
                    if (request.id == requestId) {
                        request.copy(status = AccompanyRequestStatus.CANCELLED)
                    } else request
                }
            )
        }
        showSuccess("请求已取消")
    }

    fun completeRequest(requestId: String) {
        viewModelScope.launch {
            _uiState.update { state ->
                state.copy(
                    requests = state.requests.map { request ->
                        if (request.id == requestId) {
                            request.copy(status = AccompanyRequestStatus.COMPLETED)
                        } else request
                    }
                )
            }
            communityRepository.incrementServiceCount()
            showSuccess("感谢志愿者，服务已完成！")
        }
    }

    private fun showSuccess(message: String) {
        viewModelScope.launch {
            _uiState.update { it.copy(showSuccessMessage = message) }
            kotlinx.coroutines.delay(3000)
            _uiState.update { it.copy(showSuccessMessage = null) }
        }
    }

    fun dismissMessage() {
        _uiState.update { it.copy(showSuccessMessage = null) }
    }
}
