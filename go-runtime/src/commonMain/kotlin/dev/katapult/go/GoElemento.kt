package dev.katapult.go

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * EL CATÁLOGO v2: todo lo que la lógica descargada puede pedir que se pinte.
 * Diseño completo en docs/CATALOGO_GO_PROPUESTA.md.
 *
 * Cobertura por combinación, no por cantidad: los contenedores anidan (hijos
 * recursivos) y [Tocable] hace interactivo cualquier subárbol. El precio de
 * cada tipo nuevo: rama en GoCatalogo.swift + consola + release del IPA.
 * Las pantallas que combinan piezas existentes son gratis, para siempre.
 *
 * Disciplina de contrato (hay apps viejas instaladas): campos nuevos SIEMPRE
 * con valor por defecto; NUNCA renombrar un @SerialName.
 */
@Serializable
sealed interface GoElemento {

    // ─────────────────────────── Primitivas ───────────────────────────

    @Serializable
    @SerialName("texto")
    data class Texto(
        val texto: String,
        val estilo: EstiloTexto = EstiloTexto.CUERPO,
        /** Estilo libre acotado (jul 2026): anula al semántico si se da. */
        val libre: EstiloLibre? = null,
    ) : GoElemento

    @Serializable
    @SerialName("boton")
    data class Boton(
        val id: String,
        val etiqueta: String,
        val estilo: EstiloBoton = EstiloBoton.NORMAL,
        val habilitado: Boolean = true,
    ) : GoElemento

    @Serializable
    @SerialName("campo")
    data class Campo(
        val id: String,
        val pista: String,
        val valor: String,
        val teclado: Teclado = Teclado.TEXTO,
        /** true = contraseña: puntos en pantalla y sin autocorrección. */
        val seguro: Boolean = false,
    ) : GoElemento

    /**
     * Texto tocable ("¿No tienes cuenta? *Crea una*"): la parte [enlace] va en
     * negrita y dispara el evento. Distinto de [Boton] a propósito — media
     * pantalla de login son enlaces, y pintarlos como botones queda tosco.
     * OJO: pieza nueva (jul 2026) — las apps anteriores NO la deserializan;
     * emitirla solo cuando el IPA instalado ya la soporte.
     */
    @Serializable
    @SerialName("enlace")
    data class Enlace(
        val id: String,
        /** Texto normal previo; puede ir vacío. */
        val texto: String = "",
        val enlace: String,
        /** true = alineado al final (derecha); false = centrado. */
        val alFinal: Boolean = false,
        val habilitado: Boolean = true,
    ) : GoElemento

    /** Manda "true"/"false" como valor del evento. */
    @Serializable
    @SerialName("interruptor")
    data class Interruptor(
        val id: String,
        val etiqueta: String,
        val activo: Boolean,
    ) : GoElemento

    /** Manda el valor como String de un Double, al soltar el dedo. */
    @Serializable
    @SerialName("deslizador")
    data class Deslizador(
        val id: String,
        val valor: Double,
        val minimo: Double = 0.0,
        val maximo: Double = 1.0,
    ) : GoElemento

    /** La descarga la hace el ANFITRIÓN (QuickJS no tiene red). */
    @Serializable
    @SerialName("imagen")
    data class Imagen(
        val url: String,
        val alto: Int? = null,
    ) : GoElemento

    /** [valor] 0..1 para barra determinada; null para spinner indeterminado. */
    @Serializable
    @SerialName("progreso")
    data class Progreso(val valor: Double? = null) : GoElemento

    /**
     * La válvula de escape visual: dibujo libre interpretado sobre un Canvas
     * NATIVO (Core Graphics). La pieza es fija; las órdenes viajan — gráficos,
     * medidores, iconografía propia, sin release y sin web.
     *
     * Coordenadas fraccionales (0..1 del ancho/alto real): los dibujos se
     * adaptan solos a cualquier pantalla. Radios, grosores y tamaños de letra
     * van en puntos absolutos.
     */
    @Serializable
    @SerialName("lienzo")
    data class Lienzo(
        /** Alto del lienzo en puntos; el ancho es todo el disponible. */
        val alto: Int,
        val ordenes: List<OrdenDibujo>,
    ) : GoElemento

    @Serializable
    @SerialName("separador")
    data object Separador : GoElemento

    @Serializable
    @SerialName("espacio")
    data class Espacio(val alto: Int = 8) : GoElemento

    // ─────────────── Contenedores (aquí se multiplica todo) ───────────────

    @Serializable
    @SerialName("columna")
    data class Columna(
        val hijos: List<GoElemento>,
        val espaciado: Int = 8,
    ) : GoElemento

