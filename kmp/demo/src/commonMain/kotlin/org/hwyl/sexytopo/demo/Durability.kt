package org.hwyl.sexytopo.demo

/**
 * Whether the platform has promised to keep what the app has saved.
 *
 * `localStorage` is *best-effort* by specification: the browser may reclaim it under storage
 * pressure. `navigator.storage.persist()` is how a page asks not to be treated that way — Chrome
 * grants it silently, Firefox prompts, Safari does its own thing. On native platforms this
 * question does not arise.
 */
expect fun requestDurableStorage()

/** What to tell the surveyor about how safe their saved surveys are, or null when there is nothing to say. */
expect fun durabilityWarning(): String?
