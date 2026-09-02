plugins {
    // Same AGP and Gradle major version as the Android app in app/: Compose Multiplatform 1.12.0
    // bundles androidx.compose artifacts that require AGP 9.1+, so this build moved off Gradle
    // 8.14/AGP 8.7 to stay current alongside it rather than pinning to an older pair.
    id("com.android.application") version "9.2.1" apply false
    id("com.android.library") version "9.2.1" apply false
    kotlin("multiplatform") version "2.3.20" apply false
    kotlin("plugin.serialization") version "2.3.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.20" apply false
    id("org.jetbrains.compose") version "1.12.0" apply false
}
