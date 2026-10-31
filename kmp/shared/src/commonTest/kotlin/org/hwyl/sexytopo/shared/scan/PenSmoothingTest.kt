package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class PenSmoothingTest {

    /** A wall edge running north along x = 2, sampled every [step] metres, with hand wobble. */
    private fun wobblyWall(lengthMetres: Float, step: Float = 0.02f, wobble: Float = 0.03f, seed: Int = 1): List<Coord3D> {
        val random = Random(seed)
        val count = (lengthMetres / step).toInt()
        return List(count) { i ->
            Coord3D(2f + (random.nextFloat() - 0.5f) * 2 * wobble, i * step, 1f + (random.nextFloat() - 0.5f) * 2 * wobble)
        }
    }

    private fun worstDistanceFromWall(line: List<Coord3D>): Float =
        line.maxOf { maxOf(abs(it.x - 2f), abs(it.z - 1f)) }

    @Test
    fun noSmoothingIsExactlyWhatWasMeasured() {
        val raw = wobblyWall(3f)
        assertEquals(raw, PenSmoothing.smooth(raw, 0f))
    }

    @Test
    fun smoothingTakesTheWobbleOut() {
        val raw = wobblyWall(3f)

        val smoothed = PenSmoothing.smooth(raw, 1f)

        assertTrue(worstDistanceFromWall(smoothed) < worstDistanceFromWall(raw) / 2)
    }

    @Test
    fun moreSmoothingIsSmoother() {
        val raw = wobblyWall(3f)

        val some = worstDistanceFromWall(PenSmoothing.smooth(raw, 0.2f))
        val more = worstDistanceFromWall(PenSmoothing.smooth(raw, 0.8f))

        assertTrue(more < some)
    }

    @Test
    fun aReturnFromBehindTheWallIsDropped() {
        val raw = wobblyWall(2f, wobble = 0f).toMutableList()
        // The LiDAR saw through a gap: one hit a metre behind the wall.
        raw[50] = Coord3D(3f, raw[50].y, 1f)

        val smoothed = PenSmoothing.smooth(raw, 0.5f)

        assertEquals(raw.size - 1, smoothed.size)
        assertTrue(worstDistanceFromWall(smoothed) < 0.01f)
    }

    @Test
    fun aRealBendInTheWallSurvivesSmoothing() {
        // A wall that steps out by a metre halfway along: a corner a caver would draw.
        val raw = List(200) { i -> Coord3D(if (i < 100) 2f else 3f, i * 0.02f, 1f) }

        val smoothed = PenSmoothing.smooth(raw, 0.5f)

        assertEquals(2f, smoothed.first().x, 0.01f)
        assertEquals(3f, smoothed.last().x, 0.01f)
    }

    @Test
    fun aSlowHandIsSmoothedAsMuchAsAFastOne() {
        // The same wiggle drawn once with hits every centimetre and once every four: averaging by
        // distance along the line, not by count, treats them alike.
        fun wiggle(step: Float) = List((2f / step).toInt()) { i -> Coord3D(2f + 0.05f * sin(i * step * 20f), i * step, 1f) }

        val slow = worstDistanceFromWall(PenSmoothing.smooth(wiggle(0.01f), 0.6f))
        val fast = worstDistanceFromWall(PenSmoothing.smooth(wiggle(0.04f), 0.6f))

        assertEquals(slow, fast, 0.01f)
    }

    @Test
    fun aStrokeFreezesWhatIsMoreThanItsLiveLengthBehind() {
        val stroke = PenStroke()
        // No wobble, so distance along the stroke is distance along the wall.
        wobblyWall(8f, wobble = 0f).forEach(stroke::add)

        // Eight metres drawn, five live: the first three are frozen.
        val frozenLength = stroke.liveStart * 0.02f
        assertEquals(3f, frozenLength, 0.2f)
    }

    @Test
    fun movingTheSliderLeavesTheFrozenPartWhereItWas() {
        val stroke = PenStroke()
        stroke.smoothing = 0.8f
        wobblyWall(10f).forEach(stroke::add)
        val frozen = stroke.liveStart
        val before = stroke.line().take(frozen / 2)

        stroke.smoothing = 0f
        val after = stroke.line().take(frozen / 2)

        assertEquals(before, after)
    }

    @Test
    fun movingTheSliderChangesTheLivePart() {
        val stroke = PenStroke()
        stroke.smoothing = 0.8f
        wobblyWall(10f).forEach(stroke::add)
        val before = stroke.line().takeLast(50)

        stroke.smoothing = 0f

        assertNotEquals(before, stroke.line().takeLast(50))
    }

    @Test
    fun resmoothingInReviewReachesTheFrozenPartToo() {
        val stroke = PenStroke()
        stroke.smoothing = 0.8f
        wobblyWall(10f).forEach(stroke::add)

        stroke.resmooth(0f)

        assertEquals(stroke.rawHits, stroke.line())
    }

    @Test
    fun aLivePartShorterThanTheSmoothingsReachIsRefused() {
        assertFailsWith<IllegalArgumentException> { PenStroke(liveLengthMetres = 1f) }
    }

    @Test
    fun theLengthOfALineIsTheSumOfItsSteps() {
        val line = listOf(Coord3D(0f, 0f, 0f), Coord3D(3f, 4f, 0f), Coord3D(3f, 4f, 2f))
        assertEquals(7f, lengthOf(line), 0.0001f)
    }
}
