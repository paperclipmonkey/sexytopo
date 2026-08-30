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
