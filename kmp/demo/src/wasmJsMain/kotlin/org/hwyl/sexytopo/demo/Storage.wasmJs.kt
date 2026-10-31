package org.hwyl.sexytopo.demo

import kotlinx.browser.window
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.hwyl.sexytopo.shared.io.store.FileStore

/**
 * A [FileStore] over the browser's `localStorage`.
 *
 * Chosen over IndexedDB on purpose. IndexedDB is asynchronous, and threading suspending reads
 * through a synchronous [FileStore] would either change that interface for every platform or need
 * a cache in front of it that has its own consistency problems. A cave survey is text - a few tens
 * of kilobytes of JSON - and `localStorage` holds around 5 MB per origin, so the simpler thing is
 * also the sufficient thing. If a survey ever outgrows that, [save] reports the failure rather than
 * losing data quietly - which is no longer hypothetical now that a survey can carry photographs;
 * see [writeBytes].
 *
 * Keys are the joined path under a prefix, so one origin can hold this app's surveys without
 * colliding with anything else the page stores.
 */
class BrowserFileStore(private val prefix: String = "sexytopo:") : FileStore {

    private val storage = window.localStorage

    private fun fileKey(path: List<String>) = prefix + "f:" + path.joinToString("/")

    private fun dirKey(path: List<String>) = prefix + "d:" + path.joinToString("/")

    /**
     * Where a binary file lives, base64-encoded.
     *
     * `localStorage` holds strings, and a `.top` file put through it as text comes back mangled —
     * every byte that is not valid UTF-8 becomes a replacement character, which for a binary format
     * means a length prefix moves and everything after it is garbage. Base64 costs a third more
     * space and is exact.
     */
    private fun binaryKey(path: List<String>) = prefix + "b:" + path.joinToString("/")

    private fun allKeys(): List<String> =
        (0 until storage.length).mapNotNull { storage.key(it) }.filter { it.startsWith(prefix) }

    override fun exists(path: List<String>): Boolean =
        storage.getItem(fileKey(path)) != null ||
            storage.getItem(binaryKey(path)) != null ||
            isDirectory(path)

    override fun isDirectory(path: List<String>): Boolean =
        path.isEmpty() || storage.getItem(dirKey(path)) != null

    override fun list(path: List<String>): List<String> {
        val depth = path.size
        val parent = path.joinToString("/")
        val names = mutableSetOf<String>()
        for (key in allKeys()) {
            val body = key.removePrefix(prefix).substringAfter(':')
            if (body.isEmpty()) continue
            val parts = body.split("/")
            if (parts.size != depth + 1) continue
            if (parts.dropLast(1).joinToString("/") != parent) continue
            names.add(parts.last())
        }
        return names.sorted()
    }

    override fun readText(path: List<String>): String? = storage.getItem(fileKey(path))

    /**
     * Bytes, from either kind of key.
     *
     * A file the picker stored as base64 decodes; anything else is text, and its UTF-8 bytes are
     * what a caller asking for bytes wants.
     */
    @OptIn(ExperimentalEncodingApi::class)
    override fun readBytes(path: List<String>): ByteArray? {
        storage.getItem(binaryKey(path))?.let { encoded ->
            return runCatching { Base64.decode(encoded) }.getOrNull()
        }
        return storage.getItem(fileKey(path))?.encodeToByteArray()
    }

    override fun writeText(path: List<String>, content: String) {
        createDirectory(path.dropLast(1))
        // Throws QuotaExceededError when the origin's storage is full; SurveyLibrary turns that
        // into a message rather than letting it reach the surveyor as a crash.
        storage.setItem(fileKey(path), content)
    }

    /**
     * Bytes, base64-encoded under [binaryKey] — the mirror image of what [readBytes] decodes.
     *
     * Throws QuotaExceededError when the origin's storage is full, exactly as [writeText] does,
     * and for the same reason it is left to throw: SurveyLibrary turns it into a message rather
     * than letting it reach the surveyor as a crash. Worth saying twice here, because this is the
     * call that will actually hit the limit — a survey's JSON is tens of kilobytes, a photograph
     * is megabytes, and base64 adds a third to it again, so a handful of pictures fills the ~5 MB
     * an origin gets.
     */
    @OptIn(ExperimentalEncodingApi::class)
    override fun writeBytes(path: List<String>, bytes: ByteArray) {
        createDirectory(path.dropLast(1))
        // The text key first, so a path that held text and now holds bytes cannot be found as
        // both: readBytes prefers the binary key, and a stale twin would only mislead readText.
        storage.removeItem(fileKey(path))
        storage.setItem(binaryKey(path), Base64.encode(bytes))
    }

    override fun createDirectory(path: List<String>) {
        for (i in 1..path.size) {
            storage.setItem(dirKey(path.take(i)), "")
        }
    }

    override fun delete(path: List<String>): Boolean {
        val body = path.joinToString("/")
        val doomed = allKeys().filter {
            val rest = it.removePrefix(prefix).substringAfter(':')
            rest == body || rest.startsWith("$body/")
        }
        doomed.forEach { storage.removeItem(it) }
        return doomed.isNotEmpty()
    }
}

actual fun platformFileStore(): FileStore = BrowserFileStore()
