// La escena de bajo nivel y el interceptor de IME son APIs internas/experimentales
// de compose-ui: el precio de poder inyectar un PlatformContext propio. Fijadas a
// la versión exacta de CMP del proyecto (ver la nota de versiones en el build).
@file:OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)

package dev.katapult.mirror

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.asComposeCanvas
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.platform.PlatformContext
import androidx.compose.ui.platform.PlatformTextInputMethodRequest
import androidx.compose.ui.scene.CanvasLayersComposeScene
import androidx.compose.ui.scene.ComposeScenePointer
import androidx.compose.ui.text.input.CommitTextCommand
import androidx.compose.ui.text.input.DeleteSurroundingTextCommand
import androidx.compose.ui.text.input.EditCommand
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.ImeOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PlatformTextInputService
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntSize
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.awaitCancellation
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo
import org.jetbrains.skia.Surface

/**
 * LifecycleOwner mínimo para la escena.
 *
 * Sin esto, `collectAsStateWithLifecycle` (que espera el estado STARTED) nunca
 * empieza a recolectar y la app se queda en su pantalla vacía, SIN dar error.
 * Una ventana de Compose Desktop provee uno; la escena a pelo no.
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
 * El campo de texto enfocado en la escena, resumido para el protocolo del
 * espejo: lo que el cliente nativo necesita para superponer su UITextField.
 * [rect] va en píxeles de la escena (el cliente lo proyecta a su pantalla).
 */
data class FocoTexto(
    val valor: String,
    val teclado: String,
    val accionIme: String,
    val seguro: Boolean,
    val rect: Rect?,
)

/**
 * La sesión de entrada de texto activa, unificada: Compose tiene dos tuberías
 * de IME (la nueva por PlatformTextInputMethodRequest y la legacy por
 * PlatformTextInputService) y según la versión del widget dispara una u otra.
 * Interceptamos AMBAS y las reducimos a esto.
 */
private class SesionTexto(
    val valor: () -> TextFieldValue,
    val imeOptions: ImeOptions,
    val editar: (List<EditCommand>) -> Unit,
    val accionIme: (ImeAction) -> Unit,
    val rect: () -> Rect?,
)

