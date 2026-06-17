package com.blindpath.base.i18n

import android.content.Context
import android.content.SharedPreferences
import timber.log.Timber
import java.util.Locale

/**
 * 语言管理器
 * 管理应用的多语言支持
 */
class LanguageManager(
    private val context: Context
) {
    companion object {
        private const val PREFS_NAME = "language_settings"
        private const val KEY_SELECTED_LANGUAGE = "selected_language"
        private const val KEY_FOLLOW_SYSTEM = "follow_system"
        
        /**
         * 支持的语言列表
         */
        val SUPPORTED_LANGUAGES = listOf(
            Language("zh", "简体中文", Locale.SIMPLIFIED_CHINESE),
            Language("zh_TW", "繁體中文", Locale.TRADITIONAL_CHINESE),
            Language("en", "English", Locale.ENGLISH),
            Language("ja", "日本語", Locale.JAPANESE),
            Language("ko", "한국어", Locale.KOREAN)
        )
        
        /**
         * 获取语言代码
         */
        fun getLanguageCode(locale: Locale): String {
            return when (locale.language) {
                "zh" -> {
                    if (locale.country == "TW" || locale.country == "HK") "zh_TW"
                    else "zh"
                }
                else -> locale.language
            }
        }
    }
    
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    
    /**
     * 语言数据类
     */
    data class Language(
        val code: String,
        val displayName: String,
        val locale: Locale
    )
    
    /**
     * 是否跟随系统语言
     */
    var followSystem: Boolean
        get() = prefs.getBoolean(KEY_FOLLOW_SYSTEM, true)
        set(value) {
            prefs.edit().putBoolean(KEY_FOLLOW_SYSTEM, value).apply()
        }
    
    /**
     * 当前选择的语言
     */
    var selectedLanguage: Language
        get() {
            if (followSystem) {
                return getSystemLanguage()
            }
            val code = prefs.getString(KEY_SELECTED_LANGUAGE, null) ?: return getDefaultLanguage()
            return SUPPORTED_LANGUAGES.find { it.code == code } ?: getDefaultLanguage()
        }
        set(value) {
            prefs.edit()
                .putString(KEY_SELECTED_LANGUAGE, value.code)
                .putBoolean(KEY_FOLLOW_SYSTEM, false)
                .apply()
            Timber.d("Language changed to: ${value.code}")
        }
    
    /**
     * 获取系统语言（匹配支持的语言）
     */
    private fun getSystemLanguage(): Language {
        val systemLocale = Locale.getDefault()
        val code = getLanguageCode(systemLocale)
        
        return SUPPORTED_LANGUAGES.find { it.code == code } ?: getDefaultLanguage()
    }
    
    /**
     * 获取默认语言（简体中文）
     */
    private fun getDefaultLanguage(): Language {
        return SUPPORTED_LANGUAGES.first { it.code == "zh" }
    }
    
    /**
     * 获取当前 Locale
     */
    fun getCurrentLocale(): Locale {
        return selectedLanguage.locale
    }
    
    /**
     * 获取所有支持的语言
     */
    fun getSupportedLanguages(): List<Language> {
        return SUPPORTED_LANGUAGES
    }
    
    /**
     * 切换到下一个支持的语言
     */
    fun cycleToNextLanguage(): Language {
        val currentIndex = SUPPORTED_LANGUAGES.indexOf(selectedLanguage)
        val nextIndex = (currentIndex + 1) % SUPPORTED_LANGUAGES.size
        val nextLanguage = SUPPORTED_LANGUAGES[nextIndex]
        selectedLanguage = nextLanguage
        return nextLanguage
    }
    
    /**
     * 检查是否是 RTL 语言
     */
    fun isRtlLanguage(): Boolean {
        val locale = getCurrentLocale()
        return when (locale.language) {
            "ar", "he", "fa", "ur" -> true
            else -> false
        }
    }
    
    /**
     * 获取语音播报语言设置
     */
    fun getTtsLanguage(): Locale {
        return getCurrentLocale()
    }
    
    /**
     * 检查 TTS 是否支持当前语言
     */
    fun isTtsLanguageSupported(): Boolean {
        // 基本检查，实际需要检查 TTS 引擎支持
        val locale = getCurrentLocale()
        return locale.language in listOf("zh", "en", "ja", "ko")
    }
}
