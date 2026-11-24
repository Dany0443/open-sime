// Runs before the stylesheet: match the saved theme without reading session data.
(function () {
  if (!window.__TAURI_INTERNALS__) return;
  const root = document.documentElement;
  root.dataset.desktop = "true";
  root.dataset.theme = window.matchMedia("(prefers-color-scheme: dark)").matches ? "dark" : "light";
  try {
    const theme = window.localStorage.getItem("open-sime.theme");
    if (theme === "light" || theme === "dark") root.dataset.theme = theme;
  } catch {}
  let revealing = false;
  window.__simeStartup = {
    reveal() {
      if (revealing) return;
      revealing = true;
      // Let the ready Home/auth view lay out and paint beneath the loading surface.
      window.requestAnimationFrame(() => window.requestAnimationFrame(() => {
        const splash = document.getElementById("splash");
        if (!splash) return;
        const finish = () => { splash.hidden = true; };
        if (window.matchMedia("(prefers-reduced-motion: reduce)").matches || root.dataset.reduceMotion === "true") {
          finish();
          return;
        }
        splash.addEventListener("transitionend", finish, {once: true});
        splash.classList.add("is-ready");
        // Cleanup only; pointer input is available as soon as the fade starts.
        window.setTimeout(finish, 220);
      }));
    },
  };
})();
