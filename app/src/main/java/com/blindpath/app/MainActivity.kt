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
import com.blindpath.app.service.TtsManager
import com.blindpath.app.ui.screens.MainScreen
import com.blindpath.app.ui.theme.BlindPathTheme
import com.blindpath.module_obstacle.service.ObstacleService
import com.blindpath.module_navigation.service.NavigationService

class MainActivity : ComponentActivity() {

    private lateinit var ttsManager: TtsManager
    private var pendingAction: String? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.all { it.value }
        if (allGranted) {
            pendingAction?.let { performAction(it) }
        } else {
            Toast.makeText(this, "需要权限才能使用此功能", Toast.LENGTH_LONG).show()
            ttsManager.speak("需要相关权限才能使用此功能，请在设置中授权")
        }
        pendingAction = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        ttsManager = TtsManager(this)

        setContent {
            BlindPathTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onObstacleDetectionClick = { requestPermissionAndAction("obstacle") },
                        onLocationClick = { requestPermissionAndAction("location") },
                        onSosClick = { performSos() }
                    )
                }
            }
        }

        // 应用启动后延迟播报
        ttsManager.speak(TtsManager.MSG_APP_READY)
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
        }
    }

    private fun startObstacleDetection() {
        ttsManager.speak(TtsManager.MSG_CAMERA_STARTED)
        
        val intent = Intent(this, ObstacleService::class.java).apply {
            action = ObstacleService.ACTION_START
        }
        startForegroundService(intent)
        
        Toast.makeText(this, "障碍物检测已开启", Toast.LENGTH_SHORT).show()
    }

    private fun startLocationService() {
        ttsManager.speak(TtsManager.MSG_LOCATION_UPDATED)
        
        val intent = Intent(this, NavigationService::class.java).apply {
            action = NavigationService.ACTION_START
        }
        startForegroundService(intent)
        
        Toast.makeText(this, "位置服务已开启", Toast.LENGTH_SHORT).show()
    }

    private fun performSos() {
        ttsManager.speak(TtsManager.MSG_SOS_SENT)
        
        // 简单的SOS：打开拨号界面
        val intent = Intent(Intent.ACTION_DIAL).apply {
            data = Uri.parse("tel:110")
        }
        startActivity(intent)
        
        Toast.makeText(this, "正在拨打紧急求助", Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::ttsManager.isInitialized) {
            ttsManager.shutdown()
        }
    }
}
