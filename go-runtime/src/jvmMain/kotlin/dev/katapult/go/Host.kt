package dev.katapult.go

import app.cash.zipline.loader.ManifestVerifier.Companion.NO_SIGNATURE_CHECKS
import app.cash.zipline.loader.ZiplineLoader
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * El anfitrión de consola: hace en Linux lo que katapult-go hace en el iPhone,
 * sin IPA de por medio. La lógica real está compartida en [arrancarGo]
 * (hostMain); aquí solo se pone el loader de OkHttp y el render en texto.
 */

// 8081: el 8080 es del espejo. Ver el configureEach de ZiplineServeTask en el build.
private const val MANIFEST_URL = "http://localhost:8081/manifest.zipline.json"

fun main(args: Array<String>) {
    val manifestUrl = args.firstOrNull() ?: MANIFEST_URL
    // QuickJS no es thread-safe: todo Zipline vive en un único hilo.
    val executor = Executors.newSingleThreadExecutor { Thread(it, "zipline") }
    val dispatcher = executor.asCoroutineDispatcher()

    runBlocking {
        val loader = ZiplineLoader(
            dispatcher = dispatcher,
            // Sin firma en desarrollo; el paso 2 del plan añade manifests firmados.
            manifestVerifier = NO_SIGNATURE_CHECKS,
            httpClient = OkHttpClient(),
        )

        println("Esperando lógica en $manifestUrl …")
        println("(arranca `./gradlew :go-runtime:serveDevelopmentZipline --continuous` en otra terminal)\n")

        arrancarGo(this, dispatcher, loader, manifestUrl) { estado ->
            when (estado) {
                is GoEstado.Esperando -> println("✗ sin lógica todavía: ${estado.detalle}")
                is GoEstado.Corriendo -> render(estado.pantalla, estado.version)
            }
        }
        // El Job de arrancarGo es hijo de este runBlocking: se queda vivo aquí.
    }
}

// La consola es un anfitrión de solo lectura: pinta los elementos pero no
// manda eventos. La interacción de verdad se prueba en katapult-go.
private fun render(pantalla: GoPantalla, version: Int) {
    val ancho = 64
    println("┌" + "─".repeat(ancho) + "┐")
    println("│ ${pantalla.titulo.padEnd(ancho - 2)} │")
    println("├" + "─".repeat(ancho) + "┤")
    for (elemento in pantalla.elementos) {
        val linea = when (elemento) {
            is GoElemento.Texto -> if (elemento.destacado) "» ${elemento.texto}" else elemento.texto
            is GoElemento.Boton -> "[ ${elemento.etiqueta} ]"
            is GoElemento.Campo -> "${elemento.pista}: ${elemento.valor.ifEmpty { "_" }}"
        }
        println("│ ${linea.padEnd(ancho - 2)} │")
    }
    println("└" + "─".repeat(ancho) + "┘  (lógica v$version)")
}
