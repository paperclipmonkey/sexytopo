package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SurfaceTilesTest {

    private fun points(tiles: SurfaceTiles): List<Coord3D> {
        val all = mutableListOf<Coord3D>()
        tiles.forEachPoint { all.add(it) }
        return all
    }

    @Test
    fun theSameBoxSeenTwiceIsOneBoxAtTheAverageOfBoth() {
        val tiles = SurfaceTiles(0.05f, InMemoryTileSink())

        assertTrue(tiles.add(1.01f, 2.01f, 0.01f))
        assertFalse(tiles.add(1.03f, 2.03f, 0.03f))

        assertEquals(1, tiles.boxCount)
        val point = points(tiles).single()
        assertEquals(1.02f, point.x, 0.0001f)
        assertEquals(2.02f, point.y, 0.0001f)
    }

    @Test
    fun boxesEitherSideOfTheOriginAreDifferentBoxes() {
        // Truncating instead of flooring folds the box just below zero onto the one just above.
        val tiles = SurfaceTiles(0.05f, InMemoryTileSink())

        tiles.add(0.01f, 0.01f, 0.01f)
        assertTrue(tiles.add(-0.01f, -0.01f, -0.01f))
    }

    @Test
    fun walkingAwayKeepsOnlyNearbyTilesInMemory() {
        val sink = InMemoryTileSink()
        val tiles = SurfaceTiles(0.05f, sink)

        // A hundred metres of wall, one point every 5 cm, the surveyor keeping pace.
        for (i in 0 until 2000) {
            val north = i * 0.05f
            tiles.add(1.5f, north, 0f)
            tiles.add(-1.5f, north, 0f)
            if (i % 20 == 0) tiles.moveTo(0f, north, 0f)
        }

        assertTrue(tiles.residentTileCount <= 3 * 3 * 3)
        assertTrue(sink.storeCount > 10)
    }

    @Test
    fun everyBoxComesBackWhetherInMemoryOrNot() {
        val tiles = SurfaceTiles(0.05f, InMemoryTileSink())
        for (i in 0 until 2000) {
            // Mid-box, so float rounding cannot land two consecutive points in one box.
            val north = i * 0.05f + 0.025f
            tiles.add(1.5f, north, 0f)
            if (i % 20 == 0) tiles.moveTo(0f, north, 0f)
        }

        assertEquals(2000, tiles.boxCount)
        assertEquals(2000, points(tiles).size)
    }

    @Test
    fun returningToAPassageAddsToWhatWasSeenThere() {
        val tiles = SurfaceTiles(0.05f, InMemoryTileSink())
        tiles.add(1.51f, 0.51f, 0.01f)
        tiles.moveTo(0f, 100f, 0f)
        assertEquals(0, tiles.residentTileCount)

        tiles.moveTo(0f, 0f, 0f)
        val isNew = tiles.add(1.53f, 0.53f, 0.03f)

        assertFalse(isNew)
        assertEquals(1, tiles.boxCount)
        assertEquals(1.52f, points(tiles).single().x, 0.0001f)
    }

    @Test
    fun aTileSurvivesBeingWrittenOutAndReadBack() {
        val tile = SurfaceTile(TileKey(-2, 7, 0))
        tile.add(5, 1f, 2f, 3f)
        tile.add(5, 3f, 4f, 5f)
        tile.add(900_000, -1f, -2f, -3f)

        val back = SurfaceTileCodec.decode(SurfaceTileCodec.encode(tile))

        assertEquals(tile.key, back.key)
        assertEquals(2, back.size)
        val original = mutableListOf<Coord3D>().also { list -> tile.forEachPoint { list.add(it) } }
        val restored = mutableListOf<Coord3D>().also { list -> back.forEachPoint { list.add(it) } }
        assertEquals(original, restored)
    }

    @Test
    fun aTileHoldsManyMoreBoxesThanItStartsWithRoomFor() {
        val tile = SurfaceTile(TileKey(0, 0, 0))
        for (i in 0 until 10_000) tile.add(i * 7, i.toFloat(), 0f, 0f)
        for (i in 0 until 10_000) assertFalse(tile.add(i * 7, i.toFloat(), 0f, 0f))
        assertEquals(10_000, tile.size)
    }

    @Test
    fun floorDivisionRoundsDownForNegativeNumbers() {
        assertEquals(-1, SurfaceTiles.floorDiv(-1, 128))
        assertEquals(-1, SurfaceTiles.floorDiv(-128, 128))
        assertEquals(-2, SurfaceTiles.floorDiv(-129, 128))
        assertEquals(0, SurfaceTiles.floorDiv(127, 128))
        assertEquals(1, SurfaceTiles.floorDiv(128, 128))
    }
}
