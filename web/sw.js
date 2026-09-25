// Кешує оболонку сайту, щоб він швидко відкривався й запускався без мережі.
// Дані про тривоги йдуть через проксі на іншому домені й ніколи не кешуються тут.
const CACHE = "vidbiy-v3";
const SHELL = [
  "./", "index.html", "style.css", "app.js", "icons.js", "regions.js",
  "sounds.js", "nosleep.mp4", "manifest.webmanifest",
  "sounds/marimba.mp3", "sounds/sunrise.mp3", "sounds/harp.mp3", "sounds/birds.mp3",
  "sounds/pulse.mp3", "sounds/classic.mp3", "sounds/bells.mp3",
  "icons/favicon.svg", "icons/apple-touch-icon.png", "icons/icon-192.png", "icons/icon-512.png",
];

self.addEventListener("install", (e) => {
  e.waitUntil(caches.open(CACHE).then((c) => c.addAll(SHELL)).then(() => self.skipWaiting()));
});

self.addEventListener("activate", (e) => {
  e.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== CACHE).map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

// Спершу мережа (щоб оновлення доходили одразу), кеш — якщо мережі немає.
self.addEventListener("fetch", (e) => {
  const url = new URL(e.request.url);
  if (e.request.method !== "GET" || url.origin !== location.origin) return;
  e.respondWith(
    fetch(e.request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(CACHE).then((c) => c.put(e.request, copy));
        }
        return res;
      })
      .catch(() => caches.match(e.request).then((hit) => hit || caches.match("index.html"))),
  );
});
