package com.blindpath.module_settings.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

/**
 * 应用设置数据类
 */
data class AppSettings(
    // 紧急联系人
    val emergencyContact: String = "",
    val emergencyName: String = "",
    
    // 语音设置
    val speechRate: Float = 1.0f,      // 0.5 - 2.0
    val speechPitch: Float = 1.0f,     // 0.5 - 2.0
    val voiceEnabled: Boolean = true,
    
    // 振动设置
    val vibrationEnabled: Boolean = true,
    val vibrationIntensity: VibrationIntensity = VibrationIntensity.MEDIUM,
    
    // 检测设置
    val detectionSensitivity: DetectionSensitivity = DetectionSensitivity.MEDIUM,
    val detectionDistance: Int = 5,    // 检测距离（米）
    
    // 隐私设置
    val autoLocationShare: Boolean = false
)

enum class VibrationIntensity(val value: Int, val displayName: String) {
    LOW(1, "轻柔"),
    MEDIUM(2, "中等"),
    HIGH(3, "强烈")
}

enum class DetectionSensitivity(val value: Float, val displayName: String) {
    LOW(0.7f, "低（远距离检测）"),
    MEDIUM(1.0f, "中（标准）"),
    HIGH(1.3f, "高（近距离敏感）")
}

@Singleton
class SettingsRepository @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private object Keys {
        val EMERGENCY_CONTACT = stringPreferencesKey("emergency_contact")
        val EMERGENCY_NAME = stringPreferencesKey("emergency_name")
        val SPEECH_RATE = floatPreferencesKey("speech_rate")
        val SPEECH_PITCH = floatPreferencesKey("speech_pitch")
        val VOICE_ENABLED = booleanPreferencesKey("voice_enabled")
        val VIBRATION_ENABLED = booleanPreferencesKey("vibration_enabled")
        val VIBRATION_INTENSITY = intPreferencesKey("vibration_intensity")
        val DETECTION_SENSITIVITY = floatPreferencesKey("detection_sensitivity")
        val DETECTION_DISTANCE = intPreferencesKey("detection_distance")
        val AUTO_LOCATION_SHARE = booleanPreferencesKey("auto_location_share")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { preferences ->
            AppSettings(
                emergencyContact = preferences[Keys.EMERGENCY_CONTACT] ?: "",
                emergencyName = preferences[Keys.EMERGENCY_NAME] ?: "",
                speechRate = preferences[Keys.SPEECH_RATE] ?: 1.0f,
                speechPitch = preferences[Keys.SPEECH_PITCH] ?: 1.0f,
                voiceEnabled = preferences[Keys.VOICE_ENABLED] ?: true,
                vibrationEnabled = preferences[Keys.VIBRATION_ENABLED] ?: true,
                vibrationIntensity = VibrationIntensity.entries.find {
                    it.value == (preferences[Keys.VIBRATION_INTENSITY] ?: 2)
                } ?: VibrationIntensity.MEDIUM,
                detectionSensitivity = DetectionSensitivity.entries.find {
                    it.value == (preferences[Keys.DETECTION_SENSITIVITY] ?: 1.0f)
                } ?: DetectionSensitivity.MEDIUM,
                detectionDistance = preferences[Keys.DETECTION_DISTANCE] ?: 5,
                autoLocationShare = preferences[Keys.AUTO_LOCATION_SHARE] ?: false
            )
        }

    suspend fun updateEmergencyContact(name: String, phone: String) {
        context.dataStore.edit { preferences ->
            preferences[Keys.EMERGENCY_NAME] = name
            preferences[Keys.EMERGENCY_CONTACT] = phone
        }
    }

    suspend fun updateSpeechRate(rate: Float) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SPEECH_RATE] = rate.coerceIn(0.5f, 2.0f)
        }
    }

    suspend fun updateSpeechPitch(pitch: Float) {
        context.dataStore.edit { preferences ->
            preferences[Keys.SPEECH_PITCH] = pitch.coerceIn(0.5f, 2.0f)
        }
    }

    suspend fun updateVoiceEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.VOICE_ENABLED] = enabled
        }
    }

    suspend fun updateVibrationEnabled(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.VIBRATION_ENABLED] = enabled
        }
    }

    suspend fun updateVibrationIntensity(intensity: VibrationIntensity) {
        context.dataStore.edit { preferences ->
            preferences[Keys.VIBRATION_INTENSITY] = intensity.value
        }
    }

    suspend fun updateDetectionSensitivity(sensitivity: DetectionSensitivity) {
        context.dataStore.edit { preferences ->
            preferences[Keys.DETECTION_SENSITIVITY] = sensitivity.value
        }
    }

    suspend fun updateDetectionDistance(distance: Int) {
        context.dataStore.edit { preferences ->
            preferences[Keys.DETECTION_DISTANCE] = distance.coerceIn(2, 10)
        }
    }

    suspend fun updateAutoLocationShare(enabled: Boolean) {
        context.dataStore.edit { preferences ->
            preferences[Keys.AUTO_LOCATION_SHARE] = enabled
        }
    }
}
