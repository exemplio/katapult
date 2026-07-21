package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import dev.katapult.cli.KatapultConfig
import dev.katapult.cli.expandHome
import dev.katapult.cli.fail
import dev.katapult.cli.resolveZsign
import dev.katapult.cli.run
import java.io.File

class Sign : CliktCommand(
    name = "sign",
    help = "Firma el IPA localmente con zsign (mismo flujo que docs/IOS_SIDELOAD_BUILD.md)."
) {
    private val ipaPath by option("--ipa", help = "IPA a firmar (por defecto, el último de build/katapult/)")
    private val password by option(
        "--password",
        help = "Contraseña del .p12 (o variable KATAPULT_CERT_PASSWORD)",
        envvar = "KATAPULT_CERT_PASSWORD",
    ).prompt("Contraseña del certificado .p12", hideInput = true)

    override fun run() {
        val cfg = KatapultConfig.loadOrFail()

        val ipa = ipaPath?.let(::File)
            ?: File("build/katapult").walkTopDown()
                .filter { it.extension == "ipa" }
                .maxByOrNull { it.lastModified() }
            ?: fail("No hay IPA en build/katapult/. Corre `katapult build ios` primero o pasa --ipa.")
        if (!ipa.exists()) fail("No existe ${ipa.path}")

        val zsign = (resolveZsign(cfg.zsignPath)
            ?: fail("No se encontró zsign. Corre `katapult setup` para instalarlo.")).path
        val cert = expandHome(cfg.certificatePath)
        val profile = expandHome(cfg.provisioningProfile)
        val out = File(ipa.parentFile, "${cfg.appName}-firmada.ipa")

        echo("→ Firmando ${ipa.name} con zsign…")
        // Espejo del comando documentado: zsign -k cert.p12 -p PASS -m profile -o out.ipa -z 9 in.ipa
        val code = run(
            zsign,
            "-k", cert,
            "-p", password,
            "-m", profile,
            "-o", out.path,
            "-z", "9",
            ipa.path,
        )
        if (code != 0) fail("zsign falló (exit $code).")

        echo("\n✓ IPA firmada: ${out.path}")
        echo("  Siguiente paso: katapult install")
    }
}
