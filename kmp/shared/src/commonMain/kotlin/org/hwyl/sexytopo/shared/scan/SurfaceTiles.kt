package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.math.abs
import kotlin.math.floor

/**
 * The rock a long scan has seen, held in a fixed amount of memory however far the surveyor walks.
 *
 * A depth scanner reports the same rock over and over as the phone passes it, so points are pooled
 * into small boxes (voxels) and each box keeps one averaged point. That alone is what a thirty
 * second sweep at a station needs. A walk down a kilometre of passage needs one thing more: boxes
 * are grouped into tiles, a few metres on a side, and only the tiles near the surveyor are kept in
 * memory. The rest go to a [TileSink], which on a phone is a file, and come back if the surveyor
 * returns to them.
 *
 * Some numbers, for a passage ten metres round. At 5 cm boxes a metre of passage is about four
 * thousand boxes, so a kilometre is four million: too many for one in-memory set on a phone that
 * is also running ARKit, and the reason this exists. A 6.4 m tile of that passage holds about
 * twenty-five thousand boxes, and the few tiles around the surveyor fit comfortably.
 *
 * Nothing here touches a file or a sensor, so it is tested on a build server against known
 * points; the platform half supplies the sink and calls [add] and [moveTo].
 */
class SurfaceTiles(
    val voxelMetres: Float,
    private val sink: TileSink,
    /** Boxes along a tile's edge. A tile's index must fit an Int, so at most 1024. */
    val tileVoxels: Int = DEFAULT_TILE_VOXELS,
    /** How many tiles either side of the surveyor stay in memory. */
    val residentRadius: Int = DEFAULT_RESIDENT_RADIUS,
) {

    init {
        require(voxelMetres > 0) { "a box of no size holds nothing" }
        require(tileVoxels in 1..1024) { "a tile's boxes must be numbered by one Int" }
        require(residentRadius >= 0) { "a negative radius keeps less than nothing" }
    }

    val tileMetres: Float get() = voxelMetres * tileVoxels

    private val resident = LinkedHashMap<TileKey, SurfaceTile>()
    private val stored = mutableSetOf<TileKey>()

    /** Tiles currently in memory: what [residentRadius] bounds. */
    val residentTileCount: Int get() = resident.size

    /** Boxes seen so far, in memory or not: the number to show a surveyor as their scan grows. */
    var boxCount: Int = 0
        private set

    /**
     * Pools a point into its box, returning whether that box had never been seen before.
     *
     * A point in a tile that was sent to the sink brings that tile back first, so returning to a
     * passage adds to what was seen there rather than starting it again.
     */
    fun add(east: Float, north: Float, up: Float): Boolean {
        val vx = floor(east / voxelMetres).toInt()
        val vy = floor(north / voxelMetres).toInt()
        val vz = floor(up / voxelMetres).toInt()
        val key = TileKey(floorDiv(vx, tileVoxels), floorDiv(vy, tileVoxels), floorDiv(vz, tileVoxels))
        val tile = tileFor(key)
        val local = localIndex(vx - key.x * tileVoxels, vy - key.y * tileVoxels, vz - key.z * tileVoxels)
        val isNew = tile.add(local, east, north, up)
        if (isNew) boxCount++
        return isNew
    }

    /**
     * Tells the store where the surveyor is now, sending every tile beyond the resident radius to
     * the sink. Called a few times a second, not per point.
     */
    fun moveTo(east: Float, north: Float, up: Float) {
        val here = tileKeyAt(east, north, up)
        val faraway =
            resident.keys.filter {
                abs(it.x - here.x) > residentRadius ||
                    abs(it.y - here.y) > residentRadius ||
                    abs(it.z - here.z) > residentRadius
            }
        for (key in faraway) evict(key)
    }

    /** Sends every tile to the sink, so nothing is lost if the app stops now. */
    fun flush() {
        for (tile in resident.values) {
            if (tile.isDirty) {
                sink.store(tile)
                stored.add(tile.key)
                tile.isDirty = false
            }
        }
    }

    /**
     * Every averaged point, resident or stored, a tile at a time. Stored tiles are loaded one by
     * one and not kept, so walking a whole scan does not undo the memory bound.
     */
    fun forEachPoint(action: (Coord3D) -> Unit) {
        for (tile in resident.values) tile.forEachPoint(action)
        for (key in stored) {
            if (key in resident) continue
            sink.load(key)?.forEachPoint(action)
        }
    }

    private fun tileFor(key: TileKey): SurfaceTile {
        resident[key]?.let { return it }
        val tile = (if (key in stored) sink.load(key) else null) ?: SurfaceTile(key)
        resident[key] = tile
        return tile
    }

    private fun evict(key: TileKey) {
        val tile = resident.remove(key) ?: return
        if (tile.isDirty) {
            sink.store(tile)
            stored.add(key)
            tile.isDirty = false
        }
    }

    private fun tileKeyAt(east: Float, north: Float, up: Float): TileKey =
        TileKey(
            floorDiv(floor(east / voxelMetres).toInt(), tileVoxels),
            floorDiv(floor(north / voxelMetres).toInt(), tileVoxels),
            floorDiv(floor(up / voxelMetres).toInt(), tileVoxels),
        )

    private fun localIndex(x: Int, y: Int, z: Int): Int = (x * tileVoxels + y) * tileVoxels + z

    companion object {
        /** 128 boxes: at 5 cm a tile is 6.4 m on a side, a sensible stretch of passage. */
        const val DEFAULT_TILE_VOXELS = 128

        /**
         * One tile either side: the surveyor's own tile and its neighbours, so the phone pointed
         * back up the passage still adds to tiles in memory.
         */
        const val DEFAULT_RESIDENT_RADIUS = 1

        /**
         * Rounds towards minus infinity. Integer division truncates towards zero, which would put
         * the tiles either side of the origin in one, and points a metre apart in the same tile.
         */
        internal fun floorDiv(a: Int, b: Int): Int {
            val quotient = a / b
            return if ((a % b != 0) && ((a < 0) != (b < 0))) quotient - 1 else quotient
        }
    }
}

