package org.hwyl.sexytopo.androidapp

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import org.hwyl.sexytopo.demo.AndroidHost
import org.hwyl.sexytopo.demo.App

/**
 * The entire Android-specific surface of this demo.
 *
 * Compare it with `iosApp/iosApp/ContentView.swift`, which is the same three lines in Swift, and
 * with `demo/src/wasmJsMain/.../Main.wasmJs.kt`, which is the same again for the browser. Three
 * hosts, one `App()`; nothing below this line knows which of them it is running in.
 *
 * `enableEdgeToEdge` is here rather than in the shared code because it is a genuinely
 * platform-specific concern — Android draws behind the status and navigation bars, iOS does not —
 * and the shared UI handles the resulting insets itself. The `Context` handed to
 * [AndroidHost] is the other: on Android a file store and a clipboard both need one, and on no
 * other platform does such a thing exist to hand over.
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        AndroidHost.attach(this)
        setContent { App() }
    }
}
