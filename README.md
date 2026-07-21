# Katapult 🛠️→🍎

**Compila, firma e instala apps iOS de Kotlin Multiplatform sin un Mac.**

Katapult orquesta un flujo que ya funciona en producción: GitHub Actions
(runner macOS de *tu* cuenta) compila el IPA sin firmar, `zsign` lo firma
localmente en Linux, y `ideviceinstaller` lo instala por USB.

```
katapult init        # genera katapult.json + workflow de Actions en tu repo KMP
katapult build ios   # dispara el build en CI, espera, descarga el IPA
katapult sign        # firma local con tu certificado (zsign)
katapult install     # instala en el iPhone por USB
katapult doctor      # verifica el entorno
```

## Modelo: BYO-CI

Katapult **no** es un servicio centralizado tipo EAS: los builds corren en los
GitHub Actions de cada usuario, con sus minutos y sus credenciales (via `gh`).
Tu código y tus certificados nunca pasan por servidores de terceros.

## Requisitos

- JDK 17+
- [GitHub CLI](https://cli.github.com) con sesión activa (`gh auth login`)
- [zsign](https://github.com/zhlynn/zsign) compilado + certificado `.p12` +
  provisioning profile (por defecto en `~/Downloads/zsign/`, configurable en
  `katapult.json`)
- `ideviceinstaller` (`sudo apt install ideviceinstaller`) para instalar por USB

## Desarrollo

```bash
./gradlew installDist
./build/install/katapult/bin/katapult doctor
```

## Roadmap

- [x] **Fase 1 — CLI de builds** (esto): init/build/sign/install/doctor
- [ ] **Fase 2 — Espejo de desarrollo**: la app corre en Compose Desktop (JVM)
      con hot reload en Linux; un cliente en el iPhone recibe frames por LAN
      (Skia host-side) y devuelve toques
- [ ] **Fase 3 — Katapult Go**: dev shell nativo con
      [Zipline](https://github.com/cashapp/zipline) (QuickJS): el mismo
      `commonMain` compilado a Kotlin/JS se recarga en caliente en el iPhone
- [ ] **Fase 4 — OTA**: actualizaciones de la capa Zipline en producción
      (manifiestos firmados), binario nativo por App Store

La tesis: **desarrolla interpretado, envía nativo.** El shell interpretado es
solo para iterar; producción compila Kotlin/Native a ARM64 real.