/**
 * Renderiza una UI Compose fuera de pantalla y devuelve el frame codificado.
 *
 * Usa CanvasLayersComposeScene (la escena de bajo nivel de skiko) en vez de
 * ImageComposeScene: mismo motor Skia, pero permite inyectar un
 * [PlatformContext] propio — que es donde se intercepta el FOCO DE TEXTO para
 * el teclado nativo del cliente iOS (Fase 2 del espejo nativo).
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

    /** Sesión de texto activa (null = ningún campo enfocado). Solo en el EDT. */
    private var sesionTexto: SesionTexto? = null

    // ——— Interceptores de las dos tuberías de IME ———

    private val plataforma = object : PlatformContext by PlatformContext.Empty() {

        // Tubería nueva (BasicTextField moderno): una corrutina por sesión de
        // foco; se cancela al perderlo.
        override suspend fun startInputMethod(request: PlatformTextInputMethodRequest): Nothing {
            sesionTexto = SesionTexto(
                valor = { request.value() },
                imeOptions = request.imeOptions,
                editar = { request.onEditCommand(it) },
                accionIme = { request.onImeAction?.invoke(it) },
                rect = { runCatching { request.textFieldRectInRoot() }.getOrNull() },
            )
            try {
                awaitCancellation()
            } finally {
                sesionTexto = null
            }
        }

        // Tubería legacy (TextField basados en value/onValueChange antiguos).
        @Suppress("OVERRIDE_DEPRECATION")
        override val textInputService: PlatformTextInputService =
            object : PlatformTextInputService {
                private var valorActual: TextFieldValue = TextFieldValue()
                private var rectActual: Rect? = null

                override fun startInput(
                    value: TextFieldValue,
                    imeOptions: ImeOptions,
                    onEditCommand: (List<EditCommand>) -> Unit,
                    onImeActionPerformed: (ImeAction) -> Unit,
                ) {
                    valorActual = value
                    sesionTexto = SesionTexto(
                        valor = { valorActual },
                        imeOptions = imeOptions,
                        editar = onEditCommand,
                        accionIme = onImeActionPerformed,
                        rect = { rectActual },
                    )
                }

                override fun stopInput() {
                    sesionTexto = null
                    rectActual = null
                }

                override fun showSoftwareKeyboard() {}
                override fun hideSoftwareKeyboard() {}

                override fun updateState(oldValue: TextFieldValue?, newValue: TextFieldValue) {
                    valorActual = newValue
                }

                @Deprecated("Sustituido en la tubería nueva; aquí es la única fuente del rect")
                override fun notifyFocusedRect(rect: Rect) {
                    rectActual = rect
                }
            }
    }

    @OptIn(InternalComposeUiApi::class)
    private val scene = CanvasLayersComposeScene(
        density = Density(density),
        size = IntSize(widthPx, heightPx),
        platformContext = plataforma,
        invalidate = {}, // renderizamos a 60 fps igualmente; no hay que despertar a nadie
    )

    // La escena dibuja sobre esta superficie raster; snapshot() la lee.
    private val surface = Surface.makeRasterN32Premul(widthPx, heightPx)

    init {
        scene.setContent {
            CompositionLocalProvider(LocalLifecycleOwner provides lifecycleOwner) {
                content()
            }
        }
        // Tras crear la composición: pasar a RESUMED destraba los efectos que
        // dependen del lifecycle (carga de datos, collectAsStateWithLifecycle…).
        lifecycleOwner.resume()
    }

    // ——— API de foco de texto para MirrorServer ———

    /** Estado del campo enfocado, o null. Llamar en el EDT. */
    fun focoInfo(): FocoTexto? = sesionTexto?.let { s ->
        FocoTexto(
            valor = s.valor().text,
            teclado = when (s.imeOptions.keyboardType) {
                KeyboardType.Email -> "email"
                KeyboardType.Number, KeyboardType.NumberPassword -> "numero"
                KeyboardType.Decimal -> "decimal"
                KeyboardType.Phone -> "telefono"
                KeyboardType.Uri -> "uri"
                KeyboardType.Password -> "password"
                else -> "texto"
            },
            accionIme = when (s.imeOptions.imeAction) {
                ImeAction.Done -> "done"
                ImeAction.Next -> "next"
                ImeAction.Go -> "go"
                ImeAction.Search -> "search"
                ImeAction.Send -> "send"
                else -> "default"
            },
            seguro = s.imeOptions.keyboardType == KeyboardType.Password ||
                s.imeOptions.keyboardType == KeyboardType.NumberPassword,
            rect = s.rect(),
        )
    }

    /** Reemplaza el texto del campo enfocado por [texto]. Llamar en el EDT. */
    fun escribirTexto(texto: String) {
        val s = sesionTexto ?: return
        val largo = s.valor().text.length
        // Borrar alrededor del cursor cubre todo el campo (los comandos
        // recortan a los límites) y el commit deja el cursor al final.
        s.editar(listOf(DeleteSurroundingTextCommand(largo, largo), CommitTextCommand(texto, 1)))
    }

    /** Dispara la acción IME del campo enfocado (Done/Next/…). En el EDT. */
    fun accionIme() {
        val s = sesionTexto ?: return
        s.accionIme(s.imeOptions.imeAction)
    }

    /**
     * Inyecta un evento de puntero en la escena. Debe llamarse en el mismo hilo
     * que construyó la escena (el EDT).
     *
     * Se declara el puntero explícitamente, en vez de usar la variante corta, por
     * dos motivos que rompen el scroll:
     *
     *  · La variante corta asume `PointerType.Mouse`, y para Compose un
     *    movimiento de ratón sin botón pulsado es *hover*, no arrastre. Los
     *    gestos de scroll nunca llegaban a dispararse.
     *  · `pressed` tiene que seguir siendo true entre el press y el release; es
     *    lo que convierte una secuencia de puntos en un arrastre.
     *
     * `timeMillis` importa igual: el detector de velocidad de Compose lo usa
     * para calcular el impulso del scroll. Sin tiempos que avancen no hay
     * inercia al soltar.
     */
    @OptIn(InternalComposeUiApi::class, ExperimentalComposeUiApi::class)
    fun sendPointer(type: PointerEventType, position: Offset, pressed: Boolean, timeMillis: Long) {
        scene.sendPointerEvent(
            eventType = type,
            pointers = listOf(
                ComposeScenePointer(
                    id = PointerId(TOUCH_POINTER_ID),
                    position = position,
                    pressed = pressed,
                    type = PointerType.Touch,
                )
            ),
            timeMillis = timeMillis,
        )
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
     * para poder codificar en OTRO hilo: la superficie de render se sobrescribe
     * en el frame siguiente.
     *
     * Medido: rasterizar ~2,5 ms, copiar ~1 ms. Codificar, en cambio, cuesta
     * ~15 ms — por eso se hace fuera de aquí.
     */
    @OptIn(InternalComposeUiApi::class)
    fun snapshot(): Bitmap {
        val t0 = System.nanoTime()
        scene.render(surface.canvas.asComposeCanvas(), t0 - startNanos)
        val t1 = System.nanoTime()

        val bitmap = Bitmap()
        bitmap.allocPixels(ImageInfo.makeN32Premul(widthPx, heightPx))
        surface.makeImageSnapshot().use { image ->
            if (!image.readPixels(bitmap)) error("Skia no pudo leer los píxeles del frame")
        }
        bitmap.setImmutable()   // requisito para makeFromBitmap sin copia extra

        lastRenderNanos = t1 - t0
        lastCopyNanos = System.nanoTime() - t1
        return bitmap
    }

    @OptIn(InternalComposeUiApi::class)
    fun close() {
        scene.close()
        surface.close()
    }

    companion object {
        /** Un solo dedo: el cliente manda un único punto de contacto. */
        private const val TOUCH_POINTER_ID = 1L

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
