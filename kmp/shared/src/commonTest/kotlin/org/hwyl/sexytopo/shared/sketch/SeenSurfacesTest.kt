package org.hwyl.sexytopo.shared.sketch

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Counting a patch of rock once, however many times the scanner reports it.
 *
 * Reported from a phone, and it was the same fault wearing three hats. ARKit's feature cloud is
 * cumulative — every read hands back everything it holds, not what is new — and the scanner kept
 * all of it five times a second. So the count climbed with the phone standing still, the screen
 * froze copying thousands of points per read, and the cross-section came out as a star of spikes.
 *
 * The third is the one worth a test of its own, and it has one below in [PassageScanTest]: the
 * noise rules count *observations*, so a single stray return duplicated a hundred and fifty times
 * clears the three-point bar on its own and owns every value the percentile sorts. Duplication did
 * not weaken the noise floor, it removed it.
 *
 * These check the arithmetic that stops it. The packing is the part that would fail silently — a
 * scan would simply come out sparse, with no error anywhere — which is why it lives in the shared
 * module where a build server can run it rather than in `iosMain` where nothing can.
 */
class SeenSurfacesTest {

    private val fine = SeenSurfaces.DEFAULT_VOXEL_METRES

    @Test
    fun theSamePointIsOnlyNewsOnce() {
        val seen = SeenSurfaces()

        assertTrue(seen.isNew(1f, 2f, 3f), "the first sight of a surface is news")
        assertFalse(seen.isNew(1f, 2f, 3f), "the same point again is not")
        assertEquals(1, seen.size)
    }

    /**
     * A hundred and fifty reads of one feature is one patch of rock.
     *
     * The shape of the bug exactly: this is what the scanner was doing five times a second, and
     * what made a stray return look like a wall.
     */
    @Test
    fun aFeatureReportedOverAndOverIsStillOnePatchOfRock() {
        val seen = SeenSurfaces()
        repeat(150) { seen.isNew(4f, 0.5f, -1f) }

        assertEquals(1, seen.size, "one feature re-reported 150 times should count once")
    }

    /**
     * Two returns off genuinely different rock stay two.
     *
     * The other half, and the one that would be silently lost if the boxes were too big: a scan
     * that collapsed real detail would come out smooth and confident and wrong.
     */
    @Test
    fun rockFurtherApartThanABoxIsCountedSeparately() {
        val seen = SeenSurfaces()

        assertTrue(seen.isNew(0f, 0f, 0f))
        assertTrue(seen.isNew(fine * 3, 0f, 0f), "6cm along is different rock")
        assertTrue(seen.isNew(0f, fine * 3, 0f), "and so is 6cm across")
        assertTrue(seen.isNew(0f, 0f, fine * 3), "and 6cm up")
        assertEquals(4, seen.size)
    }

    /**
     * The boxes either side of the surveyor's feet are different boxes.
     *
     * The trap in the packing, and the reason it uses `floor` rather than a cast: a cast truncates
     * towards zero, which folds the box just below the origin onto the box just above it on every
     * axis. The eight boxes meeting where the surveyor stands would become one, and since that is
     * exactly where every scan is centred, the fault would sit in the middle of every section.
     */
    @Test
    fun theBoxesEitherSideOfTheSurveyorAreDifferentBoxes() {
        val seen = SeenSurfaces()
        val half = fine / 2

        assertTrue(seen.isNew(half, half, half), "just above the origin")
        assertTrue(seen.isNew(-half, half, half), "just west of it is not the same box")
        assertTrue(seen.isNew(half, -half, half), "nor just south")
        assertTrue(seen.isNew(half, half, -half), "nor just below")
        assertEquals(4, seen.size, "the boxes round the origin collapsed into each other")
    }

    /**
     * Two points a long way apart never land in the same box.
     *
     * A packing that overflowed its bits would wrap, and the symptom would be a far wall silently
     * swallowing points from somewhere else entirely — believable-looking and wrong. Swept across
     * the range a cave is actually surveyed at.
     */
    @Test
    fun distantRockNeverCollidesWithNearRock() {
        val seen = SeenSurfaces()
        val alongEachAxis = listOf(-500f, -50f, -5f, 5f, 50f, 500f)

        // The origin once, on its own: it is the one place all three axes share, and asking for it
        // three times would be asking whether a point collides with itself.
        assertTrue(seen.isNew(0f, 0f, 0f), "the surveyor's own feet")
        for (metres in alongEachAxis) {
            assertTrue(seen.isNew(metres, 0f, 0f), "east $metres")
            assertTrue(seen.isNew(0f, metres, 0f), "north $metres")
            assertTrue(seen.isNew(0f, 0f, metres), "up $metres")
        }

        assertEquals(
            1 + alongEachAxis.size * 3,
            seen.size,
            "two points hundreds of metres apart were taken for the same rock, which is what a " +
                "packing that overflowed its bits would do",
        )
    }

    /** A coarser sieve is allowed, and does what it says. */
    @Test
    fun theBoxesCanBeMadeCoarser() {
        val coarse = SeenSurfaces(voxelMetres = 1f)

        assertTrue(coarse.isNew(0.1f, 0.1f, 0.1f))
        assertFalse(coarse.isNew(0.9f, 0.9f, 0.9f), "both are within the same one-metre box")
        assertTrue(coarse.isNew(1.5f, 0.1f, 0.1f), "this one is not")
    }

    /** Points, for the callers that have them: the scanner walks a raw buffer and does not. */
    @Test
    fun aWholeCloudCanBeSievedAtOnce() {
        val seen = SeenSurfaces()
        val cloud = listOf(Coord3D(1f, 1f, 1f), Coord3D(1f, 1f, 1f), Coord3D(2f, 2f, 2f))

        val distinct = cloud.filter { seen.isNew(it.x, it.y, it.z) }

        assertEquals(2, distinct.size, "a cloud holding one duplicate should sieve to two")
    }
}
