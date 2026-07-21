package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.arguments.argument
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.types.choice
import dev.katapult.cli.KatapultConfig
import dev.katapult.cli.capture
import dev.katapult.cli.fail
import dev.katapult.cli.run
import java.io.File

class Build : CliktCommand(
    name = "build",
    help = "Dispara la compilación en GitHub Actions (cuenta del usuario) y descarga el IPA."
) {
    private val platform by argument(help = "Plataforma destino").choice("ios")

    /**
     * Kotlin/Native en Release optimiza el programa completo, y eso es lo que
     * domina el tiempo de compilación. Para iterar no hace falta: el binario
     * Debug corre nativo igual, solo sin optimizar.
     */
    private val release by option(
        "--release",
        help = "Compila optimizado (más lento). Por defecto Debug, que es mucho más rápido.",
    ).flag()

    override fun run() {
        val cfg = KatapultConfig.loadOrFail()

        // Sesión de gh válida — falla temprano con mensaje claro.
        val (authCode, _) = capture("gh", "auth", "status")
        if (authCode != 0) fail("Sesión de GitHub inválida. Corre `gh auth login` y reintenta.")

        // Run previo más reciente, para detectar el nuevo tras el dispatch.
        val previous = latestRunId(cfg.workflowFile)

        val configuration = if (release) "Release" else "Debug"
        echo("→ Disparando ${cfg.workflowFile} en GitHub Actions ($configuration)…")
        if (run("gh", "workflow", "run", cfg.workflowFile, "-f", "configuration=$configuration") != 0)
            fail("No se pudo disparar el workflow. ¿Está pusheado ${cfg.workflowFile} en el default branch?")

        // GitHub registra el run con un pequeño retraso.
        echo("→ Esperando a que aparezca el run…")
        var runId: String? = null
        repeat(15) {
            Thread.sleep(3000)
            val id = latestRunId(cfg.workflowFile)
            if (id != null && id != previous) { runId = id; return@repeat }
        }
        val id = runId ?: fail("El run no apareció tras 45s. Revisa `gh run list` a mano.")

        echo("→ Run $id en curso (esto tarda varios minutos en un runner macOS)…")
        if (run("gh", "run", "watch", id, "--exit-status") != 0)
            fail("El build falló. Logs: gh run view $id --log-failed")

        val outDir = File("build/katapult").apply { mkdirs() }
        // gh run download aborta si el archivo ya existe, así que retiramos el
        // IPA sin firmar del build anterior. El firmado no se toca.
        outDir.listFiles()?.filter { it.name.endsWith("-unsigned.ipa") }?.forEach { it.delete() }
        echo("→ Descargando artifact '${cfg.artifactName}'…")
        if (run("gh", "run", "download", id, "--name", cfg.artifactName, "--dir", outDir.path) != 0)
            fail("No se pudo descargar el artifact.")

        val ipa = outDir.walkTopDown().firstOrNull { it.extension == "ipa" }
        echo("\n✓ IPA sin firmar: ${ipa?.path ?: outDir.path}")
        echo("  Siguiente paso: katapult sign")
    }

    private fun latestRunId(workflow: String): String? {
        val (code, out) = capture(
            "gh", "run", "list", "--workflow", workflow,
            "--limit", "1", "--json", "databaseId", "--jq", ".[0].databaseId"
        )
        return if (code == 0 && out.isNotBlank() && out != "null") out else null
    }
}
