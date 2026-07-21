# CLAUDE.md

Guía para Claude Code al trabajar en este repositorio.

## Qué es Katapult

Herramientas para desarrollar apps **Kotlin Multiplatform con Compose para iOS
desde Linux, sin un Mac**. Son dos mitades que resuelven problemas distintos:

| Mitad | Para qué | Dónde corre |
|---|---|---|
| **CLI** (`:cli`) | compilar, firmar e instalar el IPA nativo | CI + local |
| **Espejo** (`:mirror-runtime`, `:gradle-plugin`) | iterar UI al instante | 100% local |

**Modelo BYO-CI:** no es un servicio centralizado tipo EAS. Los builds corren en
los GitHub Actions **de la cuenta del usuario**, con sus minutos. El workflow se
genera dentro de *su* proyecto con `katapult init`; nada pasa por servidores
ajenos. La firma es siempre local: el certificado no sale de la máquina.

## Idioma y convenciones

- **Todo en español**: comentarios, mensajes del CLI, documentación, commits.
- Los comentarios explican **por qué**, no qué. Varios documentan bugs que
  costaron horas; no los borres al refactorizar.
- **Nunca hacer `git push`.** El usuario pushea a mano, siempre.
- **No publicar releases ni subir artefactos sin pedir permiso.** El usuario
  tiene su propia plataforma para instalar IPAs firmados por él.

## Estructura

```
cli/              CLI con Clikt. Comandos: init, build, sign, install,
                  doctor, setup, publish, ota
mirror-runtime/   Servidor del espejo: render Compose en JVM + H.264 + WebSocket
gradle-plugin/    Dos plugins para proyectos KMP: "dev.katapult.mirror" (espejo)
                  y "dev.katapult.go" (lógica dinámica: añade target js, aplica
                  Zipline, genera el main @JsExport y registra goServe/goDev).
                  Ejemplo real: módulo logica-go de katapult-demo.
                  Los servidores se anuncian por mDNS (_katapult._tcp,
                  Anuncio.kt) e imprimen QR con deep links katapult://; la app
                  los descubre con NWBrowser (Descubridor.swift)
go-runtime/       Fase 3: lógica dinámica con Zipline. commonMain = contrato,
                  jsMain = lógica que viaja, hostMain = anfitrión compartido,
                  jvmMain = consola Linux, iosArm64Main = GoAnfitrion para Swift
katapult-go/      App iOS en Swift (XcodeGen). Dos modos: Espejo (WKWebView del
                  cliente web) y Go (Zipline, vía GoRuntime.framework)
docs/             Referencias de decisiones. Ver OPCION_D_*.md
```

**Proyecto de pruebas: `~/katapult-demo`** (repo privado, plantilla KMP de
JetBrains). Es donde se verifica todo de punta a punta.

## La restricción que define el proyecto

En iOS **no se puede** tener a la vez: una sola app instalada + cargarle
cualquier proyecto + rendimiento nativo. Apple permite código interpretado pero
prohíbe descargar y ejecutar código **nativo**, y Compose compila su UI a código
máquina. Expo Go puede porque en React Native la UI se declara en JS.

Esto se investigó a fondo: antes de tocar nada de "Expo Go para Compose", lee
[docs/OPCION_D_EXPO_GO_PARA_COMPOSE.md](docs/OPCION_D_EXPO_GO_PARA_COMPOSE.md).
Resumen: la arquitectura viable es Redwood + Zipline (Treehouse), Cash App la
construyó, y **discontinuó Redwood en enero de 2026** mientras sigue manteniendo
Zipline.

En julio de 2026 se decidió **retomar la vía Zipline poco a poco**, como modo
adicional de katapult-go (el espejo queda intacto y sigue siendo la forma
principal de iterar UI). El paso 0 —ciclo de despliegue con lógica dinámica y
UI fija— ya funciona en JVM: módulo `:go-runtime`, ver
[docs/KATAPULT_GO_PASO_0.md](docs/KATAPULT_GO_PASO_0.md). La línea roja sigue
siendo la de la Opción D: si algún día hay catálogo de widgets, no pasar de
~20 o se está reimplementando Compose.

