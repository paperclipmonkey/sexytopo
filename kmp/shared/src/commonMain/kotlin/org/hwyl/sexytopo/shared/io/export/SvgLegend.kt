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
 * The layout is arithmetic on a cursor running down a strip below the drawing, with every
 * constant matching the Java's exactly, so the two apps' exports stay comparable.
 *
 * The one place it cannot be exact is the date: `DateFormat.MEDIUM` renders differently by
 * locale, and this port's [org.hwyl.sexytopo.shared.model.survey.SurveyDate] is deliberately
 * zoneless and locale-free — so the legend writes the ISO date instead, matching the Survex and
 * Therion exporters.
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

        /** The legend for a survey, or null for a zero-width drawing (a one-station survey). */
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
         * "© 2026 Caver Jane — CC BY 4.0", either half omitted when unset. The year comes from
         * the trip, not the export date, so re-exporting later doesn't change it.
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
         * "L: 120 m, H: 14 m", to the nearest metre. `roundToLong` rather than `round`, which is
         * ties-to-even where Android's `Math.round` is ties-up; both values here are
         * non-negative, so the two agree.
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
