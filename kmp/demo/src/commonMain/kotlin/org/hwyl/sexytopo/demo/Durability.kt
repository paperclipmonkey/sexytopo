package org.hwyl.sexytopo.demo

/**
 * Whether the platform has promised to keep what the app has saved.
 *
 * On a phone this question does not arise: a file in the app's own container stays there until the
 * app is deleted. In a browser it very much does. `localStorage` is *best-effort* storage by
 * specification — the browser may reclaim it under storage pressure, and clearing site data takes
 * it with everything else — so a survey written by [BrowserFileStore] is, by default, data the
 * browser is entitled to throw away.
 *
 * The Storage API's `navigator.storage.persist()` is how a page asks not to be treated that way.
 * Chrome grants it silently to a site the user has engaged with or installed to the home screen,
 * which the browser build is meant to be; Firefox prompts; Safari does its own thing. It costs one
 * call and it is the difference between "the browser may clear this" and "the browser will not".
 *
 * [durabilityWarning] exists because the answer can still be no, and a surveyor who thinks their
 * trip is saved when it is only probably saved is worse off than one who knows to export it.
 */
expect fun requestDurableStorage()

/**
 * What to tell the surveyor about how safe their saved surveys are, or null when there is nothing
 * to say.
 *
 * Null on every native platform, and null in a browser that has promised to keep the data. Non-null
 * is a real caveat, not a disclaimer: it means this survey could be gone after a storage squeeze or
 * a cleared cache.
 */
expect fun durabilityWarning(): String?
