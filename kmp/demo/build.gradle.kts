@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalWasmDsl::class)

import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.library")
}

kotlin {
    jvm()

    // The same composables the iOS app hosts, packaged as an Android library. Nothing in
    // commonMain changes to make this work - that is the point of the target being here.
    androidTarget {
        compilations.all {
            compileTaskProvider.configure { compilerOptions { jvmTarget.set(JvmTarget.JVM_11) } }
        }
    }

    wasmJs {
        browser()
        binaries.executable()
    }

    // The iOS app links against this framework. Builds on macOS only. The Intel simulator
    // target (iosX64) is gone: Compose Multiplatform 1.11 dropped it along with Kotlin's own
    // deprecation of Apple x86_64.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
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
            implementation(compose.components.resources)
        }
        jvmMain.dependencies {
            implementation(compose.desktop.currentOs)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            // Compose's own test framework, which drives the real composables through the
            // semantics tree rather than through pixels: `onNodeWithTag(...).performClick()`
            // where `field.mjs` has to work out which pixel a row is drawn at. Runs headlessly,
            // in seconds, with no browser.
            @OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)
            implementation(compose.uiTest)
            implementation(compose.desktop.currentOs)
        }
        // Runs on the iOS simulator, on the macOS runner. This is what turns `iosMain` from
        // "compiles" into "works": DocumentsFileStore is hand-written Objective-C interop, and
        // every way it can be wrong compiles perfectly.
        iosTest.dependencies {
            implementation(kotlin("test"))
        }
        wasmJsMain.dependencies {
            implementation("org.jetbrains.kotlinx:kotlinx-browser:0.3")
        }
        androidMain.dependencies {
            // ComponentActivity.setContent, and the activity-result launcher the camera goes
            // through - `PhotoCapture.android.kt` registers one from the composition. Everything
            // the app draws above that line is common code.
            implementation("androidx.activity:activity-compose:1.9.3")
            // FileProvider, which is how the camera app is given somewhere to write the
            // photograph. It arrives transitively through activity anyway; declared because
            // depending on it is a compile-time fact rather than something to be discovered when
            // a transitive version changes. Same version the host app already asks for, so
            // nothing new is resolved.
            implementation("androidx.core:core-ktx:1.13.1")
        }
    }
}

android {
    namespace = "org.hwyl.sexytopo.demo"
    compileSdk = 37
    defaultConfig {
        minSdk = 23
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "org.hwyl.sexytopo.demo.resources"
    generateResClass = auto
}

compose.desktop {
    application {
        mainClass = "org.hwyl.sexytopo.demo.MainKt"
    }
}

// A cache name `sw.js` can stamp into itself - see the comment on `CACHE` there. The browser's own
// service-worker update check is a byte diff of that file, so giving it a fresh value here is what
// turns "a new build was deployed" into "the browser notices sw.js changed", which is the one part
// of staying current that has to happen outside the worker's own JavaScript.
//
// `GITHUB_SHA` when CI built this (traceable to the commit in the browser's Application panel, and
// stable across a re-run of the same push rather than bumping for nothing); the current time
// otherwise, so a local build always gets a namespace nothing has served before.
val serviceWorkerBuildId: String =
    System.getenv("GITHUB_SHA")?.take(12) ?: System.currentTimeMillis().toString()

tasks.named<org.gradle.language.jvm.tasks.ProcessResources>("wasmJsProcessResources") {
    // Gradle's up-to-date check does not otherwise know this task's output depends on a value read
    // from outside the file collection it copies; without this, re-running the build without
    // touching any resource would leave a stale substitution in place rather than a fresh one.
    inputs.property("serviceWorkerBuildId", serviceWorkerBuildId)
    filesMatching("sw.js") {
        filter { line -> line.replace("%%BUILD_ID%%", serviceWorkerBuildId) }
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
