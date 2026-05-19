package com.blindpath.base.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import timber.log.Timber

/**
 * 网络状态
 */
data class NetworkStatus(
    val isConnected: Boolean,
    val isWifi: Boolean = false,
    val isCellular: Boolean = false,
    val isMetered: Boolean = false
) {
    companion object {
        val DISCONNECTED = NetworkStatus(isConnected = false)
    }
}

/**
 * 网络状态监听器
 * 提供实时网络状态监控
 */
class NetworkMonitor(
    context: Context
) {
    
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    
    /**
     * 网络状态Flow
     */
    val networkStatus: Flow<NetworkStatus> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            
            override fun onAvailable(network: Network) {
                val status = getCurrentNetworkStatus()
                Timber.d("Network available: $status")
                trySend(status)
            }
            
            override fun onLost(network: Network) {
                Timber.d("Network lost")
                trySend(NetworkStatus.DISCONNECTED)
            }
            
            override fun onCapabilitiesChanged(
                network: Network,
                networkCapabilities: NetworkCapabilities
            ) {
                val status = getNetworkStatus(networkCapabilities)
                Timber.d("Network capabilities changed: $status")
                trySend(status)
            }
        }
        
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        
        connectivityManager.registerNetworkCallback(request, callback)
        
        // 发送初始状态
        trySend(getCurrentNetworkStatus())
        
        awaitClose {
            connectivityManager.unregisterNetworkCallback(callback)
            Timber.d("Network callback unregistered")
        }
    }
    
    /**
     * 获取当前网络状态
     */
    fun getCurrentNetworkStatus(): NetworkStatus {
        val network = connectivityManager.activeNetwork ?: return NetworkStatus.DISCONNECTED
        val capabilities = connectivityManager.getNetworkCapabilities(network) 
            ?: return NetworkStatus.DISCONNECTED
        
        return getNetworkStatus(capabilities)
    }
    
    /**
     * 检查网络是否可用
     */
    fun isNetworkAvailable(): Boolean {
        return getCurrentNetworkStatus().isConnected
    }
    
    /**
     * 检查是否是WiFi
     */
    fun isWifi(): Boolean {
        return getCurrentNetworkStatus().isWifi
    }
    
    /**
     * 检查是否是计费网络
     */
    fun isMetered(): Boolean {
        return getCurrentNetworkStatus().isMetered
    }
    
    private fun getNetworkStatus(capabilities: NetworkCapabilities): NetworkStatus {
        val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        val hasValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        
        if (!hasInternet || !hasValidated) {
            return NetworkStatus.DISCONNECTED
        }
        
        val isWifi = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
        val isCellular = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
        
        // 检查是否是计费网络（移动网络）
        val isMetered = isCellular && !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        
        return NetworkStatus(
            isConnected = true,
            isWifi = isWifi,
            isCellular = isCellular,
            isMetered = isMetered
        )
    }
}
