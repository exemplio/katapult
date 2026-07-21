package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import dev.katapult.cli.fail
import dev.katapult.cli.katapultHome
import dev.katapult.cli.managedZsign
import dev.katapult.cli.run
import java.io.File
import java.net.URI
import java.security.MessageDigest

/**
 * Descarga el binario de zsign a ~/.katapult/bin/, como hace el wrapper de Gradle
 * con su distribución: fuera del repo del proyecto, compartido por todos.
 *
 * zsign es MIT, así que redistribuirlo no tiene problema legal.
 */
class Setup : CliktCommand(
    name = "setup",
    help = "Instala zsign en ~/.katapult/bin/ (descarga el binario oficial y verifica su SHA256)."
) {
    private val force by option("--force", help = "Reinstala aunque ya exista").flag()

    // Versión FIJADA a propósito: usar "latest" haría que un release nuevo
    // cambiara el comportamiento de los builds de todos sin avisar.
    private val version = "v1.1.1"
    private val baseUrl = "https://github.com/zhlynn/zsign/releases/download/$version"

    override fun run() {
        val target = managedZsign()
        if (target.exists() && !force) {
            echo("zsign ya está instalado en ${target.path}")
            echo("Usa --force para reinstalarlo.")
            return
        }

        val asset = assetForPlatform()
        echo("→ Detectado: ${System.getProperty("os.name")} ${System.getProperty("os.arch")} → $asset")

        val tmp = File.createTempFile("katapult-zsign", ".tar.gz").apply { deleteOnExit() }

        echo("→ Descargando zsign $version…")
        val bytes = try {
            URI("$baseUrl/$asset").toURL().openStream().use { it.readBytes() }
        } catch (e: Exception) {
            fail("No se pudo descargar $asset: ${e.message}")
        }
        tmp.writeBytes(bytes)
        echo("  ${bytes.size / 1024} KB")

        echo("→ Verificando SHA256…")
        // Obligatorio: este binario va a manipular el certificado de firma del usuario.
        val expected = fetchExpectedSha(asset)
        val actual = sha256(bytes)
        if (!actual.equals(expected, ignoreCase = true))
            fail("SHA256 no coincide para $asset.\n  esperado: $expected\n  obtenido: $actual")
        echo("  ✓ $actual")

        echo("→ Extrayendo…")
        val binDir = File(katapultHome(), "bin").apply { mkdirs() }
        // --strip-components no sirve: el layout del tar varía entre releases.
        // Extraemos a un temporal y buscamos el ejecutable por nombre.
        val stage = File.createTempFile("katapult-stage", "").let { it.delete(); it.mkdirs(); it }
        if (run("tar", "-xzf", tmp.path, "-C", stage.path) != 0)
            fail("Falló la extracción de $asset")
        val binary = stage.walkTopDown().firstOrNull { it.isFile && it.name == "zsign" }
            ?: fail("No se encontró el ejecutable 'zsign' dentro de $asset")
        binary.copyTo(target, overwrite = true)
        target.setExecutable(true)
        stage.deleteRecursively()

        // Comprobación real: que el binario corra en esta máquina (glibc, etc.).
        val (code, _) = dev.katapult.cli.capture(target.path, "-v")
        if (code != 0 && code != 1)
            fail("zsign se instaló pero no ejecuta correctamente (exit $code). Prueba zsign-linux-musl-static.")

        echo("\n✓ zsign instalado en ${target.path}")
        echo("  Corre `katapult doctor` para confirmar.")
    }

    /** Mapea SO+arquitectura al asset publicado por zsign. */
    private fun assetForPlatform(): String {
        val os = System.getProperty("os.name").lowercase()
        val arch = System.getProperty("os.arch").lowercase()
        return when {
            os.contains("linux") && arch in setOf("amd64", "x86_64") -> "zsign-linux-x86_64.tar.gz"
            os.contains("linux") && arch in setOf("aarch64", "arm64") -> "zsign-linux-aarch64.tar.gz"
            os.contains("linux") && arch.startsWith("arm") -> "zsign-linux-armv7.tar.gz"
            os.contains("mac") && arch in setOf("aarch64", "arm64") -> "zsign-macos-arm64.tar.gz"
            os.contains("mac") -> fail("zsign solo publica binario para macOS ARM64. Compílalo a mano en Intel.")
            os.contains("windows") -> fail("En Windows descarga zsign-windows-x64.zip a mano y apunta zsignPath a él.")
            else -> fail("Plataforma no soportada: $os/$arch. Descarga zsign a mano y ajusta zsignPath.")
        }
    }

    private fun fetchExpectedSha(asset: String): String {
        val sums = try {
            URI("$baseUrl/SHA256SUMS.txt").toURL().readText()
        } catch (e: Exception) {
            fail("No se pudo descargar SHA256SUMS.txt: ${e.message}")
        }
        // Formato sha256sum: "<hash>  <nombre>"
        return sums.lineSequence()
            .firstOrNull { it.trim().endsWith(" $asset") || it.trim().endsWith("  $asset") }
            ?.trim()?.substringBefore(" ")
            ?: fail("No hay entrada para $asset en SHA256SUMS.txt")
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
