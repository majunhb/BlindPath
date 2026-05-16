package com.blindpath.module_community.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "community_settings")

/**
 * 用户角色
 */
enum class UserRole(val displayName: String) {
    VOLUNTEER("志愿者"),
    BLIND_USER("视障用户")
}

/**
 * 出行陪伴请求状态
 */
enum class AccompanyRequestStatus(val displayName: String) {
    PENDING("待响应"),
    ACCEPTED("已接受"),
    COMPLETED("已完成"),
    CANCELLED("已取消")
}

/**
 * 志愿者信息
 */
data class Volunteer(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val phone: String,
    val city: String,
    val availableTime: String,  // 可服务时间段
    val serviceCount: Int = 0,   // 已服务次数
    val rating: Float = 5.0f     // 评分
)

/**
 * 出行陪伴请求
 */
data class AccompanyRequest(
    val id: String = UUID.randomUUID().toString(),
    val userId: String,
    val userName: String,
    val userPhone: String,
    val startLocation: String,
    val endLocation: String,
    val estimatedDuration: String,
    val status: AccompanyRequestStatus = AccompanyRequestStatus.PENDING,
    val volunteerId: String? = null,
    val volunteerName: String? = null,
    val createdAt: Long = System.currentTimeMillis()
)

/**
 * 社区用户信息
 */
data class CommunityUser(
    val id: String = UUID.randomUUID().toString(),
    val role: UserRole = UserRole.BIND_USER,
    val name: String = "",
    val phone: String = "",
    val city: String = "",
    val volunteerInfo: Volunteer? = null
)

/**
 * 社区数据模型
 */
data class CommunityData(
    val currentUser: CommunityUser = CommunityUser(),
    val myRequests: List<AccompanyRequest> = emptyList(),
    val nearbyVolunteers: List<Volunteer> = emptyList()
)

@Singleton
class CommunityRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val USER_ROLE = stringPreferencesKey("user_role")
        val USER_NAME = stringPreferencesKey("user_name")
        val USER_PHONE = stringPreferencesKey("user_phone")
        val USER_CITY = stringPreferencesKey("user_city")
        val IS_VOLUNTEER_REGISTERED = booleanPreferencesKey("is_volunteer_registered")
        val VOLUNTEER_AVAILABLE_TIME = stringPreferencesKey("volunteer_available_time")
        val REQUEST_COUNT = intPreferencesKey("request_count")
    }

    val communityUser: Flow<CommunityUser> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            val role = try {
                UserRole.valueOf(preferences[Keys.USER_ROLE] ?: "BLIND_USER")
            } catch (e: Exception) {
                UserRole.BIND_USER
            }

            val volunteerInfo = if (role == UserRole.VOLUNTEER || preferences[Keys.IS_VOLUNTEER_REGISTERED] == true) {
                Volunteer(
                    name = preferences[Keys.USER_NAME] ?: "",
                    phone = preferences[Keys.USER_PHONE] ?: "",
                    city = preferences[Keys.USER_CITY] ?: "",
                    availableTime = preferences[Keys.VOLUNTEER_AVAILABLE_TIME] ?: "",
                    serviceCount = preferences[Keys.REQUEST_COUNT] ?: 0
                )
            } else null

            CommunityUser(
                role = role,
                name = preferences[Keys.USER_NAME] ?: "",
                phone = preferences[Keys.USER_PHONE] ?: "",
                city = preferences[Keys.USER_CITY] ?: "",
                volunteerInfo = volunteerInfo
            )
        }

    suspend fun updateUserProfile(name: String, phone: String, city: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.USER_NAME] = name
            preferences[Keys.USER_PHONE] = phone
            preferences[Keys.USER_CITY] = city
        }
    }

    suspend fun registerAsVolunteer(availableTime: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.USER_ROLE] = UserRole.VOLUNTEER.name
            preferences[Keys.IS_VOLUNTEER_REGISTERED] = true
            preferences[Keys.VOLUNTEER_AVAILABLE_TIME] = availableTime
        }
    }

    suspend fun switchToBlindUser() {
        context.dataStore.edit { preferences ->
            preferences[Keys.USER_ROLE] = UserRole.BIND_USER.name
        }
    }

    suspend fun incrementServiceCount() {
        context.dataStore.edit { preferences ->
            val current = preferences[Keys.REQUEST_COUNT] ?: 0
            preferences[Keys.REQUEST_COUNT] = current + 1
        }
    }
}
