import SwiftUI
import WebKit

/// Aloja la app KMP del usuario dentro de un WKWebView.
///
/// El bundle Wasm lo sirve el dev server; aquí solo se carga y se ejecuta.
/// El "menú de desarrollo" (recargar, desconectar) se abre agitando el
/// dispositivo, igual que en Expo Go.
struct AppHostView: View {
    let url: URL
    var onDisconnect: () -> Void

    @State private var reloadToken = 0
    @State private var showMenu = false
    @State private var loadError: String?

    var body: some View {
        ZStack {
            WebView(url: url, reloadToken: reloadToken) { error in
                loadError = error
            }

            if let loadError {
                VStack(spacing: 16) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.largeTitle)
                    Text("No se pudo cargar")
                        .font(.headline)
                    Text(loadError)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    HStack {
                        Button("Reintentar") { self.loadError = nil; reloadToken += 1 }
                            .buttonStyle(.borderedProminent)
                        Button("Cambiar servidor", action: onDisconnect)
                    }
                }
                .padding()
                .background(.background)
            }
        }
        // Agitar el teléfono abre el menú, como en Expo Go / React Native.
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            showMenu = true
        }
        .sheet(isPresented: $showMenu) {
            MenuDesarrollo(
                modo: "Espejo",
                url: url,
                acciones: [
                    AccionMenu(titulo: "Recargar", icono: "arrow.clockwise") {
                        loadError = nil
                        reloadToken += 1
                    },
                    AccionMenu(titulo: "Ir al inicio", icono: "house", accion: onDisconnect),
                ],
                onCerrar: { showMenu = false },
            )
        }
    }
}

/// Puente a WKWebView. Configurado para desarrollo: inspeccionable desde Safari
/// del Mac si lo hubiera, sin zoom accidental y sin rebote de scroll.
private struct WebView: UIViewRepresentable {
    let url: URL
    let reloadToken: Int
    var onError: (String) -> Void

    func makeUIView(context: Context) -> WKWebView {
        let config = WKWebViewConfiguration()
        config.allowsInlineMediaPlayback = true
        // Necesario para que Wasm+Skia rinda: sin esto algunos recursos se
        // bloquean al servirse desde un origen HTTP local.
        config.defaultWebpagePreferences.allowsContentJavaScript = true

        let web = WKWebView(frame: .zero, configuration: config)
        web.navigationDelegate = context.coordinator
        web.scrollView.bounces = false
        web.scrollView.contentInsetAdjustmentBehavior = .never
        if #available(iOS 16.4, *) {
            web.isInspectable = true
        }
        web.load(URLRequest(url: url))
        return web
    }

    func updateUIView(_ web: WKWebView, context: Context) {
        // Cambiar reloadToken fuerza una recarga limpia.
        if context.coordinator.lastToken != reloadToken {
            context.coordinator.lastToken = reloadToken
            web.load(URLRequest(url: url))
        }
    }

    func makeCoordinator() -> Coordinator { Coordinator(onError: onError) }

    final class Coordinator: NSObject, WKNavigationDelegate {
        var lastToken = 0
        let onError: (String) -> Void

        init(onError: @escaping (String) -> Void) { self.onError = onError }

        func webView(_ w: WKWebView, didFailProvisionalNavigation n: WKNavigation!, withError e: Error) {
            onError(e.localizedDescription)
        }

        func webView(_ w: WKWebView, didFail n: WKNavigation!, withError e: Error) {
            onError(e.localizedDescription)
        }
    }
}

// MARK: - Detección de agitado

extension Notification.Name {
    static let deviceDidShake = Notification.Name("dev.katapult.deviceDidShake")
}

extension UIWindow {
    open override func motionEnded(_ motion: UIEvent.EventSubtype, with event: UIEvent?) {
        if motion == .motionShake {
            NotificationCenter.default.post(name: .deviceDidShake, object: nil)
        }
        super.motionEnded(motion, with: event)
    }
}
