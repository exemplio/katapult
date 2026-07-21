package dev.katapult.go

import app.cash.zipline.Zipline

/**
 * ESTE es el código que viaja: se compila a Kotlin/JS, el plugin de Zipline lo
 * convierte en bytecode de QuickJS (.zipline) y el anfitrión lo descarga.
 *
 * Edita cualquier cosa de esta clase con `serveDevelopmentZipline --continuous`
 * corriendo y el anfitrión la recarga solo, sin reinstalar nada.
 */
class LogicaReal : GoLogica {
    override fun pantalla(contador: Int) = GoPantalla(
        titulo = "Hola desde QuickJS 🚀",
        lineas = listOf(
            "Este texto llegó por la red, compilado a bytecode.",
            "Edita LogicaJs.kt y guarda: el anfitrión recarga solo.",
            "Ticks del anfitrión: $contador",
        ),
    )
}

// Sin @JsExport, Kotlin/JS IR no expone la función y QuickJS no puede invocarla:
// el anfitrión falla con "cannot read property 'katapult' of undefined".
@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
    val zipline = Zipline.get()
    zipline.bind<GoLogica>("goLogica", LogicaReal())
}
