package dev.katapult.mirror

import androidx.compose.runtime.Composable
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.jetbrains.skia.Bitmap

/**
 * Mensaje del cliente. `type` decide qué campos aplican:
 *  - "down"/"move"/"up": toque en (x, y) píxeles de la escena.
 *  - "cliente": el cliente se presenta (nativo=true activa los mensajes de foco;
 *    el cliente web no se presenta y así no recibe texto que no entiende).
 *  - "texto": el teclado nativo escribió; `valor` es el contenido COMPLETO.
 *  - "ime": el usuario pulsó la acción del teclado (Done/Next…).
 */
@Serializable
data class TouchEvent(
    val type: String,
    val x: Float = 0f,
    val y: Float = 0f,
    val valor: String? = null,
)

/** Mensaje de foco al cliente nativo: dónde y cómo superponer su UITextField. */
@Serializable
private data class FocoMensaje(
    val type: String = "foco",
    val valor: String,
    val teclado: String,
    val accion: String,
    val seguro: Boolean,
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int,
)

/** Un frame ya codificado, listo para mandar. */
private class VideoFrame(val bytes: ByteArray, val isKeyframe: Boolean)

/**
 * Contadores del pipeline, resumidos una vez por segundo.
 *
 * El render y la codificación viven en hilos distintos, así que cada uno anota
 * lo suyo; el resumen lo imprime el codificador, que es quien produce frames.
 */
private class Perf {
    private var frames = 0
    private var render = 0L
    private var copy = 0L
    private var read = 0L
    private var encode = 0L
    private var bytes = 0L
    private var windowStart = System.nanoTime()

    @Synchronized
    fun recordRender(renderNanos: Long, copyNanos: Long) {
        render += renderNanos
        copy += copyNanos
    }

    /** Coste de extraer los píxeles del bitmap para dárselos a ffmpeg. */
    @Synchronized
    fun recordRead(nanos: Long) {
        read += nanos
    }

    @Synchronized
    fun recordEncode(encodeNanos: Long, size: Long) {
        encode += encodeNanos
        bytes += size
        frames++

        val elapsed = System.nanoTime() - windowStart
        if (elapsed < 1_000_000_000L) return

        val ms = { total: Long -> "%.1f".format(total / 1e6 / frames) }
        val fps = frames * 1e9 / elapsed
        val mbps = bytes * 8 / (elapsed / 1e9) / 1e6
        val kb = bytes.toDouble() / frames / 1024
        println(
            "[perf] ${"%.1f".format(fps)} fps · render ${ms(render)}ms · copia ${ms(copy)}ms · " +
                "lectura ${ms(read)}ms · ${"%.1f".format(kb)}KB/frame · ${"%.1f".format(mbps)} Mbps"
        )
        frames = 0; render = 0; copy = 0; read = 0; encode = 0; bytes = 0
        windowStart = System.nanoTime()
    }
}

/**
 * Espejo de desarrollo: renderiza la UI en la JVM y la transmite por WebSocket.
 *
 * Reparto de hilos: TODA operación sobre la escena (render y eventos) ocurre en
 * Dispatchers.Main —el EDT de AWT—, porque androidx.lifecycle y navigation lo
 * exigen. Ktor corre en sus propios hilos y marshalea al EDT con withContext.
 */
