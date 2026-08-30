package org.hwyl.sexytopo.demo

/**
 * Puts [text] on the system clipboard, returning whether it went.
 *
 * The way a survey leaves the phone. On iOS a caver pastes it into Notes, Mail or Files and it is
 * off the device; without it the only route out is retyping from a photograph of the screen, which
 * would make the export screen decorative.
 *
 * Best-effort by design: clipboard access is permission-gated on the web and absent in some
 * contexts, and a refusal must not take the app down mid-survey.
 */
expect fun copyToClipboard(text: String): Boolean
