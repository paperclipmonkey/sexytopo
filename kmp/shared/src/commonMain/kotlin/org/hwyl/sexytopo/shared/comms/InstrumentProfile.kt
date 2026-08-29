package org.hwyl.sexytopo.shared.comms

/**
 * The BLE device matrix as data: how to recognise each instrument, and which GATT service and
 * characteristics carry its traffic.
 *
 * In the Android app this knowledge is scattered across the `*Manager` classes, each of which is
 * also a Nordic `BleManager` subclass — so the facts are welded to the Android Bluetooth stack.
 * Pulled out as data, the same table drives an Android transport and a CoreBluetooth one, and can
 * be asserted about in tests on any platform.
 *
 * Bluetooth Classic instruments are deliberately absent. The original DistoX and DistoX2 speak
 * RFCOMM/SPP, which iOS has no public API for at all (only MFi-certified accessories can use
 * Bluetooth Classic for data), so no profile here can describe them. Everything below is BLE and
 * therefore reachable from CoreBluetooth without any Apple certification.
 */
/**
 * Whether a command must be acknowledged by the instrument.
 *
 * A per-device fact, and not a cosmetic one: writing with a response to a characteristic that only
 * advertises write-without-response fails, and writing without one where the device expects an
 * acknowledgement can drop commands under load. The Android drivers set it explicitly per device,
 * so this table has to as well.
 */
enum class WriteType {
    /** `WRITE_TYPE_DEFAULT` on Android, `CBCharacteristicWriteWithResponse` on iOS. */
    WITH_RESPONSE,

    /** `WRITE_TYPE_NO_RESPONSE` / `CBCharacteristicWriteWithoutResponse`. */
    WITHOUT_RESPONSE,
}

