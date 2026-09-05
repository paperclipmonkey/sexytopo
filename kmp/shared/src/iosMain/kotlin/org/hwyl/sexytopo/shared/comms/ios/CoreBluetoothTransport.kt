package org.hwyl.sexytopo.shared.comms.ios

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.ObjCSignatureOverride
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.GattLink
import org.hwyl.sexytopo.shared.comms.GattSession
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.LinkState
import org.hwyl.sexytopo.shared.comms.WriteType
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBCharacteristicWriteWithoutResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.NSUUID
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * A CoreBluetooth implementation of [org.hwyl.sexytopo.shared.comms.InstrumentTransport].
 *
 * ## Status: compiles for iOS, has never reached a radio
 *
 * This file was authored on Linux, with no Xcode and no Kotlin/Native Apple toolchain, and for most
 * of its life had never been compiled at all. It is now built on every push by a macOS runner
 * (`.github/workflows/kmp.yaml`), so the selector signatures and nullability below are checked by
 * the compiler rather than by argument.
 *
 * What is still unverified is everything that needs a radio. The iOS simulator has no Bluetooth
 * stack, so CI cannot exercise one line of the actual conversation with an instrument: whether the
 * profiles match real advertised names, whether the characteristics are where the table says, and
 * whether a connection survives a cave. Compiling is a floor, not a guarantee.
 *
 * What has changed since the first draft is how much that matters. It used to hold the whole
 * connection lifecycle — and an adversarial review found six defects in it, none of which anybody
 * could have run a test against. All six were lifecycle questions rather than Bluetooth questions,
 * so they now live in [GattSession] and [GattLink] in `commonMain`, under test on the JVM and on
 * Kotlin/Wasm. What is left here is translation: CoreBluetooth's callbacks in, the session's
 * [GattSession.Action] out.
 *
 * That is worth stating plainly, because it is the whole argument for the split. The riskiest file
 * in the port is now the one with the least in it.
 *
 * With one exception: the sixth defect, the missing timeout, is only half fixed. The policy is in
 * [GattSession.tick] and tested, but nothing calls [checkTimeout], so on iOS nothing times out yet.
 * See that function.
 *
 * A later review of what remains found two hard compile errors — anonymous delegate types, and two
 * pairs of Objective-C selectors that collapse onto one Kotlin signature. The compiler then
 * confirmed both, and showed that reading had got the *fixes* wrong twice: `@ObjCSignatureOverride`
 * was reasoned onto one half of each colliding pair, from the protocol's declaration order, and both
 * times it was the wrong half. The diagnostic quotes the signature a declaration collides with
 * rather than the one it is reporting, so the message names one function and the line number points
 * at the other. Hence all four halves carry it now, and nobody should try to be clever about which.
 *
 * ## Why each attempt gets its own manager and delegates
 *
 * CoreBluetooth callbacks arrive whenever they arrive, including after the surveyor has pressed
 * disconnect. Every attempt therefore creates a fresh [CBCentralManager] with fresh delegates that
 * capture the session generation they were made under, and hands it back on every callback. A
 * callback from an abandoned attempt carries an old generation and the session discards it — which
 * is what stops a disconnected app from reconnecting itself, and stops a stale callback from
 * reporting a connection nobody asked for. Releasing the manager with the attempt is also what
 * stops a second `connect()` leaving the first one scanning for ever.
 *
 * ## What it deliberately cannot do
 *
 * There is no path here for the original DistoX or DistoX2. They speak Bluetooth Classic
 * RFCOMM/SPP, and iOS exposes no public API for that: only MFi-certified accessories may carry data
 * over Bluetooth Classic, and MFi-certifying a discontinued, third-party-modified Leica is not
 * realistic. Every instrument in [InstrumentProfile.ALL] is BLE and needs no Apple certification.
 */
