plugins {
    id("com.android.application")
    kotlin("android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * The Android host for the shared Compose UI.
 *
 * Deliberately as thin as `iosApp/`: one activity, one call to `setContent { App() }`. Everything
 * the surveyor sees is in `:demo`, which is common code — so this module existing at all is the
 * evidence, not the code inside it.
 */
android {
    namespace = "org.hwyl.sexytopo.kmpdemo"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.hwyl.sexytopo.kmpdemo"
        // The Android app's own floor, so nothing here would raise it.
        minSdk = 23
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
        debug {
            // A distinct id so this demo can sit on a phone next to the real SexyTopo without
            // either replacing the other — which is exactly how you would want to compare them.
            applicationIdSuffix = ".debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_11)
        }
    }
}

dependencies {
    implementation(project(":demo"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-ktx:1.13.1")
}
