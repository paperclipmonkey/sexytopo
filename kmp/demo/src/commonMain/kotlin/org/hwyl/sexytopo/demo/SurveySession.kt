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
import org.hwyl.sexytopo.shared.comms.ShotDetail
import org.hwyl.sexytopo.shared.comms.TransportSubscription
import org.hwyl.sexytopo.shared.comms.ShotTrouble
import org.hwyl.sexytopo.shared.comms.toHex
import org.hwyl.sexytopo.shared.io.export.formatFixed
import org.hwyl.sexytopo.shared.comms.fcl.FclProtocol
import org.hwyl.sexytopo.shared.comms.sim.SimulatedInstrument
import org.hwyl.sexytopo.shared.model.survey.Leg
import org.hwyl.sexytopo.shared.model.survey.Survey
import org.hwyl.sexytopo.shared.survey.SurveyUpdater
import org.hwyl.sexytopo.shared.survey.SurveySettings
import kotlin.time.TimeSource

/**
 * A live surveying session: readings arrive from an instrument, are decoded, and build the survey.
 *
 * Every layer it touches is the real ported code — the decoders are the Android drivers' own,
 * translated byte for byte, and [SurveyUpdater] applies the real triple-shot promotion rule.
 *
 * The radio is the only thing that varies. A [SimulatedInstrument] emitting genuine classic DistoX
 * packets and a CoreBluetooth or Web Bluetooth link to a real one both arrive here as an
 * [InstrumentTransport] and a matching [InstrumentDecoder], and nothing below this line can tell
 * them apart: the simulated path is exercised on every push, so the shared half of the real one is
 * too.
 */
