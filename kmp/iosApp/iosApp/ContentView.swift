import SwiftUI
import SexyTopoDemo

/// Hosts the shared Compose Multiplatform UI.
///
/// `MainViewControllerKt.MainViewController()` comes from `demo/src/iosMain` — the entire
/// iOS-specific surface of this demo is that one function plus this file.
struct ComposeView: UIViewControllerRepresentable {
    func makeUIViewController(context: Context) -> UIViewController {
        MainViewControllerKt.MainViewController()
    }

    func updateUIViewController(_ uiViewController: UIViewController, context: Context) {}
}

struct ContentView: View {
    var body: some View {
        ComposeView()
            // Ignoring the top safe area here too (not just the bottom) once fixed a blank grey
            // band above the app's own header - `App()` (see its
            // `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`) already reserves that
            // space itself, so SwiftUI reserving it as well was double-counting it. But on a real
            // device that change broke the on-screen keyboard again: UIKit logged a runaway
            // "Conversion error" loop while positioning the keyboard and never showed it, which
            // stopped as soon as this went back to bottom-only. The core keyboard fix matters far
            // more than the cosmetic band, so this reverts to bottom-only until a fix for the band
            // is found that does not touch the top safe area SwiftUI itself owns.
            .ignoresSafeArea(.all, edges: .bottom)
    }
}
