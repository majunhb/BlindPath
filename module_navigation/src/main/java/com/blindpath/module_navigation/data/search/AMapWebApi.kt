package com.blindpath.module_navigation.data.search

import retrofit2.http.GET
import retrofit2.http.Query

/**
 * 高德地图 Web 服务 HTTP API - Retrofit 接口
 * 
 * 提供 inputtips（输入联想）和 place/text（POI 关键词搜索）功能。
 * 使用 Web 服务 Key 鉴权，与定位 SDK Key 解耦。
 * IP 白名单留空以支持手机动态 IP。
 */
interface AMapWebApi {

    /**
     * 输入提示（联想）
     * @param key Web 服务 Key
     * @param keywords 输入关键词（至少2字）
     * @param location 可选，格式 "经度,纬度"，用于优先返回周边结果
     */
    @GET("v3/assistant/inputtips")
    suspend fun inputTips(
        @Query("key") key: String,
        @Query("keywords") keywords: String,
        @Query("location") location: String? = null
    ): AMapInputTipsResponse

    /**
     * POI 关键词搜索
     * @param key Web 服务 Key
     * @param keywords 搜索关键词
     * @param location 可选，中心点坐标 "经度,纬度"，用于距离排序
     * @param types 可选，POI 类型
     * @param offset 每页条数（最大25）
     * @param page 页码
     */
    @GET("v3/place/text")
    suspend fun searchPlace(
        @Query("key") key: String,
        @Query("keywords") keywords: String,
        @Query("location") location: String? = null,
        @Query("types") types: String? = null,
        @Query("offset") offset: Int = 10,
        @Query("page") page: Int = 1
    ): AMapPoiSearchResponse
}
