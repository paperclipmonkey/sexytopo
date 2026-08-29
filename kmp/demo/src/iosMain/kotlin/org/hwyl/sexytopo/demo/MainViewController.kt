package org.hwyl.sexytopo.demo

import androidx.compose.ui.window.ComposeUIViewController
import platform.UIKit.UIViewController

/**
 * The iOS entry point: a `UIViewController` hosting the shared Compose UI, which the SwiftUI app
 * in `iosApp/` embeds. This is the whole iOS-specific surface of the demo.
 *
 * Builds on macOS with Xcode; see kmp/README.md.
 */
fun MainViewController(): UIViewController = ComposeUIViewController { App() }
