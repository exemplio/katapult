package dev.katapult.cli

import com.github.ajalt.clikt.core.CliktError
import java.io.File

/**
 * Aborta con un mensaje limpio en stderr y código de salida 1.
 * Usar en vez de error()/require(), que dejan escapar un stack trace de Java.
 */
fun fail(message: String): Nothing = throw CliktError(message)

/** Ejecuta un comando mostrando su salida en vivo (para gh run watch, zsign, etc.). */
fun run(vararg cmd: String, dir: File? = null): Int =
    ProcessBuilder(*cmd)
        .directory(dir)
        .inheritIO()
        .start()
        .waitFor()

/** Ejecuta un comando capturando stdout+stderr. Devuelve (exitCode, salida). */
fun capture(vararg cmd: String, dir: File? = null): Pair<Int, String> {
    val p = ProcessBuilder(*cmd)
        .directory(dir)
        .redirectErrorStream(true)
        .start()
    val out = p.inputStream.bufferedReader().readText().trim()
    return p.waitFor() to out
}

/** Ruta absoluta de un binario en PATH, o null si no existe. */
fun which(bin: String): String? =
    capture("which", bin).let { (code, out) -> if (code == 0) out else null }

/** Expande ~ al home del usuario. */
fun expandHome(path: String): String =
    if (path.startsWith("~/")) System.getProperty("user.home") + path.drop(1) else path

/** Directorio de datos de Katapult, por máquina (no por proyecto). */
fun katapultHome(): File =
    File(System.getProperty("user.home"), ".katapult").apply { mkdirs() }

/** zsign gestionado por `katapult setup`. */
fun managedZsign(): File = File(katapultHome(), "bin/zsign")

/**
 * Resuelve qué zsign usar, en orden de preferencia:
 *   1. el que el usuario configuró explícitamente (si existe)
 *   2. el instalado por `katapult setup`
 *   3. uno que esté en el PATH
 * Devuelve null si no hay ninguno.
 */
fun resolveZsign(configured: String?): File? {
    configured?.let { File(expandHome(it)) }
        ?.takeIf { it.canExecute() }
        ?.let { return it }
    managedZsign().takeIf { it.canExecute() }?.let { return it }
    return which("zsign")?.let(::File)?.takeIf { it.canExecute() }
}
