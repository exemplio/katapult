# Katapult 🛠️→🍎

**Desarrolla apps Kotlin Multiplatform con Compose para iOS desde Linux, sin un Mac.**

Katapult son dos herramientas que resuelven problemas distintos:

- **El pipeline** — GitHub Actions (runner macOS de *tu* cuenta) compila el IPA
  sin firmar, `zsign` lo firma en local, y se instala por USB.
- **El espejo** — tu UI de Compose se renderiza en la JVM y se transmite al
  iPhone por WiFi a 60 fps, para iterar sin recompilar nada nativo.

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
./build/install/katapult/bin/katapult doctor
```

## Estado

- [x] **CLI de builds** — init/build/sign/install/doctor/setup/publish
- [x] **Espejo de desarrollo** — Compose en JVM, H.264 + WebCodecs, 60 fps,
      toques y gestos de vuelta
- [x] **Lógica dinámica, paso 0 (JVM)** — código Kotlin que se descarga y
      recarga en caliente con [Zipline](https://github.com/cashapp/zipline);
      ver [docs/KATAPULT_GO_PASO_0.md](docs/KATAPULT_GO_PASO_0.md)
- [ ] **Katapult Go** — ya tiene dos modos de conexión: **Espejo** (WKWebView
      del cliente web; decodificar nativo con VideoToolbox sigue pendiente) y
      **Go** (anfitrión Zipline vía `GoRuntime.framework`: la lógica corre en
      el dispositivo y se recarga en caliente). Falta compilar el IPA en CI
      y probarlo en un iPhone real
- [ ] Publicar el plugin en el portal de Gradle (ahora se resuelve por `mavenLocal`)

## Lo que Katapult no va a ser

Un "Expo Go para Compose" — una app instalada a la que le cargas cualquier
proyecto y corre nativo. **No es posible en iOS**: Apple permite descargar
código interpretado, pero Compose compila su UI a código máquina. Expo Go puede
porque en React Native la UI se declara en JavaScript.

La arquitectura que sí funcionaría (Redwood + Zipline) la construyó Cash App,
llegó a producción, y **discontinuaron Redwood en enero de 2026**. El análisis
completo, con las alternativas y sus costes, está en
[docs/OPCION_D_EXPO_GO_PARA_COMPOSE.md](docs/OPCION_D_EXPO_GO_PARA_COMPOSE.md).

Lo que sí se está haciendo, **poco a poco y sin comprometer lo anterior**, es
la parte de esa arquitectura que Expo Go también tiene: lógica interpretada que
viaja, UI fija que no. El paso 0 ya funciona en JVM
([docs/KATAPULT_GO_PASO_0.md](docs/KATAPULT_GO_PASO_0.md)).

Mientras tanto, el reparto del día a día:

| | Para qué | Ciclo |
|---|---|---|
| Android / desktop | iterar lógica y UI | segundos |
| Espejo | ver la UI en el iPhone real | instantáneo |
| IPA nativo | verificar rendimiento y APIs de plataforma | ~5 min |
