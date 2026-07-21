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

Dos terminales, **en orden** (ver "Trampas" sobre el lock de Gradle):

```bash
# 1. Servidor de módulos: compila jsMain en continuo y sirve en :8081
./gradlew :go-runtime:serveDevelopmentZipline --continuous

# 2. Anfitrión: descarga la lógica, la ejecuta en QuickJS, re-renderiza cada segundo
./gradlew :go-runtime:goHost
```

Con ambos corriendo, edita algo visible en
`go-runtime/src/jsMain/kotlin/dev/katapult/go/LogicaJs.kt` y guarda: el
servidor recompila solo y el anfitrión pinta la versión nueva (`lógica v2`)
sin reiniciarse. Ese es todo el punto.

El puerto es **8081** (el 8080 es del espejo, para poder correr ambos a la vez).

## Trampas ya pisadas aquí

1. **`main()` de jsMain necesita `@JsExport`.** Kotlin/JS IR no exporta símbolos
   por defecto; sin la anotación QuickJS falla con
   `cannot read property 'katapult' of undefined` y no hay más pista que esa.
2. **Zipline y Kotlin van emparejados.** Zipline 1.22.0 ↔ Kotlin 2.2.0 (el
   CHANGELOG de Zipline documenta el mapeo). Subir Kotlin exige subir Zipline
   a la vez, y viceversa.
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
ejecutar → recargar) y que la fricción es baja.

No valida: UI dinámica. La pantalla la pinta el anfitrión con código fijo; la
lógica solo manda **datos** (`GoPantalla`). El catálogo de widgets es el paso 1
y es el salto grande — releer la Opción D antes de darlo.

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

## Siguiente decisión (la cara)

El catálogo de widgets (paso 1): pasar de "la lógica manda datos a una UI fija"
a "la lógica compone la UI con un catálogo de ~15-20 widgets nativos". Es el
salto de meses — releer la Opción D antes de comprometerse. La experiencia de
recarga en el dispositivo real debería informar esa decisión.
