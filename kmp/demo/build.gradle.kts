@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    jvm()

    wasmJs {
        browser()
        binaries.executable()
    }

    // The iOS app links against this framework. Builds on macOS only.
    listOf(iosX64(), iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "SexyTopoDemo"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":shared"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
        }
    }
}

compose.desktop {
    application {
        mainClass = "org.hwyl.sexytopo.demo.MainKt"
    }
}

// Renders the shared Compose UI to a PNG with no display attached. This is how the port is
// demonstrated on a headless machine: the same composable that the iOS app hosts.
tasks.register<JavaExec>("renderDemoPng") {
    group = "verification"
    description = "Render the shared survey canvas to build/demo/ as PNG files."
    dependsOn("jvmMainClasses")
    mainClass.set("org.hwyl.sexytopo.demo.RenderPngKt")
    classpath =
        kotlin.jvm().compilations.getByName("main").output.allOutputs +
            kotlin.jvm().compilations.getByName("main").runtimeDependencyFiles!!
}
