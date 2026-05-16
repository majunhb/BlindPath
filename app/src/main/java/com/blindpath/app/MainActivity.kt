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
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_voice.domain.VoiceRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var voiceRepository: VoiceRepository

    @Inject
    lateinit var navigationRepository: NavigationRepository

    private var pendingAction: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            pendingAction?.let { performAction(it) }
        } else {
            Toast.makeText(this, "需要权限才能使用此功能", Toast.LENGTH_LONG).show()
            CoroutineScope(Dispatchers.Main).launch {
                voiceRepository.speak("需要相关权限才能使用此功能，请在设置中授权", queueMode = false)
            }
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 初始化语音
        CoroutineScope(Dispatchers.Main).launch {
            voiceRepository.initialize()
            voiceRepository.speak("智行助盲应用已启动", queueMode = false)
        }

        setContent {
            BlindPathTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onObstacleDetectionClick = { requestPermissionAndAction("obstacle") },
                        onLocationClick = { requestPermissionAndAction("location") },
                        onSosClick = { requestPermissionAndAction("sos") }
                    )
                }
            }
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
        CoroutineScope(Dispatchers.Main).launch {
            voiceRepository.speak("正在发起紧急求助", queueMode = false)
        }

        // 获取 GPS 位置
        val location = if (SosHelper.hasLocationPermission(this)) {
            navigationRepository.getCurrentLocation()
        } else {
            null
        }

        // 发送 SOS 短信
        SosHelper.sendSos(
            context = this,
            location = location,
            onSent = {
                runOnUiThread {
                    Toast.makeText(this, "求助短信已发送", Toast.LENGTH_SHORT).show()
                    // 打开拨号界面
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:110")
                    }
                    startActivity(dialIntent)
                }
            },
            onError = { error ->
                runOnUiThread {
                    Toast.makeText(this, error, Toast.LENGTH_LONG).show()
                    // 短信失败也打开拨号
                    val dialIntent = Intent(Intent.ACTION_DIAL).apply {
                        data = Uri.parse("tel:110")
                    }
                    startActivity(dialIntent)
                }
            }
        )
    }
}
