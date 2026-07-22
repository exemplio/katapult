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
            textoView(texto)

        } else if let boton = elemento as? GoElementoBoton {
            botonView(boton)

        } else if let enlace = elemento as? GoElementoEnlace {
            enlaceView(enlace)

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

        } else if let lienzo = elemento as? GoElementoLienzo {
            LienzoView(lienzo: lienzo)

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

        } else if let caja = elemento as? GoElementoCaja {
            cajaView(caja)

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

    /// Texto con estilo semántico o, si viaja `libre`, el subset cerrado tipo
    /// RN (tamaño/peso/color/espaciado/alineación).
    @ViewBuilder
    private func textoView(_ texto: GoElementoTexto) -> some View {
        let libre = texto.libre
        if texto.marcado {
            let base = (Text(texto.texto).foregroundStyle(colorTexto(texto)))
                + Text(" *").foregroundStyle(.red).fontWeight(.bold)
            base
                .font(libre?.tamano.map { .system(size: CGFloat(truncating: $0)) } ?? fuente(de: texto.estilo))
                .fontWeight(peso(libre?.peso))
                .kerning(libre?.espaciado.map { CGFloat(truncating: $0) } ?? 0)
        } else {
            let base = Text(texto.texto)
                .font(libre?.tamano.map { .system(size: CGFloat(truncating: $0)) } ?? fuente(de: texto.estilo))
                .fontWeight(peso(libre?.peso))
                .kerning(libre?.espaciado.map { CGFloat(truncating: $0) } ?? 0)
                .foregroundStyle(colorTexto(texto))
            if let alineacion = libre?.alineacion {
                base
                    .multilineTextAlignment(alineacionTexto(alineacion))
                    .frame(maxWidth: .infinity, alignment: marcoTexto(alineacion))
            } else {
                base
            }
        }
    }

    /// La View de RN: contenedor con estilos cerrados. Con Tocable alrededor,
    /// cualquier proyecto se fabrica sus propios botones y tarjetas.
    @ViewBuilder
    private func cajaView(_ caja: GoElementoCaja) -> some View {
        Group {
            if caja.direccion == "fila" {
                HStack(alignment: .center, spacing: CGFloat(caja.espaciado)) {
                    hijosView(caja.hijos)
                }
            } else {
                VStack(alignment: alineacionH(caja.alineacion), spacing: CGFloat(caja.espaciado)) {
                    hijosView(caja.hijos)
                }
            }
        }
        .padding(CGFloat(caja.relleno))
        .frame(
            width: caja.ancho.map { CGFloat(truncating: $0) },
            height: caja.alto.map { CGFloat(truncating: $0) }
        )
        // Como la View de RN, la Caja es de bloque: ocupa el ancho disponible.
        .frame(maxWidth: .infinity, alignment: marcoCaja(caja.alineacion))
        .background {
            if let fondo = caja.fondo {
                RoundedRectangle(cornerRadius: CGFloat(caja.esquinas)).fill(colorGo(fondo))
            }
        }
        .overlay {
            if let borde = caja.borde {
                RoundedRectangle(cornerRadius: CGFloat(caja.esquinas))
                    .strokeBorder(colorGo(borde), lineWidth: CGFloat(caja.grosorBorde))
            }
        }
    }

    private func colorTexto(_ texto: GoElementoTexto) -> Color {
        if let hex = texto.libre?.color { return colorGo(hex) }
        return texto.estilo == EstiloTexto.pie ? .secondary : .primary
    }

    private func peso(_ nombre: String?) -> Font.Weight? {
        switch nombre {
        case "medio": return .medium
        case "seminegrita": return .semibold
        case "negrita": return .bold
        case "negra": return .black
        case "normal": return .regular
        default: return nil
        }
    }

    private func alineacionH(_ nombre: String) -> HorizontalAlignment {
        switch nombre {
        case "centro": return .center
        case "fin": return .trailing
        default: return .leading
        }
    }

    private func marcoCaja(_ nombre: String) -> Alignment {
        switch nombre {
        case "centro": return .center
        case "fin": return .trailing
        default: return .leading
        }
    }

    private func alineacionTexto(_ nombre: String) -> TextAlignment {
        switch nombre {
        case "centro": return .center
        case "derecha": return .trailing
        default: return .leading
        }
    }

    private func marcoTexto(_ nombre: String) -> Alignment {
        switch nombre {
        case "centro": return .center
        case "derecha": return .trailing
        default: return .leading
        }
    }

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
        // Si la lógica mandó estilos custom (libre, fondo, esquinas…), se pinta
        // como un botón plano con el aspecto exacto de la lógica — igual que
        // Caja+Tocable+Texto pero con el estado disabled nativo de iOS.
        let esCustom = boton.libre != nil || boton.fondo != nil
            || boton.esquinas != nil || boton.borde != nil
        Group {
            if esCustom {
                Button(role: rol) { enviar(boton.id, nil) } label: {
                    Text(boton.etiqueta)
                        .font(fuenteCustom(boton.libre) ?? (boton.estilo == EstiloBoton.prominente ? .body.weight(.semibold) : .body))
                        .fontWeight(pesoCustom(boton.libre) ?? (boton.estilo == EstiloBoton.prominente ? .semibold : nil))
                        .foregroundStyle(colorTextoCustom(boton.libre) ?? colorTextoBoton(boton))
                        .frame(maxWidth: .infinity, minHeight: boton.estilo == EstiloBoton.prominente ? 34 : 26)
                }
                .buttonStyle(.plain)
                .padding(boton.estilo == EstiloBoton.prominente ? 10 : 6)
                .frame(maxWidth: .infinity)
                .background {
                    if let fondo = boton.fondo {
                        RoundedRectangle(cornerRadius: CGFloat(boton.esquinas ?? 8)).fill(colorGo(fondo))
                    } else {
                        RoundedRectangle(cornerRadius: CGFloat(boton.esquinas ?? 8))
                            .fill(Color(.secondarySystemBackground))
                    }
                }
                .overlay {
                    if let borde = boton.borde {
                        RoundedRectangle(cornerRadius: CGFloat(boton.esquinas ?? 8))
                            .strokeBorder(colorGo(borde), lineWidth: CGFloat(boton.grosorBorde ?? 1))
                    }
                }
            } else if boton.estilo == EstiloBoton.prominente {
                Button(role: rol) { enviar(boton.id, nil) } label: {
                    Text(boton.etiqueta)
                        .fontWeight(.semibold)
                        .frame(maxWidth: .infinity, minHeight: 34)
                }
                .buttonStyle(.borderedProminent)
            } else {
                Button(role: rol) { enviar(boton.id, nil) } label: {
                    Text(boton.etiqueta).frame(maxWidth: .infinity, minHeight: 26)
                }
                .buttonStyle(.bordered)
            }
        }
        .disabled(!boton.habilitado)
    }

    private func colorTextoBoton(_ boton: GoElementoBoton) -> Color {
        if boton.estilo == EstiloBoton.destructivo { return .red }
        if boton.estilo == EstiloBoton.prominente { return .white }
        return .primary
    }

    private func fuenteCustom(_ libre: EstiloLibre?) -> Font? {
        libre?.tamano.map { .system(size: CGFloat(truncating: $0)) }
    }

    private func pesoCustom(_ libre: EstiloLibre?) -> Font.Weight? {
        peso(libre?.peso)
    }

    private func colorTextoCustom(_ libre: EstiloLibre?) -> Color? {
        libre?.color.map(colorGo)
    }

    /// "texto normal *enlace*" tocable; el enlace hereda el tinte del tema.
    /// Si [monocromo] es true, la parte activa va en negrita sin color de acento
    /// — modo producción, más sobrio.
    @ViewBuilder
    private func enlaceView(_ e: GoElementoEnlace) -> some View {
        let colorEnlace: Color = e.monocromo ? .primary : .accentColor
        Button { enviar(e.id, nil) } label: {
            (Text(e.texto.isEmpty ? "" : e.texto + " ").foregroundColor(.secondary)
                + Text(e.enlace).fontWeight(.bold).foregroundColor(colorEnlace))
                .font(.subheadline)
        }
        .buttonStyle(.plain)
        .disabled(!e.habilitado)
        .frame(maxWidth: .infinity, alignment: e.alFinal ? .trailing : .center)
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
        HStack(spacing: 8) {
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
            if let icono = campo.adornoDerecha {
                Button {
                    if let idAdorno = campo.idAdorno {
                        enviar(idAdorno, nil)
                    }
                } label: {
                    Image(systemName: icono)
                        .foregroundStyle(.secondary)
                        .frame(width: 24, height: 24)
                }
                .buttonStyle(.plain)
            }
        }
        .padding(14)
        .background(
            (campo.fondo.map(colorGo) ?? Color(.systemBackground)),
            in: RoundedRectangle(cornerRadius: CGFloat(campo.esquinas ?? 12))
        )
        .overlay(
            RoundedRectangle(cornerRadius: CGFloat(campo.esquinas ?? 12))
                .strokeBorder(
                    campo.borde.map(colorGo) ?? Color(.separator),
                    lineWidth: CGFloat(campo.grosorBorde ?? 1)
                )
        )
    }

    @ViewBuilder
    private func imagenView(_ imagen: GoElementoImagen) -> some View {
        // La red la pone el anfitrión: QuickJS no tiene fetch, pero esta vista sí.
        AsyncImage(url: URL(string: imagen.url)) { fase in
            switch fase {
            case .success(let img):
                // Dentro de un overlay la imagen NO participa en el layout:
                // scaledToFill a pelo declara su tamaño natural (enorme) y
                // ensancha la Fila más que la pantalla — todo sale recortado.
                Color.clear.overlay(img.resizable().scaledToFill())
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

/// El intérprete del Lienzo: órdenes de dibujo (datos que viajan) sobre un
/// Canvas NATIVO de SwiftUI/Core Graphics. La válvula de escape visual del
/// catálogo — gráficos y widgets custom sin release y sin web.
///
/// Coordenadas fraccionales (0..1 del tamaño real); radios/grosores/letras en
/// puntos. Los sealed de Kotlin llegan como OrdenDibujoRect, OrdenDibujoLinea…
struct LienzoView: View {
    let lienzo: GoElementoLienzo

    var body: some View {
        Canvas { ctx, size in
            for orden in (lienzo.ordenes as? [OrdenDibujo] ?? []) {
                dibujar(orden, en: &ctx, tamano: size)
            }
        }
        .frame(maxWidth: .infinity)
        .frame(height: CGFloat(lienzo.alto))
    }

    private func dibujar(_ orden: OrdenDibujo, en ctx: inout GraphicsContext, tamano: CGSize) {
        if let r = orden as? OrdenDibujoRect {
            let rect = CGRect(
                x: r.x * tamano.width,
                y: r.y * tamano.height,
                width: r.ancho * tamano.width,
                height: r.alto * tamano.height
            )
            let ruta = Path(roundedRect: rect, cornerRadius: CGFloat(r.esquinas))
            if r.relleno {
                ctx.fill(ruta, with: .color(colorGo(r.color)))
            } else {
                ctx.stroke(ruta, with: .color(colorGo(r.color)), lineWidth: 1)
            }

        } else if let c = orden as? OrdenDibujoCirculo {
            let centro = CGPoint(x: c.x * tamano.width, y: c.y * tamano.height)
            let rect = CGRect(
                x: centro.x - CGFloat(c.radio), y: centro.y - CGFloat(c.radio),
                width: CGFloat(c.radio) * 2, height: CGFloat(c.radio) * 2
            )
            let ruta = Path(ellipseIn: rect)
            if c.relleno {
                ctx.fill(ruta, with: .color(colorGo(c.color)))
            } else {
                ctx.stroke(ruta, with: .color(colorGo(c.color)), lineWidth: 1)
            }

        } else if let l = orden as? OrdenDibujoLinea {
            let puntos = (l.puntos as? [Punto] ?? []).map {
                CGPoint(x: $0.x * tamano.width, y: $0.y * tamano.height)
            }
            guard puntos.count > 1 else { return }
            var ruta = Path()
            ruta.move(to: puntos[0])
            for p in puntos.dropFirst() { ruta.addLine(to: p) }
            ctx.stroke(ruta, with: .color(colorGo(l.color)), lineWidth: CGFloat(l.grosor))

        } else if let t = orden as? OrdenDibujoRotulo {
            let texto = Text(t.texto)
                .font(.system(size: CGFloat(t.tamano)))
                .foregroundColor(colorGo(t.color))
            ctx.draw(texto, at: CGPoint(x: t.x * tamano.width, y: t.y * tamano.height))
        }
    }

}

/// "#RRGGBB" o "#RRGGBBAA" → Color. Un hex roto se pinta gris, no revienta.
/// A nivel de archivo porque lo usan el Lienzo y el theming (GoTema).
func colorGo(_ hex: String) -> Color {
    var s = hex.trimmingCharacters(in: .whitespaces)
    if s.hasPrefix("#") { s.removeFirst() }
    guard s.count == 6 || s.count == 8, let v = UInt64(s, radix: 16) else {
        return .gray
    }
    let tieneAlfa = s.count == 8
    let r = Double((v >> (tieneAlfa ? 24 : 16)) & 0xFF) / 255
    let g = Double((v >> (tieneAlfa ? 16 : 8)) & 0xFF) / 255
    let b = Double((v >> (tieneAlfa ? 8 : 0)) & 0xFF) / 255
    let a = tieneAlfa ? Double(v & 0xFF) / 255 : 1
    return Color(red: r, green: g, blue: b, opacity: a)
}
