package org.hwyl.sexytopo.shared.scan

import org.hwyl.sexytopo.shared.model.graph.Coord3D
import kotlin.math.exp

/**
 * Turning the raw hits of an AR pen into a line a caver would have drawn.
 *
 * The pen is a crosshair swept along a wall edge. Each frame, the ray through the crosshair meets
 * the rock somewhere, and that hit is a point. Raw, those points are a scribble: the surveyor's
 * hand wobbles, and the LiDAR returns the odd depth off a wet surface or a drip that lands a metre
 * behind the wall. One number, the smoothing amount from nought to one, takes that scribble from
 * exactly what was measured to a clean interpretation of it.
 *
 * Two things happen as the amount rises, in this order:
 *
 *  1. **Fliers are dropped.** Each hit is compared with the median of its neighbours; one that is
 *     further from it than a threshold is not rock. The threshold tightens as the amount rises,
 *     from half a metre (only the wildest returns) to five centimetres.
 *  2. **What is left is averaged along the line.** A Gaussian over distance along the stroke, not
 *     over a count of points: a hand that slows down sends many more hits per metre, and averaging
 *     by count would smooth a slow part of a wall less than a fast one.
 *
 * ## Why each point's answer depends only on the raw hits and its own amount
 *
 * A [PenStroke] smooths its recent part with whatever the slider says now and freezes the rest
 * with whatever it said when that part was left behind. That only works if moving the slider
 * cannot reach back and shift a frozen point. So the amount a point is smoothed with also decides
 * which of its neighbours count as fliers, rather than each neighbour using its own: one point's
 * output is then a function of the raw hits around it and one number, and nothing else.
 */
object PenSmoothing {

    /** How many hits either side make up the median a hit is judged against. */
    const val MEDIAN_HALF_WINDOW = 3

    /** The flier threshold at the lowest non-zero amount. */
    const val LOOSEST_FLIER_METRES = 0.5f

    /** The flier threshold at an amount of one. */
    const val TIGHTEST_FLIER_METRES = 0.05f

    /**
     * The width of the averaging at an amount of one.
     *
     * Forty centimetres: enough to take a hand's wobble out of a wall, not enough to round a
     * corner of a passage away. The average reaches three widths each way, which is the furthest
     * any hit can influence another, and the reason a [PenStroke]'s live part must be longer.
     */
    const val WIDEST_SIGMA_METRES = 0.4f

    fun smooth(raw: List<Coord3D>, amount: Float): List<Coord3D> = smooth(raw) { amount }

    /** As [smooth], with each hit's amount given by its index. */
    fun smooth(raw: List<Coord3D>, amountAt: (Int) -> Float): List<Coord3D> {
        if (raw.size < 3) return raw
        val medians = medians(raw)
        val along = distancesAlong(medians)
        val result = ArrayList<Coord3D>(raw.size)

        for (i in raw.indices) {
            val amount = amountAt(i).coerceIn(0f, 1f)
            if (amount <= 0f) {
                result.add(raw[i])
                continue
            }
            val threshold = flierThreshold(amount)
            if (isFlier(raw, medians, i, threshold)) continue

            val sigma = amount * WIDEST_SIGMA_METRES
            result.add(average(raw, medians, along, i, sigma, threshold))
        }
        return result
    }

    fun flierThreshold(amount: Float): Float =
        LOOSEST_FLIER_METRES + (TIGHTEST_FLIER_METRES - LOOSEST_FLIER_METRES) * amount

    /** Distance along the stroke at each hit, measured through the medians so fliers add none. */
    fun distancesAlong(medians: List<Coord3D>): FloatArray {
        val along = FloatArray(medians.size)
        for (i in 1 until medians.size) {
            along[i] = along[i - 1] + StationAnchoring.distance(medians[i - 1], medians[i])
        }
        return along
    }

    /**
     * The component-wise median of each hit's neighbourhood.
     *
     * Component-wise rather than a geometric median because it is cheap and, on a line that is
     * locally straight, gives the same answer; a hit far off the line is out-voted on every axis.
     */
    fun medians(raw: List<Coord3D>): List<Coord3D> {
        val xs = FloatArray(2 * MEDIAN_HALF_WINDOW + 1)
        val ys = FloatArray(xs.size)
        val zs = FloatArray(xs.size)
        return raw.indices.map { i ->
            val from = maxOf(0, i - MEDIAN_HALF_WINDOW)
            val to = minOf(raw.size - 1, i + MEDIAN_HALF_WINDOW)
            val n = to - from + 1
            for (k in 0 until n) {
                val point = raw[from + k]
                xs[k] = point.x
                ys[k] = point.y
                zs[k] = point.z
            }
            Coord3D(median(xs, n), median(ys, n), median(zs, n))
        }
    }

    private fun median(values: FloatArray, n: Int): Float {
        values.sort(0, n)
        return if (n % 2 == 1) values[n / 2] else (values[n / 2 - 1] + values[n / 2]) / 2
    }

    private fun isFlier(raw: List<Coord3D>, medians: List<Coord3D>, i: Int, threshold: Float): Boolean =
        StationAnchoring.distance(raw[i], medians[i]) > threshold

