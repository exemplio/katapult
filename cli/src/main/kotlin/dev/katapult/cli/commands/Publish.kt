package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import dev.katapult.cli.KatapultConfig
import dev.katapult.cli.capture
import dev.katapult.cli.fail
import dev.katapult.cli.run
import java.io.File

/**
 * Publica un IPA **ya firmado en local** como release de GitHub y devuelve el
 * enlace de instalación OTA.
 *
 * La gracia frente a firmar en CI: el certificado nunca sale de tu máquina.
 * Solo se sube el binario ya firmado. iOS exige que el manifest se sirva por
 * HTTPS con certificado válido, y los releases de GitHub lo cumplen.
 */
class Publish : CliktCommand(
    name = "publish",
    help = "Publica la IPA firmada como release y da el enlace para instalarla por aire."
) {
    private val ipaPath by option("--ipa", help = "IPA firmada (por defecto, la última *-firmada.ipa)")
    private val tag by option("--tag", help = "Tag del release (por defecto, uno con marca de tiempo)")

    override fun run() {
        val cfg = KatapultConfig.loadOrFail()

        val (authCode, _) = capture("gh", "auth", "status")
        if (authCode != 0) fail("Sesión de GitHub inválida. Corre `gh auth login`.")

        val ipa = ipaPath?.let(::File)
            ?: File("build/katapult").walkTopDown()
                .filter { it.name.endsWith("-firmada.ipa") }
                .maxByOrNull { it.lastModified() }
            ?: fail("No hay IPA firmada. Corre `katapult sign` primero.")
        if (!ipa.exists()) fail("No existe ${ipa.path}")

        // El repo tiene que ser accesible por HTTPS desde el iPhone.
        val (repoCode, repo) = capture("gh", "repo", "view", "--json", "nameWithOwner", "--jq", ".nameWithOwner")
        if (repoCode != 0 || repo.isBlank()) fail("No se pudo determinar el repo de GitHub.")

        val (visCode, visibility) = capture("gh", "repo", "view", "--json", "visibility", "--jq", ".visibility")
        if (visCode == 0 && visibility.equals("PRIVATE", ignoreCase = true)) {
            echo("⚠️  El repo es privado: los assets del release exigen autenticación,")
            echo("   así que el iPhone no podrá descargarlos. Hazlo público o usa USB.\n")
        }

        val releaseTag = tag ?: "ota-${System.currentTimeMillis() / 1000}"
        val ipaName = "${cfg.appName}.ipa"
        val base = "https://github.com/$repo/releases/download/$releaseTag"

        val work = File(ipa.parentFile, ".katapult-ota").apply { mkdirs() }
        val staged = File(work, ipaName).also { ipa.copyTo(it, overwrite = true) }
        val manifest = File(work, "manifest.plist").apply {
            writeText(manifestPlist("$base/$ipaName", cfg.bundleId, cfg.appName, releaseTag))
        }

        echo("→ Publicando release $releaseTag…")
        val created = run(
            "gh", "release", "create", releaseTag,
            staged.path, manifest.path,
            "--title", "${cfg.appName} (OTA)",
            "--notes", "Instalación por aire. Abre el enlace desde Safari en el iPhone.",
        )
        if (created != 0) fail("No se pudo crear el release.")

        val installUrl = "itms-services://?action=download-manifest&url=$base/manifest.plist"

        echo("\n✓ Publicado: https://github.com/$repo/releases/tag/$releaseTag")
        echo("\n📲 Abre ESTE enlace desde Safari en el iPhone:\n")
        echo("   $installUrl\n")
        echo("Escanea para abrirlo:\n")
        echo(qr(installUrl))
    }

    private fun manifestPlist(ipaUrl: String, bundleId: String, title: String, version: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
        <plist version="1.0">
        <dict><key>items</key><array><dict>
          <key>assets</key><array><dict>
            <key>kind</key><string>software-package</string>
            <key>url</key><string>$ipaUrl</string>
          </dict></array>
          <key>metadata</key><dict>
            <key>bundle-identifier</key><string>$bundleId</string>
            <key>bundle-version</key><string>$version</string>
            <key>kind</key><string>software</string>
            <key>title</key><string>$title</string>
          </dict>
        </dict></array></dict>
        </plist>

    """.trimIndent()

    /** QR en la terminal, para no tener que teclear la URL en el teléfono. */
    private fun qr(text: String): String {
        val hints = mapOf(
            EncodeHintType.MARGIN to 1,
            EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
        )
        val m = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, hints)
        val sb = StringBuilder()
        // Dos filas por carácter con medios bloques: así el QR sale cuadrado en
        // una terminal, donde los caracteres son el doble de altos que de anchos.
        var y = 0
        while (y < m.height) {
            sb.append("  ")
            for (x in 0 until m.width) {
                val top = m.get(x, y)
                val bottom = y + 1 < m.height && m.get(x, y + 1)
                // Invertido a propósito: el módulo "oscuro" del QR se pinta como
                // espacio claro, que es lo que las cámaras leen en terminal oscura.
                sb.append(
                    when {
                        top && bottom -> ' '
                        top -> '▄'
                        bottom -> '▀'
                        else -> '█'
                    }
                )
            }
            sb.append('\n')
            y += 2
        }
        return sb.toString()
    }
}
