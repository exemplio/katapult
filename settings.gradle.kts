rootProject.name = "katapult"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
    }
}

include(":cli")             // la herramienta de línea de comandos (Fase 1)
include(":mirror-runtime")  // servidor de espejo que consumen los proyectos (Fase 2)
include(":gradle-plugin")   // plugin que cablea el espejo en un proyecto KMP
include(":go-runtime")      // lógica dinámica con Zipline para katapult-go (Fase 3, paso 0)
