# Katapult Go — Paso 0: lógica dinámica con Zipline

> Estado: **funcionando en JVM** (julio 2026). Es el paso 0 del plan descrito en
> [OPCION_D_EXPO_GO_PARA_COMPOSE.md](OPCION_D_EXPO_GO_PARA_COMPOSE.md), adoptado
> deliberadamente como **modo adicional** de katapult-go. El espejo sigue intacto:
> son complementarios, no excluyentes.

## Qué es

El ciclo Expo Go aplicado a Kotlin, en su forma mínima:

```
guardas un .kt (jsMain) ──► Kotlin/JS ──► bytecode QuickJS (.zipline)
                                               │  HTTP :8081
       anfitrión (binario fijo, instalado) ◄───┘
       detecta el manifest nuevo y recarga la lógica EN CALIENTE
```

El anfitrión nunca se reinstala. Lo que viaja por la red es bytecode
interpretado (permitido por Apple); la UI vive fija en el binario.

## Los papeles del módulo `:go-runtime`

| Source set | Papel | Viaja |
|---|---|---|
| `commonMain` | El contrato: `GoLogica` (ZiplineService) + `GoPantalla` (modelo serializable) | no |
| `jsMain` | La lógica de la app. Se compila a `.zipline` y se descarga | **sí** |
| `hostMain` | El anfitrión compartido (`arrancarGo`): descarga, ejecuta, recarga | no |
| `jvmMain` | Anfitrión de consola en Linux (loader OkHttp) | no |
| `iosArm64Main` | `GoAnfitrion`: el mismo anfitrión para Swift (loader URLSession) | no |

## Cómo se ejecuta

**Un solo comando** (el equivalente a `npx expo start`):

```bash
./gradlew :go-runtime:goDev
```

Hace las tres cosas: sirve los módulos en :8081, se anuncia por mDNS (+ QR en
la terminal), y vigila jsMain — guardas y el iPhone recarga solo. El vigilante
es un `gradlew … --continuous` que `ServidorGo` lanza como subproceso (sus
líneas salen prefijadas con `[compila]`).

Piezas sueltas, si se necesitan por separado:

```bash
./gradlew :go-runtime:goServe    # solo servir + anunciar (sin recompilación)
./gradlew :go-runtime:goHost     # anfitrión JVM de consola (probar sin iPhone)
./gradlew :go-runtime:compileDevelopmentExecutableKotlinJsZipline --continuous
./gradlew :go-runtime:serveDevelopmentZipline --continuous  # la de Zipline:
                                 # compila y sirve, pero no se anuncia por mDNS
```

Todas las variantes de servidor usan el puerto 8081 (`-PgoPort=NNNN` para
cambiarlo en goDev/goServe) — solo una a la vez.

Con ambos corriendo, edita algo visible en
`go-runtime/src/jsMain/kotlin/dev/katapult/go/LogicaJs.kt` y guarda: el
servidor recompila solo y el anfitrión pinta la versión nueva (`lógica v2`)
sin reiniciarse. Ese es todo el punto.

El puerto es **8081** (el 8080 es del espejo, para poder correr ambos a la vez).

## Trampas ya pisadas aquí

1. **`main()` de jsMain necesita `@JsExport`.** Kotlin/JS IR no exporta símbolos
   por defecto; sin la anotación QuickJS falla con
   `cannot read property 'katapult' of undefined` y no hay más pista que esa.
2. **Zipline y Kotlin van emparejados.** Par actual: Zipline 1.27.0 ↔ Kotlin
   2.3.21 (el CHANGELOG de Zipline documenta el mapeo), alineado con
   katapult-demo porque el plugin de compilador corre en el build del usuario.
   Subir Kotlin exige subir Zipline a la vez, y viceversa — y cambiar el par
   exige IPA nuevo (el bytecode debe casar con el loader del binario).
3. **El freshness checker se llama `DefaultFreshnessCheckerNotFresh`**: nunca da
   por fresco el caché → siempre consulta la red. Es lo correcto en desarrollo.
4. **QuickJS no es thread-safe**: todo Zipline vive en un único hilo
   (`newSingleThreadExecutor`). El loader y el `take()` van en ese dispatcher.
5. **No lances dos Gradle a la vez en frío.** La build continua del servidor
   retiene el lock (`Cannot lock Build Output Cleanup Cache`); si pasa,
   `./gradlew --stop` y relanzar en orden.
6. **El polling es re-emitir la URL** en un `Flow` cada 500 ms; el loader
   compara el manifest y solo recarga si el código cambió de verdad.

## Qué valida y qué NO valida este paso

Valida: el ciclo completo de despliegue (compilar → servir → descargar →
ejecutar → recargar), la fricción baja, y desde el mini-catálogo también la
**interactividad**: la lógica declara la pantalla como datos
(`GoElemento.Texto/Boton/Campo`), guarda su propio estado en QuickJS, y los
toques/textos vuelven por `GoLogica.evento(id, valor)` → repintado inmediato.

