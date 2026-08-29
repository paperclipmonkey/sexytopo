package org.hwyl.sexytopo.shared.comms

/**
 * The single-byte command vocabulary shared by every DistoX-descended instrument.
 *
 * The DistoX A3/X310 defined these codes and the clones adopted them wholesale: the classic
 * DistoX sends the bare byte over RFCOMM (`CommandProtocol`), DistoX BLE and Cavway X1 wrap the
 * same byte in a `data:` frame (`DistoXBleManager.createWriteCommandPacket`), and SAP6 and FCL
 * write it as a single-octet GATT characteristic value (`CaveBLE.sendCommand`, `FCLBLE`).
 *
 * Codes are drawn from, and agree across, `StartCalibrationProtocol` (0x31),
 * `StopCalibrationProtocol` (0x30), `DistoXOffProtocol` (0x34), `LaserOnProtocol` (0x36),
 * `LaserOffProtocol` (0x37), `TakeShotProtocol` (0x38) and the
 * `CUSTOM_COMMAND_TO_COMMAND_BYTE` maps in `DistoXBleManager` and `CavwayX1Manager`.
 *
 * Not every instrument accepts every command: the classic DistoX and Cavway have no silent mode
 * exposed, and FCL exposes no calibration commands at all (`FCLCommunicator` offers only laser
 * on/off, shot and power off). [supportedBy] records that, so a UI can offer the right buttons
 * without hard-coding per-device lists.
 */
enum class InstrumentCommand(val code: Int) {
    /** Leave calibration mode and resume sending measurement packets. */
    STOP_CALIBRATION(0x30),

    /** Enter calibration mode: the device starts sending acceleration/magnetic packet pairs. */
    START_CALIBRATION(0x31),

    /** DistoX BLE only: leave "silent" mode, so the device beeps on each shot again. */
    STOP_SILENT_MODE(0x32),

    /** DistoX BLE only: enter "silent" mode. */
    START_SILENT_MODE(0x33),

    /** Power the instrument off. */
    DEVICE_OFF(0x34),

    /** Turn the aiming laser on. */
    LASER_ON(0x36),

    /** Turn the aiming laser off. */
    LASER_OFF(0x37),

    /** Take a shot now (as if the device's own button had been pressed). */
    TAKE_SHOT(0x38),
    ;

    val byte: Byte get() = code.toByte()

    /** Whether [instrument] is driven with this command anywhere in the Android app. */
    fun supportedBy(instrument: InstrumentFamily): Boolean = this in instrument.commands

    companion object {
        /** 0x35 is unassigned; unknown codes return null rather than guessing. */
        fun fromCode(code: Int): InstrumentCommand? = entries.firstOrNull { it.code == code }
    }
}

/**
 * The instrument families whose byte-level protocols live in this package.
 *
 * Bluetooth name prefixes are taken from `org.hwyl.sexytopo.comms.InstrumentType`; matching is
 * case-insensitive `startsWith`, and DISTOX_BLE is checked before DISTOX because "DistoXBLE-"
 * also starts with "DistoX".
 */
enum class InstrumentFamily(
    val bluetoothNamePrefix: String,
    val commands: Set<InstrumentCommand>,
) {
    DISTOX_BLE(
        "DistoXBLE-",
        setOf(
            InstrumentCommand.STOP_CALIBRATION,
            InstrumentCommand.START_CALIBRATION,
            InstrumentCommand.STOP_SILENT_MODE,
            InstrumentCommand.START_SILENT_MODE,
            InstrumentCommand.DEVICE_OFF,
            InstrumentCommand.LASER_ON,
            InstrumentCommand.LASER_OFF,
            InstrumentCommand.TAKE_SHOT,
        ),
    ),
    DISTOX(
        "DistoX",
        setOf(
            InstrumentCommand.STOP_CALIBRATION,
            InstrumentCommand.START_CALIBRATION,
            InstrumentCommand.DEVICE_OFF,
            InstrumentCommand.LASER_ON,
            InstrumentCommand.LASER_OFF,
            InstrumentCommand.TAKE_SHOT,
        ),
    ),
    CAVWAY_X1("CavwayX1-", DISTOX.commands),
    BRIC4("BRIC4_", emptySet()),
    BRIC5("BRIC5_", emptySet()),
    SAP5("Shetland", DISTOX.commands),
    SAP6("SAP6", DISTOX.commands),
    FCL(
        "FCL",
        setOf(
            InstrumentCommand.DEVICE_OFF,
            InstrumentCommand.LASER_ON,
            InstrumentCommand.LASER_OFF,
            InstrumentCommand.TAKE_SHOT,
        ),
    ),
    DISCOX("DiscoX", SAP6.commands),
    ;

    companion object {
        /**
         * Matches a Bluetooth advertised name to a family, in declaration order so that
         * DISTOX_BLE wins over DISTOX for names beginning "DistoXBLE-", exactly as
         * `InstrumentType.byName` does. Returns null for an unrecognised or absent name.
         */
        fun fromBluetoothName(name: String?): InstrumentFamily? {
            if (name == null) return null
            val lowered = name.lowercase()
            return entries.firstOrNull { lowered.startsWith(it.bluetoothNamePrefix.lowercase()) }
        }
    }
}

/**
 * The BRIC4/BRIC5 control vocabulary, which is ASCII text rather than single bytes: see
 * `Bric4Manager.COMMAND_ID_TO_COMMAND_STRING` and `sendCustomCommand`, which writes
 * `command.getBytes()` to the 58e1 device-control characteristic.
 */
enum class Bric4Command(val text: String) {
    SCAN("scan"),
    TAKE_SHOT("shot"),
    TOGGLE_LASER("laser"),
    POWER_OFF("power off"),
    CLEAR_MEMORY("clear memory"),
    ;

    /** ASCII bytes; UTF-8 and the JVM default charset agree for all of these. */
    fun bytes(): ByteArray = text.encodeToByteArray()
}
