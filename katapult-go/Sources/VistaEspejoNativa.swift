import AVFoundation
import AVKit
import SwiftUI

/// Espejo nativo: reemplaza el WKWebView por decodificación H.264 por hardware
/// y renderizado directo con AVSampleBufferDisplayLayer.
///
/// Ventajas sobre el cliente web:
/// - Decodificación por hardware (VideoToolbox) → menos batería, más fluido
/// - Sin WebKit de por medio → menor latencia
/// - Teclado NATIVO: el servidor avisa del foco de texto y aquí se superpone
///   un UITextField transparente (SuperposicionTexto, Fase 2)
///
/// Arquitectura:
///   WebSocket (:8080/mirror) → datos H.264 → DecodificadorH264
///   → CMSampleBuffer → AVSampleBufferDisplayLayer → pantalla
///   Toques/teclado → JSON → WebSocket → servidor (que los inyecta en Compose)
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

            // HUD de rendimiento y estado (los errores también van aquí, no
            // en un modal: es una herramienta de desarrollo, no una app).
            VStack {
                HStack {
                    Text(estado.textoHud)
                        .font(.caption2.monospaced())
                        .foregroundStyle(estado.reconectando ? .orange : .cyan)
                        .padding(.horizontal, 8)
                        .padding(.vertical, 4)
                        .background(.ultraThinMaterial, in: RoundedRectangle(cornerRadius: 6))
                        // Sin sumar safeAreaInsets a mano: este VStack NO lleva
                        // ignoresSafeArea (solo el video), así que SwiftUI ya lo
                        // coloca bajo la barra de estado. Sumarlo lo bajaba el
                        // doble y quedaba atravesado sobre el contenido.
                        .padding(.top, 6)
                        .padding(.leading, 8)
                    Spacer()
                }
                Spacer()
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
    var reconectando = false
    var intentos = 0
    var fps: Double = 0
    var mbps: Double = 0
    var modo: String = "conectando…"
    var reintentar = false

    var textoHud: String {
        if reconectando { return "reconectando… (\(intentos))" }
        if !conectado { return "conectando…" }
        return String(format: "%.0f fps · %.1f Mbps · %@", fps, mbps, modo)
    }
}

// MARK: - Vista de video + toques

/// El contenedor del video que además captura los TOQUES nativos y los manda
/// al servidor en píxeles del stream. También proyecta rects del stream a la
/// vista (para posicionar la superposición de texto).
final class VistaVideoTactil: UIView {
    var streamSize: CGSize = .zero
    var alMensaje: ((String) -> Void)?
    private let haptico = UIImpactFeedbackGenerator(style: .light)

    /// Rect que ocupa el video dentro de la vista (videoGravity .resizeAspect).
    private func rectVideo() -> CGRect {
        guard streamSize.width > 0, streamSize.height > 0,
              bounds.width > 0, bounds.height > 0 else { return .zero }
        let escala = min(bounds.width / streamSize.width, bounds.height / streamSize.height)
        let w = streamSize.width * escala
        let h = streamSize.height * escala
        return CGRect(x: (bounds.width - w) / 2, y: (bounds.height - h) / 2, width: w, height: h)
    }

    /// Punto de la vista → píxeles del stream (nil si cae fuera del video).
    private func aPixelesStream(_ p: CGPoint) -> CGPoint? {
        let r = rectVideo()
        guard r.width > 0 else { return nil }
        let x = (p.x - r.minX) / r.width * streamSize.width
        let y = (p.y - r.minY) / r.height * streamSize.height
        guard x >= 0, y >= 0, x <= streamSize.width, y <= streamSize.height else { return nil }
        return CGPoint(x: x, y: y)
    }

    /// Rect en píxeles del stream → rect en la vista (para la superposición).
    func aRectVista(x: Int, y: Int, w: Int, h: Int) -> CGRect {
        let r = rectVideo()
        guard streamSize.width > 0, r.width > 0 else { return .zero }
        let ex = r.width / streamSize.width
        let ey = r.height / streamSize.height
        return CGRect(
            x: r.minX + CGFloat(x) * ex,
            y: r.minY + CGFloat(y) * ey,
            width: CGFloat(w) * ex,
            height: CGFloat(h) * ey
        )
    }