No valida: el catálogo de verdad (paso 1). Tres elementos sin layout ni estilos
no son Compose; son la maqueta del techo estructural: **elemento nuevo =
release de la app**. Cambiar el contrato (`GoLogica.kt` en commonMain) exige
recompilar IPA y lógica a la vez — todo lo demás es recarga en caliente.

## El anfitrión iOS (hecho, pendiente de compilar en CI)

katapult-go tiene ahora **dos modos** en su pantalla de conexión: **Espejo**
(píxeles por WebSocket, como siempre) y **Go (Zipline)**, que ejecuta la lógica
en el dispositivo:

- `go-runtime/src/iosArm64Main/.../GoAnfitrion.kt` — la cara hacia Swift:
  `arrancar(manifestUrl) { informe -> … }` sobre `Dispatchers.Main` (QuickJS
  exige un solo hilo; el callback llega en Main y SwiftUI puede pintar directo).
- `katapult-go/Sources/GoHostView.swift` — la UI fija que pinta cada
  `GoPantalla` y muestra `lógica vN` como prueba visible de recarga.
- El framework `GoRuntime` se enlaza en CI
  (`./gradlew :go-runtime:linkReleaseFrameworkIosArm64`, ya en
  `katapult-go.yml`): Kotlin/Native **solo enlaza** binarios iOS en macOS.
  Desde Linux se compila hasta el klib con
  `kotlin.native.enableKlibsCrossCompilation=true` (en `gradle.properties`),
  que es la verificación que corre aquí.

Para probarlo en el iPhone: disparar el workflow `Katapult Go (unsigned)`
(gasta minutos de Actions), `katapult sign` + `install`, y en la app elegir
modo Go con `ip-del-pc:8081` (la app completa `/manifest.zipline.json` sola).
En el iPhone `localhost` no vale: es la IP LAN del PC.

## Descubrimiento de servidores (como Expo Go)

Katapult-go ya no exige teclear la IP; hay tres caminos, de más a menos cómodo:

1. **Lista automática (mDNS/Bonjour).** El espejo y `goServe` se anuncian como
   `_katapult._tcp` en la LAN (JmDNS, `Anuncio.kt` en mirror-runtime) con el
   modo en el TXT record. La app los busca con `NWBrowser`
   (`Descubridor.swift`) y los lista en la pantalla de conexión; tocar uno
   resuelve el nombre a IP y conecta con el modo correcto.
2. **QR.** El dev server imprime en la terminal un QR con el deep link
   `katapult://espejo?url=…` o `katapult://go?url=…`. Se escanea con la cámara
   del sistema (esquema registrado en Info.plist) — sin código de cámara en la
   app, igual que el `exp://` de Expo.
3. **IP a mano.** El campo de siempre, como respaldo: en redes que bloquean
   multicast (invitados, VPNs) el mDNS no ve nada y el QR/IP siguen valiendo.

Trampas de iOS ya contempladas: `NSBonjourServices` en Info.plist es
obligatorio (sin él, el descubrimiento falla EN SILENCIO) y ya estaba
`NSLocalNetworkUsageDescription` del espejo.

## Tu propio proyecto: el plugin `dev.katapult.go`

Desde julio de 2026 el modo Go ya no es solo la demo de este repo: cualquier
proyecto KMP puede compilar SU lógica. En katapult-demo quedó el ejemplo — un
módulo `logica-go` con:

```kotlin
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    id("dev.katapult.go") version "0.1.0"
}

katapultGo {
    logica = "com.jetbrains.kmpapp.golog.LogicaMuseo"  // implementa GoLogica
}
```

El plugin (gemelo del de espejo) añade el target js, aplica Zipline, fija el
`mainFunction`, genera el `main()` con `@JsExport` (la trampa queda enterrada
en código generado) y registra `goServe`/`goDev`. El flujo del usuario:

```bash
./gradlew :logica-go:goDev     # y katapult-go lo lista en "En tu red"
```

Reglas del módulo de lógica:

- **Sin UI de Compose.** El módulo entero viaja a QuickJS, donde no hay DOM ni
  canvas. Por eso es un módulo aparte de :shared — el mismo reparto que usaba
  Treehouse en Cash App (presenters separados de la UI).
- **Sin red directa** (QuickJS no tiene fetch). Datos remotos = servicio del
  anfitrión sobre el puente; es el siguiente hueco natural del contrato.
- Versiones: el par Kotlin↔Zipline del proyecto del usuario debe casar con el
  del runtime (hoy 2.3.21 ↔ 1.27.0, alineado con katapult-demo).

## Siguiente decisión (la cara)

El catálogo de widgets (paso 1): pasar de "la lógica manda datos a una UI fija"
a "la lógica compone la UI con un catálogo de ~15-20 widgets nativos". Es el
salto de meses — releer la Opción D antes de comprometerse. La experiencia de
recarga en el dispositivo real debería informar esa decisión.
