package dev.katapult.gradle

import org.gradle.api.provider.Property

/**
 * Configuración del espejo en el build del usuario:
 *
 *     katapultMirror {
 *         entryPoint = "com.miapp.App"
 *     }
 */
abstract class MirrorExtension {
    /**
     * Nombre completo del @Composable raíz, sin paréntesis.
     * Ej: "com.jetbrains.kmpapp.App" para `fun App()` en el paquete kmpapp.
     */
    abstract val entryPoint: Property<String>

    /**
     * Función sin argumentos que hay que llamar ANTES de componer: inicialización
     * de DI, logging, etc. Opcional.
     * Ej: "com.miapp.di.initKoin"
     */
    abstract val initializer: Property<String>

    /** Puerto del servidor del espejo. */
    abstract val port: Property<Int>

    /** Frames por segundo que se transmiten. */
    abstract val fps: Property<Int>

    /** Tamaño lógico de la pantalla simulada, en dp. */
    abstract val widthDp: Property<Int>
    abstract val heightDp: Property<Int>

    /** Densidad de pantalla (2.0 = @2x, típico de un iPhone). */
    abstract val density: Property<Float>
}
