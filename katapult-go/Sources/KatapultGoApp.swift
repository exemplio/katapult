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

/// Estado de la sesión: eligiendo servidor, o dentro de la app por uno de los
/// dos modos de render.
enum Screen {
    case connect
    /// Espejo: la app corre en el PC y aquí llegan píxeles (WKWebView).
    case espejo(URL)
    /// Go: la lógica corre AQUÍ, descargada como bytecode QuickJS (Zipline).
    case go(URL)
}

struct RootView: View {
    @State private var screen: Screen = .connect

    var body: some View {
        switch screen {
        case .connect:
            ConnectView { url, mode in
                switch mode {
                case .espejo: screen = .espejo(url)
                case .go: screen = .go(url)
                }
            }
        case .espejo(let url):
            AppHostView(url: url) {
                screen = .connect
            }
            // El webview ocupa toda la pantalla, incluida la zona bajo la barra.
            .ignoresSafeArea(edges: .bottom)
        case .go(let url):
            GoHostView(manifestURL: url) {
                screen = .connect
            }
        }
    }
}
