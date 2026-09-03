package org.hwyl.sexytopo.shared.comms.android

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.UUID
import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.GattLink
import org.hwyl.sexytopo.shared.comms.GattSession
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.WriteType

/**
 * An `android.bluetooth.le` implementation of
 * [org.hwyl.sexytopo.shared.comms.InstrumentTransport].
 *
 * ## Status: compiles and installs, has never reached a radio
 *
 * Written the same way [org.hwyl.sexytopo.shared.comms.ios.CoreBluetoothTransport] was: the whole
 * connection lifecycle — scan, connect, discover, subscribe, timeout — is [GattSession] and
 * [GattLink] in `commonMain`, already under test on the JVM. What is here is translation:
 * [BluetoothGattCallback]'s callbacks in, [GattSession.Action] out. Two real differences from the
 * iOS side, both simplifications rather than complications: Android hands back every discovered
 * service and characteristic in one [BluetoothGattCallback.onServicesDiscovered] callback rather
 * than one service at a time, and there is no `poweredOn` handshake to wait for before scanning —
 * [BluetoothAdapter.isEnabled] is checked once, up front.
 *
 * One genuine extra step Android has and CoreBluetooth does not: enabling a notification is a
 * *local* flag ([BluetoothGatt.setCharacteristicNotification]) and a *remote* one, writing the
 * standard Client Characteristic Configuration descriptor on the device itself. Both are done in
 * [subscribeTo], and only the descriptor write's confirmation
 * ([BluetoothGattCallback.onDescriptorWrite]) is what [GattSession.subscriptionConfirmed] reacts
 * to, matching what [GattLink.subscribed] means: a value has been asked for, not just discovered.
 *
 * GATT callbacks arrive on a Binder thread Android chooses, not the caller's — everything here is
 * re-posted to [mainHandler] before touching [session] or emitting anything, since
 * [org.hwyl.sexytopo.shared.comms.InstrumentTransport] documents a single-threaded contract.
 *
 * `BLUETOOTH_SCAN` and `BLUETOOTH_CONNECT` (Android 12+) are checked before the call that needs
 * them and reported through [GattSession] like any other reason the radio is unusable, rather than
 * letting a `SecurityException` reach the caller — but nothing here *requests* either permission;
 * that is a UI concern this demo does not yet have.
 *
 * @param context the application context, not an activity's — held only for [BluetoothManager] and
 *   permission checks.
 */
