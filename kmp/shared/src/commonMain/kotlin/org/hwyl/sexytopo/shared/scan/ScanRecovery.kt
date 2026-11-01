package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.io.store.FileStore
import org.hwyl.sexytopo.shared.model.graph.Coord3D

/**
 * A [TileSink] that keeps each tile as a file, which is what makes a long scan's memory bounded on
 * a phone: [SurfaceTiles] sends the tiles behind the surveyor here and keeps only the ones round
 * them.
 */
class FileTileSink(private val store: FileStore, private val directory: List<String>) : TileSink {

    override fun store(tile: SurfaceTile) {
        store.writeBytes(directory + nameFor(tile.key), SurfaceTileCodec.encode(tile))
    }

    override fun load(key: TileKey): SurfaceTile? =
        store.readBytes(directory + nameFor(key))?.let { runCatching { SurfaceTileCodec.decode(it) }.getOrNull() }

    /** Every tile in the directory, whether this sink wrote it or one before it did. */
    fun storedKeys(): List<TileKey> =
        store.list(directory).mapNotNull { name ->
            val parts = name.removePrefix(PREFIX).removeSuffix(SUFFIX).split('_')
            if (!name.startsWith(PREFIX) || !name.endsWith(SUFFIX) || parts.size != 3) return@mapNotNull null
            val (x, y, z) = parts.map { it.toIntOrNull() ?: return@mapNotNull null }
            TileKey(x, y, z)
        }

    private fun nameFor(key: TileKey): String = "$PREFIX${key.x}_${key.y}_${key.z}$SUFFIX"

    private companion object {
        const val PREFIX = "tile_"
        const val SUFFIX = ".bin"
    }
}

/**
 * A scan in progress, written down every few seconds so a crash or a flat battery underground
 * loses seconds rather than the trip.
 *
 * Two halves, for the reason [WallScanStore] has two files. The rock is the tiles, which the scan
 * already writes through a [FileTileSink] into [TILES]; a checkpoint only flushes the ones still in
 * memory. Everything else — the track, the marks, the pen lines, the stamps — is small, and is
 * written whole as a scan's JSON with no points in it.
 *
 * There is only ever one scan in progress, so it lives at a fixed place in the app's storage
 * rather than beside a survey: the scanner screen does not know which survey it is for, and the
 * app finds it the next time a scan is begun or reviewed, and gives it to the survey then.
 */
object ScanRecovery {

    val DIRECTORY = listOf("wall-scan-in-progress")
    private val TILES = DIRECTORY + "tiles"
    private val CAPTURE = DIRECTORY + "scan.json"
    private val SURVEY = DIRECTORY + "survey.txt"
    private const val RECOVERY_ID = "in-progress"

    /** The sink a scanner should keep its tiles in, so a checkpoint has the rock as well. */
    fun tileSink(store: FileStore): FileTileSink = FileTileSink(store, TILES)

    /**
     * Clears what a previous scan left, ready for a new one in survey [surveyName], which is
     * written down so the scan is only ever recovered into the survey it was taken in.
     */
    fun start(store: FileStore, surveyName: String? = null) {
        store.delete(DIRECTORY)
        if (surveyName != null) store.writeText(SURVEY, surveyName)
    }

    /** The survey the scan in progress belongs to, or null if there is none or it was not said. */
    fun surveyOf(store: FileStore): String? = if (exists(store)) store.readText(SURVEY) else null

    /**
     * Writes down everything but the rock. The caller flushes its tiles first, so the files on
     * disk and this agree to within one checkpoint.
     */
    fun checkpoint(
        store: FileStore,
        path: List<Coord3D>,
        touches: List<StationTouch>,
        penLines: List<PenLine>,
        stamps: List<SymbolStamp>,
        startStation: String?,
        poorTracking: Set<Int> = emptySet(),
    ) {
        val capture = WallScan(emptyList(), path, touches, penLines, stamps, startStation, poorTracking)
        store.writeText(CAPTURE, WallScanStore.write(WallScanRecord(RECOVERY_ID, capture)))
    }

    fun exists(store: FileStore): Boolean = store.exists(CAPTURE)

    /**
     * The scan that was in progress, rock and all, or null if there is none or it cannot be read.
     * Does not clear it: the caller does that once the scan is safely saved somewhere else.
     */
    fun recover(store: FileStore): WallScan? {
        val text = store.readText(CAPTURE) ?: return null
        val sink = tileSink(store)
        val points = mutableListOf<Coord3D>()
        for (key in sink.storedKeys()) sink.load(key)?.forEachPoint { points.add(it) }
        val capture = runCatching { WallScanStore.read(text, emptyList()).scan }.getOrNull() ?: return null
        // A checkpoint taken before any rock was flushed still has its marks and pen lines, which
        // are worth having; a checkpoint with nothing at all in it is not a scan.
        if (points.isEmpty() && capture.penLines.isEmpty() && capture.touches.isEmpty() && capture.stamps.isEmpty()) {
            return null
        }
        return WallScan(
            points,
            capture.path,
            capture.touches,
            capture.penLines,
            capture.stamps,
            capture.startStation,
            capture.poorTracking,
        )
    }

    fun clear(store: FileStore) {
        store.delete(DIRECTORY)
    }
}
