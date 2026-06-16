package com.blindpath.app



import android.Manifest

import android.content.Intent

import android.content.pm.PackageManager

import android.net.Uri

import android.os.Bundle

import android.widget.Toast

import androidx.activity.ComponentActivity

import androidx.activity.compose.setContent

import androidx.activity.result.contract.ActivityResultContracts

import androidx.compose.foundation.layout.fillMaxSize

import androidx.compose.material3.MaterialTheme

import androidx.compose.material3.Surface

import androidx.compose.ui.Modifier

import androidx.core.content.ContextCompat

import com.blindpath.app.ui.screens.MainScreen

import com.blindpath.app.ui.theme.BlindPathTheme

import com.blindpath.base.sos.SosHelper

import com.blindpath.module_indoor.data.IndoorDetector
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_obstacle.data.detection.SceneClassifier
import com.blindpath.module_obstacle.domain.ObstacleRepository

import com.blindpath.module_voice.domain.VoiceRepository

import androidx.lifecycle.lifecycleScope

import dagger.hilt.android.AndroidEntryPoint

import kotlinx.coroutines.launch

import javax.inject.Inject



@AndroidEntryPoint

class MainActivity : ComponentActivity() {



    @Inject

    lateinit var voiceRepository: VoiceRepository



    @Inject

    lateinit var navigationRepository: NavigationRepository



    @Inject
    lateinit var obstacleRepository: ObstacleRepository

    @Inject
    lateinit var indoorDetector: IndoorDetector

    @Inject
    lateinit var sceneClassifier: SceneClassifier

    @Inject
    lateinit var cameraXManager: com.blindpath.app.ui.camera.CameraXManager



    private var pendingAction: String? = null



    private val permissionLauncher = registerForActivityResult(

        ActivityResultContracts.RequestMultiplePermissions()

    ) { permissions ->

        val allGranted = permissions.all { it.value }

        if (allGranted) {

            pendingAction?.let { performAction(it) }

            // 如果位置权限被授予且之前未自动启动，则启动定位服务

            if (permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||

                permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true) {

                autoStartLocationIfPermitted()

            }

        } else {

            Toast.makeText(this, "需要权限才能使用此功能", Toast.LENGTH_LONG).show()

            lifecycleScope.launch {

                voiceRepository.speak("需要相关权限才能使用此功能，请在设置中授权", queueMode = false)

            }

        }

        pendingAction = null

    }



    override fun onCreate(savedInstanceState: Bundle?) {

        super.onCreate(savedInstanceState)



        // ★★★ 修复 v2-1：移除语音初始化抢话

        // 原代码：lifecycleScope.launch { voiceRepository.initialize(); voiceRepository.speak("智行助盲应用已启动") }

        // 根因：此处的 speak() 与 ViewModel.speakWelcome() 的 waitForTtsComplete() 产生竞争，

        //   导致 TTS 状态混乱，setWakeWordEnabled(true) 没执行到 → SpeechRecognizer 从未启动监听

        // 修复：删除此处所有语音操作，统一交给 ViewModel (VoiceInteractionManager) 管理唤醒链路：

        //   initialize() → speakWelcome() → setWakeWordEnabled(true) → startContinuousListening()



        // 自动启动定位服务（不需要用户点击）

        autoStartLocationIfPermitted()



        setContent {

            BlindPathTheme {

                Surface(

                    modifier = Modifier.fillMaxSize(),

                    color = MaterialTheme.colorScheme.background

                ) {

                    MainScreen(
                        obstacleRepository = obstacleRepository,
                        navigationRepository = navigationRepository,
                        indoorDetector = indoorDetector,
                        sceneClassifier = sceneClassifier,
                        cameraXManager = cameraXManager,
                        onObstacleDetectionClick = { requestPermissionAndAction("obstacle") },

                        onLocationClick = { requestPermissionAndAction("location") },

                        onSosClick = { requestPermissionAndAction("sos") }

                    )

                }

            }

        }

    }



    /**

     * 自动启动定位服务（如果权限已授予）

     * 用户在打开APP首页时即获得实时定位信息，无需跳转

     */

    private fun autoStartLocationIfPermitted() {

        val permissions = arrayOf(

            Manifest.permission.ACCESS_FINE_LOCATION,

            Manifest.permission.ACCESS_COARSE_LOCATION

        )

        val allGranted = permissions.all {

            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED

        }

        if (allGranted) {

            val intent = Intent(this, com.blindpath.module_navigation.service.NavigationService::class.java).apply {

                action = com.blindpath.module_navigation.service.NavigationService.ACTION_START

            }

            startForegroundService(intent)

        }

    }



    private fun requestPermissionAndAction(action: String) {

        val permissions = when (action) {

            "obstacle" -> arrayOf(

                Manifest.permission.CAMERA,

                Manifest.permission.VIBRATE

            )

            "location" -> arrayOf(

                Manifest.permission.ACCESS_FINE_LOCATION,

                Manifest.permission.ACCESS_COARSE_LOCATION

            )

            "sos" -> arrayOf(

                Manifest.permission.SEND_SMS,

                Manifest.permission.CALL_PHONE,

                Manifest.permission.ACCESS_FINE_LOCATION

            )

            else -> emptyArray()

        }



        val allGranted = permissions.all {

            ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED

        }



        if (allGranted) {

            performAction(action)

        } else {

            pendingAction = action

            permissionLauncher.launch(permissions)

        }

    }



    private fun performAction(action: String) {

        when (action) {

            "obstacle" -> startObstacleDetection()

            "location" -> startLocationService()

            "sos" -> performSos()

        }

    }



    private fun startObstacleDetection() {

        val intent = Intent(this, com.blindpath.module_obstacle.service.ObstacleService::class.java).apply {

            action = com.blindpath.module_obstacle.service.ObstacleService.ACTION_START

        }

        startForegroundService(intent)

        Toast.makeText(this, "障碍物检测已开启", Toast.LENGTH_SHORT).show()

    }



    private fun startLocationService() {

        val intent = Intent(this, com.blindpath.module_navigation.service.NavigationService::class.java).apply {

            action = com.blindpath.module_navigation.service.NavigationService.ACTION_START

        }

        startForegroundService(intent)

        Toast.makeText(this, "位置服务已开启", Toast.LENGTH_SHORT).show()

    }



    private fun performSos() {

        lifecycleScope.launch {

            voiceRepository.speak("正在发起紧急求助", queueMode = false)



            // 获取 GPS 位置

            val location = if (SosHelper.hasLocationPermission(this@MainActivity)) {

                navigationRepository.getCurrentLocation()

            } else {

                null

            }



            // 发送 SOS 短信

            SosHelper.sendSos(

                context = this@MainActivity,

                location = location,

                onResult = { result ->

                    runOnUiThread {

                        val message = when (result) {

                            SosHelper.SosResult.ALL_SENT -> "求助短信已全部发送"

                            SosHelper.SosResult.PARTIAL_SENT -> "部分求助短信发送失败"

                            SosHelper.SosResult.ALL_FAILED -> "求助短信发送失败，请检查权限"

                            SosHelper.SosResult.RATE_LIMITED -> "SOS发送频率已达上限"

                        }

                        Toast.makeText(this@MainActivity, message, Toast.LENGTH_LONG).show()

                    }

                }

            )

        }

    }

}

