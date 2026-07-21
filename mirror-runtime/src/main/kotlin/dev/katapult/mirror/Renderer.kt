package dev.katapult.mirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import org.jetbrains.skia.EncodedImageFormat

/**
 * LifecycleOwner mínimo para la escena.
 *
 * Sin esto, `collectAsStateWithLifecycle` (que espera el estado STARTED) nunca
 * empieza a recolectar y la app se queda en su pantalla vacía, SIN dar error.
 * Una ventana de Compose Desktop provee uno; ImageComposeScene no.
 */
private class MirrorLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    /** Debe llamarse en el hilo main (el EDT). */
    fun resume() {
        registry.currentState = Lifecycle.State.RESUMED
    }
}

/**
 * Renderiza una UI Compose fuera de pantalla y devuelve el frame codificado.
 *
 * Usa ImageComposeScene, que rasteriza con Skia sin ventana ni servidor X.
 * Es el mismo motor que Compose Multiplatform usa en iOS, así que lo que sale
 * aquí es prácticamente idéntico a lo que se vería en el dispositivo.
 *
 * OJO: width/height van en PÍXELES, no en dp. La densidad solo convierte dp→px
 * dentro de la composición. Para simular un iPhone de 390x844 dp @2x hay que
 * pasar 780x1688 px con density = 2.
 */
class Renderer(
    widthDp: Int = DEFAULT_WIDTH_DP,
    heightDp: Int = DEFAULT_HEIGHT_DP,
    private val density: Float = DEFAULT_DENSITY,
    content: @Composable () -> Unit,
) {
    val widthPx = (widthDp * density).toInt()
    val heightPx = (heightDp * density).toInt()

    private val lifecycleOwner = MirrorLifecycleOwner()

    private val scene = ImageComposeScene(
        width = widthPx,
        height = heightPx,
        density = Density(density),
    ) {
        CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
            content()
        }
    }

    init {
        // Tras crear la composición: pasar a RESUMED destraba los efectos que
        // dependen del lifecycle (carga de datos, collectAsStateWithLifecycle…).
        lifecycleOwner.resume()
    }

    /**
     * Inyecta un evento de puntero en la escena. Debe llamarse en el mismo hilo
     * que construyó la escena (el EDT).
     */
    fun sendPointer(type: PointerEventType, position: Offset) {
        scene.sendPointerEvent(eventType = type, position = position)
    }

    // Origen del reloj de frames. render(nanoTime) mueve TODO lo que depende de
    // withFrameNanos: animaciones y el despacho de recomposiciones. Con el valor
    // por defecto (0) el reloj queda congelado y la UI nunca se actualiza aunque
    // el estado cambie — se ve la pantalla inicial para siempre.
    private val startNanos = System.nanoTime()

    /** Rasteriza el estado actual y lo codifica. */
    fun frame(format: EncodedImageFormat = EncodedImageFormat.JPEG, quality: Int = 80): ByteArray {
        val image = scene.render(System.nanoTime() - startNanos)
        val data = image.encodeToData(format, quality)
            ?: error("Skia no pudo codificar el frame")
        return data.bytes
    }

    fun close() = scene.close()
}
