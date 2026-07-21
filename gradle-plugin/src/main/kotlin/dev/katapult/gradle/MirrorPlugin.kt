package dev.katapult.gradle

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget
import java.io.File

/**
 * Cablea el espejo de desarrollo de Katapult en un proyecto Kotlin Multiplatform.
 *
 * Lo que hace por el usuario (antes había que escribirlo a mano):
 *  1. Añade el target jvm() si no existe.
 *  2. Crea una compilación aparte "katapultMirror" para no ensuciar jvmMain.
 *  3. Genera el main() que arranca el espejo con el @Composable configurado.
 *  4. Añade la dependencia dev.katapult:mirror-runtime.
 *  5. Registra la tarea `katapultMirror`.
 */
class MirrorPlugin : Plugin<Project> {

    override fun apply(project: Project) {
        val ext = project.extensions.create("katapultMirror", MirrorExtension::class.java).apply {
            port.convention(8080)
            // El render cuesta ~3 ms y la codificación va en su propio hilo, así
            // que 60 es sostenible. El techo lo pone el JPEG (~14 ms → ~64 fps).
            fps.convention(60)
            widthDp.convention(390)
            heightDp.convention(844)
            density.convention(2f)
        }

        project.pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") {
            val kotlin = project.extensions.getByType(KotlinMultiplatformExtension::class.java)

            // 1. El espejo corre en la JVM. Si el proyecto no tiene ese target, lo añadimos.
            val jvmTarget = (kotlin.targets.findByName("jvm") as? KotlinJvmTarget)
                ?: kotlin.jvm()

            // 2. Compilación separada: el main generado NO entra en el artefacto jvmMain
            //    del usuario, solo existe para el espejo.
            val mainCompilation = jvmTarget.compilations.getByName("main")
            val mirrorCompilation = jvmTarget.compilations.create("katapultMirror").apply {
                associateWith(mainCompilation)
            }

            // 3. El main generado vive en build/, nunca en el árbol de fuentes del usuario.
            val genDir = File(project.layout.buildDirectory.get().asFile, "generated/katapult-mirror")
            mirrorCompilation.defaultSourceSet.kotlin.srcDir(genDir)

            val generate = project.tasks.register("generateKatapultMirrorMain") { task ->
                task.group = "katapult"
                task.description = "Genera el main() que arranca el espejo."
                task.outputs.dir(genDir)
                task.doFirst {
                    val entry = ext.entryPoint.orNull ?: error(
                        "Falta configurar el composable raíz:\n\n" +
                            "  katapultMirror {\n" +
                            "      entryPoint = \"com.tuapp.App\"\n" +
                            "  }\n"
                    )
                    genDir.mkdirs()
                    File(genDir, "KatapultMirrorMain.kt").writeText(mainSource(entry, ext))
                }
            }

            // 4. El runtime del espejo, con la misma versión que el plugin.
            //    Se declara en el source set, no en la configuración de classpath
            //    (esa no es declarable en Gradle moderno).
            mirrorCompilation.defaultSourceSet.dependencies {
                implementation("dev.katapult:mirror-runtime:$PLUGIN_VERSION")
            }

            // 5. La tarea que lo arranca.
            project.tasks.register("katapultMirror", JavaExec::class.java) { task ->
                task.group = "katapult"
                task.description = "Arranca el espejo de desarrollo y transmite la UI por WebSocket."
                task.dependsOn(generate)
                task.mainClass.set("KatapultMirrorMainKt")
                task.classpath(
                    mirrorCompilation.output.allOutputs,
                    project.provider { mirrorCompilation.runtimeDependencyFiles },
                )
            }

            project.tasks.named(mirrorCompilation.compileKotlinTaskName).configure { it.dependsOn(generate) }
        }
    }

    private fun mainSource(entryPoint: String, ext: MirrorExtension): String {
        val fn = entryPoint.substringAfterLast('.')
        val pkg = entryPoint.substringBeforeLast('.', "")
        val imports = buildList {
            add("import dev.katapult.mirror.startMirror")
            if (pkg.isNotEmpty()) add("import $pkg.$fn")
            ext.initializer.orNull?.let { init ->
                val initPkg = init.substringBeforeLast('.', "")
                if (initPkg.isNotEmpty()) add("import $initPkg.${init.substringAfterLast('.')}")
            }
        }.joinToString("\n")

        // Inicialización previa (DI, logging…) antes de componer nada.
        val initCall = ext.initializer.orNull
            ?.let { "    ${it.substringAfterLast('.')}()\n" }
            .orEmpty()

        return """
            |// Generado por el plugin dev.katapult.mirror — no editar.
            |$imports
            |
            |fun main() {
            |$initCall    startMirror(
            |        port = ${ext.port.get()},
            |        fps = ${ext.fps.get()},
            |        widthDp = ${ext.widthDp.get()},
            |        heightDp = ${ext.heightDp.get()},
            |        density = ${ext.density.get()}f,
            |    ) { $fn() }
            |}
            |
        """.trimMargin()
    }

    private companion object {
        const val PLUGIN_VERSION = "0.1.0"
    }
}
