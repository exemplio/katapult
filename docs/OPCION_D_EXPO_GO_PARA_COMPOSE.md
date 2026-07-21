# Opción D — Construir un "Expo Go" de verdad para Compose

> Documento de referencia, escrito en julio de 2026 tras descartar esta opción.
> No es un plan aprobado: es lo que hay que saber **antes** de decidir hacerlo,
> para no volver a investigarlo desde cero.

## El problema, en una frase

Quiero **una sola app instalada en el iPhone** a la que le cargue **cualquier
proyecto** y verlo correr con **rendimiento nativo**, igual que Expo Go.

## Por qué no se puede tal cual

Las tres cosas son mutuamente excluyentes en iOS:

```
① Una sola app instalada
② Le cargas cualquier proyecto      →  elige DOS
③ Rendimiento nativo
```

- **①+②** → lo que se descarga tiene que ser interpretado → se pierde ③
- **①+③** → el código nativo debe estar ya dentro del binario → se pierde ②
- **②+③** → se instala la app de verdad, cada vez → se pierde ①

### La restricción de Apple, con precisión

Conviene no repetir el mito. Lo que realmente aplica:

| Afirmación | Realidad |
|---|---|
| "Apple prohíbe descargar código" | Prohíbe descargar y ejecutar **código nativo**. El interpretado está permitido. |
| "Necesitamos permiso especial" | Un intérprete no necesita nada: no crea páginas ejecutables. |
| "El JIT es una alternativa" | El JIT exige el entitlement `dynamic-codesigning`, reservado a WebKit. |
| "Nos afectan las reglas de la App Store" | Solo si publicas ahí. Katapult Go se instala firmado por ti, así que la guideline 4.7 no aplica. |
| "Podemos hacer `dlopen` de una librería descargada" | No. La firma de código lo bloquea a nivel de kernel (AMFI). |

**Conclusión:** el bloqueo no es legal ni de permisos. Es que Compose compila
su UI a código máquina, y ese código no puede viajar por la red.

### Por qué a Expo sí le funciona

No es por Hermes. Hermes es solo el motor de JS; QuickJS haría lo mismo.

Funciona porque **en React Native la UI se declara en JavaScript**:

```js
<View style={{padding: 16}}>   // esto es un DATO, serializable
```

El catálogo de widgets nativos de RN tiene ~30 componentes y los estilos son
objetos planos. Eso cabe holgadamente en un puente.

En Compose:

```kotlin
Column(Modifier.padding(16.dp).drawBehind { /* código */ })
```

`drawBehind {}` es **código que se ejecuta durante el dibujado**. No es un dato.
Multiplícalo por `pointerInput`, `layout`, `graphicsLayer`, `nestedScroll`… y el
puente deja de ser viable.

> React Native manda **una lista de la compra**.
> Compose querría mandar **al cocinero**.

## La única arquitectura que funciona

La clave es que Compose son **dos cosas separables**:

- `compose-runtime` — árbol, estado, recomposición. Kotlin puro, sin gráficos.
- `compose-ui` — layout, Skia, Material. Nativo y pesado.

Se envía el **runtime** al intérprete y se deja la **UI** nativa:

```
App anfitriona (nativa, instalada)   ←── puente ──→   Intérprete (QuickJS)
  catálogo de widgets nativos                         tu código, como Kotlin/JS
  dibuja de verdad                                    decide QUÉ mostrar
```

Y esto **ya existe**: se llama **Treehouse** = **Redwood** + **Zipline**.

## Estado de las piezas (julio 2026)

