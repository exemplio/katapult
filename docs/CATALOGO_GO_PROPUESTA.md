# Catálogo Go v2 — IMPLEMENTADO (julio 2026)

> Nació como documento de diseño y se implementó el mismo día. El código
> canónico: [GoElemento.kt](../go-runtime/src/commonMain/kotlin/dev/katapult/go/GoElemento.kt)
> (contrato) y [GoCatalogo.swift](../katapult-go/Sources/GoCatalogo.swift)
> (render SwiftUI recursivo). Ejemplo completo con grid de imágenes y
> navegación lista→detalle: LogicaMuseo.kt en katapult-demo.
> Este MD queda como referencia de diseño y criterio de crecimiento.

## Principio de diseño

No se maximiza cobertura transcribiendo Material widget a widget (esa es la
trampa Redwood: ~50 componentes × decenas de parámetros × dos plataformas ×
para siempre). Se maximiza con **pocas piezas que combinan**:

- **Contenedores anidables** (`hijos: List<GoElemento>`) → combinaciones
  infinitas con piezas finitas.
- **`Tocable`** envuelve cualquier subárbol y lo hace interactivo → cualquier
  cosa puede ser una fila de lista, una tarjeta clicable, un chip.
- Estilos como **enums semánticos** (TITULO, PROMINENTE…), nunca como bolsa
  abierta de propiedades — la bolsa de estilos es la pendiente hacia
  reimplementar Modifier.

Lo que este modelo NUNCA dará (y no debe intentar): `Modifier` arbitrario,
layouts custom, animaciones libres, canvas. Para eso ya existe el espejo, que
ejecuta Compose de verdad. Los dos modos se reparten el trabajo.

## El catálogo propuesto (13 piezas + 3 enums)

### Primitivas (9)

| Pieza | Datos | Evento que manda | SwiftUI |
|---|---|---|---|
| `Texto` | texto, estilo: `TITULO/SUBTITULO/CUERPO/PIE` | — | `Text` + font |
| `Boton` | id, etiqueta, estilo: `PROMINENTE/NORMAL/DESTRUCTIVO`, habilitado | `(id, null)` | `Button` + buttonStyle/role |
| `Campo` | id, pista, valor, teclado: `TEXTO/NUMERO/EMAIL/URL`, seguro | `(id, texto)` por cambio | `TextField`/`SecureField` + keyboardType |
| `Interruptor` | id, etiqueta, activo | `(id, "true"/"false")` | `Toggle` |
| `Deslizador` | id, valor, minimo=0, maximo=1 | `(id, valor.toString())` | `Slider` |
| `Imagen` | url, alto? | — | `AsyncImage` (descarga el ANFITRIÓN; QuickJS no tiene red) |
| `Progreso` | valor? (null = indeterminado) | — | `ProgressView` |
| `Lienzo` | alto, ordenes (Rect/Circulo/Linea/Rotulo, coords 0..1, hex) | — | `Canvas` nativo — la válvula de escape visual: gráficos y widgets custom SIN release y SIN web |
| `Separador` | — | — | `Divider` |
| `Espacio` | alto=8 | — | `Spacer().frame(height:)` |

### Contenedores (4) — aquí se multiplica todo

| Pieza | Datos | SwiftUI |
|---|---|---|
| `Columna` | hijos, espaciado=8 | `VStack(alignment: .leading)` |
| `Fila` | hijos, espaciado=8 | `HStack` |
| `Tarjeta` | hijos | `VStack` + fondo/borde suaves |
| `Tocable` | id, hijos | `Button(.plain)` + `contentShape` → `(id, null)` |

Ejemplo de lo que ya cubre: una fila de lista clásica es
`Tocable(Tarjeta(Fila(Imagen, Columna(Texto(SUBTITULO), Texto(PIE)))))`.

### Kotlin (borrador del contrato)