class MirrorServer(
    private val port: Int = DEFAULT_PORT,
    private val fps: Int = DEFAULT_FPS,
    private val widthDp: Int = DEFAULT_WIDTH_DP,
    private val heightDp: Int = DEFAULT_HEIGHT_DP,
    private val density: Float = DEFAULT_DENSITY,
    private val content: @Composable () -> Unit,
) {
    private val frames = MutableSharedFlow<VideoFrame>(replay = 1, extraBufferCapacity = 4)
    private lateinit var renderer: Renderer
    private val perf = Perf()

    // Mensajes de foco de texto hacia los clientes nativos. replay=1: el que
    // se conecta (o se presenta) con un campo ya enfocado recibe el estado.
    private val focoMensajes = MutableSharedFlow<String>(replay = 1, extraBufferCapacity = 8)

    /** Último JSON de foco emitido; evita repetir mensajes idénticos por frame. */
    private var ultimoFocoJson: String? = null

    // encodeDefaults: sin él, kotlinx omite `type: "foco"` (valor por defecto)
    // y el cliente, que enruta por ese campo, ignoraría el mensaje.
    private val jsonFoco = Json { encodeDefaults = true }

    /** H.264 si hay ffmpeg; si no, JPEG, que siempre funciona pero pesa 40x más. */
    private val useH264 = H264Encoder.isAvailable()
    private var encoder: H264Encoder? = null

    /**
     * Último keyframe emitido. Un cliente que se conecta a mitad de stream no
     * puede decodificar frames diferenciales: necesita empezar por uno completo.
     */
    @Volatile
    private var lastKeyframe: ByteArray? = null

    fun start() {
        val scope = CoroutineScope(Dispatchers.Main)

        // Canal render → codificador. CONFLATED: si el codificador se atrasa se
        // descarta el frame viejo en vez de acumular latencia; onUndeliveredElement
        // libera el bitmap descartado, que es memoria nativa y no la ve el GC.
        val snapshots = Channel<Bitmap>(Channel.CONFLATED) { it.close() }

        // Bucle de render: vive en el EDT de principio a fin. Solo rasteriza y
        // copia (~3,5 ms), así que puede sostener holgadamente 60 fps.
        scope.launch {
            renderer = Renderer(widthDp, heightDp, density, content)
            println("→ Escena ${renderer.widthPx}x${renderer.heightPx} px a $fps fps")
            val framePeriod = 1000L / fps
            while (true) {
                val loopStart = System.nanoTime()
                val bitmap = renderer.snapshot()
                perf.recordRender(renderer.lastRenderNanos, renderer.lastCopyNanos)
                snapshots.send(bitmap)
                vigilarFoco()
                // Descontar lo ya gastado; si nos pasamos del periodo, no dormimos.
                val spentMs = (System.nanoTime() - loopStart) / 1_000_000
                delay((framePeriod - spentMs).coerceAtLeast(0))
            }
        }

        // Codificador: fuera del EDT, en un único hilo para no reordenar frames.
        @OptIn(ExperimentalCoroutinesApi::class)
        val encodeScope = CoroutineScope(Dispatchers.IO.limitedParallelism(1))

        if (useH264) {
            // ffmpeg entrega los frames por su cuenta, desde el hilo lector.
            val h264 = H264Encoder(
                width = (widthDp * density).toInt(),
                height = (heightDp * density).toInt(),
                fps = fps,
            ) { bytes, isKey ->
                perf.recordEncode(0, bytes.size.toLong())
                lastKeyframe = if (isKey) bytes else lastKeyframe
                encodeScope.launch { frames.emit(VideoFrame(bytes, isKey)) }
            }
            h264.start()
            encoder = h264

            encodeScope.launch {
                for (bitmap in snapshots) {
                    val t0 = System.nanoTime()
                    val bgra = bitmap.readPixels(bitmap.imageInfo, bitmap.rowBytes, 0, 0)
                    bitmap.close()
                    perf.recordRead(System.nanoTime() - t0)
                    if (bgra != null) h264.submit(bgra)
                }
            }
        } else {
            encodeScope.launch {
                for (bitmap in snapshots) {
                    val t0 = System.nanoTime()
                    val jpeg = Renderer.encode(bitmap)
                    perf.recordEncode(System.nanoTime() - t0, jpeg.size.toLong())
                    frames.emit(VideoFrame(jpeg, isKeyframe = true))
                }
            }
        }

        embeddedServer(Netty, port = port) {
            install(WebSockets)

            routing {
                get("/") { call.respondText(CLIENT_HTML, io.ktor.http.ContentType.Text.Html) }

                get("/health") { call.respondText(hello(), io.ktor.http.ContentType.Application.Json) }

                webSocket("/mirror") {
                    println("→ Cliente conectado (${if (useH264) "H.264" else "JPEG"})")

                    // Primero, cómo interpretar lo que viene después.
                    send(Frame.Text(hello()))

                    // Solo los clientes que se PRESENTAN como nativos reciben
                    // mensajes de foco: el cliente web hace JSON.parse de todo
                    // texto como si fuera la config y se rompería.
                    var nativo = false
                    val focoJob = launch {
                        focoMensajes.collect { if (nativo) send(Frame.Text(it)) }
                    }

                    // Mensajes del cliente: toques, texto del teclado nativo, IME.
                    val incoming = launch {
                        for (frame in this@webSocket.incoming) {
                            if (frame !is Frame.Text) continue
                            val ev = runCatching { Json.decodeFromString<TouchEvent>(frame.readText()) }
                                .getOrNull() ?: continue
                            when (ev.type) {
                                "cliente" -> nativo = true
                                "texto" -> withContext(Dispatchers.Main) {
                                    renderer.escribirTexto(ev.valor.orEmpty())
                                }
                                "ime" -> withContext(Dispatchers.Main) { renderer.accionIme() }
                                else -> withContext(Dispatchers.Main) { sendTouch(ev) }
                            }
                        }
                    }

                    // En H.264 los frames son diferencias: sin un keyframe previo
                    // el decodificador no tiene de dónde partir. Se manda el último.
                    if (useH264) lastKeyframe?.let { send(Frame.Binary(true, withHeader(it, true))) }

                    var waitingForKey = useH264
                    frames.asSharedFlow().collect { frame ->
                        // Descartar diferencias hasta el primer keyframe: decodificarlas
                        // sin referencia produce basura en pantalla.
                        if (waitingForKey && !frame.isKeyframe) return@collect
                        waitingForKey = false
                        send(Frame.Binary(true, withHeader(frame.bytes, frame.isKeyframe)))
                    }
                    incoming.cancel()
                    focoJob.cancel()
                }
            }
        }.start(wait = false)

        println("→ Espejo en http://0.0.0.0:$port  (WebSocket: /mirror)")
    }

    /** Lo que el cliente necesita saber antes del primer frame. */
    private fun hello(): String {
        val w = (widthDp * density).toInt()
        val h = (heightDp * density).toInt()
        return """{"ok":true,"mode":"${if (useH264) "h264" else "jpeg"}",""" +
            """"fps":$fps,"width":$w,"height":$h}"""
    }

    /**
     * Antepone un byte que marca si el frame es clave. WebCodecs exige declararlo
     * en cada chunk, y en el binario del stream esa información no viaja aparte.
     */
    private fun withHeader(bytes: ByteArray, isKeyframe: Boolean): ByteArray =
        ByteArray(bytes.size + 1).also {
            it[0] = if (isKeyframe) 1 else 0
            bytes.copyInto(it, destinationOffset = 1)
        }

    /**
     * Vigila el foco de texto una vez por frame (en el EDT) y emite el cambio.
     * Comparar el JSON evita repetir mensajes idénticos; los rects van en Int
     * para que el jitter subpíxel del layout no meta ruido.
     */
    private fun vigilarFoco() {
        val foco = renderer.focoInfo()
        val json = foco?.let {
            val r = it.rect
            jsonFoco.encodeToString(
                FocoMensaje.serializer(),
                FocoMensaje(
                    valor = it.valor,
                    teclado = it.teclado,
                    accion = it.accionIme,
                    seguro = it.seguro,
                    x = r?.left?.toInt() ?: 0,
                    y = r?.top?.toInt() ?: 0,
                    w = r?.width?.toInt() ?: 0,
                    h = r?.height?.toInt() ?: 0,
                ),
            )
        }
        if (json != ultimoFocoJson) {
            ultimoFocoJson = json
            focoMensajes.tryEmit(json ?: """{"type":"blur"}""")
        }
    }

    /** Debe llamarse en el EDT. */
    private fun sendTouch(ev: TouchEvent) {
        if (!::renderer.isInitialized) return
        val type = when (ev.type) {
            "down" -> PointerEventType.Press
            "move" -> PointerEventType.Move
            "up" -> PointerEventType.Release
            else -> return
        }
        // El dedo sigue apoyado durante el arrastre y se levanta en el release.
        // Sin esto Compose ve puntos sueltos y no reconoce el gesto de scroll.
        val pressed = type != PointerEventType.Release
        renderer.sendPointer(type, Offset(ev.x, ev.y), pressed, System.currentTimeMillis())
    }
}
