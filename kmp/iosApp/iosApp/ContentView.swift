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
            .ignoresSafeArea(.all, edges: .bottom)
    }
}
