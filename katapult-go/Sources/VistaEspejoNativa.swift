import AVFoundation
import AVKit
import SwiftUI

/// Espejo nativo: reemplaza el WKWebView por decodificación H.264 por hardware
/// y renderizado directo con AVSampleBufferDisplayLayer.
///
/// Ventajas sobre el cliente web:
/// - Decodificación por hardware (VideoToolbox) → menos batería, más fluido
/// - Sin WebKit de por medio → menor latencia
/// - Prepara la superficie para overlay de UITextField nativo (Fase 2)
///
/// Arquitectura:
///   WebSocket (:8080/mirror) → datos H.264 → DecodificadorH264
///   → CMSampleBuffer → AVSampleBufferDisplayLayer → pantalla
///   Toques → JSON → WebSocket → servidor
struct EspejoNativoView: View {
    let url: URL
    var onDisconnect: () -> Void

    @State private var estado = EstadoEspejo()
    @State private var showMenu = false

    var body: some View {
        ZStack {
            RepresentableEspejo(
                url: url,
                estado: $estado
            )
            .ignoresSafeArea()

            // HUD de rendimiento
            VStack {
                HStack {
                    Text(estado.textoHud)
                        .font(.caption2.monospaced())
                        .foregroundStyle(estado.error != nil ? .red : .cyan)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 6))
                        .padding(.top, max(6, (UIApplication.shared.connectedScenes.first as? UIWindowScene)?.windows.first?.safeAreaInsets.top ?? 0))
                        .padding(.leading, 8)
                    Spacer()
                }
                Spacer()
            }

            // Error
            if let error = estado.error {
                VStack(spacing: 16) {
                    Image(systemName: "wifi.exclamationmark")
                        .font(.largeTitle)
                    Text("No se pudo cargar")
                        .font(.headline)
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)
                    HStack {
                        Button("Reintentar") { estado.error = nil; estado.reintentar.toggle() }
                            .buttonStyle(.borderedProminent)
                        Button("Cambiar servidor", action: onDisconnect)
                    }
                }
                .padding()
                .background(.background, in: RoundedRectangle(cornerRadius: 16))
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            showMenu = true
        }
        .sheet(isPresented: $showMenu) {
            MenuDesarrollo(
                modo: "Espejo (nativo)",
                url: url,
                acciones: [
                    AccionMenu(titulo: "Recargar", icono: "arrow.clockwise") {
                        estado.error = nil
                        estado.reintentar.toggle()
                    },
                    AccionMenu(titulo: "Ir al inicio", icono: "house", accion: onDisconnect),
                ],
                onCerrar: { showMenu = false },
            )
        }
    }
}

// MARK: - Estado del espejo

struct EstadoEspejo {
    var conectado = false
    var fps: Double = 0
    var mbps: Double = 0
    var modo: String = "conectando…"
    var error: String?
    var reintentar = false

    var textoHud: String {
        if let error { return error }
        if !conectado { return "conectando…" }
        return String(format: "%.0f fps · %.1f Mbps · %@", fps, mbps, modo)
    }
}

// MARK: - UIViewRepresentable

private struct RepresentableEspejo: UIViewRepresentable {
    let url: URL
    @Binding var estado: EstadoEspejo

    func makeUIView(context: Context) -> UIView {
        let container = UIView()
        container.backgroundColor = .black

        // AVSampleBufferDisplayLayer: muestra frames decodificados.
        let displayLayer = AVSampleBufferDisplayLayer()
        displayLayer.videoGravity = .resizeAspect
        container.layer.addSublayer(displayLayer)
        context.coordinator.displayLayer = displayLayer

        conectar(context: context)
        return container
    }

    func updateUIView(_ container: UIView, context: Context) {
        context.coordinator.displayLayer?.frame = container.bounds
        if estado.reintentar {
            estado.reintentar = false
            conectar(context: context)
        }
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(estado: $estado)
    }

    private func conectar(context: Context) {
        context.coordinator.conectar(url: url)
    }

    // MARK: - Coordinator (maneja WebSocket + decodificador)

    final class Coordinator: NSObject, URLSessionWebSocketDelegate {
        @Binding var estado: EstadoEspejo
        var displayLayer: AVSampleBufferDisplayLayer?
        private var websocket: URLSessionWebSocketTask?
        private var decodificador: DecodificadorH264?
        private var session: URLSession?
        private var mostrados = 0
        private var bytesRecibidos = 0
        private var lastReport = Date()
        private var cfgAncho = 0
        private var cfgAlto = 0