    private fun average(
        raw: List<Coord3D>,
        medians: List<Coord3D>,
        along: FloatArray,
        i: Int,
        sigma: Float,
        threshold: Float,
    ): Coord3D {
        val reach = 3 * sigma
        val twoSigmaSquared = 2 * sigma * sigma
        var x = 0.0
        var y = 0.0
        var z = 0.0
        var weights = 0.0

        fun include(j: Int) {
            if (isFlier(raw, medians, j, threshold)) return
            val gap = along[j] - along[i]
            val weight = exp(-(gap * gap) / twoSigmaSquared).toDouble()
            x += raw[j].x * weight
            y += raw[j].y * weight
            z += raw[j].z * weight
            weights += weight
        }

        include(i)
        var j = i - 1
        while (j >= 0 && along[i] - along[j] <= reach) include(j--)
        j = i + 1
        while (j < raw.size && along[j] - along[i] <= reach) include(j++)

        return Coord3D((x / weights).toFloat(), (y / weights).toFloat(), (z / weights).toFloat())
    }
}

/**
 * One sweep of the AR pen, smoothed live at its working end and frozen behind it.
 *
 * The slider acts on the last [liveLengthMetres] of the line, five metres unless said otherwise:
 * the part the surveyor is still looking at and can still judge against the rock. Once a hit is
 * further back than that, it is committed with whatever amount the slider showed at that moment,
 * and later slider moves leave it where it is. Every raw hit is kept regardless, so [resmooth]
 * can take the whole line to one new amount in review.
 *
 * The live length must be longer than the furthest one hit can influence another, three times
 * [PenSmoothing.WIDEST_SIGMA_METRES]; otherwise adding hits at the end could still nudge the
 * frozen part, which is what freezing is for.
 */
class PenStroke(val liveLengthMetres: Float = DEFAULT_LIVE_LENGTH_METRES) {

    init {
        require(liveLengthMetres > 3 * PenSmoothing.WIDEST_SIGMA_METRES) {
            "the live part must outreach the smoothing, or freezing does not hold"
        }
    }

    private val raw = mutableListOf<Coord3D>()
    private val frozenAmounts = mutableListOf<Float>()

    /** The slider: how smooth the live part is, from nought (raw) to one. */
    var smoothing: Float = DEFAULT_SMOOTHING
        set(value) {
            field = value.coerceIn(0f, 1f)
        }

    val rawHits: List<Coord3D> get() = raw.toList()

    /** Where the live part starts: hits before this index are frozen. */
    val liveStart: Int get() = frozenAmounts.size

    /** The amount each frozen hit was committed at, in order: what a saved stroke keeps. */
    val frozenSmoothing: List<Float> get() = frozenAmounts.toList()

    fun add(hit: Coord3D) {
        raw.add(hit)
        freezeWhatHasFallenBehind()
    }

    /**
     * The whole line as it should be drawn: frozen hits at their own amounts, live ones at the
     * slider's. Fliers are missing from it, so it can be shorter than [rawHits].
     */
    fun line(): List<Coord3D> = PenSmoothing.smooth(raw) { i -> amountAt(i) }

    /** Every hit, frozen or not, smoothed again at [amount]. Review mode's slider. */
    fun resmooth(amount: Float) {
        val clamped = amount.coerceIn(0f, 1f)
        for (i in frozenAmounts.indices) frozenAmounts[i] = clamped
        smoothing = clamped
    }

    private fun amountAt(i: Int): Float = if (i < frozenAmounts.size) frozenAmounts[i] else smoothing

    /**
     * Freezes every hit more than the live length behind the newest, measured along the stroke.
     *
     * Only the live part is walked, which is bounded by the live length, so a long sweep costs no
     * more per hit than a short one.
     */
    private fun freezeWhatHasFallenBehind() {
        val from = maxOf(0, frozenAmounts.size - PenSmoothing.MEDIAN_HALF_WINDOW)
        val live = raw.subList(from, raw.size)
        if (live.size < 2) return
        val along = PenSmoothing.distancesAlong(PenSmoothing.medians(live))
        val end = along.last()
        var i = frozenAmounts.size
        while (i < raw.size && end - along[i - from] > liveLengthMetres) {
            frozenAmounts.add(smoothing)
            i++
        }
    }

    companion object {
        /**
         * A stroke as it was saved: its raw hits, the amounts its frozen part was committed at, and
         * the slider's last position for the rest. Nothing is re-frozen on the way back in, so the
         * line drawn from it is the line that was drawn before it was saved.
         */
        fun restore(
            rawHits: List<Coord3D>,
            frozenSmoothing: List<Float>,
            smoothing: Float,
            liveLengthMetres: Float = DEFAULT_LIVE_LENGTH_METRES,
        ): PenStroke {
            val stroke = PenStroke(liveLengthMetres)
            stroke.raw.addAll(rawHits)
            stroke.frozenAmounts.addAll(frozenSmoothing.take(rawHits.size).map { it.coerceIn(0f, 1f) })
            stroke.smoothing = smoothing
            return stroke
        }

        const val DEFAULT_LIVE_LENGTH_METRES = 5f

        /** Halfway: fliers gone and the wobble out, without rounding anything a caver would draw. */
        const val DEFAULT_SMOOTHING = 0.5f
    }
}

/** The length of a line, for tests and for telling a surveyor how much they have drawn. */
fun lengthOf(line: List<Coord3D>): Float {
    var total = 0f
    for (i in 1 until line.size) total += StationAnchoring.distance(line[i - 1], line[i])
    return total
}
