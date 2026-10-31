package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import java.io.File

/**
 * A [FileStore] over the app's private files directory.
 *
 * Deliberately not the Storage Access Framework folder the real SexyTopo asks for: that needs a
 * folder-picker `Intent`, a persisted URI permission and `DocumentFile`, a platform surface of its
 * own rather than a `FileStore` implementation. This just keeps a survey across a restart; getting
 * the files out is still Export and the clipboard.
 */
private class FilesDirFileStore(private val root: File) : FileStore {

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

    override fun writeBytes(path: List<String>, bytes: ByteArray) {
        val file = fileFor(path)
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
    }

    override fun createDirectory(path: List<String>) {
        fileFor(path).mkdirs()
    }

    override fun delete(path: List<String>): Boolean =
        fileFor(path).takeIf { it.exists() }?.deleteRecursively() ?: false
}

actual fun platformFileStore(): FileStore =
    AndroidHost.appContext?.let { FilesDirFileStore(it.filesDir) } ?: InMemoryFileStore()
