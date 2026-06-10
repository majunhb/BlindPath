package com.blindpath.base.sos

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.telephony.SmsManager
import android.widget.Toast
import androidx.core.content.ContextCompat
import timber.log.Timber
import java.util.concurrent.CopyOnWriteArrayList

/**
 * SOS 紧急求助工具
 * 支持发送求助短信（含GPS位置）给预设紧急联系人
 */
object SosHelper {

    private const val TAG = "SosHelper"
    private const val MAX_SOS_PER_HOUR = 3
    private const val SOS_WINDOW_MS = 60 * 60 * 1000L // 1小时

    // 预设紧急联系人（可配置化）
    private val emergencyContacts = CopyOnWriteArrayList<String>()

    private val sosHistory = CopyOnWriteArrayList<Long>()

    /**
     * SOS发送结果
     */
    enum class SosResult {
        ALL_SENT,      // 全部发送成功
        PARTIAL_SENT,  // 部分发送成功
        ALL_FAILED,    // 全部发送失败
        RATE_LIMITED   // 频率限制
    }

    /**
     * 检查是否可以发送SOS
     * @return Pair<Boolean, String> (是否允许, 提示消息)
     */
    private fun canSendSos(): Pair<Boolean, String> {
        val now = System.currentTimeMillis()
        // 清理过期记录
        sosHistory.removeAll { now - it > SOS_WINDOW_MS }
        return if (sosHistory.size >= MAX_SOS_PER_HOUR) {
            Pair(false, "SOS发送频率已达上限，请1小时后再试")
        } else {
            Pair(true, "")
        }
    }

    /**
     * 设置紧急联系人
     */
    fun setEmergencyContacts(contacts: List<String>) {
        emergencyContacts.clear()
        emergencyContacts.addAll(contacts.filter { it.isNotBlank() })
    }

    /**
     * 发送 SOS 求救短信
     * @param context 上下文
     * @param location 当前 GPS 位置（可选）
     * @param onResult 发送结果回调
     */
    fun sendSos(
        context: Context,
        location: Location? = null,
        onResult: (SosResult) -> Unit = {}
    ) {
        val (allowed, message) = canSendSos()
        if (!allowed) {
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
            onResult(SosResult.RATE_LIMITED)
            return
        }
        // 记录发送时间
        sosHistory.add(System.currentTimeMillis())

        val sosMessage = buildSosMessage(location)

        if (emergencyContacts.isEmpty()) {
            onResult(SosResult.ALL_FAILED)
            return
        }

        var successCount = 0
        var failureCount = 0

        try {
            val smsManager = context.getSystemService(SmsManager::class.java)

            for (contact in emergencyContacts) {
                try {
                    // 分拆长短信
                    val parts = smsManager.divideMessage(sosMessage)
                    if (parts.size == 1) {
                        smsManager.sendTextMessage(contact, null, sosMessage, null, null)
                    } else {
                        smsManager.sendMultipartTextMessage(contact, null, parts, null, null)
                    }
                    successCount++
                    Timber.tag(TAG).d("SOS sent to $contact")
                } catch (e: Exception) {
                    failureCount++
                    Timber.tag(TAG).e(e, "Failed to send SOS to $contact")
                }
            }

            // 根据成功/失败数量返回结果
            val result = when {
                successCount > 0 && failureCount == 0 -> SosResult.ALL_SENT
                successCount > 0 && failureCount > 0 -> SosResult.PARTIAL_SENT
                else -> SosResult.ALL_FAILED
            }
            onResult(result)
        } catch (e: SecurityException) {
            failureCount = emergencyContacts.size
            Timber.tag(TAG).e(e, "SMS permission denied")
            onResult(SosResult.ALL_FAILED)
        } catch (e: Exception) {
            failureCount = emergencyContacts.size
            Timber.tag(TAG).e(e, "SOS failed")
            onResult(SosResult.ALL_FAILED)
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