// BetaInteropApi as well as ExperimentalForeignApi: constructing Objective-C objects from Kotlin -
// `object : NSObject(), …Protocol` here, `NSData.create` below - is gated behind it, and its
// RequiresOptIn level is ERROR, so a missing opt-in fails the build rather than warning. Opting in
// where it turns out not to be needed only warns, which is the right way round for a file that
// cannot be compiled here.
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
class CoreBluetoothTransport(profile: InstrumentProfile) : BaseInstrumentTransport() {

    private val session = GattSession(profile)

    private var central: CBCentralManager? = null
    private var peripheral: CBPeripheral? = null
    private var writeCharacteristic: CBCharacteristic? = null

    /**
     * Held so ARC does not collect them: `CBCentralManager.delegate` is a weak reference, and a
     * delegate nothing else retains is deallocated immediately, after which no callback ever
     * arrives and the connection silently never happens.
     */
    private var delegates: Delegates? = null

    override val isConnected: Boolean
        get() = session.isConnected

    override val linkState: LinkState
        get() = session.linkState

    /**
     * The last peripheral this transport actually reached, by iOS's own identifier for it.
     *
     * Kept so a later attempt can ask CoreBluetooth for it by name rather than hunting for it in
     * the air. The identifier is stable for as long as iOS remembers the device, and is the only
     * durable handle on offer: a `CBPeripheral` belongs to the manager that produced it, so the
     * object itself cannot outlive the attempt.
     */
    private var lastKnownIdentifier: NSUUID? = null

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun connect() {
        if (session.start(nowMillis()) != GattSession.Action.SCAN) {
            // Already scanning or connected; starting again would strand the attempt in flight.
            return
        }
        val delegates = Delegates(session.generation)
        this.delegates = delegates
        // A null queue means callbacks arrive on the main queue, which matches the
        // single-threaded contract InstrumentTransport documents.
        central = CBCentralManager(delegate = delegates.centralDelegate, queue = null)
        // Scanning itself starts once the manager reports poweredOn; see the delegate below.
    }

    override fun disconnect() {
        // Captured before stop(), which bumps the generation and so deliberately discards the
        // peripheral's own disconnect callback - the callback that would otherwise have emitted
        // this. InstrumentTransport.disconnect documents that onDisconnected follows, and
        // SimulatedInstrument honours it, so without this line the two transports disagree about
        // the contract and only the one nobody can compile is wrong.
        val wasConnected = session.isConnected
        session.stop()
        releasePeripheral()
        releaseCentral()
        if (wasConnected) emitDisconnected(null)
    }

    /**
     * Give up if the attempt has taken too long.
     *
     * **Nothing calls this yet, so on iOS nothing times out.** That is the honest state: the policy
     * is ported and tested in [GattSession.tick], and the plumbing to run it is not written.
     *
     * It is not driven by a timer here on purpose. Scheduling one means `NSTimer` or
     * `dispatch_after`, both of which are cinterop surface this file cannot compile to check — and
     * an unbalanced timer keeps the radio awake, which is a worse failure than the one it fixes.
     * The intent is that the host calls this from wherever it already has a run loop, and the first
     * iOS host to exist has to do so; until then, an instrument that is off or out of range leaves
     * the connection attempt hanging exactly as the first draft did.
     */
    fun checkTimeout() {
        apply(session.tick(nowMillis()))
    }

    override fun send(bytes: ByteArray) {
        val characteristic = writeCharacteristic
        val target = peripheral
        if (characteristic == null || target == null || !session.isConnected) {
            emitFailure("not connected")
            return
        }
        // Per device, from the profile. CaveBLE.kt and FCLBLE.kt both set WRITE_TYPE_NO_RESPONSE
        // on the command characteristic, so this port writes to SAP6, DiscoX and FCL the same way.
        // Neither driver reads the characteristic's advertised properties, so this is the app's
        // choice rather than a fact about the hardware - but a with-response write to a
        // characteristic that only supports write-without-response fails outright, and the command
        // simply never reaches the instrument, which looks exactly like a broken cable and is very
        // hard to diagnose in a cave.
        val writeType =
            when (session.profile.writeType) {
                WriteType.WITH_RESPONSE -> CBCharacteristicWriteWithResponse
                WriteType.WITHOUT_RESPONSE -> CBCharacteristicWriteWithoutResponse
            }
        target.writeValue(bytes.toNSData(), characteristic, writeType)
    }

