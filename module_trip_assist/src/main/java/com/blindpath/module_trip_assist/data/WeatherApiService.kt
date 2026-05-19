package com.blindpath.module_trip_assist.data

import com.google.gson.annotations.SerializedName

/**
 * 和风天气 API 响应数据模型
 * 文档：https://dev.qweather.com/docs/api/weather/weather-now/
 */
data class WeatherNowResponse(
    val code: String = "",
    val now: NowData? = null
)

data class NowData(
    val temp: String = "",
    val feelsLike: String = "",
    val icon: String = "",
    val text: String = "",
    val wind360: String = "",
    val windDir: String = "",
    val windScale: String = "",
    val windSpeed: String = "",
    val humidity: String = "",
    val precip: String = "",
    val pressure: String = "",
    val vis: String = "",
    val cloud: String = "",
    val dew: String = ""
)

/**
 * 空气质量 API 响应
 */
data class AirQualityResponse(
    val code: String = "",
    val now: AirQualityNow? = null
)

data class AirQualityNow(
    val aqi: String = "",
    val category: String = "",
    val pm2p5: String = "",
    val pm10: String = "",
    val no2: String = "",
    val so2: String = "",
    val co: String = "",
    val o3: String = ""
)

/**
 * 天气 API 服务接口（Retrofit）
 */
interface WeatherApiService {

    /**
     * 获取实时天气
     * @param location 经纬度（格式：经度,纬度）
     * @param key API Key
     */
    @retrofit2.http.GET("v7/weather/now")
    suspend fun getWeatherNow(
        @retrofit2.http.Query("location") location: String,
        @retrofit2.http.Query("key") key: String
    ): WeatherNowResponse

    /**
     * 获取空气质量
     */
    @retrofit2.http.GET("v7/air/now")
    suspend fun getAirQuality(
        @retrofit2.http.Query("location") location: String,
        @retrofit2.http.Query("key") key: String
    ): AirQualityResponse
}
