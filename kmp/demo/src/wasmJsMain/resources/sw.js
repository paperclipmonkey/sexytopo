// Service worker: what makes this usable in a cave.
//
// A cave has no signal. Without this the app is a web page that fails to load the moment the
// surveyor is underground, which would make everything else here pointless. With it, the whole
// app — the WebAssembly module, Skia, the bundled font, the toolbar icons — is on the phone, and
// the network is only ever consulted to find out whether there is a newer version.
//
// Cache-first, deliberately. The usual advice is network-first with a cache fallback, which is
// right for a news site and wrong here: at the mouth of a cave a phone often has *one bar*, so a
// network attempt does not fail fast, it hangs. Serving from cache and updating in the background
// means the app opens instantly whatever the signal is doing.
const CACHE = 'sexytopo-v1'

// Everything named in the build output. Hashed filenames change per build, so rather than list
// them the install step fetches the page and its module graph, and the fetch handler caches
// anything else the app asks for on first use while it still has signal.
const CORE = ['./', './index.html', './manifest.webmanifest']

self.addEventListener('install', (event) => {
  event.waitUntil(
    caches
      .open(CACHE)
      .then((cache) => cache.addAll(CORE))
      // A failure here must not leave the surveyor with no worker at all; the fetch handler will
      // fill the cache on first use instead.
      .catch(() => undefined)
      .then(() => self.skipWaiting()),
  )
})

self.addEventListener('activate', (event) => {
  event.waitUntil(
    caches
      .keys()
      .then((names) => Promise.all(names.filter((n) => n !== CACHE).map((n) => caches.delete(n))))
      .then(() => self.clients.claim()),
  )
})

self.addEventListener('fetch', (event) => {
  const request = event.request
  if (request.method !== 'GET') return
  // Only our own origin: nothing here should be trying to cache somebody else's server.
  if (new URL(request.url).origin !== self.location.origin) return

  event.respondWith(
    caches.match(request).then((hit) => {
      // Refresh in the background so a later launch gets the newer build, but never wait for it.
      const fetching = fetch(request)
        .then((response) => {
          if (response && response.ok) {
            const copy = response.clone()
            caches.open(CACHE).then((cache) => cache.put(request, copy))
          }
          return response
        })
        .catch(() => hit)

      return hit || fetching
    }),
  )
})
