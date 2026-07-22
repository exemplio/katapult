# Katapult 🛠️→🍎

**Desarrolla apps Kotlin Multiplatform con Compose para iOS desde Linux, sin un Mac.**

Katapult son tres herramientas que resuelven problemas distintos:

- **El pipeline** — GitHub Actions (runner macOS de *tu* cuenta) compila el IPA
  sin firmar, `zsign` lo firma en local, y se instala por USB.
- **El espejo** — tu UI de Compose se renderiza en la JVM y se transmite al
  iPhone por WiFi a 60 fps, para iterar sin recompilar nada nativo.
- **Katapult Go** — tu lógica Kotlin viaja por la red como bytecode
  ([Zipline](https://github.com/cashapp/zipline)) y corre *en* el iPhone con
  recarga en caliente; la UI se describe como datos sobre un catálogo de
  piezas nativas de SwiftUI.

```bash
katapult init        # genera katapult.json + workflow de Actions en tu repo KMP
katapult build ios   # dispara el build en CI, espera, descarga el IPA
katapult sign        # firma local con tu certificado (zsign)
katapult install     # instala en el iPhone por USB
katapult doctor      # verifica el entorno
```

## Modelo: BYO-CI

Katapult **no** es un servicio centralizado tipo EAS. Los builds corren en los
GitHub Actions de cada usuario, con sus minutos y sus credenciales (vía `gh`).
Tu código y tus certificados nunca pasan por servidores de terceros: solo se
sube el fuente que ya está en tu repo, y la firma ocurre siempre en tu máquina.

## El espejo de desarrollo

```bash
# en tu proyecto KMP
./gradlew :shared:katapultMirror
# y abres http://<tu-ip-lan>:8080 desde Safari en el iPhone
```

Compose renderiza fuera de pantalla con Skia —el mismo motor que usa en iOS—, se
codifica en H.264 y viaja por WebSocket. El cliente decodifica con **WebCodecs**,
disponible en iPhone desde iOS 17, y devuelve los toques.

Se activa con dos líneas en el `build.gradle.kts` de tu módulo compartido:

```kotlin
plugins { id("dev.katapult.mirror") version "0.1.0" }

katapultMirror {
    entryPoint = "com.tuapp.App"           // tu @Composable raíz
    initializer = "com.tuapp.di.initKoin"  // opcional, si necesitas DI
}
```

Rendimiento medido sobre una escena de 780x1688 px:

| | JPEG | H.264 |
|---|---|---|
| fps | 59,7 | 59,3 |
| Ancho de banda | 84 Mbps | **0,1 – 2,5 Mbps** |

*(El ancho de banda de H.264 está medido con la app en reposo; con scroll y
animaciones sube.)*

**El espejo sirve para ver e interactuar, no para juzgar rendimiento**: lo que
llega al iPhone son píxeles renderizados en tu PC. Para medir rendimiento real,
compila el IPA nativo.

## Katapult Go: lógica que viaja

El otro modo de la app **Katapult Go** (la misma que muestra el espejo). Tu
lógica de Kotlin se compila a bytecode de QuickJS, viaja por WiFi y se ejecuta
*en el dispositivo*; al guardar un archivo, el iPhone recarga solo, sin IPA de
por medio. La pantalla se declara como un árbol de datos (`GoElemento`, 16
piezas: textos, botones, campos, imágenes, contenedores anidables, un `Lienzo`
de órdenes de dibujo para gráficos custom…) que la app pinta con SwiftUI
nativo — sin WebView.

Se activa con dos líneas en un módulo de tu proyecto KMP:

```kotlin
plugins { id("dev.katapult.go") version "0.1.0" }

katapultGo {
    logica = "com.tuapp.golog.MiLogica"    // tu implementación de GoLogica
    espejo = ":shared:katapultMirror"      // opcional: goDev arranca también el espejo
}
```

```bash
./gradlew :tu-modulo:goDev
# sirve en :8081, se anuncia por mDNS (la app lo lista en "En tu red", como
# Expo Go), imprime un QR con deep link katapult:// y recompila al guardar.
# Con `espejo` configurado levanta además el espejo en :8080: un solo comando
# y en el iPhone salen las dos filas, "Espejo" y "Go (Zipline)".
```

Los límites son deliberados: el catálogo es fijo (Apple prohíbe descargar
código nativo, así que las piezas nuevas requieren IPA) y no hay `Modifier`
arbitrario ni animaciones libres — para eso está el espejo, que ejecuta
Compose de verdad. Lo que sí viaja es estilo **acotado, a la React Native**:
`GoTema` (fondo/acento/claro), la `Caja` (contenedor con padding, fondo,
esquinas y borde, como la `View` de RN) y `Texto` con estilo libre (tamaño,
peso, color). Con eso un proyecto describe su sistema de diseño casi al píxel
— el mismo mecanismo por el que cualquier app RN corre en Expo Go. Diseño y
criterio de crecimiento en
[docs/CATALOGO_GO_PROPUESTA.md](docs/CATALOGO_GO_PROPUESTA.md).

## Requisitos

- JDK 17+
- [GitHub CLI](https://cli.github.com) con sesión activa (`gh auth login`)
- [zsign](https://github.com/zhlynn/zsign) + certificado `.p12` + provisioning
  profile. `katapult setup` descarga zsign a `~/.katapult/bin/`; las rutas de
  firma se configuran en `katapult.json`
- `ideviceinstaller` (`sudo apt install ideviceinstaller`) para instalar por USB
- `ffmpeg` **solo para el espejo**. Sin él funciona igual, pero cae a JPEG y
  consume unas 40x más de red

## Desarrollo

```bash
./gradlew installDist
./cli/build/install/katapult/bin/katapult doctor
```

## Estado

- [x] **CLI de builds** — init/build/sign/install/doctor/setup/publish
- [x] **Espejo de desarrollo** — Compose en JVM, H.264 + WebCodecs, 60 fps,
      toques y gestos de vuelta
- [x] **Katapult Go** — probado en iPhone real, con sus dos modos: **Espejo**
      (cliente web en WKWebView) y **Go** (anfitrión Zipline vía
      `GoRuntime.framework`: lógica en el dispositivo, recarga en caliente,
      catálogo de 16 piezas con `Lienzo` incluido). Descubrimiento de
      servidores por mDNS + QR con deep links `katapult://`.
      Cronología: [docs/KATAPULT_GO_PASO_0.md](docs/KATAPULT_GO_PASO_0.md)
- [x] **Servicios de anfitrión: red** (`RedGo`) — el anfitrión hace las
      peticiones HTTP por la lógica (QuickJS no tiene sockets): el modo Go
      puede hablar con la API real del proyecto. El anfitrión de consola
      (`goHost`) es interactivo: eventos por stdin, para probar flujos
      completos desde Linux sin IPA
- [ ] Decodificación nativa del espejo en la app (VideoToolbox) — hoy el modo
      espejo es un WKWebView del cliente web
- [ ] Publicar los plugins en el portal de Gradle (ahora se resuelven por
      `mavenLocal`)

## Lo que Katapult no va a ser

Un "Expo Go para Compose" — una app instalada a la que le cargas cualquier
proyecto y corre nativo. **No es posible en iOS**: Apple permite descargar
código interpretado, pero Compose compila su UI a código máquina. Expo Go puede
porque en React Native la UI se declara en JavaScript.

La arquitectura que sí funcionaría (Redwood + Zipline) la construyó Cash App,
llegó a producción, y **discontinuaron Redwood en enero de 2026**. El análisis
completo, con las alternativas y sus costes, está en
[docs/OPCION_D_EXPO_GO_PARA_COMPOSE.md](docs/OPCION_D_EXPO_GO_PARA_COMPOSE.md).

Lo que sí se hizo, **poco a poco y sin comprometer lo anterior**, es la parte
de esa arquitectura que Apple sí permite: lógica interpretada que viaja
(Zipline) más una UI descrita como **datos** sobre un catálogo nativo pequeño
— pocas piezas que combinan (contenedores anidables, `Tocable`, `Lienzo`), no
una transcripción de Material widget a widget, que es la trampa en la que
murió Redwood. Eso es el modo Go de arriba.

El reparto del día a día:

| | Para qué | Ciclo |
|---|---|---|
| Android / desktop | iterar lógica y UI | segundos |
| Espejo | ver la UI de Compose en el iPhone real | instantáneo |
| Katapult Go | lógica real corriendo *en* el iPhone, UI sobre el catálogo | recarga en caliente |
| IPA nativo | verificar rendimiento y APIs de plataforma | ~5 min |
