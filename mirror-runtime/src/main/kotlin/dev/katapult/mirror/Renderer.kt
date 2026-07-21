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
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

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

    /** Coste del último snapshot, en nanosegundos. Para diagnosticar el cuello. */
    var lastRenderNanos = 0L
        private set
    var lastCopyNanos = 0L
        private set

    /**
     * Rasteriza el estado actual y copia los píxeles a un bitmap propio.
     *
     * Debe llamarse en el EDT, como todo lo que toca la escena. La copia existe
     * para poder codificar en OTRO hilo: la imagen que devuelve `render` apunta
     * a la superficie de la escena, que el frame siguiente sobrescribe.
     *
     * Medido: rasterizar ~2,5 ms, copiar ~1 ms. Codificar, en cambio, cuesta
     * ~15 ms — por eso se hace fuera de aquí.
     */
    fun snapshot(): Bitmap {
        val t0 = System.nanoTime()
        val image = scene.render(t0 - startNanos)
        val t1 = System.nanoTime()

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(widthPx, heightPx))
        if (!image.readPixels(bitmap)) error("Skia no pudo leer los píxeles del frame")
        bitmap.setImmutable()   // requisito para makeFromBitmap sin copia extra

        lastRenderNanos = t1 - t0
        lastCopyNanos = System.nanoTime() - t1
        return bitmap
    }

    fun close() = scene.close()

    companion object {
        /**
         * Codifica un snapshot. Es la parte cara, así que va fuera del EDT.
         * Consume el bitmap: lo libera al terminar.
         */
        fun encode(
            bitmap: Bitmap,
            format: EncodedImageFormat = EncodedImageFormat.JPEG,
            quality: Int = DEFAULT_QUALITY,
        ): ByteArray = try {
            Image.makeFromBitmap(bitmap).use { image ->
                (image.encodeToData(format, quality)
                    ?: error("Skia no pudo codificar el frame")).use { it.bytes }
            }
        } finally {
            bitmap.close()
        }
    }
}
