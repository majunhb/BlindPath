// Top-level build file

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.hilt.android) apply false
    alias(libs.plugins.ksp) apply false
}

allprojects {
    configurations.all {
        resolutionStrategy {
            // Compose - force all to BOM version
            force("androidx.compose.runtime:runtime:1.5.4")
            force("androidx.compose.runtime:runtime-saveable:1.5.4")
            force("androidx.compose.ui:ui:1.5.4")
            force("androidx.compose.ui:ui-graphics:1.5.4")
            force("androidx.compose.ui:ui-text:1.5.4")
            force("androidx.compose.ui:ui-unit:1.5.4")
            force("androidx.compose.ui:ui-geometry:1.5.4")
            force("androidx.compose.animation:animation:1.5.4")
            force("androidx.compose.animation:animation-core:1.5.4")
            force("androidx.compose.foundation:foundation:1.5.4")
            force("androidx.compose.foundation:foundation-layout:1.5.4")
            force("androidx.compose.material:material-ripple:1.5.4")
            force("androidx.compose.material:material-icons-core:1.5.4")
            // Lifecycle - force to project version
            force("androidx.lifecycle:lifecycle-runtime:2.6.2")
            force("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
            force("androidx.lifecycle:lifecycle-viewmodel:2.6.2")
            force("androidx.lifecycle:lifecycle-viewmodel-ktx:2.6.2")
            force("androidx.lifecycle:lifecycle-service:2.6.2")
            force("androidx.lifecycle:lifecycle-process:2.6.2")
            force("androidx.lifecycle:lifecycle-livedata:2.6.2")
            force("androidx.lifecycle:lifecycle-livedata-ktx:2.6.2")
            force("androidx.lifecycle:lifecycle-common:2.6.2")
            force("androidx.lifecycle:lifecycle-common-java8:2.6.2")
            // Fragment
            force("androidx.fragment:fragment:1.6.2")
            // Core
            force("androidx.core:core:1.12.0")
            force("androidx.core:core-ktx:1.12.0")
            // Activity
            force("androidx.activity:activity:1.8.1")
            force("androidx.activity:activity-compose:1.8.1")
            // Hilt
            force("com.google.dagger:hilt-android:2.48")
            force("com.google.dagger:hilt-core:2.48")
            force("com.google.dagger:dagger:2.48")
        }
    }
}