/** Which tile, counted in tiles from the scan's origin on each axis. */
data class TileKey(val x: Int, val y: Int, val z: Int)

/** Where tiles go when the surveyor walks away from them, and come back from. */
interface TileSink {
    fun store(tile: SurfaceTile)

    fun load(key: TileKey): SurfaceTile?
}

/** A sink that keeps tiles as encoded bytes in memory: for tests, and for short scans. */
class InMemoryTileSink : TileSink {
    private val tiles = mutableMapOf<TileKey, ByteArray>()

    val storeCount: Int get() = tiles.size

    override fun store(tile: SurfaceTile) {
        tiles[tile.key] = SurfaceTileCodec.encode(tile)
    }

    override fun load(key: TileKey): SurfaceTile? = tiles[key]?.let { SurfaceTileCodec.decode(it) }
}

/**
 * One tile's boxes: a running sum of the points pooled into each, and how many.
 *
 * The boxes are found by an open-addressed table of Ints rather than a map, because a map boxes
 * every key and value it holds, and on Kotlin/Native that is an allocation per box.
 */
class SurfaceTile(val key: TileKey) {

    private var slots = IntArray(INITIAL_CAPACITY) { EMPTY }
    private var slotOf = IntArray(INITIAL_CAPACITY)
    private var sums = FloatArray(INITIAL_CAPACITY * 3)
    private var counts = IntArray(INITIAL_CAPACITY)
    private var localIndices = IntArray(INITIAL_CAPACITY)

    var size: Int = 0
        private set

    internal var isDirty: Boolean = false

    /** Adds a point to box [local], returning whether the box is new. */
    fun add(local: Int, east: Float, north: Float, up: Float): Boolean {
        require(local >= 0) { "a box index is never negative" }
        var position = find(local)
        val isNew = slots[position] == EMPTY
        if (isNew) {
            if ((size + 1) * 4 > slots.size * 3) {
                grow()
                position = find(local)
            }
            ensureRoom(size + 1)
            slots[position] = local
            slotOf[position] = size
            localIndices[size] = local
            size++
        }
        val slot = slotOf[position]
        sums[slot * 3] += east
        sums[slot * 3 + 1] += north
        sums[slot * 3 + 2] += up
        counts[slot]++
        isDirty = true
        return isNew
    }

    fun forEachPoint(action: (Coord3D) -> Unit) {
        for (slot in 0 until size) {
            val n = counts[slot].toFloat()
            action(Coord3D(sums[slot * 3] / n, sums[slot * 3 + 1] / n, sums[slot * 3 + 2] / n))
        }
    }

