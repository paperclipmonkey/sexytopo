package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.io.store.InMemoryFileStore
import org.hwyl.sexytopo.shared.model.graph.Coord3D
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.Symbol
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A scan written down as it goes, read back as if the phone had died: the tiles from their files,
 * the rest from the checkpoint.
 */
class ScanRecoveryTest {

    @Test
    fun tilesSentToFilesComeBack() {
        val store = InMemoryFileStore()
        val sink = FileTileSink(store, listOf("t"))
        val tiles = SurfaceTiles(0.05f, sink, tileVoxels = 16, residentRadius = 0)
        // Ten metres of wall, walked past: most tiles leave memory on the way.
        for (i in 0 until 1000) {
            tiles.add(i * 0.01f, 1f, 0.5f)
            tiles.moveTo(i * 0.01f, 0f, 0f)
        }
        tiles.flush()
        val fromFiles = mutableListOf<Coord3D>()
        for (key in sink.storedKeys()) sink.load(key)?.forEachPoint { fromFiles.add(it) }
        val fromTiles = mutableListOf<Coord3D>()
        tiles.forEachPoint { fromTiles.add(it) }

        assertTrue(sink.storedKeys().size > 5)
        assertEquals(tiles.boxCount, fromFiles.size)
        assertEquals(fromTiles.size, fromFiles.size)
    }

    @Test
    fun aCheckpointedScanIsRecoveredWithItsRockAndItsMarks() {
        val store = InMemoryFileStore()
        ScanRecovery.start(store)
        val tiles = SurfaceTiles(0.05f, ScanRecovery.tileSink(store))
        for (i in 0 until 200) tiles.add(i * 0.05f, 1f, 0f)
        tiles.flush()
        val stroke = PenStroke()
        for (i in 0 until 50) stroke.add(Coord3D(i * 0.1f, 0.9f, -0.5f))
        ScanRecovery.checkpoint(
            store,
            path = listOf(Coord3D.ORIGIN, Coord3D(5f, 0f, 0f)),
            touches = listOf(StationTouch("1", Coord3D(0f, 0f, -1f))),
            penLines = listOf(PenLine(stroke, Colour.BLUE)),
            stamps = listOf(SymbolStamp(Symbol.GOUR, Coord3D(2f, 0f, -1f))),
            startStation = "1",
            poorTracking = setOf(1),
        )

        assertTrue(ScanRecovery.exists(store))
        val scan = assertNotNull(ScanRecovery.recover(store))

        assertEquals(200, scan.points.size)
        assertEquals(2, scan.path.size)
        assertEquals("1", scan.touches.single().stationName)
        assertEquals(Colour.BLUE, scan.penLines.single().colour)
        assertEquals(50, scan.penLines.single().stroke.rawHits.size)
        assertEquals(Symbol.GOUR, scan.stamps.single().symbol)
        assertEquals("1", scan.startStation)
        assertEquals(setOf(1), scan.poorTracking)

        ScanRecovery.clear(store)

        assertFalse(ScanRecovery.exists(store))
        assertNull(ScanRecovery.recover(store))
    }

    @Test
    fun startingANewScanForgetsTheLastOne() {
        val store = InMemoryFileStore()
        ScanRecovery.checkpoint(store, emptyList(), listOf(StationTouch("1", Coord3D.ORIGIN)), emptyList(), emptyList(), null)
        ScanRecovery.start(store)
        assertFalse(ScanRecovery.exists(store))
    }

    @Test
    fun aCheckpointWithNothingInItIsNotAScan() {
        val store = InMemoryFileStore()
        ScanRecovery.checkpoint(store, listOf(Coord3D.ORIGIN), emptyList(), emptyList(), emptyList(), "1")
        assertNull(ScanRecovery.recover(store))
    }

    @Test
    fun theSurveyAScanWasTakenInIsKept() {
        val store = InMemoryFileStore()
        ScanRecovery.start(store, "Swildons")
        assertNull(ScanRecovery.surveyOf(store), "no scan yet, so it belongs nowhere")
        ScanRecovery.checkpoint(store, emptyList(), listOf(StationTouch("1", Coord3D.ORIGIN)), emptyList(), emptyList(), null)

        assertEquals("Swildons", ScanRecovery.surveyOf(store))
    }
}
