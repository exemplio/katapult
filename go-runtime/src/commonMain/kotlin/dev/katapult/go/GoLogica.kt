package dev.katapult.go

import app.cash.zipline.ZiplineService
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * El contrato entre el anfitrión (nativo, instalado) y la lógica (descargada).
 *
 * Regla de oro del puente: por aquí solo viajan DATOS serializables, nunca
 * lambdas ni Modifiers. Es la misma restricción que hace viable a Expo Go.
 *
 * OJO: cambiar este fichero rompe el contrato → hay que recompilar el IPA de
 * katapult-go A LA VEZ que la lógica. Todo lo que quepa dentro del contrato
 * actual se recarga en caliente; esto es lo único que no.
 */
interface GoLogica : ZiplineService {
    /** El estado actual de la pantalla. La lógica guarda su propio estado. */
    fun pantalla(): GoPantalla

    /**
     * Un evento del usuario: [id] identifica el elemento ("sumar", "nombre"…)
     * y [valor] lleva el texto en los campos (null en botones). Tras cada
     * evento el anfitrión vuelve a pedir [pantalla].
     */
    fun evento(id: String, valor: String?)
}

@Serializable
data class GoPantalla(
    val titulo: String,
    val elementos: List<GoElemento>,
    /** null = aspecto del sistema, como siempre. Apps viejas lo ignoran (el
     *  Json del puente lleva ignoreUnknownKeys): degradación sin roturas. */
    val tema: GoTema? = null,
    /** false = oculta el título y la barra de navegación superior. */
    val mostrarTitulo: Boolean = true,
    /** true = muestra el footer de depuración (versión, URL). Solo en desarrollo. */
    val mostrarFooter: Boolean = true,
)

/**
 * Theming ACOTADO del catálogo: tres perillas de marca y ni una más. La línea
 * roja de siempre aplica — esto NO es un sistema de estilos por elemento (esa
 * es la trampa Redwood); es el mínimo para que el wireframe sea "de tu app"
 * en vez de genérico. Colores en hex "#RRGGBB".
 */
@Serializable
data class GoTema(
    /** Fondo de toda la pantalla. */
    val fondo: String? = null,
    /** Tinte de acento: botones prominentes, interruptores, progreso. */
    val acento: String? = null,
    /** true fuerza apariencia clara, false oscura; null respeta el sistema. */
    val claro: Boolean? = null,
)

// El catálogo de elementos vive en GoElemento.kt (archivo aparte a propósito:
// el contrato de esta interface casi nunca debe cambiar; el catálogo crece).
