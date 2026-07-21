package dev.katapult.gradle

import app.cash.zipline.gradle.ZiplineExtension
import app.cash.zipline.gradle.ZiplineServeTask
import java.io.File
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

/**
 * Cablea el modo Go de Katapult en un proyecto Kotlin Multiplatform: la lógica
 * del módulo compila a bytecode QuickJS y katapult-go la descarga y recarga en
 * caliente. El gemelo del MirrorPlugin, para la Fase 3.
 *
 * Lo que hace por el usuario:
 *  1. Añade el target js() si no existe (browser + executable, lo exige Zipline).
 *  2. Aplica el plugin de Zipline y fija su mainFunction.
 *  3. Añade la dependencia dev.katapult:go-runtime (el contrato GoLogica).
 *  4. Genera el main() con @JsExport que registra la lógica del usuario
 *     (la trampa del @JsExport ya nos costó una tarde: aquí queda enterrada).
 *  5. Registra goServe y goDev, con el mismo servidor que anuncia por mDNS.
 *
 * OJO: aplicarlo a un módulo SIN UI de Compose. El código del módulo entero
 * viaja a QuickJS, donde no hay DOM ni canvas: Compose no puede ir ahí.
 */
class GoPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("katapultGo", GoExtension::class.java).apply {
            // -PgoPort=NNNN gana sobre el convenio; katapultGo { port = … } gana sobre ambos.
            port.convention((project.findProperty("goPort") as? String)?.toIntOrNull() ?: 8081)
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            project.pluginManager.apply("app.cash.zipline")

            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

            // 1. La lógica viaja como JS. binaries.executable() es obligatorio:
            //    sin él no hay main() que empaquetar en el .zipline.
            if (kotlin.targets.findByName("js") == null) {
                kotlin.js {
                    browser()
                    binaries.executable()
                }
            }

            // 3. El contrato, visible desde commonMain del usuario.
            kotlin.sourceSets.getByName("commonMain").dependencies {
                api("dev.katapult:go-runtime:$PLUGIN_VERSION")
            }

            // 4. El main() generado vive en build/, nunca en el árbol del usuario.
            val genDir = File(project.layout.buildDirectory.get().asFile, "generated/katapult-go")
            kotlin.sourceSets.getByName("jsMain").kotlin.srcDir(genDir)

            val generate = project.tasks.register("generateKatapultGoMain") { task ->
                task.group = "katapult"
                task.description = "Genera el main() @JsExport que registra la lógica en Zipline."
                task.outputs.dir(genDir)
                task.doFirst {
                    val logica = ext.logica.orNull ?: error(
                        "Falta configurar la lógica dinámica:\n\n" +
                            "  katapultGo {\n" +
                            "      logica = \"com.tuapp.golog.MiLogica\"\n" +
                            "  }\n",
                    )
                    genDir.mkdirs()
                    File(genDir, "KatapultGoMain.kt").writeText(mainSource(logica))
                }
            }
            project.tasks.withType(org.jetbrains.kotlin.gradle.tasks.Kotlin2JsCompile::class.java)
                .configureEach { it.dependsOn(generate) }

            // 2. Zipline invoca esta función al cargar el módulo en QuickJS.
            project.extensions.configure(ZiplineExtension::class.java) {
                it.mainFunction.set("katapult.go.generado.main")
            }
            project.tasks.withType(ZiplineServeTask::class.java).configureEach {
                it.port.set(ext.port)
            }

            // 5. El servidor con anuncio mDNS + QR viene del runtime publicado;
            //    no hace falta que el proyecto del usuario lo declare.
            val servidorCp = project.configurations.detachedConfiguration(
                project.dependencies.create("dev.katapult:go-runtime-jvm:$PLUGIN_VERSION"),
            )
            val devDir = project.layout.buildDirectory.dir("zipline/Development")
            val tareaZipline = "compileDevelopmentExecutableKotlinJsZipline"

            project.tasks.register("goServe", JavaExec::class.java) { task ->
                task.group = "katapult"
                task.description = "Sirve la lógica Zipline y anuncia el servidor por mDNS (con QR)."
                task.dependsOn(tareaZipline)
                task.mainClass.set("dev.katapult.go.ServidorGoKt")
                task.classpath(servidorCp)
                task.args(devDir.get().asFile.absolutePath, ext.port.get().toString())
            }

            project.tasks.register("goDev", JavaExec::class.java) { task ->
                task.group = "katapult"
                task.description = "Todo en uno: sirve + anuncia por mDNS + recompila la lógica al guardar."
                task.dependsOn(tareaZipline)
                task.mainClass.set("dev.katapult.go.ServidorGoKt")
                task.classpath(servidorCp)
                task.args(
                    devDir.get().asFile.absolutePath, ext.port.get().toString(),
                    "--watch", project.rootDir.absolutePath,
                    "${project.path}:$tareaZipline",
                )
            }
        }
    }

    private fun mainSource(logica: String): String {
        val clase = logica.substringAfterLast('.')
        val paquete = logica.substringBeforeLast('.', "")
        val importLogica = if (paquete.isNotEmpty()) "import $logica" else ""

        return """
            |// Generado por el plugin dev.katapult.go — no editar.
            |package katapult.go.generado
            |
            |import app.cash.zipline.Zipline
            |import dev.katapult.go.GoLogica
            |$importLogica
            |
            |// Sin @JsExport, QuickJS no encuentra la función y el anfitrión falla
            |// con "cannot read property 'go' of undefined".
            |@OptIn(ExperimentalJsExport::class)
            |@JsExport
            |fun main() {
            |    Zipline.get().bind<GoLogica>("goLogica", $clase())
            |}
            |
        """.trimMargin()
    }

    private companion object {
        const val PLUGIN_VERSION = "0.1.0"
    }
}
