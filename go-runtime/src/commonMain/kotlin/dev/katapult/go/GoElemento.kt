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
}

@Serializable
enum class EstiloTexto { TITULO, SUBTITULO, CUERPO, PIE }

@Serializable
enum class EstiloBoton { PROMINENTE, NORMAL, DESTRUCTIVO }

@Serializable
enum class Teclado { TEXTO, NUMERO, EMAIL, URL }
