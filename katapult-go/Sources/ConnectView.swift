import SwiftUI

/// Pantalla de conexión, equivalente a la de Expo Go cuando metes la URL a mano.
///
/// Recuerda el último servidor usado, porque en desarrollo se reconecta al mismo
/// una y otra vez.
struct ConnectView: View {
    var onConnect: (URL) -> Void

    @AppStorage("katapult.lastHost") private var host: String = ""
    @State private var error: String?
    @FocusState private var focused: Bool

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

            VStack(alignment: .leading, spacing: 8) {
                Text("Servidor de desarrollo")
                    .font(.caption)
                    .foregroundStyle(.secondary)

                TextField("192.168.0.10:8090", text: $host)
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
        guard let url = normalized(host) else {
            error = "No entiendo esa dirección. Ejemplo: 192.168.0.10:8090"
            return
        }
        host = url.absoluteString
        onConnect(url)
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
