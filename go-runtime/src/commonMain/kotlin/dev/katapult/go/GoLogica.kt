package dev.katapult.go

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable

/**
 * El contrato entre el anfitrión (nativo, instalado) y la lógica (descargada).
 *
 * Regla de oro del puente: por aquí solo viajan DATOS serializables, nunca
 * lambdas ni Modifiers. Es la misma restricción que hace viable a Expo Go.
 */
interface GoLogica : ZiplineService {
    /** Devuelve el modelo de la pantalla para el instante [contador]. */
    fun pantalla(contador: Int): GoPantalla
}

/**
 * Modelo de UI del paso 0: el anfitrión lo pinta con su UI fija.
 * En el paso 1 esto se convierte en un árbol de widgets del catálogo.
 */
@Serializable
data class GoPantalla(
    val titulo: String,
    val lineas: List<String>,
)