    private func enviar(_ tipo: String, _ toque: UITouch) {
        guard let p = aPixelesStream(toque.location(in: self)) else { return }
        alMensaje?("{\"type\":\"\(tipo)\",\"x\":\(Int(p.x)),\"y\":\(Int(p.y))}")
    }

    override func touchesBegan(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first else { return }
        haptico.impactOccurred(intensity: 0.6)
        enviar("down", t)
    }

    override func touchesMoved(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first else { return }
        enviar("move", t)
    }

    override func touchesEnded(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first else { return }
        enviar("up", t)
    }

    override func touchesCancelled(_ touches: Set<UITouch>, with event: UIEvent?) {
        guard let t = touches.first else { return }
        enviar("up", t)
    }
}

// MARK: - UIViewRepresentable

private struct RepresentableEspejo: UIViewRepresentable {
    let url: URL
    @Binding var estado: EstadoEspejo

    func makeUIView(context: Context) -> VistaVideoTactil {
        let container = VistaVideoTactil()
        container.backgroundColor = .black
        container.isMultipleTouchEnabled = false

        let displayLayer = AVSampleBufferDisplayLayer()
        displayLayer.videoGravity = .resizeAspect
        container.layer.addSublayer(displayLayer)

        // El UITextField del teclado nativo, por encima del video.
        let superposicion = SuperposicionTexto(frame: .zero)
        container.addSubview(superposicion)

        context.coordinator.displayLayer = displayLayer
        context.coordinator.vista = container
        context.coordinator.superposicion = superposicion
        container.alMensaje = { [weak coordinator = context.coordinator] json in
            coordinator?.enviarTexto(json)
        }
        superposicion.alMensaje = { [weak coordinator = context.coordinator] json in
            coordinator?.enviarTexto(json)
        }

        context.coordinator.conectar(url: url)
        return container
    }

    func updateUIView(_ container: VistaVideoTactil, context: Context) {
        context.coordinator.displayLayer?.frame = container.bounds
        if estado.reintentar {
            DispatchQueue.main.async { estado.reintentar = false }
            context.coordinator.conectar(url: url)
        }
    }

    static func dismantleUIView(_ uiView: VistaVideoTactil, coordinator: Coordinator) {
        coordinator.cerrar()
    }

    func makeCoordinator() -> Coordinator {
        Coordinator(estado: $estado)
    }

    // MARK: - Coordinator (WebSocket + decodificador + foco de texto)

    final class Coordinator: NSObject, URLSessionWebSocketDelegate {
        @Binding var estado: EstadoEspejo
        var displayLayer: AVSampleBufferDisplayLayer?
        weak var vista: VistaVideoTactil?
        weak var superposicion: SuperposicionTexto?
        private var websocket: URLSessionWebSocketTask?
        private var decodificador: DecodificadorH264?
        private var session: URLSession?
        private var urlActual: URL?
        private var cerrado = false
        private var reconexionProgramada = false
        private var mostrados = 0
        private var bytesRecibidos = 0
        private var lastReport = Date()

        init(estado: Binding<EstadoEspejo>) {
            self._estado = estado
            super.init()
        }

        func conectar(url: URL) {
            urlActual = url
            reconexionProgramada = false
            decodificador?.detener()
            decodificador = nil
            websocket?.cancel()
            mostrados = 0
            bytesRecibidos = 0
            lastReport = Date()

            displayLayer?.flushAndRemoveImage()
            superposicion?.ocultar()

            guard var comps = URLComponents(url: url, resolvingAgainstBaseURL: false) else {
                estado.modo = "URL inválida"
                return
            }
            comps.scheme = "ws"
            comps.path = "/mirror"
            guard let wsUrl = comps.url else {
                estado.modo = "URL inválida"
                return
            }

            let decoder = DecodificadorH264()
            self.decodificador = decoder

            session = URLSession(configuration: .default, delegate: self, delegateQueue: .main)
            websocket = session?.webSocketTask(with: wsUrl)
            websocket?.resume()

            Task { await leerMensajes() }

            estado.conectado = false
        }

        /// Cualquier JSON hacia el servidor (toques, texto del teclado, IME).
        func enviarTexto(_ json: String) {
            websocket?.send(.string(json)) { _ in }
        }

