package dev.katapult.cli

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Configuración del proyecto del usuario, persistida en katapult.json
 * en la raíz de su repo (análogo a app.json de Expo).
 */
@Serializable
data class KatapultConfig(
    val appName: String,
    val bundleId: String,
    /** Scheme de Xcode. El wizard oficial de KMP genera "iosApp". */
    val scheme: String = "iosApp",
    /** Ruta al .xcodeproj relativa a la raíz del repo. */
    val xcodeProject: String = "iosApp/iosApp.xcodeproj",
    /**
     * Imagen del runner macOS. Determina la versión de Xcode y, con ella, el SDK
     * de iOS disponible. Compose Multiplatform sigue de cerca los SDK de Apple:
     * CMP 1.11+ referencia símbolos de UIKit que solo existen en el SDK de iOS 26,
     * así que necesita macos-26 (Xcode 26). Con macos-15 (Xcode 16.4 / SDK 18.5)
     * el enlazado falla con "Undefined symbols ... _OBJC_CLASS_$_UIViewLayoutRegion".
     */
    val runnerImage: String = "macos-26",
    /** Nombre del archivo de workflow que dispara katapult build. */
    val workflowFile: String = "katapult-ios.yml",
    /** Nombre del artifact que sube el workflow. */
    val artifactName: String = "ios-unsigned-ipa",
    /** Kit de firma local (fuera del repo). */
    val zsignPath: String = "~/Downloads/zsign/zsign",
    val certificatePath: String = "~/Downloads/zsign/certificado.p12",
    val provisioningProfile: String = "~/Downloads/zsign/test.mobileprovision",
) {
    companion object {
        const val FILE_NAME = "katapult.json"
        // encodeDefaults: escribe TODOS los campos en katapult.json para que el
        // usuario descubra qué puede configurar (runnerImage, scheme, rutas de firma…).
        private val json = Json { prettyPrint = true; ignoreUnknownKeys = true; encodeDefaults = true }

        fun load(dir: File = File(".")): KatapultConfig? {
            val f = File(dir, FILE_NAME)
            return if (f.exists()) json.decodeFromString(serializer(), f.readText()) else null
        }

        fun loadOrFail(dir: File = File(".")): KatapultConfig =
            load(dir) ?: error("No existe $FILE_NAME en este directorio. Corre `katapult init` primero.")
    }

    fun save(dir: File = File(".")) {
        File(dir, FILE_NAME).writeText(json.encodeToString(serializer(), this) + "\n")
    }
}
