package org.hwyl.sexytopo.demo

/**
 * Writes an exported survey out as a file, and says where it went.
 *
 * The clipboard beside this button is fine for one file on one phone. It stops being fine on the
 * way home from a trip with four exports to hand somebody, and it cannot carry a file into Therion
 * at all — a caver needs an actual `.svx` on an actual filesystem. Every platform has somewhere to
 * put one, and no two of them agree on where, so this returns a description a human can act on
 * rather than a path a program could.
 *
 * Returns null if nothing could be written, which the UI reports rather than swallowing: a save
 * that silently did nothing is worse than one that says it failed.
 */
expect fun saveTextFile(filename: String, text: String): String?

/**
 * The same, for a file that is not text.
 *
 * A separate seam rather than bytes added to [FileStore], because that is the shape the text one
 * already has: `saveTextFile` writes an *export* to wherever a platform lets a user get at it,
 * which is a different place from where the app keeps its own surveys. Only one thing needs it —
 * handing over a whole survey as a zip — and giving `FileStore` a `writeBytes` would mean the
 * browser's local storage, the iOS Documents directory and three others all learning to hold bytes
 * for the sake of one button.
 */
expect fun saveBinaryFile(filename: String, bytes: ByteArray): String?
