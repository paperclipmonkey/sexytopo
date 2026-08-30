package org.hwyl.sexytopo.demo

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import org.hwyl.sexytopo.shared.io.store.FileStore
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSURL
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.stringWithContentsOfURL
import platform.Foundation.writeToURL

/**
 * A [FileStore] over the app's Documents directory.
 *
 * Documents rather than a private container, deliberately: with `UIFileSharingEnabled` and
 * `LSSupportsOpeningDocumentsInPlace` in `Info.plist` — both already set — everything written here
 * shows up in the Files app under "On My iPhone / SexyTopo". That is the iOS counterpart of the
 * Storage Access Framework folder the Android app uses, and it means a surveyor can hand the
 * `.data.json` to Therion or email it without the app needing a share sheet of its own.
 *
 * Paths are lists of names throughout, so nothing here has to agree with any other platform about
 * separators.
 *
 * ## Status
 *
 * Compiled on every push by the macOS CI job, and **never run**. The iOS simulator would exercise
 * it, but nothing in this repository launches one yet. Expect the first device build to find
 * something; the shared [org.hwyl.sexytopo.shared.io.store.SurveyStorage] above it is the part that
 * is tested, and it is deliberately the larger part.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class DocumentsFileStore : FileStore {

    private val fileManager = NSFileManager.defaultManager

    private val root: String =
        (
            NSSearchPathForDirectoriesInDomains(
                NSDocumentDirectory,
                NSUserDomainMask,
                true,
            ).firstOrNull() as? String
        ) ?: NSTemporaryFallback

    private fun pathOf(path: List<String>): String =
        if (path.isEmpty()) root else root + "/" + path.joinToString("/")

    private fun urlOf(path: List<String>): NSURL = NSURL.fileURLWithPath(pathOf(path))

    override fun exists(path: List<String>): Boolean =
        fileManager.fileExistsAtPath(pathOf(path))

    override fun isDirectory(path: List<String>): Boolean {
        // The two-argument form needs a pointer out-param; asking the contents instead avoids the
        // cinterop dance and answers the same question for our purposes.
        if (!exists(path)) return false
        return fileManager.contentsOfDirectoryAtPath(pathOf(path), null) != null
    }

    override fun list(path: List<String>): List<String> =
        (fileManager.contentsOfDirectoryAtPath(pathOf(path), null) ?: emptyList<Any?>())
            .filterIsInstance<String>()
            .sorted()

    override fun readText(path: List<String>): String? =
        NSString.stringWithContentsOfURL(urlOf(path), NSUTF8StringEncoding, null) as String?

    override fun writeText(path: List<String>, content: String) {
        createDirectory(path.dropLast(1))
        (content as NSString).writeToURL(urlOf(path), true, NSUTF8StringEncoding, null)
    }

    /**
     * The bytes of a file, for the one caller that needs them: PocketTopo's binary `.top`.
     *
     * `NSData` copied into a Kotlin `ByteArray` rather than wrapped, because the array outlives
     * this function and the `NSData`'s buffer is only guaranteed while it is alive.
     */
    override fun readBytes(path: List<String>): ByteArray? {
        val data = fileManager.contentsAtPath(pathOf(path)) ?: return null
        val length = data.length.toInt()
        if (length == 0) return ByteArray(0)
        val start = data.bytes?.reinterpret<ByteVar>() ?: return null
        return ByteArray(length) { index -> start[index] }
    }

    override fun createDirectory(path: List<String>) {
        if (path.isEmpty()) return
        fileManager.createDirectoryAtPath(pathOf(path), true, null, null)
    }

    override fun delete(path: List<String>): Boolean {
        if (!exists(path)) return false
        return fileManager.removeItemAtPath(pathOf(path), null)
    }

    private companion object {
        /** Only reachable if the OS reports no Documents directory, which should not happen. */
        const val NSTemporaryFallback = "/tmp"
    }
}

actual fun platformFileStore(): FileStore = DocumentsFileStore()
