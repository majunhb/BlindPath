package com.blindpath.base.error

import android.content.Context
import com.blindpath.base.R
import timber.log.Timber

/**
 * 错误消息解析器
 * 将技术错误转换为用户友好的提示信息
 */
object ErrorMessageResolver {
    
    /**
     * 解析错误并返回用户友好的消息
     * @param error 错误对象
     * @param context Android Context（用于获取资源字符串）
     * @return 用户友好的错误消息
     */
    fun resolve(error: BlindPathError, context: Context): String {
        return when (error) {
            // AI 检测错误
            is BlindPathError.ModelLoadError -> {
                Timber.e(error.cause, "模型加载失败: ${error.modelName}")
                "AI模型加载失败，将使用基础检测模式"
            }
            is BlindPathError.InferenceError -> {
                Timber.e(error.cause, "AI推理失败")
                "AI检测遇到问题，请稍后重试"
            }
            is BlindPathError.ModelNotFoundError -> {
                Timber.w("模型文件缺失: ${error.modelName}")
                "AI模型文件缺失，将使用基础检测模式"
            }
            
            // 摄像头错误
            is BlindPathError.CameraPermissionDenied -> {
                "请授予摄像头权限以使用障碍物检测功能"
            }
            is BlindPathError.CameraInitError -> {
                Timber.e(error.cause, "摄像头初始化失败")
                "无法启动摄像头，请检查是否有其他应用正在使用"
            }
            is BlindPathError.CameraBusy -> {
                "摄像头正在被其他应用使用，请关闭其他相机应用"
            }
            
            // GPS 错误
            is BlindPathError.GpsPermissionDenied -> {
                "请授予定位权限以使用导航功能"
            }
            is BlindPathError.GpsDisabled -> {
                "请在设置中开启GPS定位服务"
            }
            is BlindPathError.GpsSignalWeak -> {
                "GPS信号较弱，定位精度约${error.accuracy.toInt()}米，建议移至开阔区域"
            }
            is BlindPathError.LocationError -> {
                Timber.e(error.cause, "定位失败")
                "获取位置失败，请检查GPS是否开启"
            }
            
            // 语音错误
            is BlindPathError.TtsInitError -> {
                Timber.e(error.cause, "TTS初始化失败")
                "语音引擎初始化失败，语音播报功能暂不可用"
            }
            is BlindPathError.TtsLanguageNotSupported -> {
                "语音引擎不支持${error.language}语言，将使用系统默认语言"
            }
            is BlindPathError.TtsSpeakError -> {
                Timber.e(error.cause, "语音播报失败")
                "语音播报失败，请稍后重试"
            }
            
            // 网络错误
            is BlindPathError.NetworkUnavailable -> {
                "网络连接不可用，部分功能可能受限"
            }
            is BlindPathError.NetworkTimeout -> {
                "网络请求超时，请检查网络连接"
            }
            is BlindPathError.ServerError -> {
                Timber.e("服务器错误: ${error.code}")
                "服务暂时不可用，请稍后重试"
            }
            
            // 存储错误
            is BlindPathError.StoragePermissionDenied -> {
                "请授予存储权限以保存数据"
            }
            is BlindPathError.StorageInsufficient -> {
                "存储空间不足，请清理后重试"
            }
            is BlindPathError.FileReadError -> {
                Timber.e(error.cause, "文件读取失败: ${error.fileName}")
                "读取文件失败"
            }
            is BlindPathError.FileWriteError -> {
                Timber.e(error.cause, "文件写入失败: ${error.fileName}")
                "保存文件失败"
            }
            
            // 通用错误
            is BlindPathError.UnknownError -> {
                Timber.e(error.cause, "未知错误: ${error.message}")
                "发生未知错误，请稍后重试"
            }
            is BlindPathError.FeatureUnavailable -> {
                "${error.feature}功能暂时不可用"
            }
            is BlindPathError.OperationCancelled -> {
                "" // 取消操作不显示错误
            }
        }
    }
    
    /**
     * 获取错误对应的操作建议
     * @param error 错误对象
     * @return 操作建议（可为空）
     */
    fun getActionSuggestion(error: BlindPathError): ActionSuggestion? {
        return when (error) {
            is BlindPathError.CameraPermissionDenied -> ActionSuggestion(
                buttonText = "去设置",
                action = ActionType.OPEN_APP_SETTINGS
            )
            is BlindPathError.GpsPermissionDenied -> ActionSuggestion(
                buttonText = "去设置",
                action = ActionType.OPEN_APP_SETTINGS
            )
            is BlindPathError.GpsDisabled -> ActionSuggestion(
                buttonText = "去设置",
                action = ActionType.OPEN_LOCATION_SETTINGS
            )
            is BlindPathError.NetworkUnavailable -> ActionSuggestion(
                buttonText = "去设置",
                action = ActionType.OPEN_NETWORK_SETTINGS
            )
            is BlindPathError.StoragePermissionDenied -> ActionSuggestion(
                buttonText = "去设置",
                action = ActionType.OPEN_APP_SETTINGS
            )
            else -> null
        }
    }
    
    /**
     * 判断错误是否可恢复
     */
    fun isRecoverable(error: BlindPathError): Boolean {
        return when (error) {
            is BlindPathError.ModelLoadError,
            is BlindPathError.ModelNotFoundError -> true // 可降级到基础模式
            is BlindPathError.CameraBusy -> true // 可能稍后可用
            is BlindPathError.GpsSignalWeak -> true // 可能在移动后改善
            is BlindPathError.NetworkUnavailable,
            is BlindPathError.NetworkTimeout -> true // 网络可能恢复
            is BlindPathError.TtsLanguageNotSupported -> true // 可降级到默认语言
            else -> false
        }
    }
    
    /**
     * 判断是否需要立即处理
     */
    fun requiresImmediateAttention(error: BlindPathError): Boolean {
        return when (error) {
            is BlindPathError.CameraPermissionDenied,
            is BlindPathError.GpsPermissionDenied,
            is BlindPathError.GpsDisabled,
            is BlindPathError.StoragePermissionDenied -> true
            else -> false
        }
    }
}

/**
 * 操作建议
 */
data class ActionSuggestion(
    val buttonText: String,
    val action: ActionType
)

/**
 * 操作类型
 */
enum class ActionType {
    OPEN_APP_SETTINGS,       // 打开应用设置页
    OPEN_LOCATION_SETTINGS,  // 打开位置设置页
    OPEN_NETWORK_SETTINGS,   // 打开网络设置页
    RETRY,                   // 重试操作
    DISMISS                  // 关闭提示
}
