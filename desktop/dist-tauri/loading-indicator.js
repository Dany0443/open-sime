// Startup loading indicator: a web recreation of the Android Material 3 Expressive
// LoadingIndicator used by the pull-to-refresh box (android/.../HomeRefreshBox.kt).
// It morphs through SoftBurst -> Sunny -> Cookie9Sided while rotating, drawn on one canvas.
// Shapes are approximated by radial profiles (not Compose's exact RoundedPolygon geometry).
(function () {
  const POINTS = 120;
  const MORPH_INTERVAL_MS = 650; // time spent on each morph step
  const FULL_ROTATION_MS = 4666; // continuous global rotation period
  const STEP_ROTATION_DEG = 90; // extra rotation added by every morph step
  const SHAPE_SCALE = 0.79; // shape diameter relative to its container (38dp in 48dp)

  // n = number of lobes, inner = valley radius (outer radius is 1), k = lobe fatness.
  const SHAPES = [
    { n: 10, inner: 0.62, k: 1.5 }, // SoftBurst
    { n: 8, inner: 0.76, k: 1.1 }, // Sunny
    { n: 9, inner: 0.84, k: 0.9 }, // Cookie9Sided
  ];

  function buildProfile(shape) {
    const radii = new Float32Array(POINTS);
    for (let i = 0; i < POINTS; i += 1) {
      const theta = (i / POINTS) * Math.PI * 2;
      const wave = (1 + Math.cos(shape.n * theta)) / 2;
      radii[i] = shape.inner + (1 - shape.inner) * Math.pow(wave, shape.k);
    }
    return radii;
  }

  // Under-damped spring easing (settles at 1 with a slight overshoot), like the Compose spring.
  function springEase(t) {
    if (t >= 1) return 1;
    return 1 - Math.exp(-6 * t) * Math.cos(10 * t);
  }

  const canvas = document.getElementById("loading-canvas");
  const holder = document.getElementById("loading-indicator");
  if (!canvas || !canvas.getContext) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  const profiles = SHAPES.map(buildProfile);
  const reduceMotionQuery = window.matchMedia
    ? window.matchMedia("(prefers-reduced-motion: reduce)")
    : null;
  const darkQuery = window.matchMedia
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;

  let frameId = 0;
  let stopped = false;
  let color = "#00105c";
  let size = 0;

  function readColor() {
    const value = getComputedStyle(document.documentElement)
      .getPropertyValue("--on-primary-container")
      .trim();
    if (value) color = value;
  }

  function resize() {
    const css = canvas.clientWidth || 48;
    const dpr = window.devicePixelRatio || 1;
    size = Math.round(css * dpr);
    if (canvas.width !== size || canvas.height !== size) {
      canvas.width = size;
      canvas.height = size;
    }
  }

  function draw(from, to, mix, rotationDeg) {
    ctx.clearRect(0, 0, size, size);
    const center = size / 2;
    const radius = (size / 2) * SHAPE_SCALE;
    const rotation = (rotationDeg * Math.PI) / 180;
    ctx.beginPath();
    for (let i = 0; i < POINTS; i += 1) {
      const r = (from[i] + (to[i] - from[i]) * mix) * radius;
      const theta = (i / POINTS) * Math.PI * 2 + rotation;
      const x = center + Math.cos(theta) * r;
      const y = center + Math.sin(theta) * r;
      if (i === 0) ctx.moveTo(x, y);
      else ctx.lineTo(x, y);
    }
    ctx.closePath();
    ctx.fillStyle = color;
    ctx.fill();
  }

  function drawStatic() {
    draw(profiles[0], profiles[0], 0, 0);
  }

  function frame(now) {
    if (stopped) return;
    const step = Math.floor(now / MORPH_INTERVAL_MS);
    const t = (now % MORPH_INTERVAL_MS) / MORPH_INTERVAL_MS;
    const mix = springEase(t);
    const from = profiles[step % profiles.length];
    const to = profiles[(step + 1) % profiles.length];
    const rotation = (now / FULL_ROTATION_MS) * 360 + (step + mix) * STEP_ROTATION_DEG;
    draw(from, to, mix, rotation);
    frameId = window.requestAnimationFrame(frame);
  }

  function start() {
    window.cancelAnimationFrame(frameId);
    if (stopped) return;
    resize();
    readColor();
    if (reduceMotionQuery && reduceMotionQuery.matches) {
      drawStatic();
      return;
    }
    frameId = window.requestAnimationFrame(frame);
  }

  function stop() {
    stopped = true;
    window.cancelAnimationFrame(frameId);
    if (holder) holder.hidden = true;
  }

  window.addEventListener("resize", start);
  if (reduceMotionQuery && reduceMotionQuery.addEventListener) {
    reduceMotionQuery.addEventListener("change", start);
  }
  if (darkQuery && darkQuery.addEventListener) {
    darkQuery.addEventListener("change", start);
  }

  window.__simeLoadingIndicator = { stop: stop };
  start();
})();
