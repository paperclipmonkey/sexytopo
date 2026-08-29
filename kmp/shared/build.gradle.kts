@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
}

kotlin {
    jvm()

    // Browser target: lets the same shared core and UI be demonstrated without a Mac.
    wasmJs { browser() }

    // Declared so the shared core builds for iOS. These compile only on a macOS
    // host; on other hosts kotlin.native.ignoreDisabledTargets keeps them inert.
    iosX64()
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
