package org.hwyl.sexytopo.demo

import org.hwyl.sexytopo.shared.comms.BaseInstrumentTransport
import org.hwyl.sexytopo.shared.comms.GattLink
import org.hwyl.sexytopo.shared.comms.InstrumentProfile
import org.hwyl.sexytopo.shared.comms.InstrumentTransport
import org.hwyl.sexytopo.shared.comms.WriteType

/**
 * Web Bluetooth: a real radio, in a browser.
 *
 * This is the second transport in the port and the first one anybody without a Mac can run. Chrome
 * has had Web Bluetooth since 2017 and Chrome on Android has it too, so an Android phone in a
 * browser can talk to a DistoX-BLE, a Cavway, a BRIC, a SAP6 or an FCL — through exactly the same
 * profiles, decoders and acknowledgements that CoreBluetooth uses.
 *
 * Two limits are worth stating plainly. Safari has never implemented Web Bluetooth on any platform,
 * so this does nothing on an iPhone — which is why the native build exists. And Web Bluetooth is
 * GATT only: no browser speaks Bluetooth Classic, so the original DistoX and DistoX2 are as far out
 * of reach here as they are on iOS.
 *
 * ## Why it is written as a queue rather than as callbacks
 *
 * Kotlin/Wasm's `js()` interop is one-way — Kotlin can call into JavaScript and read back a
 * primitive, and JavaScript cannot call back into Kotlin. Web Bluetooth is entirely callbacks and
 * promises. So the JavaScript side runs the conversation and appends every event to a queue, and
 * [pump] drains it from the same Compose ticker that drives the iOS timeout. The events are lines
 * of text rather than JSON because the demo module has no serialisation dependency and this format
 * is four fields wide.
 *
 * ## Status
 *
 * Written against the specification and never run against an instrument, exactly like
 * `CoreBluetoothTransport`. What *is* checked is that the page loads and the button reports
 * honestly when the browser has no Web Bluetooth at all, which is the case a caver with an iPhone
 * will meet.
 */
class WebBluetoothTransport private constructor(
    private val profile: InstrumentProfile,
) : BaseInstrumentTransport() {

    private val link = GattLink(profile)
    private var connected = false

    override val isConnected: Boolean get() = connected

    override fun connect() {
        // requestDevice must be called from inside a user gesture, which a tap on Connect is.
        val started =
            bleStart(
                namePrefix = profile.namePrefix,
                serviceUuid = profile.serviceUuid,
                writeServiceUuid = profile.writeServiceUuid,
                notifyUuids = profile.notifyCharacteristicUuids.joinToString(","),
                writeUuid = profile.writeCharacteristicUuid,
                withResponse = profile.writeType == WriteType.WITH_RESPONSE,
            )
        if (!started) emitFailure("this browser cannot start a Bluetooth request")
    }

    override fun disconnect() {
        bleStop()
        if (connected) {
            connected = false
            emitDisconnected("disconnected")
        }
    }

    override fun send(bytes: ByteArray) {
        if (!connected) {
            emitFailure("not connected")
            return
        }
        if (!bleSend(bytes.toHex())) emitFailure("the write did not reach the instrument")
    }

    /**
     * Drains whatever the browser has done since the last call.
     *
     * Called from the host's ticker rather than from a callback, for the reason in the class
     * comment. Each line is `type|argument`, and an unrecognised type is ignored rather than
     * treated as an error, so a newer shim can add events without breaking an older build.
     */
    fun pump() {
        val queued = blePoll()
        if (queued.isEmpty()) return

        for (line in queued.split('\n')) {
            if (line.isEmpty()) continue
            val type = line.substringBefore('|')
            val argument = line.substringAfter('|', "")
            when (type) {
                "connected" -> {
                    connected = true
                    emitConnected()
                }

                "disconnected" -> {
                    connected = false
                    emitDisconnected(argument.ifEmpty { null })
                }

                "failed" -> {
                    connected = false
                    emitFailure(argument.ifEmpty { "the connection failed" })
                }

                "frame" -> {
                    val uuid = argument.substringBefore('|')
                    val hex = argument.substringAfter('|', "")
                    emitFrame(hex.fromHex(), link.channelFor(uuid))
                }
            }
        }
    }

    companion object {
        /** Whether this browser has the API at all. False in every Safari, on every platform. */
        fun isSupported(): Boolean = bleSupported()

        fun createIfSupported(profile: InstrumentProfile): InstrumentTransport? =
            if (bleSupported()) WebBluetoothTransport(profile) else null
    }
}

private fun ByteArray.toHex(): String =
    joinToString("") { byte ->
        val value = byte.toInt() and 0xFF
        HEX[value shr 4].toString() + HEX[value and 0xF]
    }

