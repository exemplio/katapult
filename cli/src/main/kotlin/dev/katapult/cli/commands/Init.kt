package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.default
import com.github.ajalt.clikt.parameters.options.flag
import com.github.ajalt.clikt.parameters.options.option
import com.github.ajalt.clikt.parameters.options.prompt
import dev.katapult.cli.KatapultConfig
import dev.katapult.cli.fail
import java.io.File

class Init : CliktCommand(
    name = "init",
    help = "Genera katapult.json y el workflow de GitHub Actions en el repo actual."
) {
    private val appName by option("--name", help = "Nombre de la app").prompt("Nombre de la app")
    private val bundleId by option("--bundle-id", help = "Bundle id iOS").prompt("Bundle id (ej. com.miapp.app)")
    private val scheme by option("--scheme", help = "Scheme de Xcode").default("iosApp")
    private val xcodeProject by option("--xcode-project", help = "Ruta al .xcodeproj")
        .default("iosApp/iosApp.xcodeproj")
    private val force by option("--force", help = "Sobrescribe archivos existentes").flag()

    override fun run() {
        val cfg = KatapultConfig(
            appName = appName,
            bundleId = bundleId,
            scheme = scheme,
            xcodeProject = xcodeProject,
        )

        // katapult.json
        val cfgFile = File(KatapultConfig.FILE_NAME)
        if (cfgFile.exists() && !force) fail("Ya existe ${cfgFile.name}. Usa --force para sobrescribir.")
        cfg.save()
        echo("  ✓ ${cfgFile.name}")

        // Workflow desde la plantilla embebida
        val template = javaClass.getResourceAsStream("/templates/ios-workflow.yml")
            ?.bufferedReader()?.readText()
            ?: fail("Plantilla ios-workflow.yml no encontrada en el jar")
        val rendered = template
            .replace("__APP_NAME__", cfg.appName)
            .replace("__SCHEME__", cfg.scheme)
            .replace("__XCODE_PROJECT__", cfg.xcodeProject)
            .replace("__ARTIFACT_NAME__", cfg.artifactName)
            .replace("__RUNNER_IMAGE__", cfg.runnerImage)
            .replace("__BUNDLE_ID__", cfg.bundleId)

        val wfFile = File(".github/workflows/${cfg.workflowFile}")
        if (wfFile.exists() && !force) fail("Ya existe ${wfFile.path}. Usa --force para sobrescribir.")
        wfFile.parentFile.mkdirs()
        wfFile.writeText(rendered)
        echo("  ✓ ${wfFile.path}")

        echo(
            """
            |
            |Listo. Siguientes pasos:
            |  1. Commitea y pushea el workflow a GitHub.
            |  2. katapult build ios   → compila el IPA sin firmar en Actions
            |  3. katapult sign        → firma local con zsign
            |  4. katapult install     → instala en el iPhone por USB
            """.trimMargin()
        )
    }
}