class AndroidBleTransport(
    private val context: Context,
    profile: InstrumentProfile,
) : BaseInstrumentTransport() {

    private val session = GattSession(profile)
    private val mainHandler = Handler(Looper.getMainLooper())

    private val bluetoothManager: BluetoothManager? =
        context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter

    /** The attempt currently in flight, if any — recreated fresh by every [connect]. */
    private var callbacks: Callbacks? = null
    private var device: BluetoothDevice? = null
    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null

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
        if (adapter?.isEnabled != true) {
            apply(
                session.radioStateChanged(
                    poweredOn = false,
                    description = "adapter off or absent",
                    generation = session.generation,
                ),
            )
            return
        }
        callbacks = Callbacks(session.generation)
        apply(GattSession.Action.SCAN)
    }

    override fun disconnect() {
        // Captured before stop(), for the same reason CoreBluetoothTransport captures it: stop()
        // bumps the generation and so deliberately discards whatever callback would otherwise have
        // emitted this, and the two transports have to agree that onDisconnected still follows.
        val wasConnected = session.isConnected
        session.stop()
        teardown()
        if (wasConnected) emitDisconnected(null)
    }

    /**
     * Give up if the attempt has taken too long. See [tickTransport][org.hwyl.sexytopo.demo] for
     * where this is actually called from — [GattSession] holds the policy and has no clock of its
     * own.
     */
    fun checkTimeout() {
        apply(session.tick(nowMillis()))
    }

    override fun send(bytes: ByteArray) {
        val characteristic = writeCharacteristic
        val client = gatt
        if (characteristic == null || client == null || !session.isConnected || !hasConnectPermission()) {
            emitFailure("not connected")
            return
        }
        // Per device, from the profile — see CoreBluetoothTransport.send for which devices want
        // which, since the choice is the app's rather than a fact CoreBluetooth or Android expose.
        val writeType =
            when (session.profile.writeType) {
                WriteType.WITH_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                WriteType.WITHOUT_RESPONSE -> BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            client.writeCharacteristic(characteristic, bytes, writeType)
        } else {
            @Suppress("DEPRECATION")
            characteristic.writeType = writeType
            @Suppress("DEPRECATION")
            characteristic.value = bytes
            @Suppress("DEPRECATION")
            client.writeCharacteristic(characteristic)
        }
    }

    // ---------------------------------------------------------------------------------------
    // Turning the session's decisions into android.bluetooth.le calls
    // ---------------------------------------------------------------------------------------

    private fun apply(action: GattSession.Action) {
        when (action) {
            GattSession.Action.NONE -> Unit

            GattSession.Action.SCAN -> {
                val scanner = adapter?.bluetoothLeScanner
                val scanCallback = callbacks?.scanCallback
                if (scanner == null || scanCallback == null || !hasScanPermission()) {
                    apply(
                        session.radioStateChanged(
                            poweredOn = false,
                            description = "no scanner available",
                            generation = session.generation,
                        ),
                    )
                    return
                }
                // No filter, same reasoning as the iOS side: several instruments advertise no
                // service UUID at all, and matching is by name.
                scanner.startScan(scanCallback)
            }

            GattSession.Action.CONNECT -> {
                val target = device
                val gattCallback = callbacks?.gattCallback
                if (target == null || gattCallback == null || !hasConnectPermission()) {
                    apply(session.connectionFailed("bluetooth permission not granted", session.generation))
                    return
                }
                gatt = connectGatt(target, gattCallback)
            }

            GattSession.Action.DISCOVER_SERVICES ->
                if (hasConnectPermission()) gatt?.discoverServices()

            GattSession.Action.REPORT_CONNECTED -> emitConnected()

            // Both failure paths tear the attempt down the same way here — unlike the iOS side,
            // where REPORT_FAILURE only ever arrives before a peripheral exists, Android's simpler
            // connection-state callback (see Callbacks.gattCallback) can reach this with or without
            // a live BluetoothGatt, so releasing both unconditionally is simpler than telling them
            // apart, and releasing something that was never opened is a no-op either way.
            GattSession.Action.REPORT_FAILURE, GattSession.Action.DISCONNECT_AND_REPORT_FAILURE -> {
                teardown()
                emitFailure(session.failure ?: "could not connect")
            }
        }
    }

    /**
     * The 4-argument overload is deprecated in favour of one that also takes a preferred PHY, added
     * in API 26 — three years after this port's `minSdk`, so the deprecated one is the only choice
     * that reaches every device this app declares support for.
     */
    @Suppress("DEPRECATION")
    private fun connectGatt(device: BluetoothDevice, callback: BluetoothGattCallback): BluetoothGatt =
        device.connectGatt(context, false, callback, BluetoothDevice.TRANSPORT_LE)

    private fun subscribeTo(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
        if (!hasConnectPermission()) return
        gatt.setCharacteristicNotification(characteristic, true)
        val cccd = characteristic.getDescriptor(CLIENT_CHARACTERISTIC_CONFIG_UUID) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt.writeDescriptor(cccd, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION")
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            @Suppress("DEPRECATION")
            gatt.writeDescriptor(cccd)
        }
    }

    private fun handleFrame(characteristicUuid: String, bytes: ByteArray?) {
        val data = bytes ?: return
        mainHandler.post { emitFrame(data, session.link.channelFor(characteristicUuid)) }
    }

    private fun teardown() {
        val scanCallback = callbacks?.scanCallback
        if (scanCallback != null && hasScanPermission()) {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        }
        val client = gatt
        if (client != null && hasConnectPermission()) {
            client.disconnect()
            client.close()
        }
        callbacks = null
        device = null
        gatt = null
        writeCharacteristic = null
    }

    private fun hasScanPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED

    private fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED

    /** Milliseconds since the epoch; only differences of it are ever used. */
    private fun nowMillis(): Long = System.currentTimeMillis()

    // ---------------------------------------------------------------------------------------
    // The callbacks, one pair per attempt
    // ---------------------------------------------------------------------------------------

    private inner class Callbacks(val generation: Int) {

        val scanCallback: ScanCallback =
            object : ScanCallback() {

                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    mainHandler.post {
                        // The advertisement's own name, not the (possibly stale or absent) cached
                        // name on the device object — the same preference CoreBluetoothTransport
                        // makes, for the same reason.
                        val advertisedName = result.scanRecord?.deviceName ?: result.device?.name
                        val action = session.peripheralDiscovered(advertisedName, generation)
                        if (action != GattSession.Action.CONNECT) return@post
                        if (hasScanPermission()) adapter?.bluetoothLeScanner?.stopScan(this)
                        device = result.device
                        apply(action)
                    }
                }

                override fun onScanFailed(errorCode: Int) {
                    mainHandler.post {
                        apply(
                            session.radioStateChanged(
                                poweredOn = false,
                                description = "scan failed ($errorCode)",
                                generation = generation,
                            ),
                        )
                    }
                }
            }

        val gattCallback: BluetoothGattCallback =
            object : BluetoothGattCallback() {

                override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
                    mainHandler.post {
                        if (newState == BluetoothProfile.STATE_CONNECTED) {
                            if (status != BluetoothGatt.GATT_SUCCESS) {
                                apply(session.connectionFailed("connect failed ($status)", generation))
                            } else {
                                apply(session.peripheralConnected(generation))
                            }
                            return@post
                        }
                        // A transient STATE_CONNECTING/STATE_DISCONNECTING carries no decision of
                        // its own; only the terminal disconnected state does.
                        if (newState != BluetoothProfile.STATE_DISCONNECTED) return@post

                        val reason = if (status == BluetoothGatt.GATT_SUCCESS) null else "disconnected ($status)"
                        // Android reports "never connected" and "was connected, now is not" through
                        // this one callback; CoreBluetooth has two. Telling them apart here needs
                        // the session's own phase, which is exactly what it is public for.
                        if (session.phase == GattSession.Phase.READY) {
                            if (session.peripheralDisconnected(reason, generation)) {
                                teardown()
                                emitDisconnected(reason)
                            }
                        } else {
                            apply(session.connectionFailed(reason ?: "disconnected before ready", generation))
                        }
                    }
                }

                override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
                    mainHandler.post {
                        if (status != BluetoothGatt.GATT_SUCCESS) {
                            apply(session.connectionFailed("service discovery failed ($status)", generation))
                            return@post
                        }
                        // Every service and characteristic arrives in this one callback, unlike
                        // CoreBluetooth's one-service-at-a-time delegate calls — simpler here, not
                        // harder: there is no "have all services reported yet" to track.
                        for (service in g.services) {
                            for (characteristic in service.characteristics) {
                                when (session.characteristicDiscovered(characteristic.uuid.toString(), generation)) {
                                    GattLink.Role.WRITE -> writeCharacteristic = characteristic
                                    GattLink.Role.NOTIFY -> subscribeTo(g, characteristic)
                                    GattLink.Role.IGNORED -> Unit
                                }
                            }
                        }
                        apply(session.serviceDiscoveryFinished(generation))
                    }
                }

                override fun onDescriptorWrite(
                    g: BluetoothGatt,
                    descriptor: BluetoothGattDescriptor,
                    status: Int,
                ) {
                    mainHandler.post {
                        val error = if (status == BluetoothGatt.GATT_SUCCESS) null else "subscribe failed ($status)"
                        apply(
                            session.subscriptionConfirmed(
                                uuid = descriptor.characteristic.uuid.toString(),
                                error = error,
                                generation = generation,
                            ),
                        )
                    }
                }

                // The pre-Tiramisu overload. Android calls exactly one of this pair per platform
                // version - see the other overload below - so there is no double-handling to guard
                // against.
                @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                ) {
                    handleFrame(characteristic.uuid.toString(), characteristic.value)
                }

                // Added in API 33. Overriding it stops the platform's own default implementation -
                // which otherwise forwards to the deprecated overload above for compatibility - from
                // doing so, which is what keeps this pair from firing twice on a new device.
                override fun onCharacteristicChanged(
                    g: BluetoothGatt,
                    characteristic: BluetoothGattCharacteristic,
                    value: ByteArray,
                ) {
                    handleFrame(characteristic.uuid.toString(), value)
                }
            }
    }

    companion object {
        /** The standard Client Characteristic Configuration descriptor every notify characteristic has. */
        private val CLIENT_CHARACTERISTIC_CONFIG_UUID: UUID =
            UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }
}
