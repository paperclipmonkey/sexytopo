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
            // `App()` (see its `Modifier.windowInsetsPadding(WindowInsets.safeDrawing)`) already
            // reserves space for the status bar, notch and home indicator itself. Ignoring the
            // safe area on every edge here, not just the bottom, hands Compose the whole screen
            // as a single source of truth for that padding — leaving SwiftUI to also reserve the
            // top double-counts it, showing as a blank band above the app's own header where
            // nothing draws.
            .ignoresSafeArea(.all, edges: .all)
    }
}
