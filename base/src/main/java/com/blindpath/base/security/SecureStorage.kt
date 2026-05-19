package com.blindpath.base.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import timber.log.Timber
import java.nio.charset.Charset
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 安全存储管理器
 * 使用 Android Keystore 加密敏感数据
 */
class SecureStorage(
    private val context: Context
) {
    companion object {
        private const val ANDROID_KEY_STORE = "AndroidKeyStore"
        private const val KEY_ALIAS = "blindpath_secure_key"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
        private const val TAG_LENGTH = 128
        private const val SHARED_PREFS_NAME = "secure_storage"
    }
    
    private val keyStore: KeyStore by lazy {
        KeyStore.getInstance(ANDROID_KEY_STORE).apply { load(null) }
    }
    
    private val sharedPreferences by lazy {
        context.getSharedPreferences(SHARED_PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * 初始化密钥
     */
    fun initializeKey(): Boolean {
        return try {
            if (!keyStore.containsAlias(KEY_ALIAS)) {
                createKey()
            }
            true
        } catch (e: Exception) {
            Timber.e(e, "Failed to initialize key")
            false
        }
    }
    
    /**
     * 创建新密钥
     */
    private fun createKey() {
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            ANDROID_KEY_STORE
        )
        
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setRandomizedEncryptionRequired(true)
            .build()
        
        keyGenerator.init(spec)
        keyGenerator.generateKey()
        
        Timber.d("New encryption key created")
    }
    
    /**
     * 加密数据
     */
    fun encrypt(data: String): EncryptedData? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getSecretKey())
            
            val encryptedBytes = cipher.doFinal(data.toByteArray(Charset.forName("UTF-8")))
            val iv = cipher.iv
            
            EncryptedData(
                encryptedData = android.util.Base64.encodeToString(encryptedBytes, android.util.Base64.NO_WRAP),
                iv = android.util.Base64.encodeToString(iv, android.util.Base64.NO_WRAP)
            )
        } catch (e: Exception) {
            Timber.e(e, "Encryption failed")
            null
        }
    }
    
    /**
     * 解密数据
     */
    fun decrypt(encryptedData: EncryptedData): String? {
        return try {
            val cipher = Cipher.getInstance(TRANSFORMATION)
            
            val spec = GCMParameterSpec(TAG_LENGTH, android.util.Base64.decode(encryptedData.iv, android.util.Base64.NO_WRAP))
            cipher.init(Cipher.DECRYPT_MODE, getSecretKey(), spec)
            
            val decryptedBytes = cipher.doFinal(
                android.util.Base64.decode(encryptedData.encryptedData, android.util.Base64.NO_WRAP)
            )
            
            String(decryptedBytes, Charset.forName("UTF-8"))
        } catch (e: Exception) {
            Timber.e(e, "Decryption failed")
            null
        }
    }
    
    /**
     * 安全存储字符串
     */
    fun putSecureString(key: String, value: String): Boolean {
        val encrypted = encrypt(value) ?: return false
        
        sharedPreferences.edit()
            .putString("${key}_data", encrypted.encryptedData)
            .putString("${key}_iv", encrypted.iv)
            .apply()
        
        return true
    }
    
    /**
     * 获取安全存储的字符串
     */
    fun getSecureString(key: String): String? {
        val data = sharedPreferences.getString("${key}_data", null) ?: return null
        val iv = sharedPreferences.getString("${key}_iv", null) ?: return null
        
        return decrypt(EncryptedData(data, iv))
    }
    
    /**
     * 移除安全存储的数据
     */
    fun removeSecureString(key: String) {
        sharedPreferences.edit()
            .remove("${key}_data")
            .remove("${key}_iv")
            .apply()
    }
    
    /**
     * 检查是否存在安全存储的数据
     */
    fun hasSecureString(key: String): Boolean {
        return sharedPreferences.contains("${key}_data")
    }
    
    /**
     * 清除所有安全存储的数据
     */
    fun clearAll() {
        sharedPreferences.edit().clear().apply()
    }
    
    private fun getSecretKey(): SecretKey {
        return (keyStore.getEntry(KEY_ALIAS, null) as KeyStore.SecretKeyEntry).secretKey
    }
    
    /**
     * 加密数据结构
     */
    data class EncryptedData(
        val encryptedData: String,
        val iv: String
    )
}

/**
 * 安全存储键定义
 */
object SecureStorageKeys {
    const val USER_TOKEN = "user_token"
    const val USER_ID = "user_id"
    const val EMERGENCY_CONTACTS = "emergency_contacts"
    const val USER_PHONE = "user_phone"
    const val USER_EMAIL = "user_email"
    const val API_KEY = "api_key"
    const val NAVIGATION_HISTORY = "navigation_history"
    const val LOCATION_HISTORY = "location_history"
}

/**
 * 敏感数据脱敏工具
 */
object DataMasker {
    
    /**
     * 手机号脱敏
     * 138****1234
     */
    fun maskPhoneNumber(phone: String): String {
        if (phone.length < 7) return phone
        return "${phone.substring(0, 3)}****${phone.substring(phone.length - 4)}"
    }
    
    /**
     * 邮箱脱敏
     * a***@example.com
     */
    fun maskEmail(email: String): String {
        val parts = email.split("@")
        if (parts.size != 2) return email
        
        val name = parts[0]
        val domain = parts[1]
        
        val maskedName = if (name.length > 1) {
            "${name[0]}***"
        } else {
            name
        }
        
        return "$maskedName@$domain"
    }
    
    /**
     * 地址脱敏
     * 北京市朝阳区***
     */
    fun maskAddress(address: String): String {
        if (address.length < 6) return address
        return "${address.substring(0, address.length - 3)}***"
    }
    
    /**
     * 身份证号脱敏
     * 110***********1234
     */
    fun maskIdCard(idCard: String): String {
        if (idCard.length < 8) return idCard
        return "${idCard.substring(0, 3)}***********${idCard.substring(idCard.length - 4)}"
    }
    
    /**
     * 姓名脱敏
     * 张*
     */
    fun maskName(name: String): String {
        if (name.isEmpty()) return name
        return "${name[0]}*"
    }
}