    internal fun forEachBox(action: (local: Int, sumX: Float, sumY: Float, sumZ: Float, count: Int) -> Unit) {
        for (slot in 0 until size) {
            action(localIndices[slot], sums[slot * 3], sums[slot * 3 + 1], sums[slot * 3 + 2], counts[slot])
        }
    }

    internal fun restore(local: Int, sumX: Float, sumY: Float, sumZ: Float, count: Int) {
        var position = find(local)
        if ((size + 1) * 4 > slots.size * 3) {
            grow()
            position = find(local)
        }
        ensureRoom(size + 1)
        slots[position] = local
        slotOf[position] = size
        localIndices[size] = local
        sums[size * 3] = sumX
        sums[size * 3 + 1] = sumY
        sums[size * 3 + 2] = sumZ
        counts[size] = count
        size++
    }

    private fun find(local: Int): Int {
        val mask = slots.size - 1
        var position = mix(local) and mask
        while (slots[position] != EMPTY && slots[position] != local) {
            position = (position + 1) and mask
        }
        return position
    }

    private fun grow() {
        val oldSlots = slots
        val oldSlotOf = slotOf
        slots = IntArray(oldSlots.size * 2) { EMPTY }
        slotOf = IntArray(oldSlots.size * 2)
        for (i in oldSlots.indices) {
            if (oldSlots[i] == EMPTY) continue
            val position = find(oldSlots[i])
            slots[position] = oldSlots[i]
            slotOf[position] = oldSlotOf[i]
        }
    }

    private fun ensureRoom(needed: Int) {
        if (needed <= counts.size) return
        val capacity = maxOf(needed, counts.size * 2)
        sums = sums.copyOf(capacity * 3)
        counts = counts.copyOf(capacity)
        localIndices = localIndices.copyOf(capacity)
    }

    private fun mix(value: Int): Int {
        // Neighbouring boxes have neighbouring indices; spread them so a wall does not fill one
        // run of the table and make every lookup a long walk.
        var h = value * -0x61c88647
        h = h xor (h ushr 16)
        return h
    }

    private companion object {
        const val EMPTY = -1

        /** A power of two, as the table's masking needs. */
        const val INITIAL_CAPACITY = 64
    }
}

/**
 * A tile as bytes, for a sink to write: a short header, then one record per box.
 *
 * Little-endian throughout. The sums and counts are kept rather than averages so a tile loaded
 * back and added to averages correctly, as if it had never left memory.
 */
object SurfaceTileCodec {

    private const val MAGIC = 0x53544C31 // "STL1"
    private const val HEADER_BYTES = 4 * 5
    private const val BOX_BYTES = 4 * 5

    fun encode(tile: SurfaceTile): ByteArray {
        val bytes = ByteArray(HEADER_BYTES + BOX_BYTES * tile.size)
        var at = 0
        fun int(value: Int) {
            bytes[at] = value.toByte()
            bytes[at + 1] = (value shr 8).toByte()
            bytes[at + 2] = (value shr 16).toByte()
            bytes[at + 3] = (value shr 24).toByte()
            at += 4
        }
        int(MAGIC)
        int(tile.key.x)
        int(tile.key.y)
        int(tile.key.z)
        int(tile.size)
        tile.forEachBox { local, x, y, z, count ->
            int(local)
            int(x.toRawBits())
            int(y.toRawBits())
            int(z.toRawBits())
            int(count)
        }
        return bytes
    }

    fun decode(bytes: ByteArray): SurfaceTile {
        require(bytes.size >= HEADER_BYTES) { "too short to be a tile" }
        var at = 0
        fun int(): Int {
            val value =
                (bytes[at].toInt() and 0xFF) or
                    ((bytes[at + 1].toInt() and 0xFF) shl 8) or
                    ((bytes[at + 2].toInt() and 0xFF) shl 16) or
                    ((bytes[at + 3].toInt() and 0xFF) shl 24)
            at += 4
            return value
        }
        require(int() == MAGIC) { "not a tile" }
        val tile = SurfaceTile(TileKey(int(), int(), int()))
        val boxes = int()
        require(bytes.size == HEADER_BYTES + BOX_BYTES * boxes) { "a tile cut short" }
        repeat(boxes) {
            val local = int()
            val x = Float.fromBits(int())
            val y = Float.fromBits(int())
            val z = Float.fromBits(int())
            tile.restore(local, x, y, z, int())
        }
        return tile
    }
}
