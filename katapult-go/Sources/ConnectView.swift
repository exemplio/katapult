import SwiftUI

/// Pantalla de conexión, equivalente a la de Expo Go cuando metes la URL a mano.
///
/// Recuerda el último servidor usado, porque en desarrollo se reconecta al mismo
/// una y otra vez.
/// Los dos modos de render de Katapult Go.
enum ConnectMode: String {
    /// La app corre en el PC; llegan píxeles por WebSocket (puerto 8080).
    case espejo
    /// La lógica corre aquí, descargada con Zipline (puerto 8081).
    case go
}

struct ConnectView: View {
    var onConnect: (URL, ConnectMode) -> Void

    @AppStorage("katapult.lastHost") private var host: String = ""
    @AppStorage("katapult.lastMode") private var modeRaw: String = ConnectMode.espejo.rawValue
    @State private var error: String?
    @FocusState private var focused: Bool

    private var mode: ConnectMode { ConnectMode(rawValue: modeRaw) ?? .espejo }

    var body: some View {
        VStack(spacing: 24) {
            Spacer()

            VStack(spacing: 8) {
                Text("Katapult Go")
                    .font(.largeTitle.bold())
                Text("Dev client para Kotlin Multiplatform")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            // El modo cambia qué servidor se espera al otro lado: el espejo
            // (píxeles) o el servidor de módulos Zipline (bytecode).
            Picker("Modo", selection: $modeRaw) {
                Text("Espejo").tag(ConnectMode.espejo.rawValue)
                Text("Go (Zipline)").tag(ConnectMode.go.rawValue)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal)

            VStack(alignment: .leading, spacing: 8) {
                Text("Servidor de desarrollo")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                TextField(mode == .espejo ? "192.168.0.10:8080" : "192.168.0.10:8081", text: $host)
                    .textFieldStyle(.roundedBorder)
                    .font(.system(.body, design: .monospaced))
                    .keyboardType(.URL)
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
                    .submitLabel(.go)
                    .focused($focused)
                    .onSubmit(connect)

                if let error {
                    Text(error)
                        .font(.caption)
                        .foregroundStyle(.red)
                }
            }
            .padding(.horizontal)

            Button(action: connect) {
                Text("Conectar")
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 6)
            }
            .buttonStyle(.borderedProminent)
            .disabled(host.trimmingCharacters(in: .whitespaces).isEmpty)
            .padding(.horizontal)

            Spacer()

            Text("Arranca el dev server en tu máquina y escribe aquí su IP y puerto.")
                .font(.footnote)
                .foregroundStyle(.secondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
                .padding(.bottom)
        }
        .onAppear { focused = host.isEmpty }
    }

    private func connect() {
        error = nil
        guard var url = normalized(host) else {
            error = "No entiendo esa dirección. Ejemplo: 192.168.0.10:8080"
            return
        }
        host = url.absoluteString
        if mode == .go {
            // El loader de Zipline quiere la URL del manifest, pero al usuario
            // le basta con escribir ip:puerto — completamos el resto aquí.
            url = manifestURL(from: url)
        }
        onConnect(url, mode)
    }

    /// "http://ip:8081" → "http://ip:8081/manifest.zipline.json" (respetando
    /// una ruta explícita si el usuario ya escribió una).
    private func manifestURL(from url: URL) -> URL {
        if url.path.isEmpty || url.path == "/" {
            return url.appendingPathComponent("manifest.zipline.json")
        }
        return url
    }

    /// Acepta "192.168.0.10:8090", "http://…" o un host suelto y devuelve una URL válida.
    private func normalized(_ raw: String) -> URL? {
        var s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !s.isEmpty else { return nil }
        if !s.contains("://") { s = "http://" + s }
        guard let url = URL(string: s), url.host != nil else { return nil }
        return url
    }
}
