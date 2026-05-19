package com.blindpath.base.security

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.util.Date

/**
 * 隐私合规管理器
 * 管理用户隐私设置和合规相关功能
 */
class PrivacyManager(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "privacy_settings"
        private const val KEY_PRIVACY_ACCEPTED = "privacy_accepted"
        private const val KEY_PRIVACY_ACCEPTED_DATE = "privacy_accepted_date"
        private const val KEY_PRIVACY_VERSION = "privacy_version"
        private const val KEY_LOCATION_DATA_COLLECTED = "location_data_consent"
        private const val KEY_USAGE_DATA_COLLECTED = "usage_data_consent"
        private const val KEY_CRASH_DATA_COLLECTED = "crash_data_consent"
        private const val KEY_CAMERA_DATA_COLLECTED = "camera_data_consent"
        private const val KEY_LAST_PRIVACY_REVIEW = "last_privacy_review"
        
        const val CURRENT_PRIVACY_VERSION = 2
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 隐私设置
     */
    data class PrivacySettings(
        val isPrivacyAccepted: Boolean,
        val privacyAcceptedDate: Long?,
        val privacyVersion: Int,
        val isLocationDataAllowed: Boolean,
        val isUsageDataAllowed: Boolean,
        val isCrashDataAllowed: Boolean,
        val isCameraDataAllowed: Boolean
    )
    
    /**
     * 检查是否已接受隐私政策
     */
    val isPrivacyAccepted: Boolean
        get() = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false)
    
    /**
     * 检查是否需要显示隐私政策
     */
    fun shouldShowPrivacyPolicy(): Boolean {
        if (!isPrivacyAccepted) return true
        
        // 如果隐私政策版本更新，需要重新确认
        val acceptedVersion = prefs.getInt(KEY_PRIVACY_VERSION, 0)
        return acceptedVersion < CURRENT_PRIVACY_VERSION
    }
    
    /**
     * 接受隐私政策
     */
    fun acceptPrivacyPolicy(
        allowLocationData: Boolean = true,
        allowUsageData: Boolean = true,
        allowCrashData: Boolean = true,
        allowCameraData: Boolean = true
    ) {
        prefs.edit()
            .putBoolean(KEY_PRIVACY_ACCEPTED, true)
            .putLong(KEY_PRIVACY_ACCEPTED_DATE, System.currentTimeMillis())
            .putInt(KEY_PRIVACY_VERSION, CURRENT_PRIVACY_VERSION)
            .putBoolean(KEY_LOCATION_DATA_COLLECTED, allowLocationData)
            .putBoolean(KEY_USAGE_DATA_COLLECTED, allowUsageData)
            .putBoolean(KEY_CRASH_DATA_COLLECTED, allowCrashData)
            .putBoolean(KEY_CAMERA_DATA_COLLECTED, allowCameraData)
            .apply()
        
        Timber.d("Privacy policy accepted with settings: location=$allowLocationData, usage=$allowUsageData, crash=$allowCrashData, camera=$allowCameraData")
    }
    
    /**
     * 撤销隐私政策同意
     */
    fun revokePrivacyPolicy() {
        prefs.edit()
            .putBoolean(KEY_PRIVACY_ACCEPTED, false)
            .putLong(KEY_PRIVACY_ACCEPTED_DATE, 0)
            .apply()
        
        Timber.d("Privacy policy revoked")
    }
    
    /**
     * 获取当前隐私设置
     */
    fun getPrivacySettings(): PrivacySettings {
        return PrivacySettings(
            isPrivacyAccepted = prefs.getBoolean(KEY_PRIVACY_ACCEPTED, false),
            privacyAcceptedDate = prefs.getLong(KEY_PRIVACY_ACCEPTED_DATE, 0).takeIf { it > 0 },
            privacyVersion = prefs.getInt(KEY_PRIVACY_VERSION, 0),
            isLocationDataAllowed = prefs.getBoolean(KEY_LOCATION_DATA_COLLECTED, false),
            isUsageDataAllowed = prefs.getBoolean(KEY_USAGE_DATA_COLLECTED, false),
            isCrashDataAllowed = prefs.getBoolean(KEY_CRASH_DATA_COLLECTED, false),
            isCameraDataAllowed = prefs.getBoolean(KEY_CAMERA_DATA_COLLECTED, false)
        )
    }
    
    /**
     * 更新数据收集偏好
     */
    fun updateDataCollectionPreferences(
        allowLocation: Boolean? = null,
        allowUsage: Boolean? = null,
        allowCrash: Boolean? = null,
        allowCamera: Boolean? = null
    ) {
        val editor = prefs.edit()
        
        allowLocation?.let { editor.putBoolean(KEY_LOCATION_DATA_COLLECTED, it) }
        allowUsage?.let { editor.putBoolean(KEY_USAGE_DATA_COLLECTED, it) }
        allowCrash?.let { editor.putBoolean(KEY_CRASH_DATA_COLLECTED, it) }
        allowCamera?.let { editor.putBoolean(KEY_CAMERA_DATA_COLLECTED, it) }
        
        editor.apply()
        
        Timber.d("Data collection preferences updated")
    }
    
    /**
     * 检查是否允许收集位置数据
     */
    val isLocationDataCollectionAllowed: Boolean
        get() = isPrivacyAccepted && prefs.getBoolean(KEY_LOCATION_DATA_COLLECTED, false)
    
    /**
     * 检查是否允许收集使用数据
     */
    val isUsageDataCollectionAllowed: Boolean
        get() = isPrivacyAccepted && prefs.getBoolean(KEY_USAGE_DATA_COLLECTED, false)
    
    /**
     * 检查是否允许收集崩溃数据
     */
    val isCrashDataCollectionAllowed: Boolean
        get() = isPrivacyAccepted && prefs.getBoolean(KEY_CRASH_DATA_COLLECTED, false)
    
    /**
     * 检查是否允许使用摄像头数据
     */
    val isCameraDataCollectionAllowed: Boolean
        get() = isPrivacyAccepted && prefs.getBoolean(KEY_CAMERA_DATA_COLLECTED, false)
    
    /**
     * 记录隐私审查
     */
    fun recordPrivacyReview() {
        prefs.edit()
            .putLong(KEY_LAST_PRIVACY_REVIEW, System.currentTimeMillis())
            .apply()
    }
    
    /**
     * 检查是否需要隐私审查提醒
     * 每6个月提醒一次
     */
    fun shouldShowPrivacyReviewReminder(): Boolean {
        val lastReview = prefs.getLong(KEY_LAST_PRIVACY_REVIEW, 0)
        if (lastReview == 0L) return false
        
        val sixMonthsInMillis = 6L * 30 * 24 * 60 * 60 * 1000
        return System.currentTimeMillis() - lastReview > sixMonthsInMillis
    }
    
    /**
     * 生成隐私报告（用户可导出）
     */
    fun generatePrivacyReport(): String {
        val settings = getPrivacySettings()
        val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.getDefault())
        
        return """
            |=== BlindPath 隐私报告 ===
            |
            |生成时间: ${dateFormat.format(Date())}
            |
            |1. 隐私政策状态
            |   - 已接受: ${if (settings.isPrivacyAccepted) "是" else "否"}
            |   - 接受时间: ${settings.privacyAcceptedDate?.let { dateFormat.format(Date(it)) } ?: "未接受"}
            |   - 政策版本: ${settings.privacyVersion}
            |
            |2. 数据收集偏好
            |   - 位置数据: ${if (settings.isLocationDataAllowed) "允许" else "不允许"}
            |   - 使用数据: ${if (settings.isUsageDataAllowed) "允许" else "不允许"}
            |   - 崩溃数据: ${if (settings.isCrashDataAllowed) "允许" else "不允许"}
            |   - 摄像头数据: ${if (settings.isCameraDataAllowed) "允许" else "不允许"}
            |
            |3. 数据处理说明
            |   - 位置数据: 用于导航服务，仅存储在本地设备
            |   - 摄像头数据: 用于障碍物检测，实时处理不上传
            |   - 崩溃数据: 用于改进应用稳定性
            |   - 使用数据: 用于优化用户体验
            |
            |4. 数据删除
            |   - 您可以随时通过应用设置删除所有本地数据
            |   - 如需删除服务器数据，请联系我们
            |
            |5. 联系我们
            |   - 邮箱: privacy@blindpath.app
            |   - 网站: https://blindpath.app/privacy
        """.trimMargin()
    }
    
    /**
     * 删除所有用户数据
     */
    fun deleteAllUserData() {
        // 清除隐私设置
        prefs.edit().clear().apply()
        
        // 清除缓存
        context.cacheDir.deleteRecursively()
        
        // 清除共享偏好
        context.getSharedPreferences("user_data", Context.MODE_PRIVATE).edit().clear().apply()
        
        Timber.d("All user data deleted")
    }
}
