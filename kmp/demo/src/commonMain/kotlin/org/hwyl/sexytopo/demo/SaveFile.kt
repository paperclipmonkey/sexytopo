package org.hwyl.sexytopo.demo

/**
 * Writes an exported survey out as a file, and says where it went.
 *
 * No two platforms agree on where a user-facing file goes, so this returns a description a human
 * can act on rather than a path a program could. Returns null if nothing could be written, which
 * the UI reports rather than swallowing.
 */
expect fun saveTextFile(filename: String, text: String): String?

/**
 * The same, for a file that is not text.
 *
 * A separate seam rather than bytes added to [FileStore]: `saveTextFile` writes an *export* to
 * wherever a platform lets a user get at it, a different place from where the app keeps its own
 * surveys, and only one thing (handing over a whole survey as a zip) needs bytes.
 */
expect fun saveBinaryFile(filename: String, bytes: ByteArray): String?
