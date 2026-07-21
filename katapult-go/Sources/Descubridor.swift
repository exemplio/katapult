import Foundation
import Network

/// Un dev server de Katapult encontrado en la LAN.
struct ServidorEncontrado: Identifiable, Equatable {
    let id: String
    let nombre: String
    let modo: ConnectMode
    let endpoint: NWEndpoint

    static func == (a: Self, b: Self) -> Bool { a.id == b.id }
}

/// Busca servidores `_katapult._tcp` por mDNS/Bonjour, como hace Expo Go.
/// Del otro lado anuncian el espejo (al arrancar) y goServe (modo Go), con
/// JmDNS. Requiere `NSBonjourServices` en Info.plist: sin esa clave iOS 14+
/// silencia el descubrimiento SIN dar error — la trampa clásica.
final class Descubridor: ObservableObject {
    @Published var servidores: [ServidorEncontrado] = []
    private var browser: NWBrowser?

    func iniciar() {
        detener()
        let parametros = NWParameters()
        parametros.includePeerToPeer = true
        let b = NWBrowser(
            for: .bonjourWithTXTRecord(type: "_katapult._tcp", domain: nil),
            using: parametros
        )
        b.browseResultsChangedHandler = { [weak self] resultados, _ in
            let encontrados = resultados.compactMap { r -> ServidorEncontrado? in
                guard case let .service(nombre, _, _, _) = r.endpoint else { return nil }
                // El modo viaja en el TXT record del anuncio (modo=espejo|go).
                var modo = ConnectMode.espejo
                if case let .bonjour(txt) = r.metadata,
                   txt.dictionary["modo"] == ConnectMode.go.rawValue {
                    modo = .go
                }
                return ServidorEncontrado(
                    id: "\(modo.rawValue)|\(nombre)",
                    nombre: nombre,
                    modo: modo,
                    endpoint: r.endpoint
                )
            }.sorted { $0.nombre < $1.nombre }
            DispatchQueue.main.async { self?.servidores = encontrados }
        }
        b.start(queue: .main)
        browser = b
    }

    func detener() {
        browser?.cancel()
        browser = nil
        servidores = []
    }

    /// Bonjour entrega un nombre de servicio, no una IP. Se resuelve abriendo
    /// una conexión efímera al endpoint y mirando a dónde quedó conectada de
    /// verdad — el mismo truco que usan los clientes de Apple.
    func resolver(_ servidor: ServidorEncontrado, listo: @escaping (URL?) -> Void) {
        let conexion = NWConnection(to: servidor.endpoint, using: .tcp)
        var terminado = false
        let acabar: (URL?) -> Void = { url in
            guard !terminado else { return }
            terminado = true
            conexion.cancel()
            DispatchQueue.main.async { listo(url) }
        }
        conexion.stateUpdateHandler = { estado in
            switch estado {
            case .ready:
                guard case let .hostPort(host, puerto)? = conexion.currentPath?.remoteEndpoint else {
                    acabar(nil)
                    return
                }
                var ip: String
                switch host {
                case .ipv4(let v4): ip = "\(v4)"
                case .ipv6(let v6): ip = "[\(v6)]"
                case .name(let n, _): ip = n
                @unknown default: ip = "\(host)"
                }
                // El sufijo de interfaz ("%en0") no pinta nada en una URL.
                if let corte = ip.firstIndex(of: "%") {
                    let cerrado = ip.hasPrefix("[") ? "]" : ""
                    ip = String(ip[..<corte]) + cerrado
                }
                var url = URL(string: "http://\(ip):\(puerto.rawValue)")
                if servidor.modo == .go {
                    url = url?.appendingPathComponent("manifest.zipline.json")
                }
                acabar(url)
            case .failed, .cancelled:
                acabar(nil)
            default:
                break
            }
        }
        conexion.start(queue: .main)
    }
}
