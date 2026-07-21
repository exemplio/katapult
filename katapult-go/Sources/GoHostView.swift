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
                VStack(alignment: .leading, spacing: 0) {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 16) {
                            Text(titulo)
                                .font(.title2.bold())

                            // El mini-catálogo del paso 0: la lógica manda QUÉ
                            // mostrar; estos son los tres widgets que el binario
                            // sabe pintar.
                            ForEach(Array(elementos.enumerated()), id: \.offset) { _, elemento in
                                vista(de: elemento)
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                    }

                    // El contador de versión es la prueba visible de la recarga:
                    // sube cada vez que llega código nuevo por la red.
                    Text("lógica v\(version) · \(manifestURL.absoluteString)")
                        .font(.caption2.monospaced())
                        .foregroundStyle(.secondary)
                        .padding([.horizontal, .bottom])
                }
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

    /// Pinta un elemento del catálogo. Los sealed de Kotlin llegan a Swift
    /// como subclases (GoElementoTexto, GoElementoBoton, GoElementoCampo).
    @ViewBuilder
    private func vista(de elemento: GoElemento) -> some View {
        if let texto = elemento as? GoElementoTexto {
            Text(texto.texto)
                .font(texto.destacado ? .headline : .body)
        } else if let boton = elemento as? GoElementoBoton {
            Button(boton.etiqueta) {
                anfitrion.evento(id: boton.id, valor: nil)
            }
            .buttonStyle(.borderedProminent)
        } else if let campo = elemento as? GoElementoCampo {
            TextField(campo.pista, text: enlace(para: campo))
                .textFieldStyle(.roundedBorder)
                .textInputAutocapitalization(.never)
        }
    }

    /// Binding del campo: lee/escribe el borrador local y reenvía cada cambio
    /// a la lógica, que decide qué hacer con él.
    private func enlace(para campo: GoElementoCampo) -> Binding<String> {
        Binding(
            get: { borradores[campo.id] ?? campo.valor },
            set: { nuevo in
                borradores[campo.id] = nuevo
                anfitrion.evento(id: campo.id, valor: nuevo)
            }
        )
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
