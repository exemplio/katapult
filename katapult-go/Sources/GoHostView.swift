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
    @State private var elementos: [GoElemento] = []
    @State private var version: Int32 = 0
    @State private var detalle: String?
    @State private var showMenu = false
    // Lo que el usuario va tecleando en cada campo, por id. Es estado LOCAL:
    // así el TextField no pelea con los repintados que llegan de la lógica.
    @State private var borradores: [String: String] = [:]

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
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        Text(titulo)
                            .font(.title2.bold())

                        // El catálogo v2: la lógica manda QUÉ mostrar como
                        // árbol de GoElemento; ElementoView (GoCatalogo.swift)
                        // lo pinta recursivamente con SwiftUI nativo.
                        ForEach(Array(elementos.enumerated()), id: \.offset) { _, elemento in
                            ElementoView(
                                elemento: elemento,
                                enviar: { id, valor in anfitrion.evento(id: id, valor: valor) },
                                borradores: $borradores
                            )
                        }
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                }
                // El contador de versión es la prueba visible de la recarga:
                // sube cada vez que llega código nuevo por la red. Como inset
                // del área segura no se solapa nunca con el contenido, y con
                // fondo .bar se lee sobre cualquier cosa.
                .safeAreaInset(edge: .bottom) {
                    Text("lógica v\(version) · \(manifestURL.absoluteString)")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .lineLimit(1)
                        .truncationMode(.middle)
                        .frame(maxWidth: .infinity)
                        .padding(.horizontal)
                        .padding(.vertical, 6)
                        .background(.bar)
                }
            }
        }
        .onAppear(perform: arrancar)
        .onDisappear { anfitrion.detener() }
        // Agitar abre el menú, igual que en el modo espejo.
        .onReceive(NotificationCenter.default.publisher(for: .deviceDidShake)) { _ in
            showMenu = true
        }
        .sheet(isPresented: $showMenu) {
            MenuDesarrollo(
                modo: "Go (Zipline)",
                url: manifestURL,
                detalle: version > 0 ? "lógica v\(version) cargada" : "esperando la primera lógica…",
                acciones: [
                    // Reinicia QuickJS y el estado de la lógica, no solo la vista.
                    AccionMenu(titulo: "Recargar lógica", icono: "arrow.clockwise") { arrancar() },
                    AccionMenu(titulo: "Ir al inicio", icono: "house", accion: onDisconnect),
                ],
                onCerrar: { showMenu = false },
            )
        }
    }

    private func arrancar() {
        anfitrion.detener()
        version = 0
        detalle = nil
        borradores = [:]
        // El callback llega en el hilo principal (Dispatchers.Main en Kotlin),
        // así que se puede tocar el estado de SwiftUI directamente.
        anfitrion.arrancar(manifestUrl: manifestURL.absoluteString) { informe in
            if let pantalla = informe.pantalla {
                titulo = pantalla.titulo
                elementos = pantalla.elementos as? [GoElemento] ?? []
                version = informe.version
                detalle = nil
            } else {
                detalle = informe.detalle
            }
        }
    }
}
