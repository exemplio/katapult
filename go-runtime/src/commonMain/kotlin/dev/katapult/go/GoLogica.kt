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
)

// El catálogo de elementos vive en GoElemento.kt (archivo aparte a propósito:
// el contrato de esta interface casi nunca debe cambiar; el catálogo crece).
