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

/** Evento de toque que llega desde el cliente, en coordenadas de píxel. */
@Serializable
data class TouchEvent(val type: String, val x: Float, val y: Float)

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
    private var encode = 0L
    private var bytes = 0L
    private var windowStart = System.nanoTime()

    @Synchronized
    fun recordRender(renderNanos: Long, copyNanos: Long) {
        render += renderNanos
        copy += copyNanos
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
        println(
            "[perf] ${"%.1f".format(fps)} fps · render ${ms(render)}ms · copia ${ms(copy)}ms · " +
                "jpeg ${ms(encode)}ms · ${bytes / frames / 1024}KB/frame · ${"%.0f".format(mbps)} Mbps"
        )
        frames = 0; render = 0; copy = 0; encode = 0; bytes = 0
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
    private val frames = MutableSharedFlow<ByteArray>(replay = 1, extraBufferCapacity = 2)
    private lateinit var renderer: Renderer
    private val perf = Perf()

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
                // Descontar lo ya gastado; si nos pasamos del periodo, no dormimos.
                val spentMs = (System.nanoTime() - loopStart) / 1_000_000
                delay((framePeriod - spentMs).coerceAtLeast(0))
            }
        }

        // Codificador: fuera del EDT, en un único hilo para no reordenar frames.
        @OptIn(ExperimentalCoroutinesApi::class)
        CoroutineScope(Dispatchers.Default.limitedParallelism(1)).launch {
            for (bitmap in snapshots) {
                val t0 = System.nanoTime()
                val jpeg = Renderer.encode(bitmap)
                perf.recordEncode(System.nanoTime() - t0, jpeg.size.toLong())
                frames.emit(jpeg)
            }
        }

        embeddedServer(Netty, port = port) {
            install(WebSockets)

            routing {
                get("/") { call.respondText(CLIENT_HTML, io.ktor.http.ContentType.Text.Html) }

                get("/health") {
                    call.respondText("""{"ok":true,"fps":$fps}""", io.ktor.http.ContentType.Application.Json)
                }

                webSocket("/mirror") {
                    println("→ Cliente conectado")

                    // Los toques del cliente se inyectan en la escena (en el EDT).
                    val incoming = launch {
                        for (frame in this@webSocket.incoming) {
                            if (frame !is Frame.Text) continue
                            val ev = runCatching { Json.decodeFromString<TouchEvent>(frame.readText()) }
                                .getOrNull() ?: continue
                            withContext(Dispatchers.Main) { sendTouch(ev) }
                        }
                    }

                    // Frames hacia el cliente.
                    frames.asSharedFlow().collect { jpeg ->
                        send(Frame.Binary(true, jpeg))
                    }
                    incoming.cancel()
                }
            }
        }.start(wait = false)

        println("→ Espejo en http://0.0.0.0:$port  (WebSocket: /mirror)")
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
        renderer.sendPointer(type, Offset(ev.x, ev.y))
    }
}
