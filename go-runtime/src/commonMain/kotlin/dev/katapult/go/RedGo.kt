package dev.katapult.go

import app.cash.zipline.ZiplineService
import kotlinx.serialization.Serializable

/**
 * El primer servicio de anfitrión: red para la lógica Go. QuickJS no tiene
 * fetch ni sockets, así que el ANFITRIÓN (JVM u iOS) hace la petición HTTP por
 * la lógica y le devuelve la respuesta como datos. Con esto el modo Go deja de
 * estar limitado a datos en memoria: puede hablar con la API real del proyecto.
 *
 * La lógica lo toma con `Zipline.get().take<RedGo>(SERVICIO_RED)`. Si la app
 * instalada es anterior a este contrato, la llamada a [pedir] fallará: hay que
 * capturarlo y explicar que toca actualizar la app (IPA nuevo).
 */
interface RedGo : ZiplineService {
    suspend fun pedir(peticion: PeticionRed): RespuestaRed
}

/** Nombre con el que el anfitrión publica el servicio en el puente Zipline. */
const val SERVICIO_RED = "redGo"

@Serializable
data class PeticionRed(
    val url: String,
    val metodo: String = "GET",
    /** "Content-Type" incluido aquí; el anfitrión lo trata como es debido. */
    val cabeceras: Map<String, String> = emptyMap(),
    val cuerpo: String? = null,
)

@Serializable
data class RespuestaRed(
    /** Código HTTP, o 0 si la petición ni siquiera llegó (ver [fallo]). */
    val codigo: Int,
    val cuerpo: String = "",
    val cabeceras: Map<String, String> = emptyMap(),
    /** Mensaje legible si no hubo respuesta: sin red, DNS, timeout… */
    val fallo: String? = null,
)
