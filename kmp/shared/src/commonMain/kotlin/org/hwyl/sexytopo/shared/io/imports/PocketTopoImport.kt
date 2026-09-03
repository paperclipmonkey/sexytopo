package org.hwyl.sexytopo.shared.io.imports

import org.hwyl.sexytopo.shared.math.adjustAngle
import org.hwyl.sexytopo.shared.model.graph.Coord2D
import org.hwyl.sexytopo.shared.model.graph.ExtendedElevationDirection
import org.hwyl.sexytopo.shared.model.sketch.Colour
import org.hwyl.sexytopo.shared.model.sketch.PathDetail
import org.hwyl.sexytopo.shared.model.sketch.Sketch
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Station
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.model.survey.SurveyDate
import org.hwyl.sexytopo.shared.model.survey.Trip
import org.hwyl.sexytopo.shared.survey.SurveyUpdater

/**
 * PocketTopo's own binary `.top` file, version 3: the file Save writes, not the text Export
 * writes, so it's simply what's on the phone or card someone hands you.
 *
 * ## Why the shots are read in passes
 *
 * A `.top` file stores shots in recording order, not tree order: a surveyor doubling back
 * records a leg whose *from* station doesn't exist yet. So the reader sweeps repeatedly,
 * attaching whatever it can, until a sweep attaches nothing — which also handles a backsight (a
 * shot whose *to* end already exists) by reversing it into this app's forward direction.
 *
 * Legs are attached directly rather than through `SurveyUpdater.update`, as the Java does: the
 * triple-shot rule would see a file's repeated shots as fresh readings and invent auto-named
 * stations mid-import.
 */
object PocketTopoImporter {

    /** `Top` and a version byte. Only version 3 is described by the specification. */
    private val HEADER = byteArrayOf('T'.code.toByte(), 'o'.code.toByte(), 'p'.code.toByte(), 3)

    /** @param name what to call it — the trip block has no name field. */
    fun read(bytes: ByteArray, name: String): Survey {
        val reader = ByteReader(bytes)
        verifyHeader(reader)

        val trips = readTrips(reader)
        val shots = readShots(reader)
        val references = readReferences(reader)

        readMapping(reader) // the overview's own mapping, which nothing here uses

        val planDrawing = readDrawing(reader)
        val elevationDrawing = readDrawing(reader)

        val survey = buildSurvey(name, trips, shots, references)
        survey.planSketch = buildSketch(planDrawing)
        survey.elevationSketch = buildSketch(elevationDrawing)
        survey.isSaved = true
        return survey
    }

    /** Whether these bytes start like a `.top` file, without reading the rest of it. */
    fun looksLikeTopFile(bytes: ByteArray): Boolean =
        bytes.size >= HEADER.size && HEADER.indices.all { bytes[it] == HEADER[it] }

    private fun verifyHeader(reader: ByteReader) {
        val header = ByteArray(HEADER.size) { reader.readByte().toByte() }
        if (!header.contentEquals(HEADER)) {
            throw PocketTopoFormatException(
                "Not a PocketTopo file: expected Top version 3, got " +
                    header.joinToString(" ") { (it.toInt() and 0xFF).toString(16) },
            )
        }
    }

    internal class TripData(val date: SurveyDate, val comment: String, val declination: Float)

    internal class ShotData(
        val from: String?,
        val to: String?,
        val distance: Float,
        val azimuth: Float,
        val inclination: Float,
        val flipped: Boolean,
        val comment: String?,
    )

    internal class ReferenceData(val station: String?, val comment: String)

    internal class PolygonData(val colour: Colour, val points: List<Coord2D>)

    private fun readTrips(reader: ByteReader): List<TripData> =
        List(readCount(reader, "trips")) {
            TripData(
                date = PocketTopoFile.ticksToDate(reader.readInt64()),
                comment = reader.readString(),
                declination = PocketTopoFile.azimuthToDegrees(reader.readInt16()),
            )
        }

    private fun readShots(reader: ByteReader): List<ShotData> =
        List(readCount(reader, "shots")) {
            val from = reader.readId()
            val to = reader.readId()
            val distance = PocketTopoFile.distanceToMetres(reader.readInt32())
            val azimuth = PocketTopoFile.azimuthToDegrees(reader.readInt16())
            val inclination = PocketTopoFile.inclinationToDegrees(reader.readInt16())
            val flags = reader.readByte()
            reader.readByte() // roll, which this app has nowhere to put
            reader.readInt16() // which trip, which nothing here uses
            ShotData(
                from = from,
                to = to,
                distance = distance,
                azimuth = azimuth,
                inclination = inclination,
                flipped = (flags and 1) != 0,
                comment = if ((flags and 2) != 0) reader.readString() else null,
            )
        }

    private fun readReferences(reader: ByteReader): List<ReferenceData> =
        List(readCount(reader, "references")) {
            val station = reader.readId()
            reader.readInt64() // easting
            reader.readInt64() // northing
            reader.readInt32() // altitude
            ReferenceData(station, reader.readString())
        }

    /**
     * A count, checked before it sizes anything: the Java hands a corrupt one straight to
     * `new ArrayList<>(count)`, so four bad bytes are an `OutOfMemoryError`.
     */
    private fun readCount(reader: ByteReader, what: String): Int {
        val count = reader.readInt32()
        if (count < 0 || count > reader.remaining) {
            throw PocketTopoFormatException("A file claiming $count $what is not a PocketTopo file")
        }
        return count
    }

    private fun readMapping(reader: ByteReader) {
        reader.readInt32() // origin x
        reader.readInt32() // origin y
        reader.readInt32() // scale
    }

