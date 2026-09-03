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
//
// Staying current without ever blocking on it. Cache-first hides updates by construction - a hit
// answers before anything asks whether the network has something newer. Two things claw that back,
// and neither costs the offline case anything:
//
//  1. CACHE is namespaced per build: `%%BUILD_ID%%` below is substituted by the Gradle build (see
//     `serviceWorkerBuildId` in demo/build.gradle.kts) with the commit or timestamp that produced
//     this file. A real deploy therefore changes these bytes, which is exactly what the browser's
//     own service-worker update check is looking for the next time it looks (index.html calls
//     `registration.update()` on load and whenever the tab returns to the foreground, so that is
//     usually within a surveyor's next open of the app, not whenever the browser gets around to
//     its own 24-hour check). `activate` below already deletes every cache but the current one, so
//     the new build's cache starts empty - the first request for `demo.js`, or anything else, under
//     the new name is a genuine miss, and `hit || fetching` in the fetch handler falls through to
//     the network response rather than anything the previous deploy left behind.
//  2. `self.skipWaiting()` and `self.clients.claim()` (below) mean the new worker activates and
//     takes over already-open tabs immediately rather than waiting for them to close, which fires
//     `controllerchange` on `navigator.serviceWorker` in each of them. index.html listens for that
//     to offer a reload - never to force one, since a surveyor can be mid-sketch when it happens -
//     while a tab's *own* first controller (its first-ever install) is not treated as an update.
//
// Nothing here makes the app wait on the network to open; it only changes what a tab that already
// has a signal will notice, and how soon.
const CACHE = 'sexytopo-%%BUILD_ID%%'

// Everything named in the build output. Hashed filenames change per build, so rather than list
// them the install step fetches the page and its module graph, and the fetch handler caches
// anything else the app asks for on first use while it still has signal.
//
// Fetched with `cache: 'reload'`, bypassing the browser's own HTTP cache rather than just this
// worker's. Install only runs when the byte diff above has already found a genuinely new sw.js, so
// this is a handful of small files, rarely - the point where paying for a guaranteed-fresh fetch is
// cheap and the alternative is silently re-precaching whatever `index.html` a same-origin request
// happened to have sitting in the browser's HTTP cache from the build this install is replacing.
const CORE = ['./', './index.html', './manifest.webmanifest'].map(
  (url) => new Request(url, { cache: 'reload' }),
)

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