## Números medidos (julio 2026)

No son estimaciones; salen de ejecuciones reales. Si tocas algo de esto,
vuelve a medir antes de afirmar mejoras.

**Build iOS en CI** (`katapult-demo`, runner `macos-26`):

| | Base | Debug + cachés | En caliente |
|---|---|---|---|
| xcodebuild | 12m46s | 5m11s | **2m36s** |
| Total | 14m29s | 8m42s | **5m23s** |

El recorte grande vino de compilar en **Debug** (Kotlin/Native en Release hace
optimización de programa completo). El caché de tareas de Gradle sí ayuda al
trabajo de Kotlin/Native, contra lo que cabría suponer.

**Espejo** (escena 780x1688 px):

| | JPEG | H.264 |
|---|---|---|
| fps | 59,7 | 59,3 |
| KB/frame | 180 | **0,2 – 5** |
| Ancho de banda | 84 Mbps | **0,1 – 2,5 Mbps** |

Coste por frame en el EDT: render 2,0 ms + copia 0,7 ms + lectura 1,9 ms.

## Cómo se ejecuta cada cosa

```bash
# CLI
./gradlew :cli:installDist
./build/install/katapult/bin/katapult doctor

# Espejo (desde el proyecto del usuario, p. ej. ~/katapult-demo)
./gradlew :shared:katapultMirror     # sirve en :8080, imprime líneas [perf]
# y se abre desde el iPhone en http://<ip-lan>:8080

# Publicar los módulos para que katapult-demo los resuelva
./gradlew :mirror-runtime:publishToMavenLocal :gradle-plugin:publishToMavenLocal

# Katapult Go (lógica dinámica Zipline) — el comando único de desarrollo:
# sirve en :8081 + anuncia por mDNS + QR + recompila jsMain al guardar
./gradlew :go-runtime:goDev
# piezas sueltas: goServe (solo servir+anunciar), goHost (anfitrión JVM de
# consola), serveDevelopmentZipline --continuous (la de Zipline, sin mDNS).
# Mismo puerto 8081 todas (-PgoPort=NNNN en goDev/goServe): solo una a la vez.

# Verificar el código iOS desde Linux (solo klib; el framework lo enlaza CI)
./gradlew :go-runtime:compileKotlinIosArm64

# Build + firma + instalación de katapult-go, todo en uno (gasta minutos de
# Actions del usuario: no dispararlo sin que lo pida). Pasos sueltos dentro.
./scripts/actualizar-go.sh
```

El catálogo de widgets del modo Go (13 piezas, contenedores anidables) vive en
go-runtime/…/GoElemento.kt + katapult-go/Sources/GoCatalogo.swift; diseño y
criterio de crecimiento en docs/CATALOGO_GO_PROPUESTA.md. Añadir una pieza =
cambio de contrato: publishToMavenLocal + IPA nuevo, y los campos nuevos
SIEMPRE con valor por defecto (hay apps viejas instaladas).

## Arquitectura del espejo

```
EDT (AWT)                    hilo codificador           hilo lector de ffmpeg
─────────                    ────────────────           ─────────────────────
Renderer.snapshot()   ──►    lee píxeles BGRA    ──►    parsea Annex B
render 2ms + copia 1ms       y los mete a ffmpeg        y emite frames
       │                                                        │
       └── canal CONFLATED ───────────────────────────►  WebSocket → cliente
```

Reglas que hay que respetar:

- **Todo lo que toca la escena va en `Dispatchers.Main` (el EDT)**: render y
  eventos de puntero. `androidx.lifecycle` y `navigation` lo exigen.
- La codificación **nunca** en el EDT. Bloquearlo tira los fps.
- El canal es *conflated* a propósito: si el codificador se atrasa, se descarta
  el frame viejo en vez de acumular latencia. `onUndeliveredElement` cierra el
  bitmap descartado — es memoria nativa que el GC no ve.

## Trampas que ya costaron horas

No son hipotéticas: todas se manifestaron y se arreglaron aquí.

