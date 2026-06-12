package com.blindpath.module_navigation.domain.model

/**
 * 离线模式状态
 *
 * 用于追踪导航模块的离线运行状态，包括当前是否处于离线模式、
 * 正在使用的缓存路线信息、可用缓存路线数量以及上次成功在线规划的
 * 目的地名称。
 */
data class OfflineModeState(
    val isOffline: Boolean = false,
    val activeCachedRoute: com.blindpath.module_navigation.data.CachedNavigationRoute? = null,
    val cachedRouteCount: Int = 0,
    val lastSuccessfulOnlinePlan: String? = null
)