private fun String.fromHex(): ByteArray =
    ByteArray(length / 2) { index ->
        ((HEX.indexOf(this[index * 2]) shl 4) or HEX.indexOf(this[index * 2 + 1])).toByte()
    }

private const val HEX = "0123456789abcdef"

// ---------------------------------------------------------------------------------------------
// The JavaScript side. One queue, drained by pump().
// ---------------------------------------------------------------------------------------------

private fun bleSupported(): Boolean =
    js("typeof navigator !== 'undefined' && !!navigator.bluetooth")

private fun bleStart(
    namePrefix: String,
    serviceUuid: String,
    writeServiceUuid: String,
    notifyUuids: String,
    writeUuid: String,
    withResponse: Boolean,
): Boolean =
    js(
        """{
          try {
            var S = (globalThis.__sexytopoBle = globalThis.__sexytopoBle || { queue: [] });
            S.queue = [];
            S.writeChar = null;
            S.device = null;
            var push = function (line) { S.queue.push(line); };
            var hex = function (view) {
              var out = '';
              for (var i = 0; i < view.byteLength; i++) {
                out += ('0' + view.getUint8(i).toString(16)).slice(-2);
              }
              return out;
            };
            var services = [serviceUuid];
            if (writeServiceUuid !== serviceUuid) services.push(writeServiceUuid);
            navigator.bluetooth
              .requestDevice({ filters: [{ namePrefix: namePrefix }], optionalServices: services })
              .then(function (device) {
                S.device = device;
                device.addEventListener('gattserverdisconnected', function () {
                  push('disconnected|the instrument went out of range or was switched off');
                });
                return device.gatt.connect();
              })
              .then(function (server) {
                S.server = server;
                return server.getPrimaryService(serviceUuid);
              })
              .then(function (service) {
                S.service = service;
                var names = notifyUuids.split(',');
                var chain = Promise.resolve();
                names.forEach(function (uuid) {
                  chain = chain
                    .then(function () { return service.getCharacteristic(uuid); })
                    .then(function (characteristic) {
                      characteristic.addEventListener('characteristicvaluechanged', function (e) {
                        push('frame|' + uuid + '|' + hex(e.target.value));
                      });
                      return characteristic.startNotifications();
                    });
                });
                return chain;
              })
              .then(function () {
                if (writeServiceUuid === serviceUuid) return S.service;
                return S.server.getPrimaryService(writeServiceUuid);
              })
              .then(function (service) { return service.getCharacteristic(writeUuid); })
              .then(function (characteristic) {
                S.writeChar = characteristic;
                S.withResponse = withResponse;
                push('connected|');
              })
              .catch(function (error) {
                // A device with no write characteristic is still usable for BRIC, whose profile
                // says so; anything else is a real failure. The Kotlin side already knows which,
                // so this reports and lets it decide.
                push('failed|' + (error && error.message ? error.message : 'could not connect'));
              });
            return true;
          } catch (e) {
            return false;
          }
        }""",
    )

private fun blePoll(): String =
    js(
        """{
          var S = globalThis.__sexytopoBle;
          if (!S || S.queue.length === 0) return '';
          var out = S.queue.join('\n');
          S.queue = [];
          return out;
        }""",
    )

private fun bleSend(hex: String): Boolean =
    js(
        """{
          try {
            var S = globalThis.__sexytopoBle;
            if (!S || !S.writeChar) return false;
            var bytes = new Uint8Array(hex.length / 2);
            for (var i = 0; i < bytes.length; i++) {
              bytes[i] = parseInt(hex.substr(i * 2, 2), 16);
            }
            // A with-response write to a characteristic that only supports write-without-response
            // fails outright and the command never arrives, which looks exactly like a broken
            // cable. The profile says which each instrument wants; older browsers have only the
            // one method, so fall back to it.
            if (S.withResponse && S.writeChar.writeValueWithResponse) {
              S.writeChar.writeValueWithResponse(bytes);
            } else if (!S.withResponse && S.writeChar.writeValueWithoutResponse) {
              S.writeChar.writeValueWithoutResponse(bytes);
            } else {
              S.writeChar.writeValue(bytes);
            }
            return true;
          } catch (e) {
            return false;
          }
        }""",
    )

private fun bleStop(): Boolean =
    js(
        """{
          try {
            var S = globalThis.__sexytopoBle;
            if (S && S.device && S.device.gatt && S.device.gatt.connected) S.device.gatt.disconnect();
            if (S) { S.queue = []; S.writeChar = null; }
            return true;
          } catch (e) {
            return false;
          }
        }""",
    )