        func cerrar() {
            cerrado = true
            decodificador?.detener()
            websocket?.cancel()
        }

        // Fase 3: si la conexión se cae, reintentar sola cada 1,5 s. El estado
        // se ve en el HUD; nada de modales en una herramienta de desarrollo.
        private func programarReconexion() {
            guard !cerrado, !reconexionProgramada, let url = urlActual else { return }
            reconexionProgramada = true
            estado.reconectando = true
            estado.intentos += 1
            DispatchQueue.main.asyncAfter(deadline: .now() + 1.5) { [weak self] in
                guard let self, !self.cerrado else { return }
                self.conectar(url: url)
            }
        }

        private func leerMensajes() async {
            guard let ws = websocket, let decoder = decodificador else { return }

            while true {
                do {
                    let mensaje = try await ws.receive()
                    switch mensaje {
                    case .data(let data):
                        bytesRecibidos += data.count
                        if let texto = String(data: data, encoding: .utf8), texto.hasPrefix("{") {
                            // Al MainActor SIEMPRE: procesarTexto toca UIKit
                            // (becomeFirstResponder, frames) y este Task corre
                            // en el pool cooperativo. Llamarlo desde aquí
                            // crashea al enfocar un campo ("modifications to
                            // the layout engine from a background thread").
                            await MainActor.run { self.procesarTexto(texto, decoder: decoder) }
                        } else {
                            // El video sí se decodifica fuera del main a
                            // propósito; enqueue() del layer es thread-safe.
                            decoder.decodificar(datos: data)
                        }
                    case .string(let texto):
                        await MainActor.run { self.procesarTexto(texto, decoder: decoder) }
                    @unknown default:
                        break
                    }
                    reportarMetricas()
                } catch {
                    await MainActor.run {
                        if !self.cerrado { self.programarReconexion() }
                    }
                    return
                }
            }
        }

        /// Mensajes de texto del servidor: la config inicial, o foco/blur.
        private func procesarTexto(_ texto: String, decoder: DecodificadorH264) {
            guard let data = texto.data(using: .utf8),
                  let dict = try? JSONSerialization.jsonObject(with: data) as? [String: Any]
            else { return }

            // La config trae "mode"; los demás mensajes traen "type".
            if let mode = dict["mode"] as? String {
                estado.modo = mode
                estado.conectado = true
                estado.reconectando = false
                estado.intentos = 0
                let w = dict["width"] as? Int ?? 0
                let h = dict["height"] as? Int ?? 0
                vista?.streamSize = CGSize(width: w, height: h)
                decoder.iniciar(ancho: w, alto: h, fps: dict["fps"] as? Int ?? 60) { [weak self] buffer in
                    self?.mostrarFrame(buffer)
                }
                // Presentarse como cliente nativo activa los mensajes de foco
                // (el cliente web no se presenta y el servidor no se los manda).
                enviarTexto(#"{"type":"cliente"}"#)
                return
            }

            switch dict["type"] as? String {
            case "foco":
                guard let vista, let superposicion else { return }
                let marco = vista.aRectVista(
                    x: dict["x"] as? Int ?? 0,
                    y: dict["y"] as? Int ?? 0,
                    w: dict["w"] as? Int ?? 0,
                    h: dict["h"] as? Int ?? 0
                )
                superposicion.mostrar(
                    valor: dict["valor"] as? String ?? "",
                    teclado: dict["teclado"] as? String ?? "texto",
                    seguro: dict["seguro"] as? Bool ?? false,
                    accion: dict["accion"] as? String ?? "default",
                    marco: marco
                )
            case "blur":
                superposicion?.ocultar()
            default:
                break
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
                if !cerrado { programarReconexion() }
            }
        }

        func urlSession(_ session: URLSession, task: URLSessionTask, didCompleteWithError error: (any Error)?) {
            if error != nil {
                Task { @MainActor in
                    if !cerrado { programarReconexion() }
                }
            }
        }
    }
}

/// El mensaje "hello" del servidor: {"mode":"h264","fps":60,"width":780,"height":1688}
/// (se parsea como diccionario en procesarTexto; los mensajes de foco/blur
/// llegan por el mismo canal con "type" en vez de "mode").
