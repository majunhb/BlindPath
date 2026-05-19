package com.blindpath.base.i18n

import android.content.Context
import timber.log.Timber
import java.util.Locale

/**
 * 字符串资源管理器
 * 提供多语言字符串访问
 */
class StringResources(
    private val context: Context
) {
    
    // 缓存已加载的字符串
    private val stringCache = mutableMapOf<String, Map<String, String>>()
    
    /**
     * 获取本地化字符串
     */
    fun getString(key: String): String {
        return getString(key, LanguageManager(context).getCurrentLocale())
    }
    
    /**
     * 获取本地化字符串（带格式化参数）
     */
    fun getString(key: String, vararg args: Any): String {
        val template = getString(key)
        return try {
            String.format(template, *args)
        } catch (e: Exception) {
            Timber.w(e, "Failed to format string: $key")
            template
        }
    }
    
    /**
     * 获取指定语言的字符串
     */
    fun getString(key: String, locale: Locale): String {
        val langCode = LanguageManager.getLanguageCode(locale)
        val strings = getStringsForLanguage(langCode)
        return strings[key] ?: getDefaultStrings()[key] ?: key
    }
    
    /**
     * 获取语言的所有字符串
     */
    private fun getStringsForLanguage(langCode: String): Map<String, String> {
        return stringCache.getOrPut(langCode) {
            loadStringsForLanguage(langCode)
        }
    }
    
    /**
     * 加载语言的字符串资源
     */
    private fun loadStringsForLanguage(langCode: String): Map<String, String> {
        // 从代码中定义的字符串资源加载
        return when (langCode) {
            "zh" -> ChineseStrings.strings
            "zh_TW" -> TraditionalChineseStrings.strings
            "en" -> EnglishStrings.strings
            "ja" -> JapaneseStrings.strings
            "ko" -> KoreanStrings.strings
            else -> getDefaultStrings()
        }
    }
    
    /**
     * 获取默认字符串（简体中文）
     */
    private fun getDefaultStrings(): Map<String, String> = ChineseStrings.strings
}

/**
 * 字符串键定义
 */
object StringKeys {
    // 应用名称
    const val APP_NAME = "app_name"
    
    // 功能名称
    const val OBSTACLE_DETECTION = "obstacle_detection"
    const val NAVIGATION = "navigation"
    const val VOICE_ASSISTANT = "voice_assistant"
    const val SETTINGS = "settings"
    const val SOS = "sos"
    
    // 障碍物类型
    const val OBSTACLE_PERSON = "obstacle_person"
    const val OBSTACLE_VEHICLE = "obstacle_vehicle"
    const val OBSTACLE_STAIRS = "obstacle_stairs"
    const val OBSTACLE_DOOR = "obstacle_door"
    
    // 预警级别
    const val ALERT_DANGER = "alert_danger"
    const val ALERT_WARNING = "alert_warning"
    const val ALERT_CAUTION = "alert_caution"
    const val ALERT_SAFE = "alert_safe"
    
    // 导航指令
    const val NAV_TURN_LEFT = "nav_turn_left"
    const val NAV_TURN_RIGHT = "nav_turn_right"
    const val NAV_GO_STRAIGHT = "nav_go_straight"
    const val NAV_ARRIVED = "nav_arrived"
    const val NAV_DISTANCE_FORMAT = "nav_distance_format"
    
    // 系统消息
    const val PERMISSION_REQUIRED = "permission_required"
    const val LOCATION_DISABLED = "location_disabled"
    const val NETWORK_ERROR = "network_error"
    const val LOADING = "loading"
    const val ERROR_OCCURRED = "error_occurred"
    
    // 设置项
    const val SETTINGS_LANGUAGE = "settings_language"
    const val SETTINGS_VOICE = "settings_voice"
    const val SETTINGS_ABOUT = "settings_about"
    const val SETTINGS_PRIVACY = "settings_privacy"
}

/**
 * 简体中文字符串
 */
