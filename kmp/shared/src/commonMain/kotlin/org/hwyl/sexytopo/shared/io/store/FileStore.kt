package org.hwyl.sexytopo.shared.io.store

/**
 * The whole of the filesystem, as this port needs it.
 *
 * Deliberately tiny. The Android app reaches the filesystem through `DocumentFile`, an abstraction
 * over the Storage Access Framework, and the temptation when porting is to reproduce that shape.
 * There is no reason to: the entire 8,000-line `control/io` package uses ten `DocumentFile` methods
 * and exactly two byte-level operations, `IoUtils.slurpFile` and `IoUtils.saveToFile`. Everything
 * else is naming and orchestration, which is why almost all of this layer can live in `commonMain`.
 *
 * Paths are lists of names rather than strings, so no implementation has to agree with any other
 * about separators, escaping, or what a leading slash means.
 *
 * ## Why this is an interface rather than an expect/actual
 *
 * So that tests can use [InMemoryFileStore]. That is not a convenience: the Android app's own
 * `MetadataTranslaterTest` has to Mockito-mock `Uri` and `DocumentFile`, and its one real
 * round-trip test is `@Ignore`d with the note "To mock static methods, need to use inline mocks,
 * which breaks other tests". Behind an interface with a fake, that test runs - on three platforms.
 */
interface FileStore {

    /** Whether anything exists at [path]. */
    fun exists(path: List<String>): Boolean

    /** Whether [path] names a directory rather than a file. */
    fun isDirectory(path: List<String>): Boolean

    /**
     * The names directly inside directory [path], in a stable order.
     *
     * Stable matters: the Android app's PocketTopo exporter is unreproducible precisely because it
     * iterates a hash-ordered collection, and a store that listed files in a varying order would
     * push the same problem into every caller here.
     */
    fun list(path: List<String>): List<String>

    /** Reads a file as UTF-8 text, or null if it is absent. */
    fun readText(path: List<String>): String?

    /** Writes UTF-8 text, creating the file and any missing parent directories. */
    fun writeText(path: List<String>, content: String)

    /** Creates a directory and any missing parents. Succeeds if it already exists. */
    fun createDirectory(path: List<String>)

    /** Deletes a file, or a directory and everything under it. Returns false if it was absent. */
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
    private val directories = mutableSetOf<String>()

    init {
        directories.add(key(emptyList()))
    }

    private fun key(path: List<String>): String = path.joinToString("/")

    override fun exists(path: List<String>): Boolean =
        files.containsKey(key(path)) || directories.contains(key(path))

    override fun isDirectory(path: List<String>): Boolean = directories.contains(key(path))

    override fun list(path: List<String>): List<String> {
        val prefix = key(path)
        val depth = if (path.isEmpty()) 0 else path.size
        val names = mutableSetOf<String>()
        for (candidate in files.keys + directories) {
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
        files[key(path)] = content
    }

    override fun createDirectory(path: List<String>) {
        for (i in 0..path.size) {
            directories.add(key(path.take(i)))
        }
    }

    override fun delete(path: List<String>): Boolean {
        val target = key(path)
        val removedFile = files.remove(target) != null
        val removedDirectory = directories.remove(target)
        // A directory takes its contents with it.
        val prefix = "$target/"
        val descendants = files.keys.filter { it.startsWith(prefix) }
        descendants.forEach { files.remove(it) }
        directories.removeAll { it.startsWith(prefix) }
        return removedFile || removedDirectory
    }
}
