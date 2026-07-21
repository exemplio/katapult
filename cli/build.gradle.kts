plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    application
}

dependencies {
    implementation("com.github.ajalt.clikt:clikt:4.4.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    // QR en la terminal para el enlace de instalación OTA.
    implementation("com.google.zxing:core:3.5.3")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("dev.katapult.cli.MainKt")
    applicationName = "katapult"
}

kotlin {
    jvmToolchain(17)
}