```kotlin
@Serializable
sealed interface GoElemento {
    @Serializable @SerialName("texto")
    data class Texto(val texto: String, val estilo: EstiloTexto = EstiloTexto.CUERPO) : GoElemento

    @Serializable @SerialName("boton")
    data class Boton(
        val id: String, val etiqueta: String,
        val estilo: EstiloBoton = EstiloBoton.NORMAL,
        val habilitado: Boolean = true,
    ) : GoElemento

    @Serializable @SerialName("campo")
    data class Campo(
        val id: String, val pista: String, val valor: String,
        val teclado: Teclado = Teclado.TEXTO,
        val seguro: Boolean = false,
    ) : GoElemento

    @Serializable @SerialName("interruptor")
    data class Interruptor(val id: String, val etiqueta: String, val activo: Boolean) : GoElemento

    @Serializable @SerialName("deslizador")
    data class Deslizador(
        val id: String, val valor: Double,
        val minimo: Double = 0.0, val maximo: Double = 1.0,
    ) : GoElemento

    @Serializable @SerialName("imagen")
    data class Imagen(val url: String, val alto: Int? = null) : GoElemento

    @Serializable @SerialName("progreso")
    data class Progreso(val valor: Double? = null) : GoElemento

    @Serializable @SerialName("separador")
    data object Separador : GoElemento

    @Serializable @SerialName("espacio")
    data class Espacio(val alto: Int = 8) : GoElemento

    @Serializable @SerialName("columna")
    data class Columna(val hijos: List<GoElemento>, val espaciado: Int = 8) : GoElemento

    @Serializable @SerialName("fila")
    data class Fila(val hijos: List<GoElemento>, val espaciado: Int = 8) : GoElemento

    @Serializable @SerialName("tarjeta")
    data class Tarjeta(val hijos: List<GoElemento>) : GoElemento

    @Serializable @SerialName("tocable")
    data class Tocable(val id: String, val hijos: List<GoElemento>) : GoElemento
}

@Serializable enum class EstiloTexto { TITULO, SUBTITULO, CUERPO, PIE }
@Serializable enum class EstiloBoton { PROMINENTE, NORMAL, DESTRUCTIVO }
@Serializable enum class Teclado { TEXTO, NUMERO, EMAIL, URL }
```

## Organización de archivos (cuando se implemente)

```
go-runtime/src/commonMain/kotlin/dev/katapult/go/
├── GoLogica.kt      ← solo el contrato (pantalla/evento) + GoPantalla
└── GoElemento.kt    ← el catálogo entero (este documento, hecho código)

katapult-go/Sources/
├── GoHostView.swift ← pantalla, ciclo de vida, menú (como hoy)
└── GoCatalogo.swift ← ElementoView recursivo: una rama por pieza
```

El render Swift pasa a ser **recursivo** (los contenedores pintan a sus
hijos): un `ElementoView` que se instancia a sí mismo dentro de los ForEach.
La consola JVM (`Host.kt`) igual, con sangría.

## Costes, sin adornos

- Implementarlo: ~1 sesión (contrato + Swift + consola + demo + docs).
- Es **cambio de contrato**: `publishToMavenLocal` + IPA nuevo.
- Mantenimiento perpetuo de 13 piezas × 2 lados del puente. Las piezas con
  estado editable (Campo, Deslizador) son las delicadas: pelean con los
  repintados (el borrador local de GoHostView ya existe por eso).
- Deriva de versiones: cada app instalada vieja tiene que seguir entendiendo
  pantallas nuevas → los campos nuevos SIEMPRE con valor por defecto, y
  NUNCA renombrar un `@SerialName`.

## Criterio de crecimiento posterior

Una pieza entra solo si una pantalla real del proyecto no se puede maquetar
sin ella. Candidatas ya identificadas para una v3, por orden de probabilidad:
`Selector` (picker de opciones), `Insignia` (badge/chip), `Pestanas` (tabs),
`Alerta` (diálogo). Ninguna entra "por si acaso".
