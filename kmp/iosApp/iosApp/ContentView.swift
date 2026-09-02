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
            // reserves the safe area itself, on every edge - so SwiftUI reserving it too left a
            // blank grey band above the app's own header. Ignoring it here was tried and reverted
            // once, blamed for the on-screen keyboard going missing on a real device. That
            // diagnosis did not hold up: the keyboard failure turned out to be an iOS-side
            // keyboard-hosting hiccup unrelated to safe-area handling, reproducible in Chrome on
            // the same device and gone for the rest of that session once any app's text field had
            // shown the keyboard once - see `rememberOpeningFocus` in `Keyboard.kt` for the retry
            // this app now does on every tap regardless.
            .ignoresSafeArea(.all)
    }
}
