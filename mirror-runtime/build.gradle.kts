/**
 * Runtime del espejo de desarrollo (Fase 2).
 *
 * Se publica como librería y lo consumen los proyectos KMP a través del plugin
 * `dev.katapult.mirror`. Contiene el renderizador offscreen (Skia) y el servidor
 * de streaming; el usuario no escribe nada de esto.
 */
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    id("org.jetbrains.compose") version "1.11.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21"
    `maven-publish`
}

dependencies {
    // api: los consumidores necesitan Compose para pasar su @Composable.
    api(compose.runtime)
    api(compose.foundation)
    api(compose.ui)
    implementation(compose.desktop.currentOs)

    // Lifecycle: la escena tiene que proveer un LifecycleOwner RESUMED, o
    // collectAsStateWithLifecycle nunca empieza a recolectar y la UI sale vacía.
    api("org.jetbrains.androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    // Dispatchers.Main en desktop — lo exige androidx.lifecycle.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")

    implementation("io.ktor:ktor-server-core:3.4.3")
    implementation("io.ktor:ktor-server-netty:3.4.3")
    implementation("io.ktor:ktor-server-websockets:3.4.3")

    // Descubrimiento tipo Expo Go: anuncio mDNS/Bonjour + QR con deep link.
    // api: go-runtime reutiliza Anuncio.kt para su propio servidor.
    api("org.jmdns:jmdns:3.6.3")
    api("io.nayuki:qrcodegen:1.8.0")
}

kotlin {
    jvmToolchain(17)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            artifactId = "mirror-runtime"
            from(components["java"])
        }
    }
}
