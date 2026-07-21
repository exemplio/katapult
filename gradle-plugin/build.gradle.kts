plugins {
    kotlin("jvm")
    `java-gradle-plugin`
    `maven-publish`
}

dependencies {
    implementation(kotlin("gradle-plugin-api"))
    compileOnly("org.jetbrains.kotlin:kotlin-gradle-plugin:2.2.0")
}

gradlePlugin {
    plugins {
        create("katapultMirror") {
            id = "dev.katapult.mirror"
            implementationClass = "dev.katapult.gradle.MirrorPlugin"
            displayName = "Katapult Mirror"
            description = "Espejo de desarrollo: renderiza tu UI Compose Multiplatform en la JVM y la transmite a un dispositivo."
        }
    }
}

kotlin {
    jvmToolchain(17)
}
