// One compositor-driven progress surface. Rust supplies real download progress;
// checking, sidecar startup and session restoration remain indeterminate.
(function () {
  const strings = {
    ro: { checking: "Se caută actualizări…", downloading: "Se descarcă actualizarea…", installing: "Se instalează…" },
    ru: { checking: "Поиск обновлений…", downloading: "Загрузка обновления…", installing: "Установка…" },
    en: { checking: "Checking for updates…", downloading: "Downloading update…", installing: "Installing…" },
  };
  const language = (navigator.language || "ro").toLowerCase().slice(0, 2);
  const labels = strings[language] || strings.ro;
  const bar = document.getElementById("update-bar");
  const fill = document.getElementById("update-fill");
  const area = document.getElementById("update-area");
  const caption = document.getElementById("update-caption");
  if (!bar || !fill || !area || !caption) return;
  let lastPercent = null;

  function show(key, ratio) {
    area.hidden = false;
    caption.textContent = labels[key] || "";
    bar.setAttribute("aria-label", labels[key] || "Open SIME");
    bar.setAttribute("data-mode", ratio === null ? "indeterminate" : "determinate");
    if (ratio === null) {
      lastPercent = null;
      bar.removeAttribute("aria-valuenow");
    } else {
      fill.style.setProperty("--progress", String(ratio));
      const percent = Math.round(ratio * 100);
      if (lastPercent === null || Math.abs(percent - lastPercent) >= 3 || (percent === 100 && lastPercent !== 100)) {
        bar.setAttribute("aria-valuenow", String(percent));
        lastPercent = percent;
      }
    }
  }

  const opening = () => show("", null);
  window.__simeUpdate = {
    checking() { show("checking", null); },
    progress(received, total) {
      const ratio = Number.isFinite(received) && Number.isFinite(total) && total > 0
        ? Math.max(0, Math.min(1, received / total)) : null;
      show("downloading", ratio);
    },
    installing() { show("installing", 1); },
    hide: opening,
    fail: opening,
    stop() { area.hidden = true; },
  };
  document.addEventListener("visibilitychange", () => {
    area.classList.toggle("is-paused", document.hidden);
  });
  opening();
})();