    private fun readDrawing(reader: ByteReader): List<PolygonData> {
        readMapping(reader) // each drawing has its own
        val polygons = mutableListOf<PolygonData>()
        while (true) {
            when (val elementId = reader.readByte()) {
                0 -> return polygons
                1 -> polygons.add(readPolygon(reader))
                // A cross-section marker: this app's own cross-sections are drawn from splays,
                // and a PocketTopo one has no outline for them, so it's skipped rather than left empty.
                3 -> skipCrossSection(reader)
                else ->
                    throw PocketTopoFormatException("Unknown drawing element type: $elementId")
            }
        }
    }

    private fun readPolygon(reader: ByteReader): PolygonData {
        val pointCount = readCount(reader, "points")
        val points =
            List(pointCount) {
                Coord2D(reader.readInt32() / 1000.0f, reader.readInt32() / 1000.0f)
            }
        return PolygonData(PocketTopoFile.topoColourToColour(reader.readByte()), points)
    }

    private fun skipCrossSection(reader: ByteReader) {
        reader.readInt32() // x
        reader.readInt32() // y
        reader.readId() // the station it hangs off
        reader.readInt32() // which way it faces
    }

    private fun buildSurvey(
        name: String,
        trips: List<TripData>,
        shots: List<ShotData>,
        references: List<ReferenceData>,
    ): Survey {
        val survey = Survey(name)

        trips.firstOrNull()?.let { first ->
            survey.trip = Trip(first.date).also { it.comments = first.comment }
        }

        survey.origin.name = shots.firstOrNull { it.from != null }?.from ?: survey.origin.name

        val processed = BooleanArray(shots.size)
        var progress = true
        while (progress) {
            progress = false
            for (i in shots.indices) {
                if (processed[i]) continue
                if (attach(survey, shots, processed, i)) progress = true
            }
        }

        for (reference in references) {
            if (reference.station == null || reference.comment.isEmpty()) continue
            survey.getStationByName(reference.station)?.comment = reference.comment
        }

        return survey
    }

    /** @return whether this sweep managed to do anything with shot [index]. */
    private fun attach(
        survey: Survey,
        shots: List<ShotData>,
        processed: BooleanArray,
        index: Int,
    ): Boolean {
        val shot = shots[index]

        // A shot with no near end is not attachable to anything; dropping it is what the Java does.
        if (shot.from == null) {
            processed[index] = true
            return true
        }

        val fromStation = survey.getStationByName(shot.from)
        val toStation = shot.to?.let { survey.getStationByName(it) }

        return when {
            shot.to == null -> {
                if (fromStation == null) return false // its station may appear in a later sweep
                processed[index] = true
                val leg = Leg(shot.distance, shot.azimuth, shot.inclination)
                fromStation.addOnwardLeg(leg)
                survey.addLegRecord(leg)
                true
            }

            fromStation != null && toStation == null -> {
                connect(survey, shots, processed, shot, fromStation, backwards = false)
                true
            }

            fromStation == null && toStation != null -> {
                connect(survey, shots, processed, shot, toStation, backwards = true)
                true
            }

            // Both ends already exist: a loop closure, which this app's tree cannot hold.
            fromStation != null -> {
                processed[index] = true
                true
            }

            else -> false // neither end exists yet
        }
    }

    /**
     * Makes the station at the far end of [shot] and hangs it off [anchor]. A backsight is
     * stored pointing away from its station like every leg, so the bearing is turned round and
     * the inclination negated, flagged as shot backwards for the table to show it as read.
     */
    private fun connect(
        survey: Survey,
        shots: List<ShotData>,
        processed: BooleanArray,
        shot: ShotData,
        anchor: Station,
        backwards: Boolean,
    ) {
        val repeats = collectRepeats(shots, processed, shot)
        val newStation = Station(if (backwards) shot.from!! else shot.to!!)
        shot.comment?.takeIf { it.isNotEmpty() }?.let { newStation.comment = it }
        newStation.extendedElevationDirection =
            if (shot.flipped) ExtendedElevationDirection.LEFT else ExtendedElevationDirection.RIGHT

        val averaged = if (repeats.size > 1) SurveyUpdater.averageLegs(repeats) else repeats[0]
        val promotedFrom = if (repeats.size > 1) repeats.toTypedArray() else emptyArray()

        val leg =
            if (backwards) {
                Leg(
                    averaged.distance,
                    adjustAngle(averaged.azimuth, 180f),
                    -averaged.inclination,
                    newStation,
                    promotedFrom,
                    true,
                )
            } else {
                Leg(averaged.distance, averaged.azimuth, averaged.inclination, newStation, promotedFrom)
            }

        anchor.addOnwardLeg(leg)
        survey.addLegRecord(leg)
        survey.activeStation = newStation
    }

    /**
     * Every unprocessed shot between the same pair of stations: PocketTopo's repeats become the
     * average, with the originals attached, as for a leg taken live.
     */
    private fun collectRepeats(
        shots: List<ShotData>,
        processed: BooleanArray,
        target: ShotData,
    ): List<Leg> {
        val legs = mutableListOf<Leg>()
        for (i in shots.indices) {
            if (processed[i]) continue
            val candidate = shots[i]
            if (candidate.from != target.from || candidate.to != target.to) continue
            legs.add(Leg(candidate.distance, candidate.azimuth, candidate.inclination))
            processed[i] = true
        }
        return legs
    }

    private fun buildSketch(polygons: List<PolygonData>): Sketch {
        val sketch = Sketch()
        sketch.pathDetails =
            polygons
                .filter { it.points.isNotEmpty() }
                // No y-flip, unlike the text importer: the binary drawing is already stored in
                // screen orientation, with y increasing downwards.
                .map { PathDetail(it.points, it.colour) }
                .toMutableList()
        return sketch
    }
}
