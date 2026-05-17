package com.blindpath.module_trip_assist.domain.model

/**
 * 天气状况枚举
 */
enum class WeatherCondition(
    val displayName: String,
    val voiceDescription: String,
    val isSafeForTravel: Boolean
) {
    CLEAR("晴", "天气晴朗，适合出行", true),
    CLOUDY("多云", "天气多云，可以出行", true),
    OVERCAST("阴", "天气阴天，注意安全出行", true),
    LIGHT_RAIN("小雨", "当前有小雨，建议携带雨具，注意路面湿滑", true),
    MODERATE_RAIN("中雨", "当前有中雨，建议推迟出行或使用室内导航", false),
    HEAVY_RAIN("大雨", "当前有大雨，不建议出行，路面湿滑危险", false),
    STORM("暴雨", "当前有暴雨，请勿出行，注意安全", false),
    SNOW("雪", "当前有雪，路面湿滑，请小心慢行", false),
    FOG("雾", "当前有雾，能见度较低，请格外注意安全", false),
    HAZE("霾", "当前有霾，建议佩戴口罩出行", true),
    UNKNOWN("未知", "天气信息未知", true);

    companion object {
        fun fromCode(code: Int): WeatherCondition = when (code) {
            100 -> CLEAR
            101 -> CLOUDY
            102 -> OVERCAST
            103, 104 -> LIGHT_RAIN
            105 -> MODERATE_RAIN
            106 -> HEAVY_RAIN
            107 -> STORM
            108 -> FOG
            109 -> HAZE
            in 200..299 -> SNOW
            in 300..399 -> CLOUDY
            in 400..499 -> RAIN
            else -> UNKNOWN
        }

        fun fromDescription(desc: String): WeatherCondition {
            val lower = desc.lowercase()
            return when {
                lower.contains("晴") && !lower.contains("多云") -> CLEAR
                lower.contains("多云") -> CLOUDY
                lower.contains("阴") -> OVERCAST
                lower.contains("暴雨") || lower.contains("大暴雨") -> STORM
                lower.contains("大雨") -> HEAVY_RAIN
                lower.contains("中雨") -> MODERATE_RAIN
                lower.contains("小雨") || lower.contains("阵雨") -> LIGHT_RAIN
                lower.contains("雪") -> SNOW
                lower.contains("雾") -> FOG
                lower.contains("霾") -> HAZE
                else -> UNKNOWN
            }
        }
    }
}

/**
 * 天气信息数据模型
 */
data class WeatherInfo(
    val cityName: String,
    val temperature: Float,           // 摄氏度
    val feelsLike: Float,             // 体感温度
    val humidity: Int,                // 湿度百分比
    val windSpeed: Float,             // 风速 m/s
    val windDirection: String,        // 风向
    val visibility: Float,            // 能见度 km
    val condition: WeatherCondition,  // 天气状况
    val description: String,          // 天气描述
    val sunriseTime: String,          // 日出时间
    val sunsetTime: String,           // 日落时间
    val aqi: Int = -1,               // 空气质量指数，-1 表示未知
    val aqiLevel: String = "未知"     // 空气质量等级
) {
    /**
     * 生成视障友好的天气播报文本
     */
    fun toVoiceText(): String {
        val parts = mutableListOf<String>()

        parts.add("当前${cityName}天气")
        parts.add("${condition.displayName}，${temperature.toInt()}度")
        parts.add("体感温度${feelsLike.toInt()}度")

        if (humidity > 80) {
            parts.add("湿度较大${humidity}%")
        }

        if (windSpeed > 5.0f) {
            parts.add("${windDirection}风${windSpeed.toInt()}米每秒")
        }

        if (visibility < 1.0f) {
            parts.add("能见度极低仅${(visibility * 1000).toInt()}米，请格外注意安全")
        } else if (visibility < 5.0f) {
            parts.add("能见度一般${visibility.toInt()}公里")
        }

        if (aqi > 0 && aqi > 100) {
            parts.add("空气质量${aqiLevel}，建议佩戴口罩")
        }

        if (!condition.isSafeForTravel) {
            parts.add("当前天气不适合出行，建议推迟行程")
        }

        return parts.joinToString("，") + "。"
    }

    /**
     * 判断是否需要出行提醒
     */
    fun needsTravelWarning(): Boolean = !condition.isSafeForTravel ||
            visibility < 1.0f ||
            (aqi > 0 && aqi > 200)
}
