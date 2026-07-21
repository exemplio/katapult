package dev.katapult.go

import app.cash.zipline.loader.ManifestVerifier.Companion.NO_SIGNATURE_CHECKS
import app.cash.zipline.loader.ZiplineLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.MainScope
import platform.Foundation.NSURLSession

/**
 * La cara del anfitrión hacia Swift. katapult-go crea uno, llama [arrancar]
 * con la URL del manifest (la IP del PC del usuario) y pinta cada [GoInforme]
 * que le llega en SwiftUI. Eso es todo el contrato.
 *
 * Todo corre en Dispatchers.Main: QuickJS exige un solo hilo, y en iOS el hilo
 * principal es el único que ya existe seguro. El callback llega también en Main,
 * así que Swift puede tocar UI directamente.
 */
class GoAnfitrion {
    private val scope = MainScope()
    private var trabajo: Job? = null
    private val control = GoControl()

    fun arrancar(manifestUrl: String, alCambiar: (GoInforme) -> Unit) {
        detener()
        val dispatcher = Dispatchers.Main
        val loader = ZiplineLoader(
            dispatcher = dispatcher,
            // Sin firma en desarrollo, igual que el host JVM.
            manifestVerifier = NO_SIGNATURE_CHECKS,
            urlSession = NSURLSession.sharedSession,
        )
        trabajo = arrancarGo(scope, dispatcher, loader, manifestUrl, control) { estado ->
            when (estado) {
                is GoEstado.Esperando -> alCambiar(GoInforme(null, 0, estado.detalle))
                is GoEstado.Corriendo -> alCambiar(GoInforme(estado.pantalla, estado.version, null))
            }
        }
    }

    /**
     * Un toque o un texto del usuario, de Swift hacia la lógica. Llamar desde
     * el hilo principal (SwiftUI ya vive ahí); el repintado llega por el
     * callback de [arrancar].
     */
    fun evento(id: String, valor: String?) {
        control.evento(id, valor)
    }

    fun detener() {
        trabajo?.cancel()
        trabajo = null
    }
}

/**
 * Aplana [GoEstado] para Swift: los sealed interface de Kotlin cruzan mal a
 * Objective-C (pierden el `when` exhaustivo), una clase plana no pierde nada.
 * Si [pantalla] es null, estamos esperando y [detalle] dice por qué.
 */
class GoInforme(
    val pantalla: GoPantalla?,
    val version: Int,
    val detalle: String?,
)
