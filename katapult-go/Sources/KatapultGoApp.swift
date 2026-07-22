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
        Group {
            switch screen {
            case .connect:
                ConnectView { url, mode in
                    switch mode {
                    case .espejo: screen = .espejo(url)
                    case .go: screen = .go(url)
                    }
                }
            case .espejo(let url):
                EspejoNativoView(url: url) {
                    screen = .connect
                }
            case .go(let url):
                GoHostView(manifestURL: url) {
                    screen = .connect
                }
            }
        }
        .onOpenURL(perform: abrir)
    }

    /// El deep link del QR que imprime el dev server en la terminal:
    /// `katapult://espejo?url=…` o `katapult://go?url=…`. Lo escanea la cámara
    /// del sistema — sin código de cámara en la app, como el exp:// de Expo.
    private func abrir(_ url: URL) {
        guard url.scheme == "katapult",
              let destino = url.host,
              let componentes = URLComponents(url: url, resolvingAgainstBaseURL: false),
              let objetivo = componentes.queryItems?.first(where: { $0.name == "url" })?.value,
              let objetivoURL = URL(string: objetivo)
        else { return }

        switch destino {
        case "espejo": screen = .espejo(objetivoURL)
        case "go": screen = .go(objetivoURL)
        default: break
        }
    }
}
