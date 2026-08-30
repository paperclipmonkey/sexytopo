package org.hwyl.sexytopo.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentTransportListener
import org.hwyl.sexytopo.shared.comms.TransportSubscription
import org.hwyl.sexytopo.shared.comms.measurements
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.SurveySettings

/**
 * A live surveying session: readings arrive from an instrument, are decoded, and build the survey.
 *
 * This is the whole point of the app, and every layer it touches is the real ported code — the
 * decoders are the Android drivers' own, translated byte for byte, and [SurveyUpdater] applies the
 * real triple-shot promotion rule.
 *
 * The radio is now the only thing that varies. A [SimulatedInstrument] emitting genuine classic
 * DistoX packets and a CoreBluetooth or Web Bluetooth link to a real one both arrive here as an
 * [InstrumentTransport] and a matching [InstrumentDecoder], and nothing below this line can tell
 * them apart. That is deliberate: the simulated path is exercised on every push, so the shared
 * half of the real one is too.
 *
 * Three mutually-agreeing readings promote to a new station, exactly as underground: a surveyor
 * shoots the same leg three times and the app decides they are the same shot.
 */
class SurveySession(
    val survey: Survey,
    private val settings: SurveySettings = SurveySettings.DEFAULT,
) {
    /** Kept so the demo's "Simulate" button can nudge it; a real instrument needs no such help. */
    val simulator = SimulatedInstrument(script = fieldScript(), loop = true)

    private var transport: InstrumentTransport = simulator
    private var decoder: InstrumentDecoder = InstrumentDecoder.classicDistoX()

    /** Which instrument is attached, for the connection screen. Null while simulated. */
    var profile by mutableStateOf<InstrumentProfile?>(null)
        private set

    /** Set when a connection attempt fails, so the surveyor is told rather than left waiting. */
    var failure by mutableStateOf<String?>(null)
        private set

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
                failure = null
                note("connected to ${profile?.name ?: "the simulated instrument"}")
            }

            override fun onDisconnected(reason: String?) {
                connected = false
                note("disconnected${reason?.let { ": $it" } ?: ""}")
            }

            override fun onFailure(reason: String) {
                connected = false
                failure = reason
                note(reason)
            }

            override fun onFrame(channel: FrameChannel, bytes: ByteArray) {
                val packets = decoder.decode(channel, bytes)

                // Before anything else. Four of these instruments will not send the next shot
                // until the last one is acknowledged, and a reply withheld because this port did
                // not understand the frame looks, underground, exactly like a flat battery.
                decoder.acknowledgementFor(channel, bytes)?.let { transport.send(it) }

                for (packet in packets) {
                    if (packet is InstrumentPacket.DeviceFailure && packet.showToUser) {
                        note("instrument: ${packet.description}")
                    }
                }

                for (leg in packets.measurements()) {
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
        }

    private var subscription: TransportSubscription = transport.observe(listener)

    fun connect() {
        failure = null
        if (!connected) transport.connect()
    }

    fun disconnect() {
        transport.disconnect()
        connected = false
    }

    /**
     * Attaches a real instrument, replacing whatever was attached before.
     *
     * Returns false when the platform has no radio, which is not an error to report as a failure:
     * the connection screen says why in words instead.
     */
    fun useInstrument(profile: InstrumentProfile): Boolean {
        val transport = platformTransportFor(profile) ?: return false
        attach(transport, InstrumentDecoder.forProfile(profile), profile)
        connect()
        return true
    }

    /** Goes back to the simulated instrument, which is what the demo runs on. */
    fun useSimulator() = attach(simulator, InstrumentDecoder.classicDistoX(), null)

    /**
     * Attaches a transport directly.
     *
     * Exists for tests: [useInstrument] goes through [platformTransportFor], which on every target
     * a test can run on returns null, so there would otherwise be no way to exercise the half of
     * this that a cave exercises.
     */
    internal fun attachForTest(transport: InstrumentTransport, decoder: InstrumentDecoder) =
        attach(transport, decoder, null)

    private fun attach(
        transport: InstrumentTransport,
        decoder: InstrumentDecoder,
        profile: InstrumentProfile?,
    ) {
        // Unsubscribe and disconnect the old one first: a transport left observed goes on feeding
        // readings into the survey from an instrument the surveyor thinks they have put away.
        subscription.cancel()
        runCatching { this.transport.disconnect() }

        this.transport = transport
        this.decoder = decoder
        this.profile = profile
        this.connected = false
        this.failure = null
        decoder.reset()
        subscription = transport.observe(listener)
        note(if (profile == null) "using the simulated instrument" else "connecting to ${profile.name}")
    }

    /**
     * Lets a connection attempt time out.
     *
     * [org.hwyl.sexytopo.shared.comms.GattSession] holds the timeout policy and has no clock, and
     * the transports deliberately schedule no timer of their own — an unbalanced one keeps the
     * radio awake, which is worse than the failure it fixes. So the host calls this while the
     * connection screen is open, which is also the only time anybody is waiting.
     */
    fun tick() = tickTransport(transport)

    /** Takes one reading from the simulator, connecting first if needed. */
    fun takeReading() {
        if (transport !== simulator) useSimulator()
        connect()
        simulator.emitNextShot()
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
