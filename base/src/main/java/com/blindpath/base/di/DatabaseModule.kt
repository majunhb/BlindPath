package com.blindpath.base.di

import com.blindpath.base.data.local.BlindPathDatabase
import com.blindpath.base.data.local.EncryptedDatabaseFactory
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 数据库模块 - 提供加密的 Room 数据库实例
 *
 * 使用 SQLCipher 对数据库进行 AES-256 加密，
 * 加密密钥由 Android Keystore 保护。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(factory: EncryptedDatabaseFactory): BlindPathDatabase {
        return factory.createDatabase()
    }
}
