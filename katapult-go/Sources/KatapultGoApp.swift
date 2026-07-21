import SwiftUI

/// Katapult Go — el dev client de Katapult, análogo a Expo Go.
///
/// Se instala UNA vez en el dispositivo. A partir de ahí carga tu app KMP
/// (compilada a Wasm) desde el dev server que corre en tu máquina, por WiFi.
/// Cambiar código no requiere recompilar ni reinstalar nada nativo.
@main
struct KatapultGoApp: App {
    var body: some Scene {
        WindowGroup {
            RootView()
        }
    }
}

/// Estado de la sesión: o estás eligiendo servidor, o estás dentro de la app.
enum Screen {
    case connect
    case running(URL)
}

struct RootView: View {
    @State private var screen: Screen = .connect

    var body: some View {
        switch screen {
        case .connect:
            ConnectView { url in
                screen = .running(url)
            }
        case .running(let url):
            AppHostView(url: url) {
                screen = .connect
            }
            // El webview ocupa toda la pantalla, incluida la zona bajo la barra.
            .ignoresSafeArea(edges: .bottom)
        }
    }
}
