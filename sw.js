// Service Worker: macht Camwater offline nutzbar und nimmt Fotos an,
// die über "Teilen → Camwater" (z.B. aus der Samsung-Galerie) kommen.

const VERSION = "camwater-v1";
const SHELL = [
  "./",
  "index.html",
  "css/style.css",
  "js/app.js",
  "js/exif.js",
  "js/presets.js",
  "js/render.js",
  "manifest.webmanifest",
  "icons/icon.svg",
  "icons/icon-192.png",
  "icons/icon-512.png",
  "icons/icon-maskable-512.png",
];

self.addEventListener("install", (event) => {
  event.waitUntil(
    caches
      .open(VERSION)
      .then((cache) => cache.addAll(SHELL))
      .then(() => self.skipWaiting()),
  );
});

self.addEventListener("activate", (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((keys) => Promise.all(keys.filter((k) => k !== VERSION && k !== "camwater-share").map((k) => caches.delete(k))))
      .then(() => self.clients.claim()),
  );
});

async function receiveShare(request) {
  const form = await request.formData();
  const files = form.getAll("photos").filter((f) => f && typeof f !== "string");
  const cache = await caches.open("camwater-share");
  for (const old of await cache.keys()) await cache.delete(old);
  let i = 0;
  for (const file of files) {
    await cache.put(
      new Request(`shared/${Date.now()}-${i++}`),
      new Response(file, {
        headers: {
          "content-type": file.type || "image/jpeg",
          "x-filename": encodeURIComponent(file.name || `Foto-${i}.jpg`),
          "x-modified": String(file.lastModified || Date.now()),
        },
      }),
    );
  }
  return Response.redirect(new URL(`./?shared=${files.length}`, self.registration.scope).href, 303);
}

self.addEventListener("fetch", (event) => {
  const { request } = event;
  const url = new URL(request.url);

  if (request.method === "POST" && url.pathname.endsWith("/share-target")) {
    event.respondWith(receiveShare(request));
    return;
  }
  if (request.method !== "GET" || url.origin !== self.location.origin) return;

  // Netzwerk zuerst (damit Updates ankommen), sonst aus dem Offline-Speicher.
  event.respondWith(
    fetch(request)
      .then((res) => {
        if (res.ok) {
          const copy = res.clone();
          caches.open(VERSION).then((cache) => cache.put(request, copy));
        }
        return res;
      })
      .catch(() => caches.match(request, { ignoreSearch: true }).then((hit) => hit || caches.match("index.html"))),
  );
});
