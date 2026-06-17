package com.blindpath.module_navigation.data.search

import com.google.gson.annotations.SerializedName

/**
 * inputtips 接口响应
 */
data class AMapInputTipsResponse(
    val status: String = "",
    val info: String = "",
    val count: String = "",
    val tips: List<AMapTip>? = null
)

/**
 * 输入联想提示项
 */
data class AMapTip(
    val id: String? = null,
    val name: String? = null,
    val district: String? = null,
    val adcode: String? = null,
    val location: String? = null,
    val address: String? = null
)

/**
 * place/text 接口响应
 */
data class AMapPoiSearchResponse(
    val status: String = "",
    val info: String = "",
    val count: String = "",
    val pois: List<AMapPoi>? = null
)

/**
 * POI 详情
 */
data class AMapPoi(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val typecode: String? = null,
    val address: String? = null,
    val location: String? = null,
    val distance: String? = null,
    val pname: String? = null,
    val cityname: String? = null,
    val adname: String? = null
)

/**
 * 统一搜索结果项，供 UI 层消费
 * @param toAccessibilityText 无障碍播报文本，读屏软件可朗读
 */
data class SearchResultItem(
    val id: String,
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double,
    val distance: Int = 0
) {
    /**
     * 生成无障碍播报文本
     * 示例: "天安门广场，北京市东城区，距离1200米"
     */
    fun toAccessibilityText(): String {
        val distStr = if (distance >= 1000) {
            "距离%.1f公里".format(distance / 1000.0)
        } else {
            "距离%d米".format(distance)
        }
        return "$name，$address，$distStr"
    }
}
