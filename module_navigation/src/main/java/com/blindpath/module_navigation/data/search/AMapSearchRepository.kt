package com.blindpath.module_navigation.data.search

import com.blindpath.base.common.Result
import com.blindpath.module_navigation.BuildConfig
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import timber.log.Timber
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 高德地图 HTTP 搜索仓库
 * 
 * 解耦设计：定位归 SDK（AMap SDK），搜索归 HTTP API（Web 服务 Key）。
 * 未来更换搜索服务只需替换此层，不影响定位和导航逻辑。
 * 
 * IP 白名单留空：手机 IP 动态变化，留空避免部分用户搜不到。
 */
@Singleton
class AMapSearchRepository @Inject constructor() {

    companion object {
        private const val BASE_URL = "https://restapi.amap.com/"
    }

    /** Web 服务 Key，从 BuildConfig 读取 */
    private val webKey: String = BuildConfig.AMAP_WEB_KEY

    /** 是否已校验 Key 有效性 */
    private var keyValidated = false

    private val api: AMapWebApi by lazy {
        val logging = HttpLoggingInterceptor { msg -> Timber.tag("AMapSearch").d(msg) }
        logging.level = if (BuildConfig.DEBUG) {
            HttpLoggingInterceptor.Level.BODY
        } else {
            HttpLoggingInterceptor.Level.BASIC
        }

        val client = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(10, TimeUnit.SECONDS)
            .addInterceptor(logging)
            .build()

        Retrofit.Builder()
            .baseUrl(BASE_URL)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(AMapWebApi::class.java)
    }

    /**
     * 输入联想提示
     * @param keywords 用户输入的关键词（至少2字）
     * @param location 可选，当前坐标 "经度,纬度"，优先返回周边结果
     * @return 联想结果列表
     */
    suspend fun getInputTips(
        keywords: String,
        location: String? = null
    ): Result<List<SearchResultItem>> {
        if (webKey.isBlank()) {
            return Result.Error(message = "高德 Web 服务 Key 未配置")
        }

        return try {
            val response = api.inputTips(webKey, keywords, location)
            if (response.status == "1" && response.tips != null) {
                val items = response.tips
                    .filter { it.location != null && !it.location.isNullOrBlank() && it.name != null }
                    .map { tip ->
                        val locParts = tip.location!!.split(",")
                        SearchResultItem(
                            id = tip.id ?: "",
                            name = tip.name!!,
                            address = tip.address ?: tip.district ?: "",
                            latitude = locParts.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0,
                            longitude = locParts.getOrElse(0) { "0" }.toDoubleOrNull() ?: 0.0
                        )
                    }
                Timber.d("inputTips: ${items.size} results for '$keywords'")
                Result.Success(items)
            } else {
                Timber.w("inputTips failed: status=${response.status}, info=${response.info}")
                Result.Error(message = "输入联想失败: ${response.info}")
            }
        } catch (e: Exception) {
            Timber.e(e, "inputTips exception: $keywords")
            Result.Error(message = e.message ?: "网络异常，请检查连接")
        }
    }

    /**
     * POI 关键词搜索
     * @param keywords 搜索关键词
     * @param location 可选，中心点坐标 "经度,纬度"，结果按距离排序
     * @return 搜索结果列表
     */
    suspend fun searchAddress(
        keywords: String,
        location: String? = null
    ): Result<List<SearchResultItem>> {
        if (webKey.isBlank()) {
            return Result.Error(message = "高德 Web 服务 Key 未配置")
        }

        return try {
            val response = api.searchPlace(webKey, keywords, location)
            if (response.status == "1" && response.pois != null) {
                val items = response.pois.map { poi ->
                    val locParts = poi.location?.split(",") ?: listOf("0", "0")
                    SearchResultItem(
                        id = poi.id ?: "",
                        name = poi.name ?: "",
                        address = poi.address ?: "",
                        latitude = locParts.getOrElse(1) { "0" }.toDoubleOrNull() ?: 0.0,
                        longitude = locParts.getOrElse(0) { "0" }.toDoubleOrNull() ?: 0.0,
                        distance = poi.distance?.toIntOrNull() ?: 0
                    )
                }
                Timber.d("searchAddress: ${items.size} results for '$keywords'")
                Result.Success(items)
            } else {
                Timber.w("searchAddress failed: status=${response.status}, info=${response.info}")
                Result.Error(message = "搜索失败: ${response.info}")
            }
        } catch (e: Exception) {
            Timber.e(e, "searchAddress exception: $keywords")
            Result.Error(message = e.message ?: "网络异常，请检查连接")
        }
    }
}
