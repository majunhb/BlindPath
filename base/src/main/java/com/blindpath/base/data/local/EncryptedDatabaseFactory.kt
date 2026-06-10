package com.blindpath.base.data.local

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import com.blindpath.base.security.SecureStorage
import net.sqlcipher.database.SupportFactory
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 加密数据库工厂 - 使用 SQLCipher 对 Room 数据库进行 AES-256 加密
 *
 * 加密密钥从 SecureStorage 获取，确保：
 * 1. 密钥不硬编码在代码中
 * 2. 密钥存储在 Android Keystore 中
 * 3. 每个设备有独立的加密密钥
 */
@Singleton
class EncryptedDatabaseFactory @Inject constructor(
    private val context: Context,
    private val secureStorage: SecureStorage
) {

    companion object {
        private const val DATABASE_NAME = "blindpath_encrypted.db"
        private const val DB_ENCRYPTION_KEY_ALIAS = "db_encryption_key"
        private const val KEY_LENGTH_BYTES = 32 // 256-bit
    }

    /**
     * 创建加密的数据库实例
     */
    fun createDatabase(): BlindPathDatabase {
        val passphrase = getOrCreatePassphrase()
        val factory = SupportFactory(passphrase)

        return Room.databaseBuilder(
            context.applicationContext,
            BlindPathDatabase::class.java,
            DATABASE_NAME
        )
            .openHelperFactory(factory)
            .build()
    }

    /**
     * 获取或创建数据库加密密钥
     * 密钥存储在 SecureStorage 中，由 Android Keystore 保护
     */
    private fun getOrCreatePassphrase(): ByteArray {
        val existingKey = secureStorage.getSecureString(DB_ENCRYPTION_KEY_ALIAS)

        return if (existingKey != null) {
            // 使用已有的密钥
            existingKey.toByteArray(Charsets.UTF_8)
        } else {
            // 生成新的随机密钥
            val newKey = generateSecureKey()
            secureStorage.putSecureString(DB_ENCRYPTION_KEY_ALIAS, newKey)
            Timber.i("EncryptedDatabaseFactory: 已生成新的数据库加密密钥")
            newKey.toByteArray(Charsets.UTF_8)
        }
    }

    /**
     * 生成安全的随机密钥
     */
    private fun generateSecureKey(): String {
        val chars = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789!@#$%^&*"
        val secureRandom = java.security.SecureRandom()
        return (1..KEY_LENGTH_BYTES)
            .map { chars[secureRandom.nextInt(chars.length)] }
            .joinToString("")
    }
}
