package dev.katapult.cli

import com.github.ajalt.clikt.core.CliktCommand
import com.github.ajalt.clikt.core.subcommands
import dev.katapult.cli.commands.Build
import dev.katapult.cli.commands.Doctor
import dev.katapult.cli.commands.Init
import dev.katapult.cli.commands.Install
import dev.katapult.cli.commands.Setup
import dev.katapult.cli.commands.Sign

class Katapult : CliktCommand(
    name = "katapult",
    help = """
        Compila, firma e instala apps iOS de Kotlin Multiplatform sin un Mac.

        Modelo BYO-CI: los builds corren en los GitHub Actions de TU cuenta;
        la firma (zsign) y la instalación (USB) son locales.
    """.trimIndent(),
) {
    override fun run() = Unit
}

fun main(args: Array<String>) = Katapult()
    .subcommands(Doctor(), Setup(), Init(), Build(), Sign(), Install())
    .main(args)
