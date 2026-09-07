package org.hwyl.sexytopo.shared.model.survey

import kotlin.math.floor

/**
 * Where one station is in the world, and how well that is known.
 *
 * A survey drawn by this app is a frame floating in space: every station is so many metres from
 * the origin, and nothing says where the origin is. That is enough to draw a cave and not enough
 * to put it on a map, compare it with what is on the surface, or join it to the survey of the cave
 * next door. What closes the gap is one station whose position is known — almost always the
 * entrance, which is the one place a satellite can be seen from — and both formats this app
 * exports have had the mechanism for it for decades: Therion's `fix` and Survex's `*fix`.
 *
 * ## Why it names a station rather than being a property of the survey
 *
 * Because that is what gets exported, and because the alternative loses information. A survey may
 * have several stations outside — a second entrance, a dig at the far end of a valley — and a
 * position that did not say which station it belonged to could not be written into either format
 * at all. One fix is stored per survey today, which is the ordinary case; the shape allows more
 * later without changing what is written to a file.
 *
 * ## Why the accuracy is part of it
 *
 * Because a phone's position is a measurement with an error bar, and both formats can carry the
 * error bar. Under open sky a phone is good to a handful of metres; a cave entrance is in a
 * wooded valley, under a cliff, or down a shakehole, which is where multipath lives, and ten to
 * thirty metres is the ordinary result. Vertical error is two or three times the horizontal, which
 * stings, because depth is the number cavers care about.
 *
 * Writing the accuracy alongside the position is the difference between data and a guess: somebody
 * reading the file in five years can see whether the entrance is known to five metres or fifty,
 * which they cannot recover from the coordinates themselves. It is null when a position was typed
 * in rather than measured, since a number off a map carries whatever accuracy the map had and this
 * has no way to know it.
 *
 * ## Which altitude
 *
 * Metres above sea level, which is what a map says and what iOS reports as `altitude` and Android
 * as `Location.getAltitude`. **Not** the height above the WGS84 ellipsoid, which is the other
 * altitude a receiver knows and which differs from sea level by about fifty metres in Britain —
 * deeper than most of the caves in it, and a silent error of exactly the kind that would never be
 * questioned because both numbers look like altitudes.
 *
 * It is required rather than optional, because both export formats want three coordinates and
 * writing a zero for an unknown height would put every fixed entrance at sea level. A receiver
 * that cannot say how high it is leaves the field empty and the surveyor fills it in — off the map
 * if need be, which is usually better than the receiver's answer anyway.
 */
class StationFix(
    /** The station this position belongs to, by name, as the export will write it. */
    val station: String,
    /** Degrees north, negative for south. */
    val latitude: Double,
    /** Degrees east, negative for west. */
    val longitude: Double,
    /** Metres above sea level. */
    val altitude: Double,
    /** Metres, at about one standard deviation, or null when the position was typed in. */
    val horizontalAccuracy: Double? = null,
    val verticalAccuracy: Double? = null,
    val source: Source = Source.ENTERED,
) {

    init {
        require(station.isNotBlank()) { "a fix has to name a station" }
        require(latitude.isFinite() && latitude in -MAX_LATITUDE..MAX_LATITUDE) {
            "a latitude is between -$MAX_LATITUDE and $MAX_LATITUDE, not $latitude"
        }
        require(longitude.isFinite() && longitude in -MAX_LONGITUDE..MAX_LONGITUDE) {
            "a longitude is between -$MAX_LONGITUDE and $MAX_LONGITUDE, not $longitude"
        }
        require(altitude.isFinite()) { "an altitude has to be a number, not $altitude" }
        require(horizontalAccuracy == null || horizontalAccuracy >= 0) {
            "an accuracy is a distance, not $horizontalAccuracy"
        }
        require(verticalAccuracy == null || verticalAccuracy >= 0) {
            "an accuracy is a distance, not $verticalAccuracy"
        }
    }

    /** Whether a receiver measured this or somebody typed it in. */
    enum class Source {
        MEASURED,
        ENTERED,
    }

    /**
     * The UTM zone this position falls in, spelled as Survex and Therion spell one: `30N`.
     *
     * Needed because a survey fixed in latitude and longitude has no metres in it, and a survey
     * program has to compute in metres — so the export names a projected system to work in, and
     * the only sensible one to name is the one this cave is actually in. Sixty zones of six
     * degrees each, counted from the antimeridian, and the letter is the hemisphere.
     *
     * The conventional exceptions are not applied: south-west Norway and Svalbard have zones of
     * odd widths by agreement, and a cave there would be projected in the zone its longitude falls
     * in rather than the one the convention prefers. That is a difference of which grid the
     * coordinates are quoted on rather than of where the cave is, and it is written down here so
     * that somebody surveying in Norway knows it is a decision rather than an oversight.
     */
    val utmZone: String
        get() {
            val zone = floor((longitude + MAX_LONGITUDE) / DEGREES_PER_ZONE).toInt() + 1
            val hemisphere = if (latitude < 0) "S" else "N"
            return "${zone.coerceIn(1, ZONES)}$hemisphere"
        }

    companion object {

        const val MAX_LATITUDE = 90.0

        const val MAX_LONGITUDE = 180.0

        private const val DEGREES_PER_ZONE = 6.0

        private const val ZONES = 60

        /**
         * Reads a latitude a surveyor typed, or null if it is not one.
         *
         * Decimal degrees only, which is what every phone, every mapping site and every cave
         * registry hands out, and what this app's own screen shows. Degrees and minutes are what a
         * paper map has and are deliberately not accepted: half-supporting them — taking
         * `54 07.4` and quietly reading it as 54.074 degrees, seven hundred metres from where the
         * surveyor meant — is worse than not taking them at all.
         */
        fun parseLatitude(text: String): Double? = parseCoordinate(text, MAX_LATITUDE)

        fun parseLongitude(text: String): Double? = parseCoordinate(text, MAX_LONGITUDE)

        /**
         * Reads a height in metres, or null if it is not one.
         *
         * Unbounded above and below on purpose: the deepest cave entrance is well above sea level
         * and the lowest is below it, and a limit here would be a guess about geography rather
         * than a check on typing.
         */
        fun parseAltitude(text: String): Double? = parseNumber(text)?.takeIf { it.isFinite() }

        private fun parseCoordinate(text: String, limit: Double): Double? =
            parseNumber(text)?.takeIf { it.isFinite() && it >= -limit && it <= limit }

        /**
         * A number as somebody types it, comma decimal separator included.
         *
         * Half of Europe writes `54,12345`, and half of European cavers are the people most likely
         * to use this. `toDoubleOrNull` takes a dot and nothing else, so a comma is turned into
         * one — but only when there is no dot already, since `1,234.5` is a thousands separator
         * and not a coordinate anybody meant.
         */
        private fun parseNumber(text: String): Double? {
            val trimmed = text.trim()
            if (trimmed.isEmpty()) return null
            val dotted =
                if (',' in trimmed && '.' !in trimmed) trimmed.replace(',', '.') else trimmed
            return dotted.toDoubleOrNull()
        }
    }
}