    /** Los hijos se reparten el ancho a partes iguales. */
    @Serializable
    @SerialName("fila")
    data class Fila(
        val hijos: List<GoElemento>,
        val espaciado: Int = 8,
    ) : GoElemento

    /** Fondo y borde suaves; el bloque visual básico para listas y secciones. */
    @Serializable
    @SerialName("tarjeta")
    data class Tarjeta(val hijos: List<GoElemento>) : GoElemento

    /**
     * El caballo de batalla: envuelve CUALQUIER subárbol y lo hace tocable.
     * Una fila de lista = Tocable(Tarjeta(Fila(Imagen, Columna(Texto, Texto)))).
     */
    @Serializable
    @SerialName("tocable")
    data class Tocable(
        val id: String,
        val hijos: List<GoElemento>,
    ) : GoElemento

    /**
     * La jugada de React Native (jul 2026): contenedor con ESTILOS, como la
     * `View` de RN. Con Caja + Tocable + Texto.libre, un proyecto describe su
     * propio sistema de diseño casi al píxel sin que el catálogo sepa nada de
     * él — igual que cualquier app RN se describe con View+Text+estilos.
     *
     * Disciplina anti-Redwood: el lenguaje de estilos es CERRADO (estas
     * propiedades, tipo CSS reducido). Crece con muchísima resistencia; lo que
     * NO crece nunca es el número de widgets semánticos.
     * OJO: pieza nueva — apps anteriores no la deserializan; emitirla solo
     * con el IPA que la soporta.
     */
    @Serializable
    @SerialName("caja")
    data class Caja(
        val hijos: List<GoElemento>,
        /** "columna" o "fila". */
        val direccion: String = "columna",
        val espaciado: Int = 0,
        /** Padding interior uniforme, en puntos. */
        val relleno: Int = 0,
        /** Color de fondo en hex; null = transparente. */
        val fondo: String? = null,
        /** Radio de esquinas, en puntos. */
        val esquinas: Double = 0.0,
        /** Color del borde en hex; null = sin borde. */
        val borde: String? = null,
        val grosorBorde: Double = 1.0,
        /** Eje transversal: "inicio" | "centro" | "fin". */
        val alineacion: String = "inicio",
        /** Tamaño fijo en puntos; null = el que pida el contenido. */
        val ancho: Int? = null,
        val alto: Int? = null,
    ) : GoElemento
}

/**
 * Subconjunto CERRADO de estilo de texto, al estilo de RN: suficiente para
 * logotipos, etiquetas y jerarquías propias; jamás un sistema tipográfico.
 */
@Serializable
data class EstiloLibre(
    /** Puntos; null = el del estilo semántico. */
    val tamano: Double? = null,
    /** "normal" | "medio" | "seminegrita" | "negrita" | "negra". */
    val peso: String? = null,
    /** Hex "#RRGGBB". */
    val color: String? = null,
    /** Espaciado entre letras, en puntos. */
    val espaciado: Double? = null,
    /** "izquierda" | "centro" | "derecha" — ocupa el ancho y alinea dentro. */
    val alineacion: String? = null,
)

/** Una orden de dibujo del [GoElemento.Lienzo]. Colores en hex "#RRGGBB" o "#RRGGBBAA". */
@Serializable
sealed interface OrdenDibujo {

    @Serializable
    @SerialName("rect")
    data class Rect(
        val x: Double, val y: Double,
        val ancho: Double, val alto: Double,
        val color: String = "#FFFFFF",
        val relleno: Boolean = true,
        /** Radio de esquinas, en puntos. */
        val esquinas: Double = 0.0,
    ) : OrdenDibujo

    @Serializable
    @SerialName("circulo")
    data class Circulo(
        val x: Double, val y: Double,
        /** En puntos. */
        val radio: Double,
        val color: String = "#FFFFFF",
        val relleno: Boolean = true,
    ) : OrdenDibujo

    /** Polilínea que une [puntos] en orden. */
    @Serializable
    @SerialName("linea")
    data class Linea(
        val puntos: List<Punto>,
        val color: String = "#FFFFFF",
        /** En puntos. */
        val grosor: Double = 2.0,
    ) : OrdenDibujo

    /** Texto centrado en (x, y). */
    @Serializable
    @SerialName("rotulo")
    data class Rotulo(
        val x: Double, val y: Double,
        val texto: String,
        /** En puntos. */
        val tamano: Double = 12.0,
        val color: String = "#FFFFFF",
    ) : OrdenDibujo
}

@Serializable
data class Punto(val x: Double, val y: Double)

@Serializable
enum class EstiloTexto { TITULO, SUBTITULO, CUERPO, PIE }

@Serializable
enum class EstiloBoton { PROMINENTE, NORMAL, DESTRUCTIVO }

@Serializable
enum class Teclado { TEXTO, NUMERO, EMAIL, URL }
