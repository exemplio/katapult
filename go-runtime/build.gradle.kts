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
 * Par de versiones: Kotlin 2.3.21 ↔ Zipline 1.27.0 (construido con 2.3.20).
 * La MISMA que katapult-demo: el plugin de compilador de Zipline corre en el
 * build del usuario y el bytecode tiene que casar con el loader del IPA.
 */
plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("app.cash.zipline") version "1.27.0"
    `maven-publish`
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
            api("app.cash.zipline:zipline:1.27.0")
        }
        // El anfitrión es el mismo en JVM y en iOS; solo cambia el HTTP client
        // (OkHttp / URLSession). Mismo reparto que el sample world-clock de Zipline.
        val hostMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("app.cash.zipline:zipline-loader:1.27.0")
            }
        }
        jvmMain {
            dependsOn(hostMain)
            dependencies {
                implementation("com.squareup.okhttp3:okhttp:5.1.0")
                // Anuncio mDNS + QR (Anuncio.kt). Arrastra las deps de Compose
                // del espejo, que aquí sobran pero no estorban.
                implementation(project(":mirror-runtime"))
                implementation("io.ktor:ktor-server-core:3.4.3")
                implementation("io.ktor:ktor-server-netty:3.4.3")
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
    // -PgoManifest=http://… para apuntar a otro servidor (p. ej. el del demo).
    providers.gradleProperty("goManifest").orNull?.let { args(it) }
}

// -PgoPort=8082 para cambiar el puerto (p. ej. para probar sin pisar el 8081).
val goPort = providers.gradleProperty("goPort").getOrElse("8081")
val ziplineDevDir = layout.buildDirectory.dir("zipline/Development")

tasks.register<JavaExec>("goServe") {
    group = "katapult"
    description = "Sirve los módulos Zipline en :$goPort y anuncia el servidor por mDNS (con QR)."
    // Compila la lógica una vez; para recompilar al guardar, corre en paralelo
    // compileDevelopmentExecutableKotlinJsZipline --continuous (o usa goDev).
    dependsOn(jvmCompilation.compileTaskProvider, "compileDevelopmentExecutableKotlinJsZipline")
    mainClass.set("dev.katapult.go.ServidorGoKt")
    classpath(jvmCompilation.output.allOutputs, project.provider { jvmCompilation.runtimeDependencyFiles })
    args(ziplineDevDir.get().asFile.absolutePath, goPort)
}

tasks.register<JavaExec>("goDev") {
    group = "katapult"
    description = "Todo en uno: sirve + anuncia por mDNS + recompila la lógica al guardar."
    dependsOn(jvmCompilation.compileTaskProvider, "compileDevelopmentExecutableKotlinJsZipline")
    mainClass.set("dev.katapult.go.ServidorGoKt")
    classpath(jvmCompilation.output.allOutputs, project.provider { jvmCompilation.runtimeDependencyFiles })
    args(
        ziplineDevDir.get().asFile.absolutePath, goPort,
        "--watch", rootProject.rootDir.absolutePath,
        "${project.path}:compileDevelopmentExecutableKotlinJsZipline",
    )
}
