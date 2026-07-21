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
    println("│ ${pantalla.titulo.padEnd(ancho - 2).take(ancho - 2)} │")
    println("├" + "─".repeat(ancho) + "┤")
    for (elemento in pantalla.elementos) {
        for (linea in lineas(elemento, "")) {
            println("│ ${linea.padEnd(ancho - 2).take(ancho - 2)} │")
        }
    }
    println("└" + "─".repeat(ancho) + "┘  (lógica v$version)")
}

// Aproximación en texto de cada pieza del catálogo, recursiva en contenedores.
private fun lineas(e: GoElemento, sangria: String): List<String> = when (e) {
    is GoElemento.Texto -> {
        val prefijo = when (e.estilo) {
            EstiloTexto.TITULO -> "══ "
            EstiloTexto.SUBTITULO -> "» "
            EstiloTexto.CUERPO -> ""
            EstiloTexto.PIE -> "· "
        }
        listOf(sangria + prefijo + e.texto)
    }
    is GoElemento.Boton -> listOf("$sangria[ ${e.etiqueta}${if (e.habilitado) "" else " (off)"} ]")
    is GoElemento.Campo -> listOf("$sangria${e.pista}: ${if (e.seguro) "•".repeat(e.valor.length) else e.valor.ifEmpty { "_" }}")
    is GoElemento.Interruptor -> listOf("$sangria(${if (e.activo) "●" else "○"}) ${e.etiqueta}")
    is GoElemento.Deslizador -> listOf("$sangria├─ ${e.valor} ─┤ [${e.minimo}..${e.maximo}]")
    is GoElemento.Imagen -> listOf("$sangria🖼  ${e.url.substringAfterLast('/')}")
    is GoElemento.Progreso -> listOf(sangria + (e.valor?.let { "▓".repeat((it * 10).toInt()) + "░".repeat(10 - (it * 10).toInt()) } ?: "◌ …"))
    is GoElemento.Separador -> listOf("$sangria────────")
    is GoElemento.Espacio -> listOf("")
    is GoElemento.Columna -> e.hijos.flatMap { lineas(it, sangria) }
    is GoElemento.Fila -> e.hijos.flatMap { lineas(it, sangria) } // en texto, apilada
    is GoElemento.Tarjeta -> e.hijos.flatMap { lineas(it, "$sangria▕ ") }
    is GoElemento.Tocable -> e.hijos.flatMap { lineas(it, sangria) }.mapIndexed { i, l ->
        if (i == 0) "$l  ⇢ toca:${e.id}" else l
    }
}
