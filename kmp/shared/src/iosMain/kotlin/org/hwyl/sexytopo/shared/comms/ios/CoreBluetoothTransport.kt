package org.hwyl.sexytopo.shared.comms.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.FrameChannel
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import platform.CoreBluetooth.CBAdvertisementDataLocalNameKey
import platform.CoreBluetooth.CBCentralManager
import platform.CoreBluetooth.CBCentralManagerDelegateProtocol
import platform.CoreBluetooth.CBCharacteristic
import platform.CoreBluetooth.CBCharacteristicWriteWithResponse
import platform.CoreBluetooth.CBManagerStatePoweredOn
import platform.CoreBluetooth.CBPeripheral
import platform.CoreBluetooth.CBPeripheralDelegateProtocol
import platform.CoreBluetooth.CBService
import platform.CoreBluetooth.CBUUID
import platform.Foundation.NSData
import platform.Foundation.NSError
import platform.Foundation.NSNumber
import platform.Foundation.create
import platform.darwin.NSObject
import platform.posix.memcpy

/**
 * A CoreBluetooth implementation of [org.hwyl.sexytopo.shared.comms.InstrumentTransport].
 *
 * ## Status: written, never compiled
 *
 * This file was authored on Linux, where there is no Xcode and no Kotlin/Native Apple toolchain, so
 * it has **not been compiled or run**. Treat it as a concrete starting point rather than working
 * code: expect to fix selector signatures and nullability on the first real build. Everything it
 * depends on — the profiles, the decoders, the survey engine — *is* tested, on both the JVM and
 * Kotlin/Wasm.
 *
 * ## Why it is short
 *
 * This is the entire iOS-specific Bluetooth surface. The feasibility study's central claim was that
 * the instrument layer splits into portable protocol logic and a thin platform transport; this is
 * the thin part. Everything above it — packet decoding, the command vocabulary, the survey engine —
 * is shared code already exercised by tests.
 *
 * ## What it deliberately cannot do
 *
 * There is no path here for the original DistoX or DistoX2. They speak Bluetooth Classic
 * RFCOMM/SPP, and iOS exposes no public API for that: only MFi-certified accessories may carry
 * data over Bluetooth Classic, and MFi-certifying a discontinued, third-party-modified Leica is not
 * realistic. Every instrument in [InstrumentProfile.ALL] is BLE and needs no Apple certification.
 *
 * ## A bug this design drops
 *
 * `Bric4Manager` on Android cannot tell which of BRIC's three indication characteristics delivered
 * a packet, so it cycles blindly through the roles and its own comment admits the desync risk.
 * CoreBluetooth passes the characteristic to every callback, so [routeChannel] can dispatch by UUID
 * and the failure mode simply does not exist here.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreBluetoothTransport(
    private val profile: InstrumentProfile,
) : BaseInstrumentTransport() {

    private var central: CBCentralManager? = null
    private var peripheral: CBPeripheral? = null
    private var writeCharacteristic: CBCharacteristic? = null
    private var connectedFlag = false

    override val isConnected: Boolean
        get() = connectedFlag

    // ---------------------------------------------------------------------------------------
    // Lifecycle
    // ---------------------------------------------------------------------------------------

    override fun connect() {
        // Passing a null queue means callbacks arrive on the main queue, which matches the
        // single-threaded contract InstrumentTransport documents.
        central = CBCentralManager(delegate = centralDelegate, queue = null)
        // Scanning starts once the manager reports poweredOn; see centralManagerDidUpdateState.
    }

    override fun disconnect() {
        peripheral?.let { central?.cancelPeripheralConnection(it) }
        central?.stopScan()
        peripheral = null
        writeCharacteristic = null
        connectedFlag = false
    }

    override fun send(bytes: ByteArray) {
        val characteristic = writeCharacteristic
        val target = peripheral
        if (characteristic == null || target == null) {
            emitFailure("not connected")
            return
        }
        target.writeValue(bytes.toNSData(), characteristic, CBCharacteristicWriteWithResponse)
    }

    // ---------------------------------------------------------------------------------------
    // Central manager: discovery and connection
    // ---------------------------------------------------------------------------------------

    private val centralDelegate =
        object : NSObject(), CBCentralManagerDelegateProtocol {

            override fun centralManagerDidUpdateState(central: CBCentralManager) {
                if (central.state == CBManagerStatePoweredOn) {
                    // Scanning for all services rather than filtering: several instruments
                    // advertise no service UUID, and the Android app matches on name anyway.
                    central.scanForPeripheralsWithServices(null, null)
                } else {
                    emitFailure("bluetooth unavailable (state ${central.state})")
                }
            }

            override fun centralManager(
                central: CBCentralManager,
                didDiscoverPeripheral: CBPeripheral,
                advertisementData: Map<Any?, *>,
                RSSI: NSNumber,
            ) {
                val advertisedName =
                    advertisementData[CBAdvertisementDataLocalNameKey] as? String
                        ?: didDiscoverPeripheral.name
                        ?: return

                // The same name-prefix matching the Android app uses, which is the one piece of
                // discovery that ports unchanged.
                if (!advertisedName.startsWith(profile.namePrefix)) return

                central.stopScan()
                peripheral = didDiscoverPeripheral
                didDiscoverPeripheral.delegate = peripheralDelegate
                central.connectPeripheral(didDiscoverPeripheral, null)
            }

            override fun centralManager(
                central: CBCentralManager,
                didConnectPeripheral: CBPeripheral,
            ) {
                val services =
                    listOf(profile.serviceUuid, profile.writeServiceUuid)
                        .distinct()
                        .map { CBUUID.UUIDWithString(it) }
                didConnectPeripheral.discoverServices(services)
            }

            override fun centralManager(
                central: CBCentralManager,
                didFailToConnectPeripheral: CBPeripheral,
                error: NSError?,
            ) {
                connectedFlag = false
                emitFailure(error?.localizedDescription ?: "failed to connect")
            }

            override fun centralManager(
                central: CBCentralManager,
                didDisconnectPeripheral: CBPeripheral,
                error: NSError?,
            ) {
                connectedFlag = false
                writeCharacteristic = null
                emitDisconnected(error?.localizedDescription)
            }
        }

    // ---------------------------------------------------------------------------------------
    // Peripheral: characteristics and inbound frames
    // ---------------------------------------------------------------------------------------

    private val peripheralDelegate =
        object : NSObject(), CBPeripheralDelegateProtocol {

            override fun peripheral(peripheral: CBPeripheral, didDiscoverServices: NSError?) {
                if (didDiscoverServices != null) {
                    emitFailure(didDiscoverServices.localizedDescription)
                    return
                }
                val services = peripheral.services.orEmpty().filterIsInstance<CBService>()
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
                    emitFailure(error.localizedDescription)
                    return
                }

                val characteristics =
                    didDiscoverCharacteristicsForService.characteristics
                        .orEmpty()
                        .filterIsInstance<CBCharacteristic>()

                val notifyUuids = profile.notifyCharacteristicUuids.map { it.lowercase() }
                for (characteristic in characteristics) {
                    val uuid = characteristic.UUID.UUIDString.lowercase()
                    when {
                        uuid == profile.writeCharacteristicUuid.lowercase() ->
                            writeCharacteristic = characteristic
                        // CoreBluetooth writes the CCCD itself, so unlike the Android drivers
                        // there is no descriptor to poke.
                        uuid in notifyUuids -> peripheral.setNotifyValue(true, characteristic)
                    }
                }

                if (writeCharacteristic != null && !connectedFlag) {
                    connectedFlag = true
                    emitConnected()
                }
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
                    routeChannel(didUpdateValueForCharacteristic.UUID.UUIDString),
                )
            }
        }

    /**
     * Maps the characteristic a frame arrived on to its logical channel — which is what lets the
     * FCL decoder tell a primary packet from an extended one without guessing.
     */
    private fun routeChannel(characteristicUuid: String): FrameChannel {
        val index =
            profile.notifyCharacteristicUuids.indexOfFirst {
                it.equals(characteristicUuid, ignoreCase = true)
            }
        return if (index >= 0) profile.notifyChannels[index] else FrameChannel.DEFAULT
    }
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