        init(estado: Binding<EstadoEspejo>) {
            self._estado = estado
            super.init()
        }

        func conectar(url: URL) {
            Task { await decodificador?.detener() }
            decodificador = nil
            websocket?.cancel()
            mostrados = 0
            bytesRecibidos = 0
            lastReport = Date()

            displayLayer?.flushAndRemoveImage()

            // Construir URL WebSocket: http:// → ws://
            let wsUrl: URL
            if var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) {
                comps.scheme = "ws"
                comps.path = "/mirror"
                wsUrl = comps.url ?? url
            } else {
                estado.error = "URL inválida"
                return
            }

            let decoder = DecodificadorH264()
            self.decodificador = decoder

            session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
            websocket = session?.webSocketTask(with: wsUrl)
            websocket?.resume()

            Task {
                await leerMensajes(decoder: decoder)
            }

            estado.conectado = false
            estado.modo = "conectando…"
            estado.error = nil
        }

        private func leerMensajes(decoder: DecodificadorH264) async {
            guard let ws = websocket else { return }

            while true {
                do {
                    let mensaje = try await ws.receive()
                    switch mensaje {
                    case .data(let data):
                        bytesRecibidos += data.count
                        // El primer mensaje es texto JSON con la configuración
                        if let texto = String(data: data, encoding: .utf8),
                           texto.hasPrefix("{") {
                            if let cfg = try? JSONDecoder().decode(ConfigEspejo.self, from: data) {
                                await MainActor.run {
                                    estado.modo = cfg.mode
                                    estado.conectado = true
                                    cfgAncho = cfg.width
                                    cfgAlto = cfg.height
                                    displayLayer?.frame = CGRect(x: 0, y: 0, width: CGFloat(cfg.width), height: CGFloat(cfg.height))
                                }
                                await decoder.iniciar(
                                    ancho: cfg.width, alto: cfg.height, fps: cfg.fps ?? 60
                                ) { [weak self] buffer in
                                    self?.mostrarFrame(buffer)
                                }
                            }
                        } else {
                            // Frame H.264: [tipo] + [NAL units]
                            await decoder.decodificar(datos: data)
                        }
                    case .string(let texto):
                        if let d = texto.data(using: .utf8),
                           let cfg = try? JSONDecoder().decode(ConfigEspejo.self, from: d) {
                            await MainActor.run {
                                estado.modo = cfg.mode
                                estado.conectado = true
                                cfgAncho = cfg.width
                                cfgAlto = cfg.height
                            }
                            await decoder.iniciar(
                                ancho: cfg.width, alto: cfg.height, fps: cfg.fps ?? 60
                            ) { [weak self] buffer in
                                self?.mostrarFrame(buffer)
                            }
                        }
                    @unknown default:
                        break
                    }

                    // Reportar métricas cada segundo
                    await reportarMetricas()
                } catch {
                    await MainActor.run {
                        estado.error = "WebSocket: \(error.localizedDescription)"
                    }
                    return
                }
            }
        }

        /// Métrica de rendimiento: fps y Mbps, igual que el HUD del cliente web.
        private func reportarMetricas() {
            let ahora = Date()
            let delta = ahora.timeIntervalSince(lastReport)
            if delta >= 1.0 {
                let fps = Double(mostrados) / delta
                let mbps = Double(bytesRecibidos) * 8 / delta / 1_000_000
                Task { @MainActor in
                    estado.fps = fps
                    estado.mbps = mbps
                }
                mostrados = 0
                bytesRecibidos = 0
                lastReport = ahora
            }
        }

        /// Frame decodificado → pantalla.
        func mostrarFrame(_ buffer: CMSampleBuffer) {
            guard let layer = displayLayer else { return }
            if layer.status == .failed {
                layer.flush()
            }
            layer.enqueue(buffer)
            mostrados += 1
        }

        // MARK: - URLSessionWebSocketDelegate

        func urlSession(_ session: URLSession, webSocketTask: URLSessionWebSocketTask, didCloseWith closeCode: URLSessionWebSocketTask.CloseCode, reason: Data?) {
            Task { @MainActor in
                estado.error = "Conexión cerrada"
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
            if let error {
                Task { @MainActor in
                    estado.error = error.localizedDescription
                }
            }
        }
    }
}

/// El mensaje "hello" del servidor: {"mode":"h264","fps":60,"width":780,"height":1688}
private struct ConfigEspejo: Decodable {
    let mode: String
    let fps: Int?
    let width: Int
    let height: Int
}