// Kotlin 2.3.21 en todo el repo: la MISMA versión que katapult-demo, porque el
// plugin de compilador de Zipline corre en el build del usuario y el bytecode
// tiene que casar con el loader que va dentro del IPA. Par actual:
// Kotlin 2.3.21 ↔ Zipline 1.27.0. No subir uno sin el otro.
plugins {
    kotlin("jvm") version "2.3.21" apply false
    kotlin("multiplatform") version "2.3.21" apply false
    kotlin("plugin.serialization") version "2.3.21" apply false
}

allprojects {
    group = "dev.katapult"
    version = "0.1.0"
}
