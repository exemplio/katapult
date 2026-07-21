import SwiftUI

/// Una entrada del menú de desarrollo (icono SF Symbol + acción).
struct AccionMenu: Identifiable {
    let id = UUID()
    let titulo: String
    let icono: String
    var rol: ButtonRole? = nil
    let accion: () -> Void
}

/// El menú de desarrollo que se abre agitando el teléfono, como en Expo Go:
/// hoja inferior con el modo activo, el servidor conectado y las acciones.
/// Compartido por los dos modos; cada host aporta sus acciones.
struct MenuDesarrollo: View {
    /// "Espejo" o "Go (Zipline)" — se muestra como subtítulo.
    let modo: String
    let url: URL
    /// Línea de estado opcional (p. ej. "lógica v3, recargada hace nada").
    var detalle: String? = nil
    let acciones: [AccionMenu]
    var onCerrar: () -> Void

    @State private var enlaceCopiado = false

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            // Cabecera: quién soy y a qué estoy conectado.
            HStack(spacing: 12) {
                Image(systemName: modo == "Espejo" ? "rectangle.on.rectangle" : "bolt.fill")
                    .font(.title2)
                    .frame(width: 44, height: 44)
                    .background(Color.accentColor.opacity(0.15), in: RoundedRectangle(cornerRadius: 10))
                VStack(alignment: .leading, spacing: 2) {
                    Text("Katapult Go")
                        .font(.headline)
                    Text(modo)
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                }
                Spacer()
            }
            .padding()

            VStack(alignment: .leading, spacing: 4) {
                Text(url.absoluteString)
                    .font(.caption.monospaced())
                    .foregroundStyle(.secondary)
                    .lineLimit(2)
                if let detalle {
                    Text(detalle)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .padding(.horizontal)
            .padding(.bottom, 8)

            Divider()

            // Las acciones del host + copiar enlace, que es común a ambos modos.
            ScrollView {
                VStack(spacing: 0) {
                    ForEach(acciones) { accion in
                        fila(accion.titulo, icono: accion.icono, rol: accion.rol) {
                            onCerrar()
                            accion.accion()
                        }
                    }
                    fila(
                        enlaceCopiado ? "Enlace copiado" : "Copiar enlace",
                        icono: enlaceCopiado ? "checkmark" : "doc.on.doc",
                    ) {
                        UIPasteboard.general.string = url.absoluteString
                        // Se queda abierto para que se vea la confirmación.
                        enlaceCopiado = true
                    }
                }
            }
        }
        .presentationDetents([.medium])
        .presentationDragIndicator(.visible)
    }

    @ViewBuilder
    private func fila(_ titulo: String, icono: String, rol: ButtonRole? = nil, accion: @escaping () -> Void) -> some View {
        Button(role: rol, action: accion) {
            HStack(spacing: 12) {
                Image(systemName: icono)
                    .frame(width: 28)
                Text(titulo)
                Spacer()
            }
            .padding(.horizontal)
            .padding(.vertical, 12)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .foregroundStyle(rol == .destructive ? Color.red : Color.primary)
    }
}
