plugins {
    // AGP 8.7 rather than the 9.x the Android app itself uses: this build runs on Gradle 8.14,
    // and 9.x needs Gradle 9. The two builds are deliberately independent, so they are free to
    // disagree about their toolchains.
    id("com.android.application") version "8.7.3" apply false
    id("com.android.library") version "8.7.3" apply false
    kotlin("multiplatform") version "2.2.0" apply false
    kotlin("plugin.serialization") version "2.2.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.0" apply false
    id("org.jetbrains.compose") version "1.8.2" apply false
}
