package com.blindpath.module_voice.receiver

import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import timber.log.Timber

/**
 * 蓝牙耳机/骨传导耳机状态监听接收器
 * 
 * 功能：
 * - 监听蓝牙耳机连接/断开
 * - 自动切换音频路由到蓝牙耳机
 * - 支持骨传导耳机
 */
class BluetoothAudioReceiver : BroadcastReceiver() {
    
    companion object {
        private var isBluetoothHeadsetConnected = false
        
        fun isHeadsetConnected(): Boolean = isBluetoothHeadsetConnected
    }
    
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> {
                val state = intent.getIntExtra(BluetoothHeadset.EXTRA_STATE, BluetoothHeadset.STATE_DISCONNECTED)
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                
                when (state) {
                    BluetoothHeadset.STATE_CONNECTED -> {
                        Timber.i("BluetoothAudio: Headset connected - ${device?.name}")
                        isBluetoothHeadsetConnected = true
                        // 切换到蓝牙耳机音频
                        switchToBluetoothAudio(context, true)
                    }
                    BluetoothHeadset.STATE_DISCONNECTED -> {
                        Timber.i("BluetoothAudio: Headset disconnected - ${device?.name}")
                        isBluetoothHeadsetConnected = false
                        // 切换回设备音频
                        switchToBluetoothAudio(context, false)
                    }
                }
            }
            
            AudioManager.ACTION_SCO_AUDIO_STATE_UPDATED -> {
                val state = intent.getIntExtra(AudioManager.EXTRA_SCO_AUDIO_STATE, AudioManager.SCO_AUDIO_STATE_DISCONNECTED)
                when (state) {
                    AudioManager.SCO_AUDIO_STATE_CONNECTED -> {
                        Timber.i("BluetoothAudio: SCO audio connected")
                    }
                    AudioManager.SCO_AUDIO_STATE_DISCONNECTED -> {
                        Timber.i("BluetoothAudio: SCO audio disconnected")
                    }
                    AudioManager.SCO_AUDIO_STATE_CONNECTING -> {
                        Timber.d("BluetoothAudio: SCO audio connecting...")
                    }
                }
            }
            
            BluetoothDevice.ACTION_ACL_CONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Timber.d("BluetoothAudio: ACL connected - ${device?.name} [${device?.type}]")
            }
            
            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val device = intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                Timber.d("BluetoothAudio: ACL disconnected - ${device?.name}")
            }
        }
    }
    
    private fun switchToBluetoothAudio(context: Context, useBluetooth: Boolean) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        
        try {
            if (useBluetooth) {
                // 切换到蓝牙耳机
                if (audioManager.isBluetoothScoAvailableOffCall) {
                    audioManager.startBluetoothSco()
                    audioManager.isBluetoothScoOn = true
                    Timber.i("BluetoothAudio: Switched to Bluetooth SCO")
                }
            } else {
                // 切换回设备音频
                audioManager.isBluetoothScoOn = false
                audioManager.stopBluetoothSco()
                Timber.i("BluetoothAudio: Switched to device audio")
            }
        } catch (e: Exception) {
            Timber.e(e, "BluetoothAudio: Failed to switch audio")
        }
    }
}
