const CACHE_NAME = 'pulse-ios-__BUILD_HASH__';

const PRECACHE_URLS = [
  '../',
  '../index.html',
  '../setup.html',
  '../config.json',
  './manifest.json',
  '../runtime/storage.js',
  '../runtime/ipc.js',
  '../runtime/bridge.js',
  '../parsers/schedule.parser.js',
  '../parsers/diary.parser.js',
  '../parsers/messages.parser.js'
];

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches.open(CACHE_NAME)
      .then((cache) => cache.addAll(PRECACHE_URLS))
      .catch(() => {})
      .then(() => self.skipWaiting())
  );
});

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches.keys()
      .then((keys) => Promise.all(
        keys.filter((k) => k !== CACHE_NAME).map((k) => caches.delete(k))
      ))
      .then(() => self.clients.claim())
  );
});

self.addEventListener('fetch', (event) => {
  const req = event.request;
  if (req.method !== 'GET') return;

  const url = new URL(req.url);

  // Внешние запросы (GitHub API, шрифты и т.д.) не кэшируем агрессивно
  if (url.origin !== self.location.origin) {
    return;
  }

  const isCodeOrData = /\.(html|js|json)$/i.test(url.pathname) || url.pathname.endsWith('/');

  if (isCodeOrData) {
    // Network-First: хотфиксы парсеров и config.json подтягиваются сразу при наличии сети
    event.respondWith(
      fetch(req)
        .then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
          }
          return res;
        })
        .catch(() => caches.match(req))
    );
  } else {
    // Cache-First: иконки и статика
    event.respondWith(
      caches.match(req).then((cached) => {
        if (cached) return cached;
        return fetch(req).then((res) => {
          if (res && res.ok) {
            const copy = res.clone();
            caches.open(CACHE_NAME).then((cache) => cache.put(req, copy));
          }
          return res;
        });
      })
    );
  }
});