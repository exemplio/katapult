package dev.katapult.mirror

/**
 * Cliente servido en "/". Decodifica el stream con WebCodecs cuando está
 * disponible —el iPhone lo trae desde iOS 17— y cae a JPEG si el servidor no
 * encontró ffmpeg.
 *
 * Por qué WebCodecs y no las alternativas: `MediaSource` no existe en iPhone, y
 * `ManagedMediaSource` cede el control del buffer al navegador, que es justo lo
 * contrario de lo que quiere un espejo de desarrollo. `VideoDecoder` entrega
 * cada frame en cuanto lo decodifica, sin buffer intermedio.
 *
 * Nota: es una plantilla de Kotlin, así que cualquier `${'$'}{...}` de JS habría
 * que escaparlo. Aquí se usa concatenación para no tener que hacerlo.
 */
val CLIENT_HTML = """
<!doctype html>
<html lang="es">
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no, viewport-fit=cover">
  <title>Katapult Mirror</title>
  <style>
    html,body { margin:0; height:100%; background:#000; overflow:hidden;
                display:flex; align-items:center; justify-content:center;
                font:13px/1.4 -apple-system, system-ui, sans-serif; }
    canvas { max-width:100vw; max-height:100vh; touch-action:none; display:block; }
    #hud { position:fixed; top:calc(env(safe-area-inset-top) + 6px); left:8px;
           color:#7dd3fc; background:rgba(0,0,0,.55); padding:3px 7px;
           border-radius:6px; font-variant-numeric:tabular-nums;
           pointer-events:none; opacity:.85; }
    #hud.err { color:#fca5a5; }
  </style>
</head>
<body>
<canvas id="c"></canvas>
<div id="hud">conectando…</div>

<script>
const canvas = document.getElementById('c');
const ctx = canvas.getContext('2d');
const hud = document.getElementById('hud');

let cfg = null;          // el "hello" del servidor
let decoder = null;      // VideoDecoder, si hay WebCodecs
let stamp = 0;           // WebCodecs exige timestamps crecientes
let shown = 0, bytes = 0, lastReport = performance.now();

function fail(msg) { hud.className = 'err'; hud.textContent = msg; }

function fit() {
  if (!cfg) return;
  // El canvas guarda la resolución del stream; el CSS lo escala a la pantalla.
  canvas.width = cfg.width;
  canvas.height = cfg.height;
  const scale = Math.min(innerWidth / cfg.width, innerHeight / cfg.height);
  canvas.style.width = (cfg.width * scale) + 'px';
  canvas.style.height = (cfg.height * scale) + 'px';
}
addEventListener('resize', fit);

function draw(frame) {
  ctx.drawImage(frame, 0, 0, canvas.width, canvas.height);
  frame.close();
  shown++;
  const now = performance.now();
  if (now - lastReport >= 1000) {
    const fps = shown * 1000 / (now - lastReport);
    const mbps = bytes * 8 / ((now - lastReport) / 1000) / 1e6;
    hud.textContent = fps.toFixed(0) + ' fps · ' + mbps.toFixed(1) + ' Mbps · ' + cfg.mode;
    shown = 0; bytes = 0; lastReport = now;
  }
}

function startDecoder() {
  decoder = new VideoDecoder({
    output: draw,
    error: e => fail('decoder: ' + e.message),
  });
  // Sin `description`, WebCodecs interpreta el flujo como Annex B, que es
  // exactamente lo que emite ffmpeg con "-f h264".
  decoder.configure({
    codec: 'avc1.42E01E',
    codedWidth: cfg.width,
    codedHeight: cfg.height,
    optimizeForLatency: true,
  });
}

const ws = new WebSocket('ws://' + location.host + '/mirror');
ws.binaryType = 'arraybuffer';

ws.onmessage = async (ev) => {
  // El primer mensaje es texto: dice el modo y las dimensiones.
  if (typeof ev.data === 'string') {
    cfg = JSON.parse(ev.data);
    fit();
    if (cfg.mode === 'h264') {
      if (typeof VideoDecoder === 'undefined') {
        return fail('Este navegador no tiene WebCodecs');
      }
      startDecoder();
      hud.textContent = 'esperando keyframe…';
    } else {
      hud.textContent = 'JPEG (sin ffmpeg en el servidor)';
    }
    return;
  }

  const buf = new Uint8Array(ev.data);
  bytes += buf.length;

  if (cfg.mode === 'jpeg') {
    const bmp = await createImageBitmap(new Blob([buf], {type: 'image/jpeg'}));
    draw(bmp);
    return;
  }

  // H.264: el primer byte marca si el frame es clave.
  const isKey = buf[0] === 1;
  const payload = buf.subarray(1);
  if (!decoder || decoder.state !== 'configured') return;
  try {
    decoder.decode(new EncodedVideoChunk({
      type: isKey ? 'key' : 'delta',
      timestamp: stamp,
      data: payload,
    }));
    stamp += Math.round(1e6 / (cfg.fps || 60));   // microsegundos
  } catch (e) {
    fail('decode: ' + e.message);
  }
};

ws.onerror = () => fail('sin conexión con el servidor');
ws.onclose  = () => fail('conexión cerrada');

// Toques → coordenadas del stream, no de la pantalla.
function send(type, touch) {
  if (ws.readyState !== 1 || !cfg) return;
  const r = canvas.getBoundingClientRect();
  ws.send(JSON.stringify({
    type,
    x: (touch.clientX - r.left) * cfg.width / r.width,
    y: (touch.clientY - r.top) * cfg.height / r.height,
  }));
}
canvas.addEventListener('touchstart', e => { e.preventDefault(); send('down', e.changedTouches[0]); }, {passive:false});
canvas.addEventListener('touchmove',  e => { e.preventDefault(); send('move', e.changedTouches[0]); }, {passive:false});
canvas.addEventListener('touchend',   e => { e.preventDefault(); send('up',   e.changedTouches[0]); }, {passive:false});
canvas.addEventListener('mousedown', e => send('down', e));
canvas.addEventListener('mousemove', e => { if (e.buttons) send('move', e); });
canvas.addEventListener('mouseup',   e => send('up', e));
</script>
</body>
</html>
""".trimIndent()
