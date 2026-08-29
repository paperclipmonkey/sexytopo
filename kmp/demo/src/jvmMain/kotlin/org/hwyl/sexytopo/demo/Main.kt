package org.hwyl.sexytopo.demo

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState

/** Desktop host for the shared UI. The iOS host in `iosMain` calls the same [App]. */
fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        state = rememberWindowState(size = DpSize(1200.dp, 900.dp)),
        title = "SexyTopo KMP demo",
    ) {
        App()
    }
}