class SurveySession(
    val survey: Survey,
    initialSettings: SurveySettings = SurveySettings.DEFAULT,
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
     * The tolerances a real reading is amalgamated against: `pref_leg_amalgamation_algorithm`,
     * `pref_max_distance_delta`/`pref_max_angle_delta` and the repeat count for a new station.
     *
     * A `var` rather than the constructor-only `val` this began as, because `DemoState` can open a
     * settings dialog on a session that already exists. A constructor-only value here made a
     * changed setting apply to the *next* survey opened and never to the one in progress: every
     * reading from an instrument or the simulator was silently checked against
     * [SurveySettings.DEFAULT] forever, whatever the dialog showed or the library had saved.
     */
    var settings: SurveySettings = initialSettings

    /**
     * Whether to chase a lost instrument, and for how long. Kept current by [DemoState].
     * `pref_auto_reconnect` and `pref_auto_reconnect_window`; see [ReconnectionPolicy].
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
     * Why the instrument is refusing to shoot, in the surveyor's terms rather than its own.
     *
     * Null until an instrument reports a problem, and null again the moment a reading arrives -
     * because a refusal that has been superseded by a good shot is history, and a banner about it
     * left on screen is worse than none. Everything that ever went wrong is still in [log].
     */
    var trouble by mutableStateOf<ShotTrouble?>(null)
        private set

    /** Everything reported since the last good shot, so [trouble] can pick the one to act on. */
    private val troublesSinceAReading = mutableSetOf<ShotTrouble>()

    /**
     * The instrument's own numbers for the codes it last refused on, newest value per code.
     *
     * A BRIC sends two floats with every error and this app used to drop both. They are the same
     * numbers the instrument prints on its own screen - `Mag1 Low: 0.8235` - and are the only thing
     * on offer that *moves* while the surveyor does, unlike the error code itself.
     *
     * Shown as the instrument sent them rather than converted, because this port does not know
     * what scale they are on.
     */
    var troubleDetail by mutableStateOf<String?>(null)
        private set

    /** Latest value per error code, in the order the codes were first seen. */
    private val troubleNumbers = LinkedHashMap<Int, String>()

    /**
     * Write every frame to the log as it arrives, decoded or not. `pref_developer_mode`.
     *
     * Exists for one situation that is otherwise undiagnosable in a cave: **the instrument is
     * shooting and the app is recording nothing.** Without it that looks the same from the outside
     * as a radio that never connected, because a frame which decodes to no packets is logged
     * nowhere. Off by default, because the log is a hundred lines and a surveyor reading it wants
     * the sentences rather than the hex.
     */
    var traceFrames by mutableStateOf(false)

    /**
     * Everything the instrument has done, oldest first, bounded at a hundred lines.
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
     * The readings taken since calibration was started: 56 shots pointed and rolled in a
     * prescribed set of positions. Kept here rather than in the survey because it belongs to the
     * *instrument*: one calibration serves every survey that instrument takes afterwards.
     */
    val calibration = CalibrationRun()

    /** Whether the instrument has been told to send calibration readings instead of shots. */
    var calibrating by mutableStateOf(false)
        private set

    /** Bumped as readings arrive, so the calibration screen redraws. */
    var calibrationRevision by mutableIntStateOf(0)
        private set

    /**
     * Whether this instrument can be put into calibration mode at all.
     *
     * False for a BRIC, whose [org.hwyl.sexytopo.shared.comms.InstrumentFamily] is declared with
     * an empty command set, and for FCL, which exposes none either. The screen asks *before*
     * offering the workflow rather than reporting the failure afterwards.
     */
    val canCalibrate: Boolean
        get() = commandFor(InstrumentCommand.START_CALIBRATION) != null

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
        val commands = calibration.writeCommands(result, decoder.family)
        for (command in commands) transport.send(command)
        // "coefficient blocks" undersells it on a BLE instrument, which gets the whole result in
        // one frame and, per DistoXBleFraming's own note, sends no reply to confirm it landed -
        // "wrote 1 coefficient block" would read as a partial write rather than the whole thing.
        note(
            if (commands.size == 1) {
                "sent calibration to the instrument"
            } else {
                "wrote ${commands.size} coefficient blocks to the instrument"
            },
        )
        return commands.size
    }

    /**
     * Have the simulated instrument send the next reading of a real 56-shot calibration.
     *
     * The demo equivalent of pressing the instrument's button while it is in calibration mode. The
     * readings are genuine — one of the datasets the solver is tested against — so working through
     * all 56 produces the fit that dataset is known to produce, rather than one that never settles.
     *
     * @return false when the 56 are used up, or when a real instrument is attached.
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
     * such command. The single command byte is the same everywhere, but how it is wrapped is not.
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
             * thread* meets code that can throw: an instrument sending something the decoder's
             * guards do not cover — a truncated notification, an unexpected firmware field — raises
             * a Kotlin exception inside a Bluetooth callback, which on iOS takes the app with it.
             * Deliberately not silent: it goes to the log the surveyor can already read and copy
             * off the phone.
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

                // The raw trace, before anything is made of the bytes — so a frame that goes on
                // to throw is still in the log, which is the case where it is worth most.
                if (traceFrames) {
                    note("frame on $channel, ${bytes.size} bytes ${bytes.toHex()}")
                    if (packets.isEmpty()) {
                        note("  ...decoded to nothing")
                    }
                }

                // Before anything else. Four of these instruments will not send the next shot
                // until the last one is acknowledged, and a reply withheld because this port did
                // not understand the frame looks, underground, exactly like a flat battery.
                decoder.acknowledgementFor(channel, bytes)?.let { transport.send(it) }

                for (packet in packets) {
                    if (packet !is InstrumentPacket.DeviceFailure) continue
                    // Only the first failure is toasted upstream (`showToUser`), but the numbers go
                    // in the log line regardless, since a record of *which* readings were refused is
                    // worth more than a record that some were.
                    val numbers = "${formatFixed(packet.data1, 4)}, ${formatFixed(packet.data2, 4)}"
                    if (packet.showToUser) note("instrument: ${packet.description} ($numbers)")
                    troublesSinceAReading += ShotTrouble.ofBric(packet.code)
                    trouble = ShotTrouble.worstOf(troublesSinceAReading)
                    troubleNumbers[packet.code] = "${packet.description} $numbers"
                    while (troubleNumbers.size > MOST_NUMBERS_WORTH_SHOWING) {
                        troubleNumbers.remove(troubleNumbers.keys.first())
                    }
                    troubleDetail =
                        "The instrument reported: " +
                            troubleNumbers.values.joinToString("; ") + "."
                }

                if (calibrating) {
                    collectCalibration(packets)
                    // A calibration reading is not a shot: it must never reach the survey. The
                    // instrument sends nothing else while it is in calibration mode, but a shot
                    // arriving here would be one taken by accident and stored as passage.
                    return
                }

                for (packet in packets.filterIsInstance<InstrumentPacket.Measurement>()) {
                    val leg = packet.leg
                    lastReading = leg
                    readingsTaken++
                    // A shot got through, so whatever was wrong is no longer what is happening.
                    troublesSinceAReading.clear()
                    troubleNumbers.clear()
                    trouble = null
                    troubleDetail = null

                    val stationCreated = SurveyUpdater.update(survey, leg, settings = settings)
                    revision++

                    if (stationCreated) {
                        note("station ${survey.activeStation.name} created from 3 readings")
                        onStationCreated?.invoke()
                    } else {
                        note("reading ${format(leg)}")
                    }
                    noteTelemetry(packet.detail)
                }
            }
        }

    /**
     * Files the calibration readings that arrive while calibrating.
     *
     * The pairing of a classic DistoX's two frames happens in the decoder; a DistoX-BLE sends both
     * halves in one notification and needs none of that. Either way one reading arrives here.
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
     * Never for the simulator: it cannot drop, and would otherwise fire on every switch away from
     * it, since [attach] disconnects the old transport on the way out.
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
     * Attaches a real instrument, replacing whatever was attached before. Returns false when the
     * platform has no radio, which is not an error: the connection screen says why in words.
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
     * Attaches a transport directly. Exists for tests: [useInstrument] goes through
     * [platformTransportFor], which on every target a test can run on returns null.
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
     * Let time pass: age out a connection attempt, and perform a retry that has come due.
     *
     * Driven from [App] whenever an instrument is attached, rather than from the connection dialog:
     * a surveyor waiting for an instrument to come back is *drawing*, not sitting on the connection
     * screen, and a clock that only runs while a dialog is open is a clock that never runs when it
     * is needed.
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
     * Called when three readings promote to a station. A callback rather than a broadcast, and set
     * by the app rather than by the session, because whether it buzzes is a preference.
     */
    var onStationCreated: (() -> Unit)? = null

    /**
     * Called after every log line, so the app can write the log out. Saved as it happens rather
     * than on the way out: the crash, the freeze and the battery dying are exactly the cases the
     * log is for, and none of them run any tidy-up code.
     */
    var onLogged: (() -> Unit)? = null

    private fun format(leg: Leg) =
        "${oneDp(leg.distance)}m  ${oneDp(leg.azimuth)}°  ${oneDp(leg.inclination)}°"

    /**
     * Logs the extra telemetry FCL rides along with a shot: the quality/battery/roll summary, then
     * the retake recommendation when quality drops below half. The classic DistoX and every other
     * supported instrument leave [detail] at [ShotDetail.NONE], so this is silent for them.
     */
    private fun noteTelemetry(detail: ShotDetail) {
        val quality = detail.shotQuality ?: return
        val battery = detail.batteryPercent
        val temperature = detail.temperatureCelsius
        val roll = detail.roll
        note(
            "  quality ${FclProtocol.qualityDescription(quality)} (${oneDp(quality * 100)}%)" +
                (battery?.let { ", battery $it%" } ?: "") +
                (roll?.let { ", roll ${oneDp(it)}°" } ?: "") +
                (temperature?.let { ", ${oneDp(it)}°C" } ?: ""),
        )
        if (quality < 0.5f) note("instrument: shot quality poor, consider retaking")
    }

    companion object {

        /**
         * How a surveyor actually works: each leg is shot three times, with the small
         * disagreement between readings that real instruments produce, kept inside
         * [SurveySettings] tolerances so the triple promotes.
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

        /**
         * Enough to see a pattern, few enough to read at arm's length by head torch: four codes
         * covers the commonest refusal - two magnetometers, an accelerometer, and the azimuth
         * calculation that failed because of them.
         */
        private const val MOST_NUMBERS_WORTH_SHOWING = 4
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
