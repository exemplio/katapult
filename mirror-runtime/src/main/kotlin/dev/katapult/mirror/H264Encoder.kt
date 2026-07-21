package dev.katapult.mirror

import java.io.DataOutputStream
import java.io.EOFException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Codificador H.264 apoyado en un proceso externo de ffmpeg.
 *
 * ¿Por qué un proceso y no una librería? Las bindings de libwebrtc/ffmpeg para
 * la JVM arrastran cientos de MB de binarios nativos por plataforma. Un pipe a
 * ffmpeg no añade ninguna dependencia al artefacto y es trivial de depurar:
 * el mismo comando se puede pegar en una terminal.
 *
 * Entra BGRA crudo por stdin, sale H.264 en Annex B por stdout. El lector parte
 * ese flujo en unidades de acceso (un frame cada una) porque WebCodecs necesita
 * recibir frames completos, no bytes sueltos.
 *
 * Medido sobre 300 frames reales a 780x1688:
 *   JPEG calidad 70 → 84 Mbps, 71 fps de techo
 *   H.264 veryfast → 2,06 Mbps, 156 fps de techo
 */
class H264Encoder(
    private val width: Int,
    private val height: Int,
    private val fps: Int,
    /** Se invoca por cada frame codificado, desde el hilo lector. */
    private val onFrame: (bytes: ByteArray, isKeyframe: Boolean) -> Unit,
) {
    private val running = AtomicBoolean(true)
    private lateinit var process: Process
    private lateinit var input: DataOutputStream

    fun start() {
        val cmd = listOf(
            ffmpegPath(),
            "-hide_banner", "-loglevel", "error",
            // Entrada: los píxeles tal cual salen de Skia (N32Premul = BGRA en
            // little-endian), sin cabecera ni contenedor.
            "-f", "rawvideo",
            "-pix_fmt", "bgra",
            "-s", "${width}x$height",
            "-r", "$fps",
            "-i", "-",
            // Salida: H.264 baseline, que es lo que confirmó el sondeo del iPhone.
            "-c:v", "libx264",
            "-preset", "veryfast",      // 156 fps medidos: 2,6x de margen sobre 60
            "-tune", "zerolatency",     // sin B-frames ni lookahead: nada de buffer
            "-profile:v", "baseline",
            "-pix_fmt", "yuv420p",
            "-g", "${fps * 2}",         // un keyframe cada 2 s, para reenganchar rápido
            "-bf", "0",
            // Delimitadores de unidad de acceso: marcan dónde empieza cada frame,
            // que es justo lo que necesita el parser de abajo.
            "-bsf:v", "h264_metadata=aud=insert",
            "-f", "h264", "-",
        )

        process = ProcessBuilder(cmd)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        // Ojo con los nombres: outputStream del proceso es la ENTRADA de ffmpeg,
        // e inputStream es su SALIDA. Van al revés de lo que sugiere el nombre.
        input = DataOutputStream(process.outputStream.buffered(1 shl 20))

        Thread({ readLoop(process.inputStream) }, "katapult-h264-reader").apply {
            isDaemon = true
            start()
        }
    }

    /** Encola un frame BGRA. Bloquea si ffmpeg va por detrás, lo que frena el render. */
    fun submit(bgra: ByteArray) {
        if (!running.get()) return
        try {
            input.write(bgra)
            input.flush()
        } catch (e: java.io.IOException) {
            if (running.get()) System.err.println("⚠️  ffmpeg cerró la entrada: ${e.message}")
            running.set(false)
        }
    }

    fun close() {
        running.set(false)
        runCatching { input.close() }
        runCatching { process.destroy() }
    }

    /**
     * Parte el flujo Annex B en unidades de acceso.
     *
     * Annex B separa cada NAL con el prefijo 00 00 01 (o 00 00 00 01). Un frame
     * puede ocupar varias NAL —SPS, PPS, SEI, la porción de imagen—, así que se
     * acumulan hasta ver el delimitador (tipo 9), que abre el frame siguiente.
     */
    private fun readLoop(out: InputStream) {
        val chunk = ByteArray(1 shl 16)
        var pending = ByteArray(0)

        try {
            while (running.get()) {
                val n = out.read(chunk)
                if (n < 0) throw EOFException("ffmpeg cerró la salida")
                pending += chunk.copyOf(n)

                // Cada delimitador abre un frame nuevo, así que todo lo anterior
                // es un frame completo. Puede haber varios en un mismo chunk.
                //
                // Se busca a partir de AU_MIN_BYTES, no del principio: `pending`
                // empieza por el delimitador del frame en curso, y buscarlo desde
                // el índice 0 lo encontraría a sí mismo — cortando un frame vacío
                // sin avanzar, o sea un bucle infinito.
                while (true) {
                    val next = indexOfAud(pending, from = AU_MIN_BYTES)
                    if (next <= 0) break
                    emit(pending.copyOf(next))
                    pending = pending.copyOfRange(next, pending.size)
                }
            }
        } catch (e: Exception) {
            if (running.get()) System.err.println("⚠️  Lector de ffmpeg terminado: ${e.message}")
        }
    }

    private fun emit(accessUnit: ByteArray) {
        if (accessUnit.isEmpty()) return
        // Un frame es clave si contiene una NAL de tipo 5 (IDR).
        val isKey = containsNal(accessUnit, type = 5)
        runCatching { onFrame(accessUnit, isKey) }
            .onFailure { System.err.println("⚠️  Error enviando frame: ${it.message}") }
    }

    /** Posición del siguiente delimitador de unidad de acceso (NAL tipo 9). */
    private fun indexOfAud(data: ByteArray, from: Int): Int = indexOfNal(data, from, type = 9)

    private fun containsNal(data: ByteArray, type: Int) = indexOfNal(data, 0, type) >= 0

    /**
     * Busca `00 00 01 <nal>` con el tipo pedido. El prefijo largo `00 00 00 01`
     * termina en el corto, así que basta con buscar este.
     */
    private fun indexOfNal(data: ByteArray, from: Int, type: Int): Int {
        var i = from.coerceAtLeast(0)
        while (i + 3 < data.size) {
            if (data[i] == 0.toByte() && data[i + 1] == 0.toByte() && data[i + 2] == 1.toByte() &&
                (data[i + 3].toInt() and 0x1F) == type
            ) {
                // Incluir el 00 extra del prefijo largo, si lo hay.
                return if (i > 0 && data[i - 1] == 0.toByte()) i - 1 else i
            }
            i++
        }
        return -1
    }

    companion object {
        /**
         * Bytes que ocupa como mínimo un delimitador con su prefijo largo
         * (00 00 00 01 09). Buscar el siguiente a partir de aquí evita
         * reencontrar el del frame en curso.
         */
        private const val AU_MIN_BYTES = 5

        /** ffmpeg tiene que estar en el PATH. `katapult doctor` debería avisarlo. */
        fun ffmpegPath(): String = System.getenv("KATAPULT_FFMPEG") ?: "ffmpeg"

        fun isAvailable(): Boolean = runCatching {
            ProcessBuilder(ffmpegPath(), "-version")
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor() == 0
        }.getOrDefault(false)
    }
}
