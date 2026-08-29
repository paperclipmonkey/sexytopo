package org.hwyl.sexytopo.shared.comms.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.GattLink
import org.hwyl.sexytopo.shared.comms.GattSession
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
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
import platform.Foundation.create
import platform.Foundation.timeIntervalSince1970
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * A CoreBluetooth implementation of [org.hwyl.sexytopo.shared.comms.InstrumentTransport].
 *
 * ## Status: written, never compiled
 *
 * This file was authored on Linux, where there is no Xcode and no Kotlin/Native Apple toolchain, so
 * it has **not been compiled or run**. Expect to fix selector signatures and nullability on the
 * first real build.
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
@OptIn(ExperimentalForeignApi::class)
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
        session.stop()
        releasePeripheral()
        central?.stopScan()
        central = null
        delegates = null
    }

    /**
     * Give up if the attempt has taken too long.
     *
     * Not driven by a timer here on purpose. Scheduling one means `NSTimer` or `dispatch_after`,
     * both of which are cinterop surface this file cannot compile to check — and getting the
     * timeout wrong is a worse failure than not having one, since an unbalanced timer keeps the
     * radio awake. The host calls this from wherever it already has a run loop; the session decides
     * what it means. See [GattSession.tick].
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
        // Per device, from the profile. SAP6, DiscoX and FCL advertise write-without-response
        // only, so writing with one to them fails and the command never reaches the instrument -
        // which would look exactly like a broken cable and be very hard to diagnose in a cave.
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
            // service UUID at all, and matching is by name anyway.
            GattSession.Action.SCAN -> central?.scanForPeripheralsWithServices(null, null)

            GattSession.Action.CONNECT ->
                peripheral?.let { central?.connectPeripheral(it, null) }

            GattSession.Action.DISCOVER_SERVICES ->
                peripheral?.discoverServices(
                    session.link.servicesToDiscover.map { CBUUID.UUIDWithString(it) },
                )

            GattSession.Action.REPORT_CONNECTED -> emitConnected()

            GattSession.Action.REPORT_FAILURE ->
                emitFailure(session.failure ?: "could not connect")

            GattSession.Action.DISCONNECT_AND_REPORT_FAILURE -> {
                releasePeripheral()
                emitFailure(session.failure ?: "could not connect")
            }
        }
    }

    private fun releasePeripheral() {
        peripheral?.let { central?.cancelPeripheralConnection(it) }
        peripheral = null
        writeCharacteristic = null
    }

    /** Milliseconds since the epoch; only differences of it are ever used. */
    private fun nowMillis(): Long = (NSDate().timeIntervalSince1970 * 1000.0).toLong()

    // ---------------------------------------------------------------------------------------
    // The delegates, one pair per attempt
    // ---------------------------------------------------------------------------------------

    private inner class Delegates(val generation: Int) {

        val centralDelegate =
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
                    // Qualified because `peripheral` alone would bind to the CBPeripheral this
                    // callback is about, not to the transport's own field.
                    this@CoreBluetoothTransport.peripheral = didDiscoverPeripheral
                    didDiscoverPeripheral.delegate = peripheralDelegate
                    apply(action)
                }

                override fun centralManager(
                    central: CBCentralManager,
                    didConnectPeripheral: CBPeripheral,
                ) {
                    apply(session.peripheralConnected(generation))
                }

                override fun centralManager(
                    central: CBCentralManager,
                    didFailToConnectPeripheral: CBPeripheral,
                    error: NSError?,
                ) {
                    apply(session.connectionFailed(error?.localizedDescription, generation))
                }

                override fun centralManager(
                    central: CBCentralManager,
                    didDisconnectPeripheral: CBPeripheral,
                    error: NSError?,
                ) {
                    if (session.peripheralDisconnected(error?.localizedDescription, generation)) {
                        releasePeripheral()
                        emitDisconnected(error?.localizedDescription)
                    }
                }
            }

        val peripheralDelegate =
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

@OptIn(ExperimentalForeignApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}
