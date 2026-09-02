@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.android.library")
}

kotlin {
    jvm()

    // Android is a target of the shared core like any other. That is the whole argument in one
    // line: the same module that an iOS app links as a framework, an Android app consumes as an
    // AAR - so adopting this core on Android is not a rewrite, it is a dependency.
    androidTarget {
        compilations.all {
            compileTaskProvider.configure { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
        }
    }

    // Browser target: lets the same shared core and UI be demonstrated without a Mac.
    wasmJs {
        browser()
        // Node lets the shared tests run on a NON-JVM target headlessly. That matters more than
        // it looks: Kotlin/Wasm, like Kotlin/Native for iOS, has no java.* at all, so a green run
        // here is real evidence the core carries no JVM-only dependency.
        nodejs()
    }

    // Declared so the shared core builds for iOS. These compile only on a macOS
    // host; on other hosts kotlin.native.ignoreDisabledTargets keeps them inert.
    // The Intel simulator target (iosX64) is gone: Compose Multiplatform 1.11
    // dropped it along with Kotlin's own deprecation of Apple x86_64.
    iosArm64()
    iosSimulatorArm64()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

android {
    namespace = "org.hwyl.sexytopo.shared"
    compileSdk = 35
    defaultConfig {
        // Matches the Android app's own minimum and Java level, so this core could drop straight
        // into it without moving anybody's floor.
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
