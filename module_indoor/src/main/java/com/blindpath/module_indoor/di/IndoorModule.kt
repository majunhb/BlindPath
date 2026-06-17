package com.blindpath.module_indoor.di

import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * 室内环境识别模块的 Hilt DI 模块
 * 
 * IndoorDetector 使用 @Inject 构造函数，无需额外 Provides
 */
@Module
@InstallIn(SingletonComponent::class)
object IndoorModule {
    // IndoorDetector 通过 @Inject 构造函数自动提供
    // 无需额外 @Provides 方法
}
