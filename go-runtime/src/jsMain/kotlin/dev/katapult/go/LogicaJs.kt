package dev.katapult.go

import app.cash.zipline.Zipline

/**
 * ESTE es el código que viaja: se compila a Kotlin/JS, el plugin de Zipline lo
 * convierte en bytecode de QuickJS (.zipline) y el anfitrión lo descarga.
 *
 * Con `./gradlew :go-runtime:goDev` corriendo, edita cualquier cosa de esta
 * clase y guarda: el iPhone recarga solo, sin reinstalar nada.
 */
class LogicaReal : GoLogica {
    // Estado de LA LÓGICA, no del anfitrión: vive aquí, en QuickJS, y se
    // pierde en cada recarga (como el estado de JS en Expo Go).
    private var nombre = ""
    private var borrador = ""
    private var contador = 0

    override fun pantalla() = GoPantalla(
        titulo = "Mini-app interactiva 🎛️",
        elementos = buildList {
            add(
                GoElemento.Texto(
                    if (nombre.isBlank()) "¿Cómo te llamas?" else "¡Hola, $nombre! 👋",
                    estilo = EstiloTexto.TITULO,
                ),
            )
            add(GoElemento.Campo(id = "nombre", pista = "escribe tu nombre", valor = borrador))
            add(GoElemento.Boton(id = "saludar", etiqueta = "Saludar", estilo = EstiloBoton.PROMINENTE))
            add(GoElemento.Separador)
            add(GoElemento.Texto("Contador: $contador", estilo = EstiloTexto.SUBTITULO))
            add(
                GoElemento.Fila(
                    listOf(
                        GoElemento.Boton(id = "sumar", etiqueta = "+1"),
                        GoElemento.Boton(id = "reiniciar", etiqueta = "Reiniciar", estilo = EstiloBoton.DESTRUCTIVO),
                    ),
                ),
            )
        },
    )

    override fun evento(id: String, valor: String?) {
        when (id) {
            "nombre" -> borrador = valor.orEmpty()
            "saludar" -> nombre = borrador
            "sumar" -> contador++
            "reiniciar" -> {
                contador = 0
                nombre = ""
                borrador = ""
            }
        }
    }
}

// Sin @JsExport, Kotlin/JS IR no expone la función y QuickJS no puede invocarla:
// el anfitrión falla con "cannot read property 'katapult' of undefined".
@OptIn(ExperimentalJsExport::class)
@JsExport
fun main() {
    val zipline = Zipline.get()
    zipline.bind<GoLogica>("goLogica", LogicaReal())
}
