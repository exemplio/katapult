package dev.katapult.go

import app.cash.zipline.loader.DefaultFreshnessCheckerNotFresh
import app.cash.zipline.loader.LoadResult
import app.cash.zipline.loader.ZiplineLoader
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch

/**
 * El corazón del anfitrión, compartido entre JVM (consola en Linux) e iOS
 * (katapult-go). Descarga la lógica, la ejecuta, y la sustituye en caliente
 * cuando el servidor publica código nuevo. Lo único que cada plataforma pone
 * de su parte es el ZiplineLoader (OkHttp vs URLSession) y qué hacer con cada
 * [GoEstado].
 */
sealed interface GoEstado {
    /** Aún no hay lógica corriendo (servidor caído o primer arranque). */
    data class Esperando(val detalle: String) : GoEstado

    /** La lógica produjo una pantalla nueva (por tick, evento o recarga). */
    data class Corriendo(val pantalla: GoPantalla, val version: Int) : GoEstado
}

/**
 * El canal de vuelta: la UI nativa llama [evento] cuando el usuario toca un
 * botón o escribe en un campo, y el anfitrión repinta con el estado nuevo.
 * DEBE invocarse desde el hilo del dispatcher de Zipline (en iOS, el Main).
 */
class GoControl {
    internal var alEvento: ((String, String?) -> Unit)? = null

    fun evento(id: String, valor: String?) {
        alEvento?.invoke(id, valor)
    }
}

fun arrancarGo(
    scope: CoroutineScope,
    dispatcher: CoroutineDispatcher,
    loader: ZiplineLoader,
    manifestUrl: String,
    control: GoControl? = null,
    alCambiar: (GoEstado) -> Unit,
): Job = scope.launch(dispatcher + SupervisorJob()) {
    val resultados = loader.load(
        applicationName = "katapult-go",
        freshnessChecker = DefaultFreshnessCheckerNotFresh,
        manifestUrlFlow = repetir(manifestUrl, 500),
    )

    var version = 0
    var trabajoAnterior: Job? = null

    resultados.collect { resultado ->
        when (resultado) {
            is LoadResult.Failure -> {
                // Si ya hay lógica corriendo se conserva: perder el servidor un
                // rato no debe tirar la app, igual que en Expo Go.
                if (trabajoAnterior == null) {
                    alCambiar(GoEstado.Esperando(resultado.exception.message ?: "sin conexión"))
                }
            }
            is LoadResult.Success -> {
                trabajoAnterior?.cancel()
                version++
                val v = version
                val logica = resultado.zipline.take<GoLogica>("goLogica")

                // El canal de eventos apunta siempre a la lógica más reciente.
                control?.alEvento = { id, valor ->
                    logica.evento(id, valor)
                    alCambiar(GoEstado.Corriendo(logica.pantalla(), v))
                }

                trabajoAnterior = launch {
                    // Tick de cortesía: repinta cada segundo por si la lógica
                    // depende del tiempo. Los eventos repintan al instante.
                    while (true) {
                        alCambiar(GoEstado.Corriendo(logica.pantalla(), v))
                        delay(1000)
                    }
                }
            }
        }
    }
}

/** Re-emitir la URL es el polling: el loader solo recarga si el manifest cambió. */
private fun <T> repetir(valor: T, cadaMs: Long): Flow<T> = flow {
    while (true) {
        emit(valor)
        delay(cadaMs)
    }
}
