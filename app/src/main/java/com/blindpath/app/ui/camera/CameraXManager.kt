package com.blindpath.app.ui.camera

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.graphics.YuvImage
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import dagger.hilt.android.qualifiers.ApplicationContext
import timber.log.Timber
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 统一 CameraX 管理器
 *
 * 解决多个 Screen 独立管理 CameraX 导致资源冲突的问题。
 * 所有 AR 相关场景共享同一个 CameraX 实例，通过 SharedFlow 分发帧。
 *
 * 设计原则：
 * - 单例：全局只有一个 CameraX 实例
 * - SharedFlow：多消费者订阅同一帧流（replay=0, extraBufferCapacity=1）
 * - 生命周期感知：跟随 LifecycleOwner 自动绑定/解绑
 * - 线程安全：analysisExecutor 单线程执行
 */
@Singleton
class CameraXManager @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    private val isBound = AtomicBoolean(false)

    private val _frameFlow = MutableSharedFlow<Bitmap>(
        replay = 0,
        extraBufferCapacity = 1
    )
    val frameFlow: SharedFlow<Bitmap> = _frameFlow.asSharedFlow()

    private var lifecycleOwner: LifecycleOwner? = null
    private var surfaceProvider: Preview.SurfaceProvider? = null

    private val lifecycleObserver = object : DefaultLifecycleObserver {
        override fun onDestroy(owner: LifecycleOwner) {
            unbind()
        }
    }

    /**
     * 绑定 CameraX 到指定的生命周期和预览表面
     *
     * @param lifecycleOwner 生命周期持有者（通常是 Activity 或 Fragment）
     * @param previewView 用于显示相机预览的 PreviewView
     * @param targetResolution 分析帧分辨率，默认 480x640（平衡性能与精度）
     * @param cameraSelector 选择前置或后置摄像头，默认后置
     */
    fun bind(
        lifecycleOwner: LifecycleOwner,
        previewView: PreviewView,
        targetResolution: android.util.Size = android.util.Size(480, 640),
        cameraSelector: CameraSelector = CameraSelector.DEFAULT_BACK_CAMERA
    ) {
        if (isBound.get()) {
            Timber.d("CameraXManager: already bound, skipping")
            return
        }

        this.lifecycleOwner = lifecycleOwner
        this.surfaceProvider = previewView.surfaceProvider

        lifecycleOwner.lifecycle.addObserver(lifecycleObserver)

        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }

                val imageAnalysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setTargetResolution(targetResolution)
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                    .build()

                imageAnalysis.setAnalyzer(analysisExecutor) { imageProxy ->
                    val bitmap = imageProxyToBitmap(imageProxy)
                    if (bitmap != null) {
                        _frameFlow.tryEmit(bitmap)
                    }
                    imageProxy.close()
                }

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(
                    lifecycleOwner,
                    cameraSelector,
                    preview,
                    imageAnalysis
                )
                isBound.set(true)
                Timber.i("CameraXManager: bound successfully")
            } catch (e: Exception) {
                Timber.e(e, "CameraXManager: bind failed")
            }
        }, ContextCompat.getMainExecutor(context))
    }

    /**
     * 解绑 CameraX，释放摄像头资源
     */
    fun unbind() {
        if (!isBound.getAndSet(false)) return

        try {
            cameraProvider?.unbindAll()
            cameraProvider = null
        } catch (e: Exception) {
            Timber.w(e, "CameraXManager: unbind failed")
        }

        lifecycleOwner?.lifecycle?.removeObserver(lifecycleObserver)
        lifecycleOwner = null
        surfaceProvider = null
        Timber.i("CameraXManager: unbound")
    }

    /**
     * 获取当前绑定状态
     */
    fun isActive(): Boolean = isBound.get()

    /**
     * 释放所有资源（应用退出时调用）
     */
    fun shutdown() {
        unbind()
        analysisExecutor.shutdownNow()
    }

    /**
     * 将 ImageProxy (YUV_420_888) 转换为 ARGB_8888 Bitmap
     */
    private fun imageProxyToBitmap(imageProxy: ImageProxy): Bitmap? {
        return try {
            val yBuffer = imageProxy.planes[0].buffer
            val uBuffer = imageProxy.planes[1].buffer
            val vBuffer = imageProxy.planes[2].buffer

            val ySize = yBuffer.remaining()
            val uSize = uBuffer.remaining()
            val vSize = vBuffer.remaining()

            val nv21 = ByteArray(ySize + uSize + vSize)
            yBuffer.get(nv21, 0, ySize)
            vBuffer.get(nv21, ySize, vSize)
            uBuffer.get(nv21, ySize + vSize, uSize)

            val yuvImage = YuvImage(
                nv21,
                ImageFormat.NV21,
                imageProxy.width,
                imageProxy.height,
                null
            )
            val out = ByteArrayOutputStream()
            yuvImage.compressToJpeg(
                android.graphics.Rect(0, 0, imageProxy.width, imageProxy.height),
                80,
                out
            )
            val jpegBytes = out.toByteArray()
            android.graphics.BitmapFactory.decodeByteArray(jpegBytes, 0, jpegBytes.size)
        } catch (e: Exception) {
            Timber.w(e, "CameraXManager: imageProxyToBitmap failed")
            null
        }
    }
}