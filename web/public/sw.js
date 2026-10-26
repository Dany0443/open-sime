const CACHE_NAME = "open-sime-v2";
const SHELL_ASSETS = [
  "/",
  "/index.html",
  "/public/style.css",
  "/public/app.js",
  "/public/strings.js",
  "/public/manifest.json",
  "/public/assets/favicon.svg",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME).then((cache) => cache.addAll(SHELL_ASSETS)).then(() => self.skipWaiting())
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches.keys().then((keys) =>
      Promise.all(keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k)))
    ).then(() => self.clients.claim())
  );
});

self.addEventListener("fetch", (event) => {
  const url = new URL(event.request.url);

  // NEVER cache auth endpoints or health check - pass straight to network
  if (url.pathname.startsWith("/api/auth/") || url.pathname === "/api/health") {
    return;
  }

  // API data endpoints (overview, catalog, schedule, absences, teachers, homework)
  if (url.pathname.startsWith("/api/")) {
    if (event.request.method !== "GET") {
      return;
    }
    event.respondWith(
      fetch(event.request)
        .then((response) => {
          if (response.ok) {
            const clone = response.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
          }
          return response;
        })
        .catch(() => {
          return caches.match(event.request, { ignoreSearch: false }).then((cached) => {
            if (cached) return cached;
            return new Response(JSON.stringify({ error: { code: "OFFLINE", message: "Ești offline" } }), {
              status: 503,
              headers: { "Content-Type": "application/json; charset=utf-8" },
            });
          });
        })
    );
    return;
  }

  // Static shell & assets: network-first with cache fallback so desktop sidecar updates never serve stale UI
  event.respondWith(
    fetch(event.request)
      .then((response) => {
        if (response.ok && event.request.method === "GET") {
          const clone = response.clone();
          caches.open(CACHE_NAME).then((cache) => cache.put(event.request, clone));
        }
        return response;
      })
      .catch(() => caches.match(event.request))
  );
});
