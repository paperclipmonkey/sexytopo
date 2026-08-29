package org.hwyl.sexytopo.demo

import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.ComposeViewport
import kotlinx.browser.document

/** Browser host for the shared UI — the same [App] the iOS and desktop hosts show. */
@OptIn(ExperimentalComposeUiApi::class)
fun main() {
    ComposeViewport(document.body!!) { App() }
}