data class InstrumentProfile(
    /** Human-readable device family. */
    val name: String,
    /**
     * Advertised-name prefix used to recognise the instrument, from `InstrumentType.byName`.
     *
     * This is the one piece of discovery that ports unchanged: Android matches bonded-device names
     * and CoreBluetooth matches advertisement names, but the prefixes are the same either way.
     */
    val namePrefix: String,
    /** The GATT service carrying measurements. */
    val serviceUuid: String,
    /** Characteristics the phone subscribes to, in the order the device's decoder expects them. */
    val notifyCharacteristicUuids: List<String>,
    /** Characteristic the phone writes commands to. */
    val writeCharacteristicUuid: String,
    /** Service holding [writeCharacteristicUuid], when it is not [serviceUuid]. */
    val writeServiceUuid: String = serviceUuid,
    /** How inbound frames map to [FrameChannel]s, parallel to [notifyCharacteristicUuids]. */
    val notifyChannels: List<FrameChannel> = notifyCharacteristicUuids.map { FrameChannel.DEFAULT },
    /** Whether commands are acknowledged; see [WriteType]. */
    val writeType: WriteType = WriteType.WITH_RESPONSE,
    /**
     * Whether the link is unusable without [writeCharacteristicUuid].
     *
     * True for every device whose Android driver checks for it in `isRequiredServiceSupported`,
     * and false for BRIC, whose driver requires only its three measurement characteristics. That
     * is a defensible choice rather than an oversight: the write characteristic is in a *separate*
     * control service, and a BRIC that exposes measurements but not control is still a BRIC you
     * can record a survey from. Refusing it here would have made this port stricter than the app
     * it copies, and refused a device that works.
     */
    val requiresWriteCharacteristic: Boolean = true,
    val notes: String = "",
) {
    init {
        require(notifyChannels.size == notifyCharacteristicUuids.size) {
            "each notify characteristic needs a channel"
        }
    }

    companion object {

        /** The Nordic UART Service, used verbatim by DistoX-BLE and Cavway X1. */
        private const val NUS_SERVICE = "6e400001-b5a3-f393-e0a9-e50e24dcca9e"
        private const val NUS_WRITE = "6e400002-b5a3-f393-e0a9-e50e24dcca9e"
        private const val NUS_NOTIFY = "6e400003-b5a3-f393-e0a9-e50e24dcca9e"

        val DISTOX_BLE =
            InstrumentProfile(
                name = "DistoX-BLE",
                namePrefix = "DistoXBLE-",
                serviceUuid = NUS_SERVICE,
                notifyCharacteristicUuids = listOf(NUS_NOTIFY),
                writeCharacteristicUuid = NUS_WRITE,
                notes = "Conversion board for the Leica X310. Commands are wrapped in a " +
                    "'data:' frame on the way out (see DistoXBleFraming); inbound packets are " +
                    "bare DistoX packets, not framed - the wrapping is outbound only.",
            )

        val CAVWAY_X1 =
            InstrumentProfile(
                name = "Cavway X1",
                namePrefix = "CavwayX1-",
                serviceUuid = NUS_SERVICE,
                notifyCharacteristicUuids = listOf(NUS_NOTIFY),
                writeCharacteristicUuid = NUS_WRITE,
                notes = "Same transport and outbound framing as DistoX-BLE.",
            )

        val BRIC4 =
            InstrumentProfile(
                name = "BRIC4",
                namePrefix = "BRIC4_",
                serviceUuid = "000058d0-0000-1000-8000-00805f9b34fb",
                notifyCharacteristicUuids =
                    listOf(
                        "000058d1-0000-1000-8000-00805f9b34fb", // measurement
                        "000058d2-0000-1000-8000-00805f9b34fb", // metadata
                        "000058d3-0000-1000-8000-00805f9b34fb", // errors
                    ),
                writeCharacteristicUuid = "000058e1-0000-1000-8000-00805f9b34fb",
                writeServiceUuid = "000058e0-0000-1000-8000-00805f9b34fb",
                // Measurement, metadata, errors — distinguishable on iOS even though they are not
                // on Android, which is what lets Bric4Decoder.feed route instead of cycling.
                notifyChannels =
                    listOf(FrameChannel.PRIMARY, FrameChannel.EXTENDED, FrameChannel.TERTIARY),
                // Bric4Manager.isRequiredServiceSupported checks the three measurement
                // characteristics and not the control one, so neither does this.
                requiresWriteCharacteristic = false,
                notes = "Android cannot tell which of the three indications it received, so " +
                    "Bric4Manager cycles blindly through the roles and its own comment admits the " +
                    "desync risk. CoreBluetooth reports the characteristic on every callback, so " +
                    "an iOS transport can route by UUID and simply not have that bug.",
            )

        /** BRIC5 has no driver of its own in the Android app: it is BRIC4 with another prefix. */
        val BRIC5 = BRIC4.copy(name = "BRIC5", namePrefix = "BRIC5_")

        val SAP6 =
            InstrumentProfile(
                name = "SAP6",
                namePrefix = "SAP6",
                serviceUuid = "137c4435-8a64-4bcb-93f1-3792c6bdc965",
                notifyCharacteristicUuids = listOf("137c4435-8a64-4bcb-93f1-3792c6bdc968"),
                writeCharacteristicUuid = "137c4435-8a64-4bcb-93f1-3792c6bdc967",
                // CaveBLE.kt sets WRITE_TYPE_NO_RESPONSE.
                writeType = WriteType.WITHOUT_RESPONSE,
                notes = "Shetland Attack Pony 6, speaking the open-source CaveBLE protocol.",
            )

        /** DiscoX reuses the SAP6 driver in the Android app. */
        val DISCOX = SAP6.copy(name = "DiscoX", namePrefix = "DiscoX")

        val FCL =
            InstrumentProfile(
                name = "FCL",
                namePrefix = "FCL",
                serviceUuid = "9cc8ffd8-1b11-4848-9026-529e47d4c500",
                notifyCharacteristicUuids =
                    listOf(
                        "9cc8ffd8-1b11-4848-9026-529e47d4c504", // primary, 20 bytes
                        "9cc8ffd8-1b11-4848-9026-529e47d4c505", // extended, 14 bytes
                    ),
                writeCharacteristicUuid = "9cc8ffd8-1b11-4848-9026-529e47d4c501",
                notifyChannels = listOf(FrameChannel.PRIMARY, FrameChannel.EXTENDED),
                // FCLBLE.kt sets WRITE_TYPE_NO_RESPONSE.
                writeType = WriteType.WITHOUT_RESPONSE,
                notes = "Genuinely two inbound streams, told apart by characteristic UUID, which " +
                    "is why FrameChannel exists.",
            )

        /**
         * Every instrument this table can describe.
         *
         * Not quite every instrument an iOS build could talk to. The Shetland Attack Pony 5 is
         * missing on purpose: `sap5/BLESocket` does not have a profile at all, it *probes* — it
         * walks every one of the device's services looking for whichever generic serial chipset is
         * present (TI CC254X `ffe0/ffe1`, Microchip RN4870, or the Nordic UART), assigning as it
         * goes without breaking, so on a device exposing two of them the *last* one recognised
         * wins. It never chooses a write type either: it checks only that the characteristic it
         * settled on is writable at all — WRITE or WRITE_NO_RESPONSE — and then writes with
         * whatever default that characteristic carries. (The Nordic branch is the one exception
         * that reads properties, and only to tell its two candidate characteristics apart.)
         *
         * That is a discovery strategy rather than a row in a table, and inventing a row for it
         * would describe a device that does not exist. An iOS port would need the same probe;
         * [GattLink] would be the place for it.
         */
        val ALL = listOf(DISTOX_BLE, CAVWAY_X1, BRIC4, BRIC5, SAP6, DISCOX, FCL)

        /**
         * Matches an advertised name to a profile, as `InstrumentType.byName` does.
         *
         * Case-insensitive, deliberately: the Java lower-cases both sides, and an advertised name
         * is a firmware string that nothing normalises, so a unit advertising "sap6-1234" must
         * still be recognised.
         */
        fun forAdvertisedName(name: String): InstrumentProfile? =
            ALL.firstOrNull { name.startsWith(it.namePrefix, ignoreCase = true) }
    }
}
