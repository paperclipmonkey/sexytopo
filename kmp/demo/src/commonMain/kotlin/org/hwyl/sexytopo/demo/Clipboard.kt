package org.hwyl.sexytopo.demo

/**
 * Puts [text] on the system clipboard, returning whether it went.
 *
 * Best-effort by design: clipboard access is permission-gated on the web and absent in some
 * contexts, and a refusal must not take the app down mid-survey.
 */
expect fun copyToClipboard(text: String): Boolean