object ChineseStrings {
    val strings = mapOf(
        StringKeys.APP_NAME to "智行助盲",
        StringKeys.OBSTACLE_DETECTION to "障碍物检测",
        StringKeys.NAVIGATION to "导航指引",
        StringKeys.VOICE_ASSISTANT to "语音助手",
        StringKeys.SETTINGS to "设置",
        StringKeys.SOS to "紧急求助",
        
        StringKeys.OBSTACLE_PERSON to "行人",
        StringKeys.OBSTACLE_VEHICLE to "车辆",
        StringKeys.OBSTACLE_STAIRS to "台阶",
        StringKeys.OBSTACLE_DOOR to "门",
        
        StringKeys.ALERT_DANGER to "危险",
        StringKeys.ALERT_WARNING to "警告",
        StringKeys.ALERT_CAUTION to "注意",
        StringKeys.ALERT_SAFE to "安全",
        
        StringKeys.NAV_TURN_LEFT to "前方左转",
        StringKeys.NAV_TURN_RIGHT to "前方右转",
        StringKeys.NAV_GO_STRAIGHT to "直行",
        StringKeys.NAV_ARRIVED to "已到达目的地",
        StringKeys.NAV_DISTANCE_FORMAT to "前方%.0f米%s",
        
        StringKeys.PERMISSION_REQUIRED to "需要权限才能使用此功能",
        StringKeys.LOCATION_DISABLED to "定位服务未开启",
        StringKeys.NETWORK_ERROR to "网络连接失败",
        StringKeys.LOADING to "加载中...",
        StringKeys.ERROR_OCCURRED to "发生错误",
        
        StringKeys.SETTINGS_LANGUAGE to "语言设置",
        StringKeys.SETTINGS_VOICE to "语音设置",
        StringKeys.SETTINGS_ABOUT to "关于我们",
        StringKeys.SETTINGS_PRIVACY to "隐私政策"
    )
}

/**
 * 英文字符串
 */
object EnglishStrings {
    val strings = mapOf(
        StringKeys.APP_NAME to "BlindPath",
        StringKeys.OBSTACLE_DETECTION to "Obstacle Detection",
        StringKeys.NAVIGATION to "Navigation",
        StringKeys.VOICE_ASSISTANT to "Voice Assistant",
        StringKeys.SETTINGS to "Settings",
        StringKeys.SOS to "Emergency",
        
        StringKeys.OBSTACLE_PERSON to "Person",
        StringKeys.OBSTACLE_VEHICLE to "Vehicle",
        StringKeys.OBSTACLE_STAIRS to "Stairs",
        StringKeys.OBSTACLE_DOOR to "Door",
        
        StringKeys.ALERT_DANGER to "Danger",
        StringKeys.ALERT_WARNING to "Warning",
        StringKeys.ALERT_CAUTION to "Caution",
        StringKeys.ALERT_SAFE to "Safe",
        
        StringKeys.NAV_TURN_LEFT to "Turn left ahead",
        StringKeys.NAV_TURN_RIGHT to "Turn right ahead",
        StringKeys.NAV_GO_STRAIGHT to "Go straight",
        StringKeys.NAV_ARRIVED to "You have arrived",
        StringKeys.NAV_DISTANCE_FORMAT to "%.0f meters ahead, %s",
        
        StringKeys.PERMISSION_REQUIRED to "Permission required",
        StringKeys.LOCATION_DISABLED to "Location service is disabled",
        StringKeys.NETWORK_ERROR to "Network connection failed",
        StringKeys.LOADING to "Loading...",
        StringKeys.ERROR_OCCURRED to "An error occurred",
        
        StringKeys.SETTINGS_LANGUAGE to "Language",
        StringKeys.SETTINGS_VOICE to "Voice",
        StringKeys.SETTINGS_ABOUT to "About",
        StringKeys.SETTINGS_PRIVACY to "Privacy Policy"
    )
}

/**
 * 繁体中文字符串
 */
object TraditionalChineseStrings {
    val strings = mapOf(
        StringKeys.APP_NAME to "智行助盲",
        StringKeys.OBSTACLE_DETECTION to "障礙物偵測",
        StringKeys.NAVIGATION to "導航指引",
        StringKeys.VOICE_ASSISTANT to "語音助手",
        StringKeys.SETTINGS to "設定",
        StringKeys.SOS to "緊急求助",
        
        StringKeys.OBSTACLE_PERSON to "行人",
        StringKeys.OBSTACLE_VEHICLE to "車輛",
        StringKeys.OBSTACLE_STAIRS to "台階",
        StringKeys.OBSTACLE_DOOR to "門",
        
        StringKeys.ALERT_DANGER to "危險",
        StringKeys.ALERT_WARNING to "警告",
        StringKeys.ALERT_CAUTION to "注意",
        StringKeys.ALERT_SAFE to "安全",
        
        StringKeys.NAV_TURN_LEFT to "前方左轉",
        StringKeys.NAV_TURN_RIGHT to "前方右轉",
        StringKeys.NAV_GO_STRAIGHT to "直行",
        StringKeys.NAV_ARRIVED to "已到達目的地",
        StringKeys.NAV_DISTANCE_FORMAT to "前方%.0f公尺%s",
        
        StringKeys.PERMISSION_REQUIRED to "需要權限才能使用此功能",
        StringKeys.LOCATION_DISABLED to "定位服務未開啟",
        StringKeys.NETWORK_ERROR to "網路連線失敗",
        StringKeys.LOADING to "載入中...",
        StringKeys.ERROR_OCCURRED to "發生錯誤",
        
        StringKeys.SETTINGS_LANGUAGE to "語言設定",
        StringKeys.SETTINGS_VOICE to "語音設定",
        StringKeys.SETTINGS_ABOUT to "關於我們",
        StringKeys.SETTINGS_PRIVACY to "隱私權政策"
    )
}

