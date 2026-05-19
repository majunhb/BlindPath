package com.blindpath.base.security

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import timber.log.Timber

/**
 * 权限管理器
 * 统一管理应用权限，遵循最小权限原则
 */
class PermissionManager(
    private val context: Context
) {
    
    /**
     * 权限组定义
     */
    object PermissionGroups {
        // 核心功能必需权限
        val CORE_PERMISSIONS = listOf(
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )
        
        // 语音功能权限
        val VOICE_PERMISSIONS = listOf(
            Manifest.permission.RECORD_AUDIO
        )
        
        // 存储权限
        val STORAGE_PERMISSIONS = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            listOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VIDEO
            )
        } else {
            listOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
        }
        
        // 电话权限（SOS功能）
        val PHONE_PERMISSIONS = listOf(
            Manifest.permission.CALL_PHONE,
            Manifest.permission.SEND_SMS
        )
        
        // 后台定位权限
        val BACKGROUND_LOCATION_PERMISSION = listOf(
            Manifest.permission.ACCESS_BACKGROUND_LOCATION
        )
    }
    
    /**
     * 权限状态
     */
    enum class PermissionStatus {
        GRANTED,        // 已授权
        DENIED,         // 已拒绝
        DENIED_ONCE,    // 单次拒绝
        PERMANENTLY_DENIED, // 永久拒绝（需要去设置）
        NOT_REQUESTED   // 未请求
    }
    
    /**
     * 检查单个权限状态
     */
    fun checkPermission(permission: String): PermissionStatus {
        return when {
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED -> {
                PermissionStatus.GRANTED
            }
            else -> {
                // 这里可以扩展检查是否之前拒绝过
                PermissionStatus.NOT_REQUESTED
            }
        }
    }
    
    /**
     * 检查多个权限状态
     */
    fun checkPermissions(permissions: List<String>): Map<String, PermissionStatus> {
        return permissions.associateWith { checkPermission(it) }
    }
    
    /**
     * 检查是否所有权限都已授权
     */
    fun areAllPermissionsGranted(permissions: List<String>): Boolean {
        return permissions.all { 
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED 
        }
    }
    
    /**
     * 获取缺失的权限
     */
    fun getMissingPermissions(permissions: List<String>): List<String> {
        return permissions.filter { 
            ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED 
        }
    }
    
    /**
     * 检查核心功能权限
     */
    fun hasCorePermissions(): Boolean {
        return areAllPermissionsGranted(PermissionGroups.CORE_PERMISSIONS)
    }
    
    /**
     * 检查语音功能权限
     */
    fun hasVoicePermissions(): Boolean {
        return areAllPermissionsGranted(PermissionGroups.VOICE_PERMISSIONS)
    }
    
    /**
     * 检查存储权限
     */
    fun hasStoragePermissions(): Boolean {
        return areAllPermissionsGranted(PermissionGroups.STORAGE_PERMISSIONS)
    }
    
    /**
     * 检查电话权限（SOS功能）
     */
    fun hasPhonePermissions(): Boolean {
        return areAllPermissionsGranted(PermissionGroups.PHONE_PERMISSIONS)
    }
    
    /**
     * 获取权限说明
     */
    fun getPermissionRationale(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> 
                "摄像头权限用于实时检测前方障碍物，帮助您安全出行"
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION -> 
                "定位权限用于提供导航服务，帮助您准确到达目的地"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> 
                "后台定位权限用于在导航过程中持续提供位置服务"
            Manifest.permission.RECORD_AUDIO -> 
                "麦克风权限用于语音识别，让您可以通过语音控制应用"
            Manifest.permission.READ_EXTERNAL_STORAGE,
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> 
                "存储权限用于保存导航路线和离线地图数据"
            Manifest.permission.CALL_PHONE -> 
                "电话权限用于在紧急情况下拨打求救电话"
            Manifest.permission.SEND_SMS -> 
                "短信权限用于在紧急情况下发送求救短信"
            else -> "此权限是应用功能所必需的"
        }
    }
    
    /**
     * 获取权限友好的名称
     */
    fun getPermissionName(permission: String): String {
        return when (permission) {
            Manifest.permission.CAMERA -> "摄像头"
            Manifest.permission.ACCESS_FINE_LOCATION -> "精确定位"
            Manifest.permission.ACCESS_COARSE_LOCATION -> "粗略定位"
            Manifest.permission.ACCESS_BACKGROUND_LOCATION -> "后台定位"
            Manifest.permission.RECORD_AUDIO -> "麦克风"
            Manifest.permission.READ_EXTERNAL_STORAGE -> "读取存储"
            Manifest.permission.WRITE_EXTERNAL_STORAGE -> "写入存储"
            Manifest.permission.CALL_PHONE -> "拨打电话"
            Manifest.permission.SEND_SMS -> "发送短信"
            Manifest.permission.READ_MEDIA_IMAGES -> "读取图片"
            Manifest.permission.READ_MEDIA_VIDEO -> "读取视频"
            else -> permission.substringAfterLast(".")
        }
    }
    
    /**
     * 根据功能获取所需权限
     */
    fun getRequiredPermissionsForFeature(feature: Feature): List<String> {
        return when (feature) {
            Feature.OBSTACLE_DETECTION -> listOf(Manifest.permission.CAMERA)
            Feature.NAVIGATION -> PermissionGroups.CORE_PERMISSIONS.filter { 
                it.contains("LOCATION") 
            }
            Feature.VOICE_CONTROL -> PermissionGroups.VOICE_PERMISSIONS
            Feature.SOS -> PermissionGroups.PHONE_PERMISSIONS
            Feature.OFFLINE_MAP -> PermissionGroups.STORAGE_PERMISSIONS
            Feature.FULL_FUNCTIONALITY -> PermissionGroups.CORE_PERMISSIONS + 
                PermissionGroups.VOICE_PERMISSIONS + 
                PermissionGroups.PHONE_PERMISSIONS
        }
    }
    
    /**
     * 功能定义
     */
    enum class Feature {
        OBSTACLE_DETECTION,  // 障碍物检测
        NAVIGATION,          // 导航
        VOICE_CONTROL,       // 语音控制
        SOS,                 // 紧急求救
        OFFLINE_MAP,         // 离线地图
        FULL_FUNCTIONALITY   // 完整功能
    }
    
    /**
     * 权限检查结果
     */
    data class PermissionCheckResult(
        val allGranted: Boolean,
        val grantedPermissions: List<String>,
        val deniedPermissions: List<String>,
        val permanentlyDeniedPermissions: List<String>
    )
    
    /**
     * 执行完整的权限检查
     */
    fun performFullPermissionCheck(): PermissionCheckResult {
        val allPermissions = PermissionGroups.CORE_PERMISSIONS + 
            PermissionGroups.VOICE_PERMISSIONS + 
            PermissionGroups.PHONE_PERMISSIONS
        
        val statuses = checkPermissions(allPermissions)
        
        val granted = statuses.filter { it.value == PermissionStatus.GRANTED }.keys.toList()
        val denied = statuses.filter { it.value == PermissionStatus.DENIED }.keys.toList()
        val permanentlyDenied = statuses.filter { it.value == PermissionStatus.PERMANENTLY_DENIED }.keys.toList()
        
        return PermissionCheckResult(
            allGranted = denied.isEmpty() && permanentlyDenied.isEmpty(),
            grantedPermissions = granted,
            deniedPermissions = denied,
            permanentlyDeniedPermissions = permanentlyDenied
        )
    }
}
