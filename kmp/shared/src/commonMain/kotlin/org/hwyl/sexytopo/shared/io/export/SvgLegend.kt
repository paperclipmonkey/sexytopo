package org.hwyl.sexytopo.shared.io.export

import org.hwyl.sexytopo.shared.model.common.Frame
import org.hwyl.sexytopo.shared.model.graph.Projection2D
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyStats
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * What turns an exported drawing into a survey somebody else can read.
 *
 * Ported from `SvgExporter`'s `LegendModel`, `buildLegendModel`, `writeLegend` and
 * `writeNorthArrow`. A plan with no scale, no north and no date is a picture; the legend is what
 * makes it evidence — and it is the difference between an export a caving club will accept and one
 * they will ask you to redo.
 *
 * The layout is arithmetic on a cursor running down a strip below the drawing, and every constant
 * is the Java's. It is reproduced exactly rather than improved because a legend that laid itself
 * out differently would make the two apps' exports impossible to compare, which is the one thing
 * this port is for.
 *
 * The one place it cannot be exact is the date. `DateFormat.MEDIUM` in the device's own locale
 * gives "12 Apr 2026" here and "Apr 12, 2026" there, which is a drawing that changes depending on
 * whose phone exported it; and this port's [org.hwyl.sexytopo.shared.model.survey.SurveyDate] is
 * deliberately zoneless and locale-free. So the legend writes the ISO date, which is what the
 * Survex and Therion exporters already write and what sorts correctly everywhere.
 */
internal class SvgLegend(
    val title: String,
    val bodyLines: List<String>,
    val barLengthInMetres: Double,
    scale: Int,
    isPlan: Boolean,
    showNorthArrow: Boolean,
    val showScaleBar: Boolean,
    val showTagline: Boolean,
) {

    val barLengthInPixels: Double = barLengthInMetres * scale

    /** `max(1, scale / 40)`, which is 1 at the exporter's own scale of 50. */
    val strokeWidth: Int = max(1, scale / 40)

    /** North is meaningless in an elevation, so the arrow is plan-only. */
    val showNorthArrow: Boolean = showNorthArrow && isPlan

    val titleFont: Double = SvgExporter.STATION_FONT * 1.6
    val bodyFont: Double = SvgExporter.STATION_FONT.toDouble()
    val scaleLabelFont: Double = bodyFont * 0.8
    val taglineFont: Double = bodyFont * 0.75

    private val tickHeight = scaleLabelFont * 0.6
    private val lineGap = bodyFont * 1.7
    private val sectionGap = bodyFont * 0.8
    private val preScaleBarGap = sectionGap * 1.5
    private val arrowSize = SvgExporter.STATION_FONT * 9.0
    private val topPadding = bodyFont * 0.6
    private val bottomPadding = bodyFont * 0.6

    val titleY: Double
    val bodyYs: List<Double>
    val taglineY: Double
    val barTopY: Double
    val barBaselineY: Double
    val scaleLabelY: Double
    val arrowCentreX: Double
    val arrowTopY: Double
    val arrowBottomY: Double

    /** How tall the strip has to be, which is what the page is grown by to make room for it. */
    val totalHeight: Double

    init {
        var cursorY = topPadding

        cursorY += titleFont
        titleY = cursorY
        cursorY += sectionGap

        bodyYs =
            bodyLines.map {
                cursorY += lineGap
                cursorY
            }

        if (this.showTagline) {
            cursorY += sectionGap
            cursorY += taglineFont
            taglineY = cursorY
        } else {
            taglineY = 0.0
        }

        if (showScaleBar) {
            cursorY += preScaleBarGap
            barTopY = cursorY
            barBaselineY = barTopY + tickHeight
            scaleLabelY = barBaselineY + scaleLabelFont
            cursorY = scaleLabelY + bottomPadding
        } else {
            barTopY = 0.0
            barBaselineY = 0.0
            scaleLabelY = 0.0
            cursorY += bottomPadding
        }

        // The arrow lives in the top right of the strip, and its vertical extent is reserved
        // separately so it cannot push the text layout around.
        arrowCentreX = max(barLengthInPixels, titleFont * 8) + arrowSize
        arrowTopY = topPadding
        arrowBottomY = arrowTopY + arrowSize
        if (this.showNorthArrow) {
            cursorY = max(cursorY, arrowBottomY + bodyFont + bottomPadding)
        }

        totalHeight = cursorY
    }

    companion object {

        /**
         * The legend for a survey, or null when there is nothing to measure.
         *
         * Null for a zero-width drawing, as in the Java: the scale bar is chosen from the drawing's
         * width, and a survey of one station has none.
         */
        fun of(
            survey: Survey,
            projection: Projection2D,
            frame: Frame,
            scale: Int,
            options: SvgExporter.Options,
        ): SvgLegend? {
            val widthInMetres = frame.width / scale.toDouble()
            if (widthInMetres <= 0) return null

            val trip = survey.trip
            val bodyLines = buildList {
                trip?.surveyDate?.let { add(it.toString()) }
                if (options.showTeam) {
                    val team = teamNames(trip)
                    if (team.isNotEmpty()) add("Surveyed By: $team")
                }
                add(statsLine(survey))
                if (options.showCopyright) {
                    val copyright = copyrightLine(trip)
                    if (copyright.isNotEmpty()) add(copyright)
                }
            }

            return SvgLegend(
                title = survey.name,
                bodyLines = bodyLines,
                barLengthInMetres = SvgExporter.scaleBarLength(widthInMetres),
                scale = scale,
                isPlan = projection == Projection2D.PLAN,
                showNorthArrow = options.showNorthArrow,
                showScaleBar = options.showScaleBar,
                showTagline = options.showTagline,
            )
        }

        /** Everyone on the trip, in the order they were entered. Blank names are dropped. */
        fun teamNames(trip: Trip?): String =
            trip?.team.orEmpty()
                .map { it.name.trim() }
                .filter { it.isNotEmpty() }
                .joinToString(", ")

        /**
         * "© 2026 Caver Jane — CC BY 4.0", with either half omitted when it is not set.
         *
         * The © and the year are added here, so the trip's copyright holder is just a name — which
         * is why a survey exported twice in different years still says the year of the *trip*.
         */
        fun copyrightLine(trip: Trip?): String {
            if (trip == null) return ""
            return buildString {
                if (trip.hasCopyrightHolder()) {
                    append("© ")
                    val year = trip.surveyDate.year.toString().padStart(4, '0')
                    if (year.isNotEmpty()) append(year).append(' ')
                    append(trip.copyrightHolder)
                }
                if (trip.hasLicence()) {
                    if (isNotEmpty()) append(" — ")
                    append(trip.licence)
                }
            }
        }

        /**
         * "L: 120 m, H: 14 m" — surveyed length and vertical range, both to the nearest metre.
         *
         * `roundToLong` rather than `round`: Kotlin's `round` is ties-to-even, and every number the
         * Android app has ever written is `Math.round`'s ties-up. Both values are non-negative
         * here, so `roundToLong` is exactly `Math.round`.
         */
        fun statsLine(survey: Survey): String {
            val length = SurveyStats.totalLength(survey).roundToLong()
            val height = SurveyStats.heightRange(survey).roundToLong()
            return "L: $length m, H: $height m"
        }

        /** "5 m", "50 cm", or the raw value for anything smaller. */
        fun scaleBarLabel(metres: Double): String =
            when {
                metres >= 1 -> "${metres.toLong()} m"
                metres >= 0.01 -> "${(metres * 100).roundToLong()} cm"
                else -> "$metres m"
            }
    }
}
