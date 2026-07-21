plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.3.21")
    // Para aplicar y configurar Zipline desde GoPlugin en el proyecto del usuario.
    implementation("app.cash.zipline:zipline-gradle-plugin:1.27.0")
}

gradlePlugin {
    plugins {
        create("katapultMirror") {
            id = "dev.katapult.mirror"
            implementationClass = "dev.katapult.gradle.MirrorPlugin"
            displayName = "Katapult Mirror"
            description = "Espejo de desarrollo: renderiza tu UI Compose Multiplatform en la JVM y la transmite a un dispositivo."
        }
        create("katapultGo") {
            id = "dev.katapult.go"
            implementationClass = "dev.katapult.gradle.GoPlugin"
            displayName = "Katapult Go"
            description = "Lógica dinámica con Zipline: tu módulo compila a bytecode QuickJS y katapult-go lo recarga en caliente."
        }
    }
}

kotlin {
    jvmToolchain(17)
}