    // ---------------------------------------------------------------------------------------
    // Turning the session's decisions into CoreBluetooth calls
    // ---------------------------------------------------------------------------------------

    private fun apply(action: GattSession.Action) {
        when (action) {
            GattSession.Action.NONE -> Unit

            // Scanning for all services rather than filtering: several instruments advertise no
            // service UUID at all, and matching is by name anyway. But not before asking iOS
            // whether it already has the instrument - see adoptKnownPeripheral, which is what
            // turns "close and reopen the app" back into "press Connect".
            GattSession.Action.SCAN ->
                if (!adoptKnownPeripheral()) {
                    central?.scanForPeripheralsWithServices(null, null)
                }

            GattSession.Action.CONNECT ->
                peripheral?.let { central?.connectPeripheral(it, null) }

            GattSession.Action.DISCOVER_SERVICES ->
                peripheral?.discoverServices(
                    session.link.servicesToDiscover.map { CBUUID.UUIDWithString(it) },
                )

            GattSession.Action.REPORT_CONNECTED -> emitConnected()

            // Both failure paths tear the attempt right down, manager included. A failure leaves
            // the session in FAILED, from which `connect()` may start again - so a manager left
            // behind here is not merely untidy, it is a scan nothing can stop: the next `connect()`
            // overwrites the field, and `disconnect()` can then only reach the newer one. The
            // commonest case is the scan timeout, where the radio is still actively scanning.
            GattSession.Action.REPORT_FAILURE -> {
                releaseCentral()
                emitFailure(session.failure ?: "could not connect")
            }

            GattSession.Action.DISCONNECT_AND_REPORT_FAILURE -> {
                releasePeripheral()
                releaseCentral()
                emitFailure(session.failure ?: "could not connect")
            }
        }
    }

    /**
     * Ask CoreBluetooth for the instrument instead of listening for it, and take it if it answers.
     *
     * Two questions, in the order that matters:
     *
     * 1. `retrieveConnectedPeripheralsWithServices` - is it *still connected to this phone*? A BLE
     *    peripheral that is connected does not advertise, so if the app has lost track of a link
     *    iOS still holds open, no amount of scanning will ever find it again. That is the state
     *    that used to be escapable only by killing the app, because killing the app is what makes
     *    iOS drop the connection. Asking outright costs one call and gets the instrument back in
     *    a second rather than never.
     * 2. `retrievePeripheralsWithIdentifiers` - is it one we have reached before? Then connect
     *    straight to it. A connect request for a known peripheral is honoured by iOS the moment
     *    the device comes back into range, which is a better way to wait out a caver walking round
     *    a corner than a scan that has to be restarted every fifteen seconds.
     *
     * Returns whether a peripheral was taken up; false means fall back to scanning, which is the
     * only option for an instrument this phone has never met.
     */
    private fun adoptKnownPeripheral(): Boolean {
        val manager = central ?: return false
        val services = session.link.servicesToDiscover.map { CBUUID.UUIDWithString(it) }

        val alreadyConnected =
            manager
                .retrieveConnectedPeripheralsWithServices(services)
                .filterIsInstance<CBPeripheral>()
                .firstOrNull { session.link.matches(it.name) }

        val known =
            alreadyConnected
                ?: lastKnownIdentifier?.let { identifier ->
                    manager
                        .retrievePeripheralsWithIdentifiers(listOf(identifier))
                        .filterIsInstance<CBPeripheral>()
                        .firstOrNull()
                }
                ?: return false

        val action = session.knownPeripheralOffered(session.generation)
        if (action != GattSession.Action.CONNECT) return false
        take(known)
        apply(action)
        return true
    }

