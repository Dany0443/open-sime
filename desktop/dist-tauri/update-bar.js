// Startup update progress: a web recreation of the Material 3 Expressive wavy
// progress bar, shown on the loader page while Rust checks for and downloads an
// update. Rust drives it through window.__simeUpdate (see src-tauri/src/lib.rs).
// This file performs no network requests and never shows errors: a failed update
// simply hides the block and normal startup continues.
(function () {
  const STRINGS = {
    ro: {
      checking: "Se caută actualizări…",
      downloading: "Se descarcă actualizarea…",
      installing: "Se instalează…",
    },
    ru: {
      checking: "Поиск обновлений…",
      downloading: "Загрузка обновления…",
      installing: "Установка…",
    },
    en: {
      checking: "Checking for updates…",
      downloading: "Downloading update…",
      installing: "Installing…",
    },
  };

  const ARIA_STEP = 3; // repaint aria-valuenow at most every 3 percent
  const WAVE_PERIOD_MS = 1400; // one full sine phase per period
  const INDETERMINATE_PERIOD_MS = 2200; // one left-to-right sweep of the wave
  const INDETERMINATE_SEGMENT = 0.34; // wave length relative to the track width

  function pickStrings(language) {
    const tag = typeof language === "string" ? language.toLowerCase() : "";
    if (tag.indexOf("ru") === 0) return STRINGS.ru;
    if (tag.indexOf("en") === 0) return STRINGS.en;
    return STRINGS.ro;
  }

  // received/total -> 0..1, or null when the total size is unknown or invalid.
  function progressRatio(received, total) {
    if (!Number.isFinite(received) || !Number.isFinite(total) || total <= 0) return null;
    if (received <= 0) return 0;
    if (received >= total) return 1;
    return received / total;
  }

  // All values are in device pixels; the wave stays inside the bar height.
  function barGeometry(width, height) {
    const thickness = Math.max(2, height * 0.4);
    return {
      thickness,
      amplitude: Math.max(0, (height - thickness) / 2),
      wavelength: Math.max(12, height * 1.6),
      dotRadius: thickness * 0.5,
      gap: Math.max(3, height * 0.35),
    };
  }

  // Horizontal span of the wavy part: the filled part when the ratio is known,
  // otherwise one wave travelling across the bar.
  function activeSegment(width, ratio, cycle) {
    if (ratio === null) {
      const length = width * INDETERMINATE_SEGMENT;
      const travel = width + length;
      const start = -length + travel * cycle;
      return { start, end: start + length };
    }
    return { start: 0, end: width * ratio };
  }

  // Sample points of the sine, relative to the bar's vertical center.
  function wavePoints(start, end, geometry, phase) {
    const step = Math.max(1, geometry.wavelength / 8);
    const xs = [];
    for (let x = start; x < end; x += step) xs.push(x);
    xs.push(end);
    return xs.map((x) => ({
      x,
      y: geometry.amplitude * Math.sin(((x - start) / geometry.wavelength) * Math.PI * 2 + phase),
    }));
  }

  const canvas = document.getElementById("update-canvas");
  const bar = document.getElementById("update-bar");
  const area = document.getElementById("update-area");
  const caption = document.getElementById("update-caption");
  if (!canvas || !bar || !area || !caption || !canvas.getContext) return;
  const ctx = canvas.getContext("2d");
  if (!ctx) return;

  const strings = pickStrings(typeof navigator !== "undefined" ? navigator.language : "");
  const reduceMotionQuery = window.matchMedia
    ? window.matchMedia("(prefers-reduced-motion: reduce)")
    : null;
  const darkQuery = window.matchMedia
    ? window.matchMedia("(prefers-color-scheme: dark)")
    : null;

  const colors = { track: "#dee0ff", active: "#00105c" };
  let size = { width: 0, height: 0 };
  let frameId = 0;
  let visible = false;
  let ratio = null;
  let clock = 0;
  let lastPercent = null;

  function readColors() {
    const styles = getComputedStyle(document.documentElement);
    const track = styles.getPropertyValue("--primary-container").trim();
    const active = styles.getPropertyValue("--on-primary-container").trim();
    if (track) colors.track = track;
    if (active) colors.active = active;
  }

  function resize() {
    const dpr = window.devicePixelRatio || 1;
    const width = Math.max(1, Math.round((canvas.clientWidth || 0) * dpr));
    const height = Math.max(1, Math.round((canvas.clientHeight || 0) * dpr));
    if (canvas.width !== width || canvas.height !== height) {
      canvas.width = width;
      canvas.height = height;
    }
    size = { width, height };
  }

  function draw() {
    resize();
    ctx.clearRect(0, 0, size.width, size.height);
    if (!visible) return;

    const geometry = barGeometry(size.width, size.height);
    const centerY = size.height / 2;
    const phase = ((clock % WAVE_PERIOD_MS) / WAVE_PERIOD_MS) * Math.PI * 2;
    // Start half way through the sweep so the indeterminate wave is visible on
    // the very first painted frame instead of entering from off-screen.
    const cycle =
      ((clock + INDETERMINATE_PERIOD_MS * 0.5) % INDETERMINATE_PERIOD_MS) /
      INDETERMINATE_PERIOD_MS;
    const segment = activeSegment(size.width, ratio, cycle);
    const start = Math.max(0, segment.start);
    const end = Math.min(size.width, segment.end);

    if (end > start) {
      const points = wavePoints(start, end, geometry, phase);
      ctx.beginPath();
      ctx.lineCap = "round";
      ctx.lineJoin = "round";
      ctx.lineWidth = geometry.thickness;
      ctx.strokeStyle = colors.active;
      points.forEach((point, index) => {
        if (index === 0) ctx.moveTo(point.x, centerY + point.y);
        else ctx.lineTo(point.x, centerY + point.y);
      });
      ctx.stroke();
    }

    const trackEnd = size.width - geometry.dotRadius * 2;
    const trackStart = Math.min(trackEnd, Math.max(end + geometry.gap, end));
    if (trackEnd > trackStart) {
      ctx.beginPath();
      ctx.lineCap = "round";
      ctx.lineWidth = geometry.thickness * 0.5;
      ctx.strokeStyle = colors.track;
      ctx.moveTo(trackStart, centerY);
      ctx.lineTo(trackEnd, centerY);
      ctx.stroke();
    }

    ctx.beginPath();
    ctx.arc(size.width - geometry.dotRadius, centerY, geometry.dotRadius, 0, Math.PI * 2);
    ctx.fillStyle = colors.active;
    ctx.fill();
  }

  function frame(now) {
    frameId = 0;
    if (!visible) return;
    clock = now;
    draw();
    frameId = window.requestAnimationFrame(frame);
  }

  function startAnimation() {
    window.cancelAnimationFrame(frameId);
    frameId = 0;
    if (!visible) return;
    draw();
    if (reduceMotionQuery && reduceMotionQuery.matches) return; // static drawing only
    frameId = window.requestAnimationFrame(frame);
  }

  function stopAnimation() {
    window.cancelAnimationFrame(frameId);
    frameId = 0;
  }

  function setRatio(next) {
    ratio = next;
    if (next === null) {
      lastPercent = null;
      bar.removeAttribute("aria-valuenow");
      return;
    }
    const percent = Math.round(next * 100);
    if (lastPercent === null || Math.abs(percent - lastPercent) >= ARIA_STEP || percent === 100) {
      lastPercent = percent;
      bar.setAttribute("aria-valuenow", String(percent));
    }
  }

  function setText(key) {
    const text = strings[key];
    if (caption.textContent !== text) caption.textContent = text;
    if (bar.getAttribute("aria-label") !== text) bar.setAttribute("aria-label", text);
  }

  function show(key, nextRatio) {
    const wasVisible = visible;
    visible = true;
    area.classList.add("is-visible");
    setText(key);
    setRatio(nextRatio);
    if (!wasVisible) startAnimation();
    else if (reduceMotionQuery && reduceMotionQuery.matches) draw();
  }

  function hide() {
    visible = false;
    area.classList.remove("is-visible");
    setRatio(null);
    clock = 0;
    stopAnimation();
    ctx.clearRect(0, 0, size.width, size.height);
  }

  window.addEventListener("resize", () => {
    if (!visible) return;
    readColors();
    if (reduceMotionQuery && reduceMotionQuery.matches) draw();
  });
  if (darkQuery && darkQuery.addEventListener) {
    darkQuery.addEventListener("change", () => {
      readColors();
      if (reduceMotionQuery && reduceMotionQuery.matches) draw();
    });
  }

  readColors();

  window.__simeUpdate = {
    checking() {
      show("checking", null);
    },
    progress(received, total) {
      show("downloading", progressRatio(received, total));
    },
    installing() {
      show("installing", 1);
    },
    hide,
    fail() {
      hide();
    },
  };

  // Pure drawing math, exposed for the loader unit tests.
  window.__simeUpdateGeometry = { pickStrings, progressRatio, barGeometry, activeSegment, wavePoints };
})();
