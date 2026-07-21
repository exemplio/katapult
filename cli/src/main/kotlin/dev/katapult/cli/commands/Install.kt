package dev.katapult.cli.commands

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.parameters.options.option
import dev.katapult.cli.capture
import dev.katapult.cli.fail
import dev.katapult.cli.run
import java.io.File

class Install : CliktCommand(
    name = "install",
    help = "Instala la IPA firmada en el iPhone conectado por USB (ideviceinstaller)."
) {
    private val ipaPath by option("--ipa", help = "IPA a instalar (por defecto, la última *-firmada.ipa)")

    override fun run() {
        val ipa = ipaPath?.let(::File)
            ?: File("build/katapult").walkTopDown()
                .filter { it.name.endsWith("-firmada.ipa") }
                .maxByOrNull { it.lastModified() }
            ?: fail("No hay IPA firmada en build/katapult/. Corre `katapult sign` primero o pasa --ipa.")
        if (!ipa.exists()) fail("No existe ${ipa.path}")

        val (code, devices) = capture("idevice_id", "-l")
        if (code != 0 || devices.isBlank())
            fail("No se detecta ningún iPhone por USB. Conéctalo, desbloquéalo y acepta 'Confiar en este equipo'.")
        echo("→ Dispositivo detectado: ${devices.lines().first()}")

        echo("→ Instalando ${ipa.name}…")
        if (run("ideviceinstaller", "-i", ipa.path) != 0)
            fail("ideviceinstaller falló. Alternativa: instala con Feather desde el teléfono.")

        echo("\n✓ Instalada. Revisa el iPhone 🚀")
    }
}
