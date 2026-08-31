package org.hwyl.sexytopo.demo

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.hwyl.sexytopo.shared.calibration.CalibrationPositions
import org.hwyl.sexytopo.shared.calibration.CalibrationReading
import org.hwyl.sexytopo.shared.calibration.CalibrationResult
import org.hwyl.sexytopo.shared.calibration.CalibrationRun
import org.hwyl.sexytopo.shared.comms.AutoReconnect
import org.hwyl.sexytopo.shared.comms.InstrumentCommand
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.demo.ExampleCalibration
import org.hwyl.sexytopo.shared.log.ActivityLog
import org.hwyl.sexytopo.shared.log.LogType
import org.hwyl.sexytopo.shared.comms.InstrumentDecoder
import org.hwyl.sexytopo.shared.comms.InstrumentPacket
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport
import org.hwyl.sexytopo.shared.comms.InstrumentTransportListener
import org.hwyl.sexytopo.shared.comms.ReconnectionPolicy
import org.hwyl.sexytopo.shared.comms.TransportSubscription
import org.hwyl.sexytopo.shared.comms.measurements
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.SurveySettings
import kotlin.time.TimeSource

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
    /**
     * Milliseconds since this session began, for the reconnection window.
     *
     * A monotonic clock rather than the wall clock the Java uses. `System.currentTimeMillis()` is
     * the wrong clock for measuring an interval: a phone that has been underground for six hours
     * and catches a signal on the way out corrects itself, and the correction lands inside
     * somebody's fifteen-minute window. Monotonic time cannot jump, and nothing here needs to know
     * what o'clock it is.
     *
     * A parameter because a test cannot otherwise wait three seconds for a retry, let alone
     * fifteen minutes for the policy to give up.
     */
    private val elapsedMillis: () -> Long = monotonicElapsed(),
) {
    /** Kept so the demo's "Simulate" button can nudge it; a real instrument needs no such help. */
    val simulator = SimulatedInstrument(script = fieldScript(), loop = true)

    /**
     * Whether to chase a lost instrument, and for how long. Kept current by [DemoState].
     *
     * `pref_auto_reconnect` and `pref_auto_reconnect_window`; see [ReconnectionPolicy] for what a
     * cave does to a Bluetooth link and why this is worth having.
     */
    var autoReconnect by mutableStateOf(AutoReconnect())

    private val reconnection =
        ReconnectionPolicy(settings = { autoReconnect }, now = elapsedMillis)

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

    /**
     * Everything the instrument has done, oldest first, bounded at a hundred lines.
     *
     * The real `Log.LogType.DEVICE`, not a summary of it. The instrument dialog shows the last few;
     * the log screen shows the lot, and can copy them, because the moment this matters is the one
     * where a DistoX will not pair in a cave and there is no console, no cable and no signal.
     *
     * `logRevision` exists because [ActivityLog] is a plain class rather than Compose state:
     * mutating it changes nothing Compose is watching, so the counter is what recomposes.
     */
    val deviceLog = ActivityLog(LogType.DEVICE)

    var logRevision by mutableIntStateOf(0)
        private set

    /** Newest first, for the connection dialog's last-few-lines summary. */
    val log: List<String> get() = deviceLog.entries.asReversed().map { it.text }

    // -------------------------------------------------------------------------------------
    // Calibration
    // -------------------------------------------------------------------------------------

    /**
     * The readings taken since calibration was started.
     *
     * A calibration is a run of 56 shots taken with the instrument pointed and rolled in a
     * prescribed set of positions, which the solver fits sensor corrections to. Kept here rather
     * than in the survey because it belongs to the *instrument*: one calibration serves every
     * survey that instrument takes afterwards.
     */
    val calibration = CalibrationRun()

    /** Whether the instrument has been told to send calibration readings instead of shots. */
    var calibrating by mutableStateOf(false)
        private set

    /** Bumped as readings arrive, so the calibration screen redraws. */
    var calibrationRevision by mutableIntStateOf(0)
        private set

    /**
     * Put the instrument into calibration mode.
     *
     * @return false if this instrument has no calibration commands — FCL exposes none — so the
     *   screen can say so rather than appearing to work.
     */
    fun startCalibration(): Boolean {
        val command = commandFor(InstrumentCommand.START_CALIBRATION) ?: return false
        decoder.calibrating = true
        transport.send(command)
        calibrating = true
        note("calibration started")
        return true
    }

    /** Take the instrument back out of calibration mode, so it sends shots again. */
    fun stopCalibration() {
        commandFor(InstrumentCommand.STOP_CALIBRATION)?.let { transport.send(it) }
        decoder.calibrating = false
        calibrating = false
        note("calibration stopped")
    }

    /**
     * Write the fitted coefficients back to the instrument.
     *
     * This is the step that actually changes anything: until it happens the instrument is still
     * using whatever coefficients it had. Each command is a four-byte memory write from address
     * 0x8010, exactly as `WriteCalibrationProtocol` does.
     */
    fun writeCalibration(result: CalibrationResult): Int {
        val commands = calibration.writeCommands(result)
        for (command in commands) transport.send(command)
        note("wrote ${commands.size} coefficient blocks to the instrument")
        return commands.size
    }

    /**
     * Have the simulated instrument send the next reading of a real 56-shot calibration.
     *
     * The demo equivalent of pressing the instrument's button while it is in calibration mode, and
     * the only way to drive this screen without hardware. The readings are genuine — one of the
     * datasets the solver is tested against — so working through all 56 produces the fit that
     * dataset is known to produce, rather than one that never settles.
     *
     * @return false when the 56 are used up, or when a real instrument is attached: this is a
     *   button for the simulator, and pressing it with a DistoX connected would be pretending.
     */
    fun simulateCalibrationReading(): Boolean {
        if (transport !== simulator) return false
        val reading = ExampleCalibration.READINGS.getOrNull(calibration.count) ?: return false
        simulator.emitCalibrationReading(
            InstrumentPacket.Acceleration(reading.gx, reading.gy, reading.gz),
            InstrumentPacket.Magnetic(reading.mx, reading.my, reading.mz),
        )
        return true
    }

    fun deleteLastCalibrationReading() {
        calibration.deleteLast()
        calibrationRevision++
    }

    fun clearCalibration() {
        calibration.clear()
        calibrationRevision++
    }

    /**
     * The bytes that carry [command] to whichever instrument is attached, or null if it has no
     * such command.
     *
     * The single command byte is the same everywhere — the DistoX defined the vocabulary and the
     * clones adopted it — but how it is wrapped is not, which is what the profile knows.
     */
    private fun commandFor(command: InstrumentCommand): ByteArray? =
        decoder.encodeCommand(command)

    private val listener =
        object : InstrumentTransportListener {
            override fun onConnected() {
                connected = true
                failure = null
                // Ready, not merely joined: `GattSession` withholds this until every
                // characteristic has been found and every subscription confirmed, which is the
                // distinction `ReconnectionPolicy.noteReady` is documented to need.
                reconnection.noteReady()
                note("connected to ${profile?.name ?: "the simulated instrument"}")
            }

            override fun onDisconnected(reason: String?) {
                connected = false
                note("disconnected${reason?.let { ": $it" } ?: ""}")
                considerReconnecting()
            }

            override fun onFailure(reason: String) {
                connected = false
                failure = reason
                note(reason, isError = true)
                considerReconnecting()
            }

            /**
             * Every byte from a radio arrives here, and none of it is under anybody's control.
             *
             * Wrapped, because this is the one place in the app where *foreign data on the main
             * thread* meets code that can throw. A decoder's guards are written against the
             * protocol as documented, and the instruments this port has never met are exactly the
             * ones likely to send something the documentation does not cover — a truncated
             * notification, a firmware revision with an extra field, a device whose advertised
             * name matched a profile it does not really speak. Every one of those is a Kotlin
             * exception raised inside a Bluetooth callback, which on iOS takes the app with it.
             *
             * Losing the app is not losing a packet. The survey is written on every change, so a
             * crash costs at most the shot in flight — but it also costs the connection, the
             * screen, and a surveyor's confidence in the thing holding their trip, in a cave,
             * with cold hands. A line in the log costs a re-shoot.
             *
             * Deliberately not silent: it goes to the log the surveyor can already read and copy
             * off the phone, which is the whole reason that log exists. See finding 56.
             */
            override fun onFrame(channel: FrameChannel, bytes: ByteArray) {
                runCatching { readFrame(channel, bytes) }
                    .onFailure { thrown ->
                        note(
                            "could not read a packet from the instrument " +
                                "(${thrown.message ?: thrown::class.simpleName}); the shot was " +
                                "not recorded",
                            isError = true,
                        )
                    }
            }

            private fun readFrame(channel: FrameChannel, bytes: ByteArray) {
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

                if (calibrating) {
                    collectCalibration(packets)
                    // A calibration reading is not a shot: it must never reach the survey. The
                    // instrument sends nothing else while it is in calibration mode, but a shot
                    // arriving here would be one taken by accident and stored as passage.
                    return
                }

                for (leg in packets.measurements()) {
                    lastReading = leg
                    readingsTaken++

                    val stationCreated = SurveyUpdater.update(survey, leg, settings = settings)
                    revision++

                    if (stationCreated) {
                        note("station ${survey.activeStation.name} created from 3 readings")
                        onStationCreated?.invoke()
                    } else {
                        note("reading ${format(leg)}")
                    }
                }
            }
        }

    /**
     * Files the calibration readings that arrive while calibrating.
     *
     * The pairing of a classic DistoX's two frames happens in the decoder, where the Android app
     * does it too — `DistoXCalibrationDecoder`, which also treats a repeated half as a lost
     * acknowledgement rather than as an error. A DistoX-BLE sends both halves in one notification
     * and needs none of that. Either way one reading arrives here.
     */
    private fun collectCalibration(packets: List<InstrumentPacket>) {
        for (packet in packets) {
            if (packet !is InstrumentPacket.CalibrationReading) continue
            calibration.add(
                CalibrationReading(packet.gx, packet.gy, packet.gz, packet.mx, packet.my, packet.mz),
            )
            calibrationRevision++
            note("calibration reading ${calibration.count} of ${CalibrationPositions.REQUIRED}")
        }
    }

    private var subscription: TransportSubscription = transport.observe(listener)

    fun connect() {
        failure = null
        reconnection.noteUserRequestedConnect()
        if (!connected) transport.connect()
    }

    fun disconnect() {
        // Before the disconnect, not after: the transport reports the drop synchronously on some
        // platforms, and a policy told afterwards would have already decided to chase it.
        reconnection.noteUserRequestedDisconnect()
        transport.disconnect()
        connected = false
    }

    /**
     * A link has gone. Chase it, give up on it, or leave it alone.
     *
     * Never for the simulator, which cannot drop and would only be theatre — and would fire on
     * every switch away from it, since [attach] disconnects the old transport on the way out.
     */
    private fun considerReconnecting() {
        val instrument = profile ?: return
        when (val decision = reconnection.onUnexpectedDisconnection()) {
            is ReconnectionPolicy.Decision.Retry ->
                note("auto-reconnecting to ${instrument.name}…")
            ReconnectionPolicy.Decision.GaveUp ->
                note("persistent connection failure; pausing auto-connection", isError = true)
            ReconnectionPolicy.Decision.LeaveItAlone -> Unit
        }
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
    internal fun attachForTest(
        transport: InstrumentTransport,
        decoder: InstrumentDecoder,
        profile: InstrumentProfile? = null,
    ) = attach(transport, decoder, profile)

    private fun attach(
        transport: InstrumentTransport,
        decoder: InstrumentDecoder,
        profile: InstrumentProfile?,
    ) {
        // Unsubscribe and disconnect the old one first: a transport left observed goes on feeding
        // readings into the survey from an instrument the surveyor thinks they have put away.
        subscription.cancel()
        reconnection.cancel()
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
    /**
     * Let time pass: age out a connection attempt, and perform a retry that has come due.
     *
     * Driven from [App] whenever an instrument is attached, rather than from the connection dialog
     * as it used to be. That was the whole reason none of this could work: a surveyor waiting for
     * an instrument to come back is *drawing*, not sitting on the connection screen, and a clock
     * that only runs while a dialog is open is a clock that never runs when it is needed.
     */
    fun tick() {
        // Wrapped for the same reason [InstrumentTransportListener.onFrame] is: this drives the
        // platform's own connection state machine, from a Compose effect, on the main thread.
        runCatching { tickTransport(transport) }
            .onFailure { note("the instrument link failed: ${it.message}", isError = true) }
        if (reconnection.retryIsDue()) {
            note("reconnecting to ${profile?.name ?: "the instrument"}")
            transport.connect()
        }
    }

    /** Takes one reading from the simulator, connecting first if needed. */
    fun takeReading() {
        if (transport !== simulator) useSimulator()
        connect()
        simulator.emitNextShot()
    }

    private fun note(message: String, isError: Boolean = false) {
        deviceLog.add(nowIso(), message, isError)
        logRevision++
        onLogged?.invoke()
    }

    /**
     * Called when three readings promote to a station.
     *
     * `NewStationNotificationService` in the Android app, which listens for the same event and
     * vibrates. A callback rather than a broadcast, and set by the app rather than by the session,
     * because whether it buzzes is a preference and preferences are not this class's business.
     */
    var onStationCreated: (() -> Unit)? = null

    /**
     * Called after every log line, so the app can write the log out.
     *
     * Saved as it happens rather than on the way out: the crash, the freeze and the battery dying
     * are exactly the cases the log is for, and none of them run any tidy-up code.
     */
    var onLogged: (() -> Unit)? = null

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

/** The default clock for [SurveySession]: milliseconds since the session was constructed. */
private fun monotonicElapsed(): () -> Long {
    val start = TimeSource.Monotonic.markNow()
    return { start.elapsedNow().inWholeMilliseconds }
}
