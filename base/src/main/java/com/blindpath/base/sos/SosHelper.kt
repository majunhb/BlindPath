package com.blindpath.base.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.telephony.SmsManager
import androidx.core.content.ContextCompat
import com.blindpath.base.tts.TtsManager
import timber.log.Timber

/**
 * SOS 紧急求助工具
 * 支持发送求助短信（含GPS位置）给预设紧急联系人
 */
object SosHelper {

    private const val TAG = "SosHelper"

    // 预设紧急联系人（可配置化）
    private var emergencyContacts = listOf("110")

    /**
     * 设置紧急联系人
     */
    fun setEmergencyContacts(contacts: List<String>) {
        emergencyContacts = contacts.filter { it.isNotBlank() }
    }

    /**
     * 发送 SOS 求救短信
     * @param context 上下文
     * @param location 当前 GPS 位置（可选）
     * @param onSent 发送成功回调
     * @param onError 发送失败回调
     */
    fun sendSos(context: Context, location: Location? = null,
                onSent: () -> Unit = {}, onError: (String) -> Unit = {}) {

        val message = buildSosMessage(location)

        if (emergencyContacts.isEmpty()) {
            onError("未设置紧急联系人")
            return
        }

        try {
            val smsManager = context.getSystemService(SmsManager::class.java)

            for (contact in emergencyContacts) {
                try {
                    // 分拆长短信
                    val parts = smsManager.divideMessage(message)
                    if (parts.size == 1) {
                        smsManager.sendTextMessage(contact, null, message, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(contact, null, parts, null, null)
                    }
                    Timber.tag(TAG).d("SOS sent to $contact")
                } catch (e: Exception) {
                    Timber.tag(TAG).e(e, "Failed to send SOS to $contact")
                }
            }

            onSent()
        } catch (e: SecurityException) {
            onError("缺少短信权限")
            Timber.tag(TAG).e(e, "SMS permission denied")
        } catch (e: Exception) {
            onError("发送失败: ${e.message}")
            Timber.tag(TAG).e(e, "SOS failed")
        }
    }

    /**
     * 构建 SOS 消息文本
     */
    private fun buildSosMessage(location: Location?): String {
        val sb = StringBuilder()
        sb.append("【紧急求助】")

        if (location != null) {
            sb.append("我在求助，位置：")
            sb.append("https://maps.google.com/?q=${location.latitude},${location.longitude}")
        } else {
            sb.append("我在求助，无法获取位置")
        }

        sb.append("\n此消息由 BlindPath 智行助盲应用自动发送")
        return sb.toString()
    }

    /**
     * 检查是否有短信权限
     */
    fun hasSmsPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.SEND_SMS
        ) == PackageManager.PERMISSION_GRANTED
    }

    /**
     * 检查是否有定位权限
     */
    fun hasLocationPermission(context: Context): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED
    }
}
