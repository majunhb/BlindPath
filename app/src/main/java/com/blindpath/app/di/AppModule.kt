package com.blindpath.app.di

import com.blindpath.module_navigation.data.NavigationRepositoryImpl
import com.blindpath.module_navigation.domain.NavigationRepository
import com.blindpath.module_obstacle.data.ObstacleRepositoryImpl
import com.blindpath.module_obstacle.domain.ObstacleRepository
import com.blindpath.module_trip_assist.data.TripAssistRepositoryImpl
import com.blindpath.module_trip_assist.domain.TripAssistRepository
import com.blindpath.module_voice.data.IntentRouterImpl
import com.blindpath.module_voice.data.NluEngineImpl
import com.blindpath.module_voice.data.VoiceCommandRepositoryImpl
import com.blindpath.module_voice.data.VoiceInteractionManagerImpl
import com.blindpath.module_voice.data.VoiceRepositoryImpl
import com.blindpath.module_voice.domain.IntentRouter
import com.blindpath.module_voice.domain.NluEngine
import com.blindpath.module_voice.domain.VoiceCommandRepository
import com.blindpath.module_voice.domain.VoiceInteractionManager
import com.blindpath.module_voice.domain.VoiceRepository
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

    @Binds
    @Singleton
    abstract fun bindVoiceRepository(
        impl: VoiceRepositoryImpl
    ): VoiceRepository

    @Binds
    @Singleton
    abstract fun bindTripAssistRepository(
        impl: TripAssistRepositoryImpl
    ): TripAssistRepository

    @Binds
    @Singleton
    abstract fun bindVoiceCommandRepository(
        impl: VoiceCommandRepositoryImpl
    ): VoiceCommandRepository

    @Binds
    @Singleton
    abstract fun bindVoiceInteractionManager(
        impl: VoiceInteractionManagerImpl
    ): VoiceInteractionManager

    @Binds
    @Singleton
    abstract fun bindNluEngine(
        impl: NluEngineImpl
    ): NluEngine

    @Binds
    @Singleton
    abstract fun bindIntentRouter(
        impl: IntentRouterImpl
    ): IntentRouter
}
