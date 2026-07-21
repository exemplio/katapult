import SwiftUI
import GoRuntime

/// El pintor del catálogo Go: una rama de SwiftUI por pieza, recursivo en los
/// contenedores. La lógica descargada manda el árbol de GoElemento como datos;
/// aquí se convierte en vistas nativas de verdad.
///
/// Los sealed de Kotlin llegan como subclases (GoElementoTexto, GoElementoFila…)
/// y los enums como singletons comparables (EstiloTexto.titulo).
struct ElementoView: View {
    let elemento: GoElemento
    /// El canal de vuelta hacia la lógica: (id, valor).
    let enviar: (String, String?) -> Void
    /// Texto en edición por campo. Estado LOCAL para que el TextField no
    /// pelee con los repintados que llegan de la lógica.
    @Binding var borradores: [String: String]

    var body: some View {
        // ——— Primitivas ———
        if let texto = elemento as? GoElementoTexto {
            Text(texto.texto)
                .font(fuente(de: texto.estilo))
                .foregroundStyle(texto.estilo == EstiloTexto.pie ? .secondary : .primary)

        } else if let boton = elemento as? GoElementoBoton {
            botonView(boton)

        } else if let campo = elemento as? GoElementoCampo {
            campoView(campo)

        } else if let interruptor = elemento as? GoElementoInterruptor {
            Toggle(interruptor.etiqueta, isOn: Binding(
                get: { interruptor.activo },
                set: { enviar(interruptor.id, $0 ? "true" : "false") }
            ))

        } else if let deslizador = elemento as? GoElementoDeslizador {
            Slider(
                value: Binding(
                    get: { deslizador.valor },
                    set: { enviar(deslizador.id, String($0)) }
                ),
                in: deslizador.minimo...deslizador.maximo
            )

        } else if let imagen = elemento as? GoElementoImagen {
            imagenView(imagen)

        } else if let progreso = elemento as? GoElementoProgreso {
            if let valor = progreso.valor {
                ProgressView(value: valor.doubleValue)
            } else {
                ProgressView()
            }

        } else if elemento is GoElementoSeparador {
            Divider()

        } else if let espacio = elemento as? GoElementoEspacio {
            Color.clear.frame(height: CGFloat(espacio.alto))

        // ——— Contenedores (recursión) ———
        } else if let columna = elemento as? GoElementoColumna {
            VStack(alignment: .leading, spacing: CGFloat(columna.espaciado)) {
                hijosView(columna.hijos)
            }

        } else if let fila = elemento as? GoElementoFila {
            HStack(alignment: .top, spacing: CGFloat(fila.espaciado)) {
                hijosView(fila.hijos, anchoIgual: true)
            }

        } else if let tarjeta = elemento as? GoElementoTarjeta {
            VStack(alignment: .leading, spacing: 8) {
                hijosView(tarjeta.hijos)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(10)
            .background(Color(.secondarySystemBackground), in: RoundedRectangle(cornerRadius: 12))

        } else if let tocable = elemento as? GoElementoTocable {
            Button {
                enviar(tocable.id, nil)
            } label: {
                VStack(alignment: .leading, spacing: 8) {
                    hijosView(tocable.hijos)
                }
                .contentShape(Rectangle())
            }
            .buttonStyle(.plain)

        } else {
            // Pieza desconocida: la app instalada es más vieja que la lógica.
            // Se avisa en vez de romper — la deriva de versiones es esperable.
            Text("⚠︎ elemento no soportado por esta versión de la app")
                .font(.caption)
                .foregroundStyle(.orange)
        }
    }

    // MARK: - Piezas con detalle

    @ViewBuilder
    private func hijosView(_ lista: [GoElemento], anchoIgual: Bool = false) -> some View {
        ForEach(Array(lista.enumerated()), id: \.offset) { _, hijo in
            if anchoIgual {
                ElementoView(elemento: hijo, enviar: enviar, borradores: $borradores)
                    .frame(maxWidth: .infinity, alignment: .topLeading)
            } else {
                ElementoView(elemento: hijo, enviar: enviar, borradores: $borradores)
            }
        }
    }

    @ViewBuilder
    private func botonView(_ boton: GoElementoBoton) -> some View {
        let rol: ButtonRole? = boton.estilo == EstiloBoton.destructivo ? .destructive : nil
        Group {
            if boton.estilo == EstiloBoton.prominente {
                Button(role: rol) { enviar(boton.id, nil) } label: { Text(boton.etiqueta) }
                    .buttonStyle(.borderedProminent)
            } else {
                Button(role: rol) { enviar(boton.id, nil) } label: { Text(boton.etiqueta) }
                    .buttonStyle(.bordered)
            }
        }
        .disabled(!boton.habilitado)
    }

    @ViewBuilder
    private func campoView(_ campo: GoElementoCampo) -> some View {
        let enlace = Binding<String>(
            get: { borradores[campo.id] ?? campo.valor },
            set: { nuevo in
                borradores[campo.id] = nuevo
                enviar(campo.id, nuevo)
            }
        )
        Group {
            if campo.seguro {
                SecureField(campo.pista, text: enlace)
            } else {
                TextField(campo.pista, text: enlace)
                    .keyboardType(teclado(de: campo.teclado))
                    .textInputAutocapitalization(.never)
                    .autocorrectionDisabled()
            }
        }
        .textFieldStyle(.roundedBorder)
    }

    @ViewBuilder
    private func imagenView(_ imagen: GoElementoImagen) -> some View {
        // La red la pone el anfitrión: QuickJS no tiene fetch, pero esta vista sí.
        AsyncImage(url: URL(string: imagen.url)) { fase in
            switch fase {
            case .success(let img):
                img.resizable().scaledToFill()
            case .failure:
                Image(systemName: "photo").foregroundStyle(.secondary)
            default:
                ProgressView()
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: imagen.alto.map { CGFloat(truncating: $0) } ?? 140)
        .clipped()
        .clipShape(RoundedRectangle(cornerRadius: 8))
    }

    private func fuente(de estilo: EstiloTexto) -> Font {
        switch estilo {
        case EstiloTexto.titulo: return .title2.bold()
        case EstiloTexto.subtitulo: return .headline
        case EstiloTexto.pie: return .caption
        default: return .body
        }
    }

    private func teclado(de tipo: Teclado) -> UIKeyboardType {
        switch tipo {
        case Teclado.numero: return .decimalPad
        case Teclado.email: return .emailAddress
        case Teclado.url: return .URL
        default: return .default
        }
    }
}