| Proyecto | Licencia | Estado | Último movimiento |
|---|---|---|---|
| [cashapp/redwood](https://github.com/cashapp/redwood) | Apache-2.0 | **Discontinuado** — 0.19.0 es el release final | commits: enero 2026 |
| [cashapp/zipline](https://github.com/cashapp/zipline) | Apache-2.0 | **Vivo y mantenido** | commits: julio 2026 |

El dato más informativo del expediente: **Cash App mató Redwood y mantiene
Zipline.** Abandonaron la capa de UI sobre el puente y conservaron el motor de
código dinámico. No es una opinión sobre la idea — es dónde ponen los commits.

## Qué cuesta y qué se obtiene

### Lo que ganarías
- Recarga de pantallas al instante, sin recompilar ni reinstalar.
- Widgets nativos: gestos del sistema, accesibilidad, aspecto de plataforma.
- Modelo mental de Compose (estado, recomposición).
- Base de código probada en producción, con licencia permisiva.

### Lo que costaría
- **No es Compose Multiplatform.** Defines tu propio catálogo de widgets y lo
  implementas en cada plataforma. El trabajo de UI se duplica.
- Sin `Modifier`, sin el sistema de layout completo de Compose. Un subconjunto.
- **El techo estructural:** el catálogo viaja *dentro* del binario. Puedes
  publicar pantallas nuevas combinando widgets existentes; **un widget nuevo
  exige release de la app**. Eso no se arregla con más ingeniería.
- Deriva de versiones: una app vieja instalada tiene que seguir ejecutando el
  código nuevo que publiques. Ese contrato lo mantienes tú, para siempre.
- Infraestructura: servidor donde alojar los módulos, firma de manifests,
  despliegue, versionado. Katapult deja de ser un CLI.
- Redwood ya arrastra deriva contra Kotlin y Compose, que se mueven rápido.
- Pasas de mantener una herramienta a **mantener un framework de UI, solo**.

### Estimación
Meses, no semanas. Como referencia: lo construyó un equipo de Cash App, con
Jake Wharton, llegó a producción, y aun así decidieron no seguir manteniéndolo.

## Si algún día se hace, por dónde empezar

1. **No forkear Redwood el día uno.** Primero montar un prototipo con Zipline
   solo (lógica dinámica, UI nativa fija) y comprobar que el ciclo de
   despliegue no es un incordio. Ahí se descubre el 80% del dolor real.
2. Definir el catálogo mínimo de widgets: no más de 15-20. Si la lista crece,
   la idea se está desviando hacia "reimplementar Compose", que es la trampa.
3. Recién entonces evaluar si forkear Redwood o escribir el applier propio
   sobre `compose-runtime`, que es multiplataforma desde hace tiempo.
4. Presupuestar el mantenimiento **como coste recurrente**, no como esfuerzo
   inicial. Es lo que hundió al original.

## La alternativa que se eligió en su lugar

Registrado aquí para que el contraste quede claro:

- **Bucle rápido en Android/desktop** — mismo `commonMain`, mismo Compose, mismo
  Skia, segundos por iteración, desde Linux. Coste: cero.
- **Espejo de desarrollo** (`mirror-runtime`) — render en la JVM, streaming al
  dispositivo. Medido: **59,7 fps**, render 2 ms, codificación en hilo aparte.
  Pendiente: H.264 para bajar de 88 Mbps a unos pocos.
- **iOS como verificación**, no como iteración — `katapult build ios`, medido en
  **5m23s** (desde 14m29s) con Debug + cachés de Gradle/Kotlin-Native.

Ninguna de las tres da las tres cosas a la vez. Ninguna requiere mantener un
framework de UI.

## Fuentes

- [Redwood 0.19.0 (Final release)](https://github.com/cashapp/redwood/discussions/2894)
- [Native UI and multiplatform Compose with Redwood — Cash App Code Blog](https://code.cash.app/native-ui-and-multiplatform-compose-with-redwood)
- [Native UI with multiplatform Compose — Jake Wharton](https://jakewharton.com/native-ui-with-multiplatform-compose/)
- [cashapp/zipline](https://github.com/cashapp/zipline)
- [Compose Hot Reload — solo desktop/JVM](https://kotlinlang.org/docs/multiplatform/compose-hot-reload.html)
