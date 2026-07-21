package dev.katapult.mirror

import androidx.compose.runtime.Composable

/**
 * Punto de entrada del espejo de desarrollo de Katapult.
 *
 * Lo invoca el `main` que genera el plugin de Gradle a partir de `entryPoint`.
 * Renderiza el composable en la JVM (sin pantalla) y lo transmite por WebSocket,
 * para poder iterar UI desde Linux y verla en un iPhone sin recompilar nada nativo.
 */
fun startMirror(
    port: Int = DEFAULT_PORT,
    fps: Int = DEFAULT_FPS,
    widthDp: Int = DEFAULT_WIDTH_DP,
    heightDp: Int = DEFAULT_HEIGHT_DP,
    density: Float = DEFAULT_DENSITY,
    content: @Composable () -> Unit,
) {
    // Sin ventanas: que AWT no intente abrir un display (permite correr headless).
    System.setProperty("java.awt.headless", "true")

    // Los fallos de la app del usuario (corrutinas de carga de datos, etc.) se
    // pierden si nadie los recoge: sin este handler la UI se queda vacía SIN
    // decir por qué. Una herramienta de desarrollo tiene que gritar los errores.
    Thread.setDefaultUncaughtExceptionHandler { thread, error ->
        System.err.println("\n⚠️  Error no capturado en '${thread.name}':")
        error.printStackTrace()
    }

    MirrorServer(
        port = port,
        fps = fps,
        widthDp = widthDp,
        heightDp = heightDp,
        density = density,
        content = content,
    ).start()

    // El servidor y el bucle de render viven en sus propios hilos.
    Thread.currentThread().join()
}

const val DEFAULT_PORT = 8080

// El render cuesta ~3,5 ms y la codificación va en otro hilo, así que 60 es
// alcanzable. El techo real lo pone el codificador JPEG (~15 ms → ~64 fps).
const val DEFAULT_FPS = 60

const val DEFAULT_WIDTH_DP = 390    // iPhone 14
const val DEFAULT_HEIGHT_DP = 844
const val DEFAULT_DENSITY = 2f

/** Calidad JPEG. Domina tanto el tamaño del frame como el coste de codificar. */
const val DEFAULT_QUALITY = 70
