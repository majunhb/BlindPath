package com.blindpath.base.error

/**
 * 错误类型定义
 * 用于统一处理应用中的各类错误
 */
sealed class BlindPathError : Throwable() {
    
    // ==================== AI 检测相关错误 ====================
    
    /** AI 模型加载失败 */
    data class ModelLoadError(
        override val message: String,
        val modelName: String? = null,
        override val cause: Throwable? = null
    ) : BlindPathError()
    
    /** AI 推理失败 */
    data class InferenceError(
        override val message: String,
        override val cause: Throwable? = null
    ) : BlindPathError()
    
    /** 模型文件缺失 */
    data class ModelNotFoundError(
        val modelName: String
    ) : BlindPathError() {
        override val message: String = "AI模型文件 $modelName 不存在"
    }
    
    // ==================== 摄像头相关错误 ====================
    
    /** 摄像头权限被拒绝 */
    object CameraPermissionDenied : BlindPathError() {
        override val message: String = "摄像头权限被拒绝"
    }
    
    /** 摄像头初始化失败 */
    data class CameraInitError(
        override val message: String = "摄像头初始化失败",
        override val cause: Throwable? = null
    ) : BlindPathError()
    
    /** 摄像头被占用 */
    object CameraBusy : BlindPathError() {
        override val message: String = "摄像头正在被其他应用使用"
    }
    
    // ==================== 定位相关错误 ====================
    
    /** GPS 权限被拒绝 */
    object GpsPermissionDenied : BlindPathError() {
        override val message: String = "定位权限被拒绝"
    }
    
    /** GPS 未开启 */
    object GpsDisabled : BlindPathError() {
        override val message: String = "GPS定位服务未开启"
    }
    
    /** GPS 信号弱 */
    data class GpsSignalWeak(
        val accuracy: Float
    ) : BlindPathError() {
        override val message: String = "GPS信号弱，精度: ${accuracy}米"
    }
    
    /** 定位失败 */
    data class LocationError(
        override val message: String = "获取位置失败",
        override val cause: Throwable? = null
    ) : BlindPathError()
    
    // ==================== 语音相关错误 ====================
    
    /** TTS 初始化失败 */
    data class TtsInitError(
        override val message: String = "语音引擎初始化失败",
        override val cause: Throwable? = null
    ) : BlindPathError()
    
    /** TTS 语言不支持 */
    data class TtsLanguageNotSupported(
        val language: String
    ) : BlindPathError() {
        override val message: String = "语音引擎不支持 $language 语言"
    }
    
    /** TTS 播报失败 */
    data class TtsSpeakError(
        override val message: String = "语音播报失败",
        override val cause: Throwable? = null
    ) : BlindPathError()
    
    // ==================== 网络相关错误 ====================
    
    /** 网络不可用 */
    object NetworkUnavailable : BlindPathError() {
        override val message: String = "网络连接不可用"
    }
    
    /** 网络请求超时 */
    object NetworkTimeout : BlindPathError() {
        override val message: String = "网络请求超时，请稍后重试"
    }
    
    /** 服务器错误 */
    data class ServerError(
        val code: Int,
        override val message: String = "服务器错误"
    ) : BlindPathError()
    
    // ==================== 存储相关错误 ====================
    
    /** 存储权限被拒绝 */
    object StoragePermissionDenied : BlindPathError() {
        override val message: String = "存储权限被拒绝"
    }
    
    /** 存储空间不足 */
    data class StorageInsufficient(
        val requiredMB: Long
    ) : BlindPathError() {
        override val message: String = "存储空间不足，需要 ${requiredMB}MB"
    }
    
    /** 文件读取失败 */
    data class FileReadError(
        val fileName: String,
        override val cause: Throwable? = null
    ) : BlindPathError() {
        override val message: String = "无法读取文件: $fileName"
    }
    
    /** 文件写入失败 */
    data class FileWriteError(
        val fileName: String,
        override val cause: Throwable? = null
    ) : BlindPathError() {
        override val message: String = "无法写入文件: $fileName"
    }
    
    // ==================== 通用错误 ====================
    
    /** 未知错误 */
    data class UnknownError(
        override val message: String = "发生未知错误",
        override val cause: Throwable? = null
    ) : BlindPathError()
    
    /** 功能不可用 */
    data class FeatureUnavailable(
        val feature: String
    ) : BlindPathError() {
        override val message: String = "$feature 功能暂时不可用"
    }
    
    /** 操作取消 */
    object OperationCancelled : BlindPathError() {
        override val message: String = "操作已取消"
    }
}
