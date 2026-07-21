package dev.katapult.gradle

import org.gradle.api.provider.Property

/**
 * Configuración del modo Go en el build del usuario:
 *
 *     katapultGo {
 *         logica = "com.miapp.golog.MiLogica"
 *     }
 */
abstract class GoExtension {
    /**
     * Nombre completo de la clase que implementa `dev.katapult.go.GoLogica`.
     * Tiene que tener constructor sin argumentos y vivir en código que compile
     * al target js (commonMain o jsMain del módulo donde se aplica el plugin).
     */
    abstract val logica: Property<String>

    /** Puerto del servidor de módulos (goServe/goDev). El espejo usa el 8080. */
    abstract val port: Property<Int>
}
