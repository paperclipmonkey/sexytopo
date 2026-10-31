package org.hwyl.sexytopo.shared.io.store

/**
 * The whole of the filesystem, as this port needs it.
 *
 * Deliberately tiny: `control/io` uses ten `DocumentFile` methods and exactly two byte-level
 * operations, `IoUtils.slurpFile` and `IoUtils.saveToFile`; everything else is naming and
 * orchestration.
 *
 * Paths are lists of names rather than strings, so no implementation has to agree with any other
 * about separators, escaping, or what a leading slash means.
 *
 * An interface rather than an expect/actual so tests can use [InMemoryFileStore]: the Android
 * app's own `MetadataTranslaterTest` has to Mockito-mock `Uri` and `DocumentFile`, and its one
 * real round-trip test is `@Ignore`d with "To mock static methods, need to use inline mocks,
 * which breaks other tests".
 */
interface FileStore {

    fun exists(path: List<String>): Boolean

    fun isDirectory(path: List<String>): Boolean

    /**
     * The names directly inside directory [path], in a stable order.
     *
     * Stable matters: the Android app's PocketTopo exporter is unreproducible precisely because it
     * iterates a hash-ordered collection, and a store that listed files in a varying order would
     * push the same problem into every caller here.
     */
    fun list(path: List<String>): List<String>

    fun readText(path: List<String>): String?

    fun writeText(path: List<String>, content: String)

    /**
     * Reads a file as bytes, or null if it is absent.
     *
     * Bytes rather than text because reading a binary file as text mangles it in a way that is
     * silent: PocketTopo's `.top` loses a byte from a length prefix and everything after it moves.
     */
    fun readBytes(path: List<String>): ByteArray?

    /**
     * Writes a file as bytes, replacing whatever was there, creating the parent directory as
     * [writeText] does.
     *
     * There was nothing here until a survey could carry photographs: they are kept as `.jpg` files
     * beside its JSON (see [PhotoStore]), and a JPEG put through [writeText] comes back destroyed
     * for exactly the reason [readBytes] exists. The Android app has no equivalent — its
     * `IoUtils.saveToFile` takes a String, because nothing it saves is binary.
     */
    fun writeBytes(path: List<String>, bytes: ByteArray)

    fun createDirectory(path: List<String>)

    fun delete(path: List<String>): Boolean
}

/**
 * A [FileStore] held entirely in memory, for tests.
 *
 * Directories are tracked explicitly rather than inferred from file paths, so that an empty
 * directory is a thing that exists - which is what [SurveyStorage.isSurveyDirectory] has to be able
 * to say "no" about.
 */
class InMemoryFileStore : FileStore {

    private val files = mutableMapOf<String, String>()
    private val binaries = mutableMapOf<String, ByteArray>()
    private val directories = mutableSetOf<String>()

    init {
        directories.add(key(emptyList()))
    }

    private fun key(path: List<String>): String = path.joinToString("/")

    private val allNames: Set<String> get() = files.keys + binaries.keys

    override fun exists(path: List<String>): Boolean =
        allNames.contains(key(path)) || directories.contains(key(path))

    override fun isDirectory(path: List<String>): Boolean = directories.contains(key(path))

    override fun list(path: List<String>): List<String> {
        val prefix = key(path)
        val depth = if (path.isEmpty()) 0 else path.size
        val names = mutableSetOf<String>()
        for (candidate in allNames + directories) {
            if (candidate.isEmpty()) continue
            val parts = candidate.split("/")
            if (parts.size != depth + 1) continue
            if (key(parts.dropLast(1)) != prefix) continue
            names.add(parts.last())
        }
        return names.sorted()
    }

    override fun readText(path: List<String>): String? = files[key(path)]

    override fun writeText(path: List<String>, content: String) {
        createDirectory(path.dropLast(1))
        binaries.remove(key(path))
        files[key(path)] = content
    }

    override fun readBytes(path: List<String>): ByteArray? =
        binaries[key(path)] ?: files[key(path)]?.encodeToByteArray()

    override fun writeBytes(path: List<String>, bytes: ByteArray) {
        createDirectory(path.dropLast(1))
        // The two maps are one namespace: a path holds text or bytes, never both, so whichever
        // kind was written last is the only one a reader can find.
        files.remove(key(path))
        binaries[key(path)] = bytes
    }

    override fun createDirectory(path: List<String>) {
        for (i in 0..path.size) {
            directories.add(key(path.take(i)))
        }
    }

    override fun delete(path: List<String>): Boolean {
        val target = key(path)
        val removedFile = files.remove(target) != null || binaries.remove(target) != null
        val removedDirectory = directories.remove(target)
        val prefix = "$target/"
        files.keys.filter { it.startsWith(prefix) }.forEach { files.remove(it) }
        binaries.keys.filter { it.startsWith(prefix) }.forEach { binaries.remove(it) }
        directories.removeAll { it.startsWith(prefix) }
        return removedFile || removedDirectory
    }
}
