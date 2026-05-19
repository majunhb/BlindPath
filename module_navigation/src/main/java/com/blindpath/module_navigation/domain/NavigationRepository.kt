package com.blindpath.module_navigation.domain

import android.location.Location
import com.blindpath.base.common.Result
import com.blindpath.module_navigation.domain.model.NavigationState
import kotlinx.coroutines.flow.Flow

interface NavigationRepository {
    val navigationState: Flow<NavigationState>

    suspend fun startNavigation(): Result<Boolean>
    suspend fun stopNavigation(): Result<Boolean>
    suspend fun setDestination(latitude: Double, longitude: Double, name: String): Result<Boolean>
    suspend fun clearDestination(): Result<Boolean>
    fun getCurrentLocation(): Location?
    fun isLocationAvailable(): Boolean
}
