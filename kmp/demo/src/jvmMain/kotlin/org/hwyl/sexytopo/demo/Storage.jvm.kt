package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.FileStore
import java.io.File

/**
 * A [FileStore] over a real directory on disk.
 *
 * The desktop build is not a toy target: it is the quickest way to try the whole app on a Mac
 * before opening Xcode, and the only one that needs no SDK, no simulator and no phone. A survey
 * that vanished when the window closed would make it one.
 *
 * The same shape as the Android store next door, and for the same reason: `SurveyStorage` above it
 * does all the naming and layout, so a platform only has to answer eight questions about files.
 *
 * Internal rather than private so the tests can put one over a temporary directory instead of
 * reimplementing it — a duplicate that would drift the first time this changed.
 */
internal class DirectoryFileStore(private val root: File) : FileStore {

    private fun fileFor(path: List<String>) = path.fold(root) { dir, name -> File(dir, name) }

    override fun exists(path: List<String>): Boolean = fileFor(path).exists()

    override fun isDirectory(path: List<String>): Boolean = fileFor(path).isDirectory

    override fun list(path: List<String>): List<String> = fileFor(path).list()?.sorted() ?: emptyList()

    override fun readText(path: List<String>): String? = fileFor(path).takeIf { it.isFile }?.readText()

    override fun writeText(path: List<String>, content: String) {
        val file = fileFor(path)
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    override fun readBytes(path: List<String>): ByteArray? =
        fileFor(path).takeIf { it.isFile }?.readBytes()

    override fun createDirectory(path: List<String>) {
        fileFor(path).mkdirs()
    }

    override fun delete(path: List<String>): Boolean =
        fileFor(path).takeIf { it.exists() }?.deleteRecursively() ?: false
}

/**
 * Where a desktop operating system expects an application to keep its data.
 *
 * Three conventions rather than one dotfile in the home directory, because a surveyor looking for
 * their files should find them where every other application on that machine puts them — and
 * because on macOS, which is the desktop this project is most likely to be run on, a stray dotted
 * folder in the home directory is a wart the Finder does not even show.
 */
internal fun desktopStorageRoot(
    osName: String = System.getProperty("os.name").orEmpty(),
    home: String = System.getProperty("user.home").orEmpty(),
    appData: String? = System.getenv("APPDATA"),
    xdgDataHome: String? = System.getenv("XDG_DATA_HOME"),
): File {
    val os = osName.lowercase()
    return when {
        os.contains("mac") || os.contains("darwin") ->
            File("$home/Library/Application Support/SexyTopo KMP")
        os.contains("win") ->
            File(appData?.takeIf { it.isNotBlank() } ?: "$home/AppData/Roaming", "SexyTopo KMP")
        else ->
            File(xdgDataHome?.takeIf { it.isNotBlank() } ?: "$home/.local/share", "sexytopo-kmp")
    }
}

/**
 * Falls back to memory rather than throwing.
 *
 * A read-only home directory, a sandbox, a CI runner with no writable user directory: none of those
 * should stop the app opening. The library reports what it could not do; losing surveys on exit is
 * a great deal better than not starting.
 */
actual fun platformFileStore(): FileStore =
    runCatching {
        val root = desktopStorageRoot()
        root.mkdirs()
        require(root.isDirectory && root.canWrite()) { "$root is not writable" }
        DirectoryFileStore(root)
    }.getOrElse { org.hwyl.sexytopo.shared.io.store.InMemoryFileStore() }
