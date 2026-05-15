package com.blindpath.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.blindpath.app.service.TtsManager
import com.blindpath.app.ui.screens.MainScreen
import com.blindpath.app.ui.theme.BlindPathTheme
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private lateinit var ttsManager: TtsManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 鍒濆鍖栬闊虫湇鍔?        ttsManager = TtsManager(this)

        setContent {
            BlindPathTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainScreen(
                        onObstacleDetectionClick = {
                            ttsManager.speak(TtsManager.MSG_CAMERA_STARTED)
                        },
                        onLocationClick = {
                            ttsManager.speak(TtsManager.MSG_LOCATION_UPDATED)
                        },
                        onSosClick = {
                            ttsManager.speak(TtsManager.MSG_SOS_SENT)
                        }
                    )
                }
            }
        }

        // 搴旂敤鍚姩鍚庡欢杩熸挱鎶ユ杩庤
        android.os.Handler(mainLooper).postDelayed({
            ttsManager.speak(TtsManager.MSG_APP_READY)
        }, 1000)
    }

    override fun onDestroy() {
        super.onDestroy()
        ttsManager.shutdown()
    }
}
