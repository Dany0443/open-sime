(function () {
  const params = new URLSearchParams(window.location.search);
  const error = params.get("error");
  const detail = params.get("detail");
  const code = params.get("code");

  const card = document.getElementById("status-card");
  const titleEl = document.getElementById("status-title");
  const detailEl = document.getElementById("status-detail");
  const codeEl = document.getElementById("status-code");

  if (error) {
    if (card) card.classList.add("is-error");
    if (titleEl) {
      titleEl.textContent = "Serviciul local Open SIME nu a putut porni";
    }
    if (detailEl) {
      detailEl.textContent =
        detail ||
        "Verifică dacă o altă instanță Open SIME sau un alt proces folosește deja portul local (127.0.0.1:17321), apoi repornește aplicația.";
    }
    if (codeEl && code) {
      codeEl.hidden = false;
      codeEl.textContent = code;
    }
  }
})();

