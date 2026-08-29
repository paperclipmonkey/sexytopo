package org.hwyl.sexytopo.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentTransportListener
import org.hwyl.sexytopo.shared.comms.distox.DistoXProtocol
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * A live surveying session: readings arrive from an instrument, are decoded, and build the survey.
 *
 * This is the whole point of the app, and every layer it touches is the real ported code — the
 * simulated instrument emits genuine DistoX wire-format packets, [DistoXProtocol] decodes them the
 * way the Android driver does, and [SurveyUpdater] applies the real triple-shot promotion rule. The
 * only pretend part is the radio: [SimulatedInstrument] stands where CoreBluetooth would.
 *
 * Three mutually-agreeing readings promote to a new station, exactly as underground: a surveyor
 * shoots the same leg three times and the app decides they are the same shot.
 */
class SurveySession(
    val survey: Survey,
    private val settings: SurveySettings = SurveySettings.DEFAULT,
) {
    val instrument = SimulatedInstrument(script = fieldScript(), loop = true)

    /** Bumped whenever the survey changes, to drive recomposition of the canvas. */
    var revision by mutableIntStateOf(0)
        private set

    var connected by mutableStateOf(false)
        private set

    var readingsTaken by mutableIntStateOf(0)
        private set

    var lastReading by mutableStateOf<Leg?>(null)
        private set

    /** Newest first, so the UI can show the last few without reversing. */
    var log by mutableStateOf<List<String>>(emptyList())
        private set

    private val listener =
        object : InstrumentTransportListener {
            override fun onConnected() {
                connected = true
                note("connected to the simulated instrument")
            }

            override fun onDisconnected(reason: String?) {
                connected = false
                note("disconnected${reason?.let { ": $it" } ?: ""}")
            }

            override fun onFrame(channel: FrameChannel, bytes: ByteArray) {
                if (!DistoXProtocol.isDataPacket(bytes)) return

                val leg = DistoXProtocol.parseMeasurement(bytes)
                lastReading = leg
                readingsTaken++

                val stationCreated = SurveyUpdater.update(survey, leg, settings = settings)
                revision++

                if (stationCreated) {
                    note("station ${survey.activeStation.name} created from 3 readings")
                } else {
                    note("reading ${format(leg)}")
                }
            }
        }

    private var subscription = instrument.observe(listener)

    fun connect() {
        if (!connected) instrument.connect()
    }

    fun disconnect() {
        if (connected) instrument.disconnect()
    }

    /** Takes one reading, connecting first if needed. */
    fun takeReading() {
        connect()
        instrument.emitNextShot()
    }

    private fun note(message: String) {
        log = (listOf(message) + log).take(6)
    }

    private fun format(leg: Leg) =
        "${oneDp(leg.distance)}m  ${oneDp(leg.azimuth)}°  ${oneDp(leg.inclination)}°"

    companion object {

        /**
         * How a surveyor actually works: each leg is shot three times, with the small
         * disagreement between readings that real instruments produce. The jitter is kept inside
         * [SurveySettings] tolerances so the triple promotes; widen it and the readings stay
         * splays, which is also the correct behaviour.
         */
        fun fieldScript(): List<Leg> {
            val legs =
                listOf(
                    Leg(5.42f, 12.5f, -3.0f),
                    Leg(8.13f, 15.0f, 1.5f),
                    Leg(3.97f, 88.0f, -12.0f),
                    Leg(12.60f, 91.5f, 0.0f),
                    Leg(6.04f, 175.0f, 22.5f),
                    Leg(9.80f, 182.0f, -8.0f),
                )
            // Deterministic jitter: a fixed pattern rather than a random one, so the demo is
            // reproducible and the golden renders do not move.
            val jitter = listOf(Triple(0f, 0f, 0f), Triple(0.01f, 0.3f, 0.2f), Triple(-0.02f, -0.4f, 0.1f))
            return legs.flatMap { leg ->
                jitter.map { (dd, da, di) ->
                    Leg(
                        (leg.distance + dd).coerceAtLeast(0f),
                        (leg.azimuth + da + 360f) % 360f,
                        (leg.inclination + di).coerceIn(-90f, 90f),
                    )
                }
            }
        }
    }
}

/** One decimal place, without java.lang.String.format (absent from commonMain). */
internal fun oneDp(value: Float): String {
    val rounded = kotlin.math.round(value * 10) / 10
    val whole = rounded.toInt()
    val tenths = kotlin.math.abs(kotlin.math.round(rounded * 10).toInt() % 10)
    return if (rounded < 0 && whole == 0) "-0.$tenths" else "$whole.$tenths"
}