1. **`scene.render()` sin `nanoTime`** congela el reloj de frames: la UI se
   queda en su primer estado aunque el estado cambie, **sin dar error**. Hay que
   pasar `System.nanoTime() - startNanos`.
2. **`ImageComposeScene` toma PÍXELES, no dp.** Para un iPhone de 390x844 @2x
   son 780x1688.
3. **Sin `LifecycleOwner` en estado RESUMED**, `collectAsStateWithLifecycle`
   nunca recolecta y la pantalla sale vacía, otra vez sin error.
4. **Los toques deben ir como `PointerType.Touch` con `pressed = true`.** La
   variante corta de `sendPointerEvent` asume ratón, y un movimiento de ratón
   sin botón es *hover*: el scroll no se dispara nunca. Y `timeMillis` tiene que
   avanzar, o no hay inercia al soltar.
5. **Annex B: buscar el delimitador desde el índice 0 lo encuentra a sí mismo**
   → frame vacío, el buffer no avanza, bucle infinito y ni un solo frame. Hay
   que buscar a partir del byte 5.
6. **`Process.outputStream` es la ENTRADA del proceso** e `inputStream` su
   salida. Van al revés de lo que sugiere el nombre.
7. **Compose MP 1.11 necesita el SDK de iOS 26** (`macos-26`). Con `macos-15`
   falla el enlazado: `Undefined symbols ... _OBJC_CLASS_$_UIViewLayoutRegion`.
8. **El espejo añade un target `jvm` nuevo**, que necesita sus propias
   dependencias de plataforma (p. ej. un engine de Ktor). No es un bug: es
   consecuencia de tener un target más.
9. En el CLI, usar `fail()` (de `Proc.kt`), **nunca `error()`** — este último
   lanza un stack trace de Java en la cara del usuario.

## Dependencias externas

- **`gh`** con sesión activa — para disparar y descargar builds.
- **`zsign`** — firma local. `katapult setup` lo descarga a `~/.katapult/bin/`.
- **`ffmpeg`** — el espejo lo usa para H.264. Si no está, cae a JPEG
  automáticamente (40x más tráfico) y lo avisa en el HUD del cliente.
  Pendiente: que `katapult doctor` lo detecte.

## Pendiente

- **Publicar el plugin** (portal de Gradle o Maven Central). Mientras no lo esté,
  `katapult-demo` lo resuelve por `mavenLocal`, y su workflow lleva un paso
  temporal que clona este repo en el runner para publicarlo. Ese andamiaje
  desaparece al publicar, y cuesta ~17s de CI.
- **Bajar de 5 min el build iOS**: quitar el andamiaje y afinar qué entra en el
  caché de Gradle (el paso "Setup de Gradle" subió a 79s al crecer el caché).
- **Medir el bitrate de H.264 con movimiento real** — los 0,1-2,5 Mbps son con
  la app en reposo. Con scroll y animaciones sube; cuánto, no se sabe.
- **Decodificación nativa en Katapult Go** con VideoToolbox. Hoy la app es un
  WKWebView, así que abrirla o abrir Safari es exactamente lo mismo; solo
  aportará cuando decodifique en nativo.
- **Adaptar la resolución al dispositivo**: el espejo renderiza fijo a 390x844
  @2x, pero el iPhone del usuario es 430x932 @3x — se está escalando y se ve
  menos nítido de lo que podría. El cliente debería informar su tamaño.
- **Actualizar el README**, cuyo roadmap (Fase 3 con Zipline) quedó desmentido.
- Reservar la organización `katapult-dev` en GitHub.

## Estrategia acordada

Ante la imposibilidad de un Expo Go para Compose, el reparto es:

- **Android o desktop** para iterar — mismo `commonMain`, segundos por ciclo.
- **Espejo** para ver la UI en el iPhone real al instante (no sirve para juzgar
  rendimiento: son píxeles renderizados en el PC).
- **iOS nativo** como verificación, no como iteración.

Katapult se centra en lo que nadie más resuelve: **el pipeline iOS sin Mac**.
Competir con Compose Hot Reload (estable e incluido desde CMP 1.10, aunque solo
en desktop/JVM) no tiene sentido.
