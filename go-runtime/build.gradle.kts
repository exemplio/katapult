/**
 * Katapult Go — Paso 0 (Fase 3): lógica dinámica con Zipline, UI fija en el anfitrión.
 *
 * Es un MODO ADICIONAL de renderizar, no un reemplazo del espejo: el espejo
 * transmite píxeles (la app corre en la JVM), esto ejecuta el código *en el
 * dispositivo* dentro de QuickJS. Ver docs/KATAPULT_GO_PASO_0.md.
 *
 * Tres source sets, tres papeles:
 *  - commonMain: el contrato (interfaces ZiplineService + modelos serializables).
 *  - jsMain:     la lógica que VIAJA — se compila a .zipline y se descarga en caliente.
 *  - jvmMain:    el anfitrión de prueba en Linux; katapult-go (iOS) hará este papel después.
 *
 * Zipline 1.22.0 es la versión construida con Kotlin 2.2.0, la de este repo.
 * Su plugin de compilador exige que coincidan: no subir una sin la otra.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("app.cash.zipline") version "1.22.0"
}

kotlin {
    jvmToolchain(17)
    jvm()
    js {
        browser()
        // Sin executable() no hay main() que empaquetar en el .zipline.
        binaries.executable()
    }
    // Solo el dispositivo real: sin Mac no hay simulador que valga.
    // En Linux esto compila hasta el klib; el enlace del framework lo hace CI.
    iosArm64 {
        binaries.framework {
            baseName = "GoRuntime"
            // Estática: se enlaza dentro del binario de la app y no hay que
            // embeber nada en el bundle (embed: false en project.yml).
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api("app.cash.zipline:zipline:1.22.0")
        }
        // El anfitrión es el mismo en JVM y en iOS; solo cambia el HTTP client
        // (OkHttp / URLSession). Mismo reparto que el sample world-clock de Zipline.
        val hostMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("app.cash.zipline:zipline-loader:1.22.0")
            }
        }
        jvmMain {
            dependsOn(hostMain)
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:5.1.0")
            }
        }
        // iosArm64Main directamente: con un único target iOS no hay jerarquía
        // intermedia que valga, y los dependsOn manuales ya desactivan el
        // template por defecto (el warning de Gradle sobre esto es esperado).
        val iosArm64Main by getting {
            dependsOn(hostMain)
        }
    }
}

zipline {
    // La función que QuickJS invoca al cargar el módulo (está en jsMain).
    mainFunction.set("dev.katapult.go.main")
}

// El espejo ya usa el 8080; el servidor de módulos Zipline va en el 8081 para
// poder correr ambos a la vez (son modos complementarios, no excluyentes).
tasks.withType<app.cash.zipline.gradle.ZiplineServeTask>().configureEach {
    port.set(8081)
}

// Anfitrión de consola para validar el ciclo desde Linux sin iPhone de por medio.
val jvmCompilation = (kotlin.targets.getByName("jvm") as org.jetbrains.kotlin.gradle.targets.jvm.KotlinJvmTarget)
    .compilations.getByName("main")

tasks.register<JavaExec>("goHost") {
    group = "katapult"
    description = "Arranca el anfitrión JVM que descarga y ejecuta la lógica Zipline."
    dependsOn(jvmCompilation.compileTaskProvider)
    mainClass.set("dev.katapult.go.HostKt")
    classpath(jvmCompilation.output.allOutputs, project.provider { jvmCompilation.runtimeDependencyFiles })
}
