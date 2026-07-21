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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** Evento de toque que llega desde el cliente, en coordenadas de píxel. */
@Serializable
data class TouchEvent(val type: String, val x: Float, val y: Float)

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

    fun start() {
        val scope = CoroutineScope(Dispatchers.Main)

        // Bucle de render: vive en el EDT de principio a fin.
        scope.launch {
            renderer = Renderer(widthDp, heightDp, density, content)
            println("→ Escena ${renderer.widthPx}x${renderer.heightPx} px")
            val frameDelay = 1000L / fps
            while (true) {
                frames.emit(renderer.frame())
                delay(frameDelay)
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
