package dev.katapult.mirror

/**
 * Cliente de prueba servido en "/". Sirve para verificar el espejo desde
 * cualquier navegador (incluido Safari del iPhone) antes de construir la app
 * nativa del paso C.
 */
val CLIENT_HTML = """
<!doctype html>
<html>
<head>
  <meta charset="utf-8">
  <meta name="viewport" content="width=device-width, initial-scale=1, user-scalable=no">
  <title>Katapult Mirror</title>
  <style>
    html,body { margin:0; height:100%; background:#111; display:flex;
                align-items:center; justify-content:center; font-family:system-ui; }
    canvas { max-width:100vw; max-height:100vh; touch-action:none; }
    #status { position:fixed; top:8px; left:8px; color:#8f8; font-size:12px; }
  </style>
</head>
<body>
  <div id="status">conectando…</div>
  <canvas id="c"></canvas>
<script>
  const c = document.getElementById('c'), ctx = c.getContext('2d');
  const status = document.getElementById('status');
  let frames = 0, t0 = Date.now();

  const ws = new WebSocket(`ws://${'$'}{location.host}/mirror`);
  ws.binaryType = 'blob';

  ws.onopen = () => status.textContent = 'conectado';
  ws.onclose = () => status.textContent = 'desconectado';

  ws.onmessage = async (e) => {
    const bmp = await createImageBitmap(e.data);
    if (c.width !== bmp.width) { c.width = bmp.width; c.height = bmp.height; }
    ctx.drawImage(bmp, 0, 0);
    bmp.close();
    if (++frames % 10 === 0) {
      const fps = (frames / ((Date.now() - t0) / 1000)).toFixed(1);
      status.textContent = `${'$'}{fps} fps · ${'$'}{c.width}x${'$'}{c.height}`;
    }
  };

  // Traduce coordenadas del canvas mostrado a píxeles de la escena.
  function toScene(ev) {
    const r = c.getBoundingClientRect();
    const p = ev.touches?.[0] ?? ev.changedTouches?.[0] ?? ev;
    return { x: (p.clientX - r.left) * (c.width / r.width),
             y: (p.clientY - r.top) * (c.height / r.height) };
  }
  function send(type, ev) {
    if (ws.readyState !== 1) return;
    const { x, y } = toScene(ev);
    ws.send(JSON.stringify({ type, x, y }));
    ev.preventDefault();
  }

  c.addEventListener('pointerdown', e => send('down', e));
  c.addEventListener('pointermove', e => e.buttons && send('move', e));
  c.addEventListener('pointerup',   e => send('up', e));
  c.addEventListener('touchstart',  e => send('down', e), {passive:false});
  c.addEventListener('touchmove',   e => send('move', e), {passive:false});
  c.addEventListener('touchend',    e => send('up', e),   {passive:false});
</script>
</body>
</html>
""".trimIndent()