    /** Hold a peripheral, its delegate and its identifier together, since they are one decision. */
    private fun take(found: CBPeripheral) {
        peripheral = found
        found.delegate = delegates?.peripheralDelegate
        lastKnownIdentifier = found.identifier
    }

    private fun releasePeripheral() {
        peripheral?.let { central?.cancelPeripheralConnection(it) }
        peripheral = null
        writeCharacteristic = null
    }

    /**
     * Order matters: [releasePeripheral] needs the manager, so call it first.
     *
     * This usually runs from inside a delegate callback, dropping the last strong reference to the
     * delegate that is mid-call. That is safe rather than lucky: ARC loads a weak `delegate` into a
     * strong local for the duration of a message send, so the object outlives the callback whatever
     * happens to this field.
     */
    private fun releaseCentral() {
        central?.stopScan()
        central = null
        delegates = null
    }

    /** Milliseconds since the epoch; only differences of it are ever used. */
    private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    // ---------------------------------------------------------------------------------------
    // The delegates, one pair per attempt
    // ---------------------------------------------------------------------------------------

    private inner class Delegates(val generation: Int) {

        // The type is written out rather than inferred. Kotlin approximates an anonymous object's
        // type to its supertype for any non-local declaration, and refuses outright when there are
        // two of them ("right-hand side has an anonymous type"), which is exactly the shape every
        // Objective-C delegate has: NSObject plus the protocol.
        val centralDelegate: CBCentralManagerDelegateProtocol =
            object : NSObject(), CBCentralManagerDelegateProtocol {

                override fun centralManagerDidUpdateState(central: CBCentralManager) {
                    val poweredOn = central.state == CBManagerStatePoweredOn
                    apply(
                        session.radioStateChanged(
                            poweredOn = poweredOn,
                            description = "state ${central.state}",
                            generation = generation,
                        ),
                    )
                }

                override fun centralManager(
                    central: CBCentralManager,
                    didDiscoverPeripheral: CBPeripheral,
                    advertisementData: Map<Any?, *>,
                    RSSI: NSNumber,
                ) {
                    // The advertisement's local name is the live one; the peripheral's cached name
                    // can be stale or absent on a device iOS has not seen before.
                    val advertisedName =
                        advertisementData[CBAdvertisementDataLocalNameKey] as? String
                            ?: didDiscoverPeripheral.name

                    val action = session.peripheralDiscovered(advertisedName, generation)
                    if (action != GattSession.Action.CONNECT) return

                    central.stopScan()
                    // Qualified because `take` assigns the transport's own `peripheral` field, and
                    // an unqualified call from in here reads well as if it were about this
                    // callback's `peripheral` parameter instead.
                    this@CoreBluetoothTransport.take(didDiscoverPeripheral)
                    apply(action)
                }

                override fun centralManager(
                    central: CBCentralManager,
                    didConnectPeripheral: CBPeripheral,
                ) {
                    apply(session.peripheralConnected(generation))
                }

                // `centralManager:didFailToConnectPeripheral:error:` and
                // `centralManager:didDisconnectPeripheral:error:` are distinct selectors that
                // cinterop maps onto one Kotlin signature — parameter names do not disambiguate an
                // override — so without this the pair is a CONFLICTING_OVERLOADS error. Both halves
                // carry it, because the compiler blames whichever it reaches first and the error
                // quotes the signature it *collides with* rather than the one it is reporting.
                @ObjCSignatureOverride
                override fun centralManager(
                    central: CBCentralManager,
                    didFailToConnectPeripheral: CBPeripheral,
                    error: NSError?,
                ) {
                    apply(session.connectionFailed(error?.localizedDescription, generation))
                }

                @ObjCSignatureOverride
                override fun centralManager(
                    central: CBCentralManager,
                    didDisconnectPeripheral: CBPeripheral,
                    error: NSError?,
                ) {
                    if (session.peripheralDisconnected(error?.localizedDescription, generation)) {
                        releasePeripheral()
                        // The session is back to IDLE, so the next `connect()` builds a fresh
                        // manager; keeping this one would strand it beyond any later disconnect().
                        releaseCentral()
                        emitDisconnected(error?.localizedDescription)
                    }
                }
            }

        val peripheralDelegate: CBPeripheralDelegateProtocol =
            object : NSObject(), CBPeripheralDelegateProtocol {

                override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
                    if (didDiscoverServices != null) {
                        apply(
                            session.connectionFailed(
                                didDiscoverServices.localizedDescription,
                                generation,
                            ),
                        )
                        return
                    }
                    val services = peripheral.services.orEmpty().filterIsInstance<CBService>()
                    if (services.isEmpty()) {
                        apply(session.serviceDiscoveryFinished(generation))
                        return
                    }
                    for (service in services) {
                        peripheral.discoverCharacteristics(null, service)
                    }
                }

                override fun peripheral(
                    peripheral: CBPeripheral,
                    didDiscoverCharacteristicsForService: CBService,
                    error: NSError?,
                ) {
                    if (error != null) {
                        apply(session.connectionFailed(error.localizedDescription, generation))
                        return
                    }

                    val characteristics =
                        didDiscoverCharacteristicsForService.characteristics
                            .orEmpty()
                            .filterIsInstance<CBCharacteristic>()

                    for (characteristic in characteristics) {
                        val role =
                            session.characteristicDiscovered(
                                characteristic.UUID.UUIDString,
                                generation,
                            )
                        when (role) {
                            GattLink.Role.WRITE -> writeCharacteristic = characteristic
                            // CoreBluetooth writes the CCCD itself, so unlike the Android drivers
                            // there is no descriptor to poke. Success or failure comes back as
                            // didUpdateNotificationStateForCharacteristic.
                            GattLink.Role.NOTIFY ->
                                peripheral.setNotifyValue(true, characteristic)
                            GattLink.Role.IGNORED -> Unit
                        }
                    }

                    // Only once *every* requested service has answered. Asking after each one
                    // would reject a device whose second service simply had not replied yet.
                    if (haveAllServicesReported(peripheral)) {
                        apply(session.serviceDiscoveryFinished(generation))
                    }
                }

                // Both members of the colliding pair carry the annotation. Which one the compiler
                // reports is decided by the order `CBPeripheralDelegateProtocol` declares them in,
                // not by the order they appear here: annotating only the one written second
                // compiled the two `centralManager` overrides fine and still failed on this pair.
                @ObjCSignatureOverride
                override fun peripheral(
                    peripheral: CBPeripheral,
                    didUpdateNotificationStateForCharacteristic: CBCharacteristic,
                    error: NSError?,
                ) {
                    apply(
                        session.subscriptionConfirmed(
                            uuid = didUpdateNotificationStateForCharacteristic.UUID.UUIDString,
                            error = error?.localizedDescription,
                            generation = generation,
                        ),
                    )
                }

                // The other half of that pair.
                @ObjCSignatureOverride
                override fun peripheral(
                    peripheral: CBPeripheral,
                    didUpdateValueForCharacteristic: CBCharacteristic,
                    error: NSError?,
                ) {
                    if (error != null) {
                        emitFailure(error.localizedDescription)
                        return
                    }
                    val data = didUpdateValueForCharacteristic.value ?: return
                    emitFrame(
                        data.toByteArray(),
                        session.link.channelFor(
                            didUpdateValueForCharacteristic.UUID.UUIDString,
                        ),
                    )
                }
            }
    }

    /**
     * Whether every service we asked for has now reported its characteristics.
     *
     * BRIC is the reason this is not simply "the first callback": its write characteristic is in a
     * second service, so judging the device on the first service to answer would reject it.
     */
    private fun haveAllServicesReported(peripheral: CBPeripheral): Boolean =
        peripheral.services
            .orEmpty()
            .filterIsInstance<CBService>()
            .all { it.characteristics != null }
}

// -------------------------------------------------------------------------------------------
// NSData bridging
// -------------------------------------------------------------------------------------------

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    val result = ByteArray(size)
    result.usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    return result
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
