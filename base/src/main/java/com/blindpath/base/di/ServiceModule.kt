package com.blindpath.base.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.components.ServiceComponent
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.android.scopes.ServiceScoped

/**
 * ServiceModule provides dependencies for Hilt's ServiceComponent.
 *
 * This module is necessary because Services do not provide Context by default in Hilt.
 * SmartPowerManager and other service-scoped classes require Context as a constructor parameter.
 *
 * Error fixed: [Dagger/MissingBinding] android.content.Context cannot be provided
 * without an @Provides-annotated method.
 */
@Module
@InstallIn(ServiceComponent::class)
object ServiceModule {

    /**
     * Provides the Application Context for Service-scoped dependencies.
     *
     * @param appContext The application context injected by Hilt
     * @return The application Context
     */
    @Provides
    @ServiceScoped
    fun provideServiceContext(
        @ApplicationContext appContext: Context
    ): Context = appContext
}
