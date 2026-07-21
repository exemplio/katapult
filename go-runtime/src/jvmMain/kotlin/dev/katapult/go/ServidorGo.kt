package dev.katapult.go

import dev.katapult.mirror.anunciarServicio
import dev.katapult.mirror.imprimirQrAcceso
import dev.katapult.mirror.ipLan
import io.ktor.server.engine.embeddedServer
import io.ktor.server.http.content.staticFiles
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import java.io.File

/**
 * El servidor de módulos de Katapult Go: sirve los `.zipline` compilados y se
 * anuncia por mDNS para que katapult-go lo liste sin teclear la IP.
 *
 * Sustituye a `serveDevelopmentZipline` (que es de Zipline y no podemos hacer
 * que se anuncie). Servir es trivial —ficheros estáticos, el loader hace
 * polling del manifest— así que el par de tareas queda:
 *
 *   :go-runtime:compileDevelopmentExecutableKotlinJsZipline --continuous
 *   :go-runtime:goServe
 */
fun main(args: Array<String>) {
    val dir = File(args.firstOrNull() ?: error("falta el directorio de módulos zipline"))
    val puerto = args.getOrNull(1)?.toIntOrNull() ?: 8081
    // --watch <raizDelRepo>: lanza la compilación continua como subproceso,
    // para que `goDev` sea UN solo comando (guardas → recompila → el iPhone
    // recarga). Sin él, goServe solo sirve y la recompilación va aparte.
    val indiceWatch = args.indexOf("--watch")
    val raizWatch = if (indiceWatch >= 0) File(args[indiceWatch + 1]) else null

    embeddedServer(Netty, port = puerto) {
        routing {
            // Se sirve del disco en cada petición: cuando la compilación
            // continua reescribe los ficheros, no hay nada que invalidar.
            staticFiles("/", dir)
        }
    }.start(wait = false)

    raizWatch?.let { raiz ->
        // El daemon de Gradle ya está caliente (esta JVM la arrancó él), así
        // que el gradle anidado no pelea por los locks de arranque en frío.
        val vigilante = ProcessBuilder(
            File(raiz, "gradlew").absolutePath,
            ":go-runtime:compileDevelopmentExecutableKotlinJsZipline",
            "--continuous",
        )
            .directory(raiz)
            .redirectErrorStream(true)
            .start()
        // Sus líneas van a esta misma terminal, prefijadas para distinguirlas.
        Thread {
            vigilante.inputStream.bufferedReader().forEachLine { linea ->
                if (linea.isNotBlank()) println("[compila] $linea")
            }
        }.apply { isDaemon = true }.start()
        Runtime.getRuntime().addShutdownHook(Thread { vigilante.destroy() })
        println("→ Vigilando cambios en jsMain: guarda y el iPhone recarga solo.")
    }

    println("→ Módulos Zipline en http://0.0.0.0:$puerto (dir: $dir)")

    anunciarServicio(modo = "go", puerto = puerto, extras = mapOf("path" to "/manifest.zipline.json"))
    ipLan()?.let { ip ->
        val url = "http://${ip.hostAddress}:$puerto/manifest.zipline.json"
        println("→ En el iPhone: elige el servidor en Katapult Go, o escanea:")
        imprimirQrAcceso(modo = "go", url = url)
    }

    Thread.currentThread().join()
}
