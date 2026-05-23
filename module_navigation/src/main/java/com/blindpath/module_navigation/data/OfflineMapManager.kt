package com.blindpath.module_navigation.data

import android.content.Context
import com.amap.api.maps.offlinemap.OfflineMapCity
import com.amap.api.maps.offlinemap.OfflineMapDownloadListener
import com.amap.api.maps.offlinemap.OfflineMapManager
import com.amap.api.maps.offlinemap.OfflineMapStatus
import com.blindpath.module_navigation.domain.model.OfflineMapState
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 离线地图管理器
 * 
 * 功能：
 * 1. 下载城市离线地图
 * 2. 管理离线地图存储
 * 3. 检查离线地图更新
 * 4. 离线地图状态监控
 * 
 * 使用场景：
 * - 弱网/无网环境下仍可使用导航功能
 * - 减少流量消耗
 * - 提升地图加载速度
 */
@Singleton
class OfflineMapManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val _state = MutableStateFlow(OfflineMapState())
    val state: StateFlow<OfflineMapState> = _state.asStateFlow()

    private var offlineMapManager: OfflineMapManager? = null

    /**
     * 初始化离线地图管理器
     */
    fun initialize(): Boolean {
        return try {
            offlineMapManager = OfflineMapManager(context, createDownloadListener())
            Timber.d("Offline map manager initialized")
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize offline map manager")
            false
        }
    }

    /**
     * 获取所有城市列表
     */
    fun getCityList(): List<OfflineMapCity> {
        return try {
            offlineMapManager?.offlineMapCityList ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get city list")
            emptyList()
        }
    }

    /**
     * 获取已下载的离线地图
     */
    fun getDownloadedMaps(): List<OfflineMapCity> {
        return try {
            offlineMapManager?.downloadOfflineMapCityList ?: emptyList()
        } catch (e: Exception) {
            Timber.e(e, "Failed to get downloaded maps")
            emptyList()
        }
    }

    /**
     * 下载城市离线地图
     * 
     * @param city 城市名称（如"北京市"、"上海市"）
     * @return 是否成功开始下载
     */
    fun downloadCityMap(city: String): Boolean {
        return try {
            val cityList = getCityList()
            val targetCity = cityList.find { it.city == city }
            
            if (targetCity != null) {
                offlineMapManager?.downloadByCityNameOrAdcode(city)
                _state.update { 
                    it.copy(
                        isDownloading = true,
                        currentDownloadCity = city,
                        downloadProgress = 0
                    )
                }
                Timber.d("Started downloading offline map for: $city")
                true
            } else {
                Timber.w("City not found: $city")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to download city map: $city")
            false
        }
    }

    /**
     * 暂停下载
     */
    fun pauseDownload() {
        try {
            offlineMapManager?.pause()
            _state.update { it.copy(isDownloading = false) }
            Timber.d("Download paused")
        } catch (e: Exception) {
            Timber.e(e, "Failed to pause download")
        }
    }

    /**
     * 取消下载
     */
    fun cancelDownload() {
        try {
            offlineMapManager?.stop()
            _state.update { 
                it.copy(
                    isDownloading = false,
                    currentDownloadCity = null,
                    downloadProgress = 0
                )
            }
            Timber.d("Download cancelled")
        } catch (e: Exception) {
            Timber.e(e, "Failed to cancel download")
        }
    }

    /**
     * 删除已下载的离线地图
     */
    fun deleteCityMap(city: String): Boolean {
        return try {
            val downloadedMaps = getDownloadedMaps()
            val targetMap = downloadedMaps.find { it.city == city }
            
            if (targetMap != null) {
                offlineMapManager?.remove(targetMap)
                Timber.d("Deleted offline map: $city")
                true
            } else {
                Timber.w("Downloaded map not found: $city")
                false
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to delete city map: $city")
            false
        }
    }

    /**
     * 检查离线地图更新
     */
    fun checkForUpdates() {
        try {
            offlineMapManager?.updateOfflineMapCityList()
            Timber.d("Checking for offline map updates")
        } catch (e: Exception) {
            Timber.e(e, "Failed to check for updates")
        }
    }

    /**
     * 获取离线地图总大小（字节）
     */
    fun getTotalOfflineMapSize(): Long {
        return try {
            getDownloadedMaps().sumOf { it.size.toLong() }
        } catch (e: Exception) {
            Timber.e(e, "Failed to get total offline map size")
            0L
        }
    }

    /**
     * 创建下载监听器
     */
    private fun createDownloadListener(): OfflineMapDownloadListener {
        return object : OfflineMapDownloadListener {
            override fun onDownload(
                status: Int,
                completeCode: Int,
                name: String?
            ) {
                when (status) {
                    OfflineMapStatus.LOADING -> {
                        Timber.d("Downloading: $name, progress: $completeCode%")
                        _state.update { 
                            it.copy(downloadProgress = completeCode)
                        }
                    }
                    OfflineMapStatus.UNZIP -> {
                        Timber.d("Unzipping: $name")
                    }
                    OfflineMapStatus.SUCCESS -> {
                        Timber.d("Download success: $name")
                        _state.update { 
                            it.copy(
                                isDownloading = false,
                                currentDownloadCity = null,
                                downloadProgress = 100,
                                lastDownloadSuccess = true
                            )
                        }
                    }
                    OfflineMapStatus.ERROR -> {
                        Timber.e("Download error: $name")
                        _state.update { 
                            it.copy(
                                isDownloading = false,
                                currentDownloadCity = null,
                                downloadProgress = 0,
                                lastDownloadSuccess = false,
                                lastError = "下载失败: $name"
                            )
                        }
                    }
                    OfflineMapStatus.EXCEPTION_AMAP -> {
                        Timber.e("AMap exception during download")
                        _state.update { 
                            it.copy(
                                isDownloading = false,
                                lastError = "高德地图服务异常"
                            )
                        }
                    }
                }
            }

            override fun onCheckUpdate(
                hasNew: Boolean,
                name: String?
            ) {
                if (hasNew) {
                    Timber.d("Update available for: $name")
                    _state.update { 
                        it.copy(hasUpdateAvailable = true)
                    }
                }
            }

            override fun onRemove(
                success: Boolean,
                name: String?,
                errorInfo: String?
            ) {
                if (success) {
                    Timber.d("Removed offline map: $name")
                } else {
                    Timber.e("Failed to remove offline map: $name, error: $errorInfo")
                }
            }
        }
    }

    /**
     * 释放资源
     */
    fun release() {
        try {
            offlineMapManager?.onDestroy()
            offlineMapManager = null
            Timber.d("Offline map manager released")
        } catch (e: Exception) {
            Timber.e(e, "Failed to release offline map manager")
        }
    }
}
