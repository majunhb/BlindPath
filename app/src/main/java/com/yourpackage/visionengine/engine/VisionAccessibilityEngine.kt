package com.yourpackage.visionengine.engine

import android.content.Context
import android.media.Image
import android.speech.tts.TextToSpeech
import android.util.Log
import androidx.camera.core.*
import androidx.lifecycle.LifecycleOwner
import com.yourpackage.visionengine.utils.DistanceEstimator
import kotlinx.coroutines.*
import org.tensorflow.lite.support.image.TensorImage
import org.tensorflow.lite.task.vision.detector.ObjectDetector
import java.util.*

class VisionAccessibilityEngine(
    private val context: Context,
    private val viewFinder: PreviewView
) : TextToSpeech.OnInitListener {

    companion object {
        private const val TAG = "VisionEngine"
        private const val MODEL_NAME = "yolov8n.tflite"
        private const val SPEAK_COOLDOWN_MS = 3000L
    }

    private var tts: TextToSpeech? = null
    private var detector: ObjectDetector? = null
    private var isTtsReady = false
    private var lastSpeakTime = 0L
    private var lastSpokenText = ""

    init {
        tts = TextToSpeech(context, this)
        initializeModel()
        initializeCamera()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            val result = tts?.setLanguage(Locale.SIMPLIFIED_CHINESE)
            if (result != TextToSpeech.LANG_MISSING_DATA && result != TextToSpeech.LANG_NOT_SUPPORTED) {
                tts?.setSpeechRate(1.2f)
                isTtsReady = true
            }
        }
    }

    private fun initializeModel() {
        try {
            val options = ObjectDetector.ObjectDetectorOptions.builder()
                .setMaxResults(5).setScoreThreshold(0.5f).build()
            detector = ObjectDetector.createFromFileAndOptions(context, MODEL_NAME, options)
        } catch (e: Exception) { Log.e(TAG, "模型加载失败", e) }
    }

    private fun initializeCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(viewFinder.surfaceProvider) }
            
            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build().also { analyzer ->
                    analyzer.setAnalysisListener { proxy ->
                        val image = proxy.acquireLatestImage()
                        image?.let { processImage(it); it.close() }
                    }
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(context as LifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, imageAnalyzer)
            } catch (e: Exception) { Log.e(TAG, "相机绑定失败", e) }
        }, ContextCompat.getMainExecutor(context))
    }

    private fun processImage(imageProxy: Image) {
        CoroutineScope(Dispatchers.Default).launch {
            try {
                val tensorImage = TensorImage.fromBitmap(convertImageProxyToBitmap(imageProxy)) 
                val results = detector?.detect(tensorImage) ?: emptyList()

                for (detection in results) {
                    val label = detection.categories.firstOrNull()?.label ?: "未知障碍物"
                    val bbox = detection.boundingBox
                    val realDistance = DistanceEstimator.estimateDistance(label, bbox.height(), tensorImage.height)
                    
                    realDistance?.let { handleObstacleDistance(label, it) }
                    break
                }
            } catch (e: Exception) { Log.e(TAG, "AI推理异常", e) }
        }
    }

    private fun handleObstacleDistance(type: String, distance: Float) {
        if (!isTtsReady) return
        val currentTime = System.currentTimeMillis()
        val message = when {
            distance < 0.5f -> "危险！$type，距离很近，请立即停下！"
            distance < 1.5f -> "注意，$type，请小心避让。"
            else -> return
        }
        if (message == lastSpokenText && currentTime - lastSpeakTime < SPEAK_COOLDOWN_MS) return

        tts?.speak(message, TextToSpeech.QUEUE_FLUSH, null, "alert")
        lastSpeakTime = currentTime; lastSpokenText = message
    }

    fun release() { tts?.stop(); tts?.shutdown(); detector?.close() }
    
    private fun convertImageProxyToBitmap(image: Image): android.graphics.Bitmap { 
        throw NotImplementedError("请引入 CameraX 官方 ImageProxy.toBitmap() 扩展或自行实现 YUV->ARGB 转换") 
    }
}
