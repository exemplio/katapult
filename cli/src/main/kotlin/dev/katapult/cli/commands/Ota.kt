package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import dev.katapult.cli.KatapultConfig
import dev.katapult.cli.capture
import dev.katapult.cli.expandHome
import dev.katapult.cli.fail
import dev.katapult.cli.run
import java.io.File
import java.util.Base64

/**
 * Configura la instalación OTA: sube el certificado de firma a los secrets del
 * repo para que CI pueda firmar y publicar un IPA instalable por aire.
 *
 * Es OPT-IN a propósito. El flujo por defecto firma en local y el certificado
 * nunca sale de tu máquina; esto lo cambia, así que lo pedimos explícitamente.
 */
class Ota : CliktCommand(
    name = "ota",
    help = "Habilita la publicación OTA subiendo el certificado a los secrets del repo."
) {
    private val password by option(
        "--password",
        help = "Contraseña del .p12 (o variable KATAPULT_CERT_PASSWORD)",
        envvar = "KATAPULT_CERT_PASSWORD",
    ).prompt("Contraseña del certificado .p12", hideInput = true)

    override fun run() {
        val cfg = KatapultConfig.loadOrFail()

        val (authCode, _) = capture("gh", "auth", "status")
        if (authCode != 0) fail("Sesión de GitHub inválida. Corre `gh auth login`.")

        val cert = File(expandHome(cfg.certificatePath))
        val profile = File(expandHome(cfg.provisioningProfile))
        if (!cert.exists()) fail("No existe el certificado en ${cert.path}")
        if (!profile.exists()) fail("No existe el provisioning profile en ${profile.path}")

        echo(
            """
            |Esto sube a los secrets de tu repo en GitHub:
            |  · ${cert.name}
            |  · ${profile.name}
            |  · la contraseña del certificado
            |
            |GitHub los guarda cifrados y no aparecen en los logs, pero dejan de
            |estar solo en tu máquina. Cancela con Ctrl-C si prefieres no hacerlo.
            |
            """.trimMargin()
        )

        val b64 = Base64.getEncoder()
        setSecret("KATAPULT_CERT_P12", b64.encodeToString(cert.readBytes()))
        setSecret("KATAPULT_PROVISIONING_PROFILE", b64.encodeToString(profile.readBytes()))
        setSecret("KATAPULT_CERT_PASSWORD", password)

        // La variable activa el job; sin ella el workflow se salta la firma.
        if (run("gh", "variable", "set", "KATAPULT_OTA", "--body", "true") != 0)
            fail("No se pudo activar la variable KATAPULT_OTA.")

        echo(
            """
            |
            |✓ OTA habilitado.
            |
            |Ahora `katapult build ios` además firma en CI y publica un release
            |con el enlace de instalación. Ábrelo desde Safari en el iPhone.
            |
            |Para desactivarlo:  gh variable set KATAPULT_OTA --body false
            """.trimMargin()
        )
    }

    private fun setSecret(name: String, value: String) {
        val p = ProcessBuilder("gh", "secret", "set", name)
            .redirectErrorStream(true)
            .start()
        p.outputStream.bufferedWriter().use { it.write(value) }
        if (p.waitFor() != 0) fail("No se pudo establecer el secret $name")
        echo("  ✓ secret $name")
    }
}
