package org.hwyl.sexytopo.shared.comms.ios

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.GattLink
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
 * it has **not been compiled or run**. Expect to fix selector signatures and nullability on the
 * first real build. What has changed since the first draft is how much that matters: every decision
 * this class used to make itself now lives in [GattLink] in `commonMain`, under test on the JVM and
 * on Kotlin/Wasm. What is left here is translation — CoreBluetooth's callbacks in, [GattLink]
 * questions out — so a compile error is the worst thing likely to be wrong with it, rather than a
 * logic error nobody could have run.
 *
 * That was not a theoretical improvement. Pulling the logic out immediately exposed a real defect:
 * this class used to compare `CBUUID.UUIDString` against the profile's 128-bit UUIDs as plain
 * strings, which silently fails for every characteristic BRIC4 and BRIC5 have. See
 * [GattLink.normaliseUuid].
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
 * RFCOMM/SPP, and iOS exposes no public API for that: only MFi-certified accessories may carry data
 * over Bluetooth Classic, and MFi-certifying a discontinued, third-party-modified Leica is not
 * realistic. Every instrument in [InstrumentProfile.ALL] is BLE and needs no Apple certification.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreBluetoothTransport(profile: InstrumentProfile) : BaseInstrumentTransport() {

    private val link = GattLink(profile)

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
        link.reset()
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
                    // advertise no service UUID at all, and matching is by name anyway.
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
                // The advertisement's local name is the live one; the peripheral's cached name can
                // be stale or absent on a device iOS has not seen before.
                val advertisedName =
                    advertisementData[CBAdvertisementDataLocalNameKey] as? String
                        ?: didDiscoverPeripheral.name
                if (!link.matches(advertisedName)) return

                central.stopScan()
                peripheral = didDiscoverPeripheral
                didDiscoverPeripheral.delegate = peripheralDelegate
                central.connectPeripheral(didDiscoverPeripheral, null)
            }

            override fun centralManager(
                central: CBCentralManager,
                didConnectPeripheral: CBPeripheral,
            ) {
                didConnectPeripheral.discoverServices(
                    link.servicesToDiscover.map { CBUUID.UUIDWithString(it) },
                )
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
                link.reset()
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
                for (service in peripheral.services.orEmpty().filterIsInstance<CBService>()) {
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

                for (characteristic in characteristics) {
                    when (link.discovered(characteristic.UUID.UUIDString)) {
                        GattLink.Role.WRITE -> writeCharacteristic = characteristic
                        // CoreBluetooth writes the CCCD itself, so unlike the Android drivers there
                        // is no descriptor to poke.
                        GattLink.Role.NOTIFY -> peripheral.setNotifyValue(true, characteristic)
                        GattLink.Role.IGNORED -> Unit
                    }
                }

                // Only report success once every characteristic the profile needs has turned up;
                // see GattLink.isReady for why a half-configured link is worse than none.
                if (link.isReady && !connectedFlag) {
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
                    link.channelFor(didUpdateValueForCharacteristic.UUID.UUIDString),
                )
            }
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