/**
 * 日文字符串
 */
object JapaneseStrings {
    val strings = mapOf(
        StringKeys.APP_NAME to "BlindPath",
        StringKeys.OBSTACLE_DETECTION to "障害物検出",
        StringKeys.NAVIGATION to "ナビゲーション",
        StringKeys.VOICE_ASSISTANT to "音声アシスタント",
        StringKeys.SETTINGS to "設定",
        StringKeys.SOS to "緊急連絡",
        
        StringKeys.OBSTACLE_PERSON to "人",
        StringKeys.OBSTACLE_VEHICLE to "車両",
        StringKeys.OBSTACLE_STAIRS to "階段",
        StringKeys.OBSTACLE_DOOR to "ドア",
        
        StringKeys.ALERT_DANGER to "危険",
        StringKeys.ALERT_WARNING to "警告",
        StringKeys.ALERT_CAUTION to "注意",
        StringKeys.ALERT_SAFE to "安全",
        
        StringKeys.NAV_TURN_LEFT to "前方左折",
        StringKeys.NAV_TURN_RIGHT to "前方右折",
        StringKeys.NAV_GO_STRAIGHT to "直進",
        StringKeys.NAV_ARRIVED to "目的地に到着しました",
        StringKeys.NAV_DISTANCE_FORMAT to "前方%.0fメートル、%s",
        
        StringKeys.PERMISSION_REQUIRED to "権限が必要です",
        StringKeys.LOCATION_DISABLED to "位置情報サービスが無効です",
        StringKeys.NETWORK_ERROR to "ネットワーク接続エラー",
        StringKeys.LOADING to "読み込み中...",
        StringKeys.ERROR_OCCURRED to "エラーが発生しました",
        
        StringKeys.SETTINGS_LANGUAGE to "言語設定",
        StringKeys.SETTINGS_VOICE to "音声設定",
        StringKeys.SETTINGS_ABOUT to "アプリについて",
        StringKeys.SETTINGS_PRIVACY to "プライバシーポリシー"
    )
}

/**
 * 韩文字符串
 */
object KoreanStrings {
    val strings = mapOf(
        StringKeys.APP_NAME to "BlindPath",
        StringKeys.OBSTACLE_DETECTION to "장애물 감지",
        StringKeys.NAVIGATION to "내비게이션",
        StringKeys.VOICE_ASSISTANT to "음성 도우미",
        StringKeys.SETTINGS to "설정",
        StringKeys.SOS to "긴급 연락",
        
        StringKeys.OBSTACLE_PERSON to "사람",
        StringKeys.OBSTACLE_VEHICLE to "차량",
        StringKeys.OBSTACLE_STAIRS to "계단",
        StringKeys.OBSTACLE_DOOR to "문",
        
        StringKeys.ALERT_DANGER to "위험",
        StringKeys.ALERT_WARNING to "경고",
        StringKeys.ALERT_CAUTION to "주의",
        StringKeys.ALERT_SAFE to "안전",
        
        StringKeys.NAV_TURN_LEFT to "앞에서 좌회전",
        StringKeys.NAV_TURN_RIGHT to "앞에서 우회전",
        StringKeys.NAV_GO_STRAIGHT to "직진",
        StringKeys.NAV_ARRIVED to "목적지에 도착했습니다",
        StringKeys.NAV_DISTANCE_FORMAT to "앞 %.0f미터, %s",
        
        StringKeys.PERMISSION_REQUIRED to "권한이 필요합니다",
        StringKeys.LOCATION_DISABLED to "위치 서비스가 비활성화되었습니다",
        StringKeys.NETWORK_ERROR to "네트워크 연결 실패",
        StringKeys.LOADING to "로딩 중...",
        StringKeys.ERROR_OCCURRED to "오류가 발생했습니다",
        
        StringKeys.SETTINGS_LANGUAGE to "언어 설정",
        StringKeys.SETTINGS_VOICE to "음성 설정",
        StringKeys.SETTINGS_ABOUT to "정보",
        StringKeys.SETTINGS_PRIVACY to "개인정보 처리방침"
    )
}
