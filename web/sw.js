// Offline shell. App files: serve from cache, refresh in the background (new versions apply on next open).
// Model API calls are cross-origin and never touched.
const CACHE = 'closet-v1';
const SHELL = ['./', 'index.html', 'app.css', 'app.js', 'engine.js', 'vision.js', 'fortune.js', 'birth.js', 'daily.js', 'cities.js', 'regions.json', 'vendor/lunar.js', 'vendor/astronomy.js', 'db.js', 'images.js',
  'manifest.webmanifest', 'fonts/serif_sc.woff2', 'fonts/display.woff2', 'icons/icon-192.png', 'icons/apple-touch-icon.png'];

self.addEventListener('install', e => {
  // cache: 'reload' bypasses the browser's HTTP cache (GitHub Pages sends max-age=600), so a new
  // version never gets installed with stale files.
  e.waitUntil(caches.open(CACHE).then(c => c.addAll(SHELL.map(u => new Request(u, { cache: 'reload' })))).then(() => self.skipWaiting()));
});
self.addEventListener('activate', e => {
  e.waitUntil(caches.keys().then(ks => Promise.all(ks.filter(k => k !== CACHE).map(k => caches.delete(k)))).then(() => self.clients.claim()));
});
self.addEventListener('fetch', e => {
  const url = new URL(e.request.url);
  if (e.request.method !== 'GET' || url.origin !== location.origin) return;
  e.respondWith(caches.open(CACHE).then(async c => {
    const hit = await c.match(e.request, { ignoreSearch: true });
    const net = fetch(e.request, { cache: 'no-cache' }).then(r => { if (r.ok) c.put(e.request, r.clone()); return r; }).catch(() => null);
    return hit ?? (await net) ?? new Response('离线，且没有缓存', { status: 503 });
  }));
});
