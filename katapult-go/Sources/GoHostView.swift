import SwiftUI
import GoRuntime

/// El modo Go: en vez de recibir píxeles del espejo, ejecuta la lógica de
/// verdad EN el dispositivo. El framework GoRuntime (Kotlin) descarga el
/// bytecode QuickJS del dev server y lo recarga en caliente; esta vista es la
/// "UI fija" del paso 0 — pinta el GoPantalla que produce esa lógica.
struct GoHostView: View {
    let manifestURL: URL
    var onDisconnect: () -> Void

    // El anfitrión Kotlin. Vive lo que viva la vista; detener() cierra QuickJS.
    @State private var anfitrion = GoAnfitrion()

    @State private var titulo = ""
    @State private var lineas: [String] = []
    @State private var version: Int32 = 0
    @State private var detalle: String?
    @State private var showMenu = false

    var body: some View {
        Group {
            if version == 0 {
                // Aún no llegó lógica: o el servidor no está, o estamos arrancando.
                VStack(spacing: 16) {
                    ProgressView()
                    Text("Esperando lógica del dev server…")
                        .font(.headline)
                    if let detalle {
                        Text(detalle)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                            .padding(.horizontal, 32)
                    }
                    Button("Cambiar servidor", action: onDisconnect)
                }
                .padding()
            } else {
                VStack(alignment: .leading, spacing: 16) {
                    Text(titulo)
                        .font(.title2.bold())

                    ForEach(lineas, id: \.self) { linea in
                        Text(linea)
                    }

                    Spacer()

                    // El contador de versión es la prueba visible de la recarga:
                    // sube cada vez que llega código nuevo por la red.
                    Text("lógica v\(version) · \(manifestURL.absoluteString)")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                .padding()
            }
        }
        .onAppear(perform: arrancar)
        .onDisappear { anfitrion.detener() }
        // Agitar abre el menú, igual que en el modo espejo.
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            showMenu = true
        }
        .confirmationDialog("Katapult Go", isPresented: $showMenu, titleVisibility: .visible) {
            Button("Reconectar") { arrancar() }
            Button("Cambiar servidor", action: onDisconnect)
            Button("Cancelar", role: .cancel) {}
        } message: {
            Text(manifestURL.absoluteString)
        }
    }

    private func arrancar() {
        anfitrion.detener()
        version = 0
        detalle = nil
        // El callback llega en el hilo principal (Dispatchers.Main en Kotlin),
        // así que se puede tocar el estado de SwiftUI directamente.
        anfitrion.arrancar(manifestUrl: manifestURL.absoluteString) { informe in
            if let pantalla = informe.pantalla {
                titulo = pantalla.titulo
                lineas = pantalla.lineas as? [String] ?? []
                version = informe.version
                detalle = nil
            } else {
                detalle = informe.detalle
            }
        }
    }
}
