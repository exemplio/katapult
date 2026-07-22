package dev.katapult.go

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType

/**
 * La implementación de [RedGo] del anfitrión, compartida entre JVM e iOS: solo
 * cambia el engine del HttpClient (OkHttp / Darwin), que inyecta cada lado.
 *
 * Nunca lanza: cualquier fallo vuelve como [RespuestaRed.fallo], porque una
 * excepción del anfitrión cruzando el puente hacia QuickJS es mucho más
 * difícil de razonar para la lógica que un valor de error normal.
 */
class RedGoKtor(private val cliente: HttpClient) : RedGo {

    override suspend fun pedir(peticion: PeticionRed): RespuestaRed = try {
        val respuesta = cliente.request(peticion.url) {
            method = HttpMethod.parse(peticion.metodo.uppercase())
            peticion.cabeceras.forEach { (clave, valor) ->
                // Ktor prohíbe Content-Type como cabecera suelta (UnsafeHeaderException).
                if (clave.equals("Content-Type", ignoreCase = true)) {
                    contentType(ContentType.parse(valor))
                } else {
                    header(clave, valor)
                }
            }
            peticion.cuerpo?.let { setBody(it) }
        }
        RespuestaRed(
            codigo = respuesta.status.value,
            cuerpo = respuesta.bodyAsText(),
            cabeceras = respuesta.headers.entries().associate { (clave, valores) ->
                clave to valores.joinToString(", ")
            },
        )
    } catch (e: Exception) {
        RespuestaRed(codigo = 0, fallo = e.message ?: "error de red en el anfitrión")
    }
}
