package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import dev.katapult.cli.KatapultConfig
import dev.katapult.cli.capture
import dev.katapult.cli.expandHome
import dev.katapult.cli.resolveZsign
import dev.katapult.cli.which
import java.io.File

class Doctor : CliktCommand(
    name = "doctor",
    help = "Verifica que las herramientas necesarias estén instaladas y configuradas."
) {
    override fun run() {
        val cfg = KatapultConfig.load()
        var problems = 0

        fun check(label: String, ok: Boolean, hint: String) {
            if (ok) echo("  ✓ $label")
            else { echo("  ✗ $label — $hint"); problems++ }
        }

        echo("Katapult doctor\n")

        // Java (necesario para Gradle del proyecto KMP del usuario)
        check("java", which("java") != null, "instala un JDK 17+")

        // gh: presencia y sesión
        val gh = which("gh") != null
        check("gh (GitHub CLI)", gh, "instálalo: https://cli.github.com")
        if (gh) {
            val (authCode, _) = capture("gh", "auth", "status")
            check("gh auth", authCode == 0, "sesión inválida o vencida: corre `gh auth login`")
        }

        // Kit de firma
        val zsign = resolveZsign(cfg?.zsignPath)
        check("zsign", zsign != null, "no instalado — corre `katapult setup`")
        if (zsign != null) echo("      ${zsign.path}")
        val cert = expandHome(cfg?.certificatePath ?: "~/Downloads/zsign/certificado.p12")
        check("certificado .p12", File(cert).exists(), "no encontrado en $cert")
        val profile = expandHome(cfg?.provisioningProfile ?: "~/Downloads/zsign/test.mobileprovision")
        check("provisioning profile", File(profile).exists(), "no encontrado en $profile")

        // Instalación por USB
        check("ideviceinstaller", which("ideviceinstaller") != null, "sudo apt install ideviceinstaller")

        // Config del proyecto
        check(KatapultConfig.FILE_NAME, cfg != null, "corre `katapult init` en la raíz de tu proyecto KMP")

        echo(if (problems == 0) "\nTodo listo 🚀" else "\n$problems problema(s) por resolver.")
    }
}
