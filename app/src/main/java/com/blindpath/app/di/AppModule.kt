package com.blindpath.app.di

import com.blindpath.module_navigation.data.NavigationRepositoryImpl
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_obstacle.data.ObstacleRepositoryImpl
import com.blindpath.module_obstacle.domain.ObstacleRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AppModule {

    @Binds
    @Singleton
    abstract fun bindObstacleRepository(
        impl: ObstacleRepositoryImpl
    ): ObstacleRepository

    @Binds
    @Singleton
    abstract fun bindNavigationRepository(
        impl: NavigationRepositoryImpl
    ): NavigationRepository
}
