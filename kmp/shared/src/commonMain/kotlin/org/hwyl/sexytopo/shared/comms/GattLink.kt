package org.hwyl.sexytopo.shared.comms

/**
 * The platform-free half of connecting to a BLE instrument.
 *
 * What to do about a discovered service, characteristic or value is decided entirely by the
 * device's [InstrumentProfile], and none of that decision needs a platform type, so it lives
 * here rather than in each platform transport.
 */
class GattLink(val profile: InstrumentProfile) {

    enum class Role {
        WRITE,
        NOTIFY,
        IGNORED,
    }

    private val writeUuid = normaliseUuid(profile.writeCharacteristicUuid)
    private val notifyUuids = profile.notifyCharacteristicUuids.map { normaliseUuid(it) }

    private var writeFound = false
    private val notifyFound = mutableSetOf<String>()
    private val notifySubscribed = mutableSetOf<String>()

    /** Usually one service; BRIC puts its write characteristic in a second one. */
    val servicesToDiscover: List<String>
        get() = listOf(profile.serviceUuid, profile.writeServiceUuid).distinct()

    /** Prefix matching, case-insensitive: an advertised name is a firmware string nothing normalises. */
    fun matches(advertisedName: String?): Boolean =
        advertisedName != null && advertisedName.startsWith(profile.namePrefix, ignoreCase = true)

    fun roleOf(characteristicUuid: String): Role {
        val uuid = normaliseUuid(characteristicUuid)
        return when {
            uuid == writeUuid -> Role.WRITE
            uuid in notifyUuids -> Role.NOTIFY
            else -> Role.IGNORED
        }
    }

    /**
     * Distinct from [discovered] on purpose: finding a notify characteristic and subscribing to it
     * are different events, and only the second means measurements will arrive.
     */
    fun subscribed(characteristicUuid: String) {
        val uuid = normaliseUuid(characteristicUuid)
        if (uuid in notifyUuids) notifySubscribed.add(uuid)
    }

    fun discovered(characteristicUuid: String): Role {
        val role = roleOf(characteristicUuid)
        when (role) {
            Role.WRITE -> writeFound = true
            Role.NOTIFY -> notifyFound.add(normaliseUuid(characteristicUuid))
            Role.IGNORED -> Unit
        }
        return role
    }

    /**
     * The transport must not report a connection before this is true: on an FCL, primary packets
     * would arrive and be held waiting for an extended half that never came, so the surveyor would
     * watch a connected instrument silently fail to record a single shot.
     */
    val isReady: Boolean
        get() = hasFoundEverything && notifySubscribed.size == notifyUuids.size

    val hasFoundEverything: Boolean
        get() =
            (writeFound || !profile.requiresWriteCharacteristic) &&
                notifyFound.size == notifyUuids.size

    val missing: List<String>
        get() {
            val outstanding = mutableListOf<String>()
            if (!writeFound && profile.requiresWriteCharacteristic) {
                outstanding.add(profile.writeCharacteristicUuid)
            }
            for (uuid in profile.notifyCharacteristicUuids) {
                if (normaliseUuid(uuid) !in notifyFound) outstanding.add(uuid)
            }
            return outstanding
        }

    /**
     * Which logical stream a frame arrived on.
     *
     * An unrecognised characteristic yields [FrameChannel.DEFAULT] rather than throwing: a stray
     * notification from some other service is not worth dropping the connection over.
     */
    fun channelFor(characteristicUuid: String): FrameChannel {
        val index = notifyUuids.indexOf(normaliseUuid(characteristicUuid))
        return if (index >= 0) profile.notifyChannels[index] else FrameChannel.DEFAULT
    }

    fun reset() {
        writeFound = false
        notifyFound.clear()
        notifySubscribed.clear()
    }

    companion object {

        fun forAdvertisedName(advertisedName: String?): GattLink? =
            advertisedName
                ?.let { InstrumentProfile.forAdvertisedName(it) }
                ?.let { GattLink(it) }

        /** The suffix every 16- and 32-bit Bluetooth UUID is shorthand for. */
        private const val BASE_UUID_SUFFIX = "-0000-1000-8000-00805f9b34fb"

        /**
         * Puts a UUID into one comparable form: lower case, and expanded to all 128 bits.
         *
         * This is not defensive nicety, it is a hazard the port's first iOS transport walked into.
         * A UUID of the form `0000xxxx-0000-1000-8000-00805f9b34fb` sits in the Bluetooth
         * assigned-number space and has a 16-bit short form — and that is exactly the form BRIC4
         * and BRIC5 use for all four of their characteristics. `CBUUID.UUIDString` reports whatever
         * width the UUID actually has, so a characteristic the peripheral advertises as 16-bit
         * comes back as `"58D1"`, not as the 128-bit string the profile table stores. Compared
         * naively, none of BRIC's characteristics match, and the surveyor gets an instrument that
         * pairs and then does nothing at all.
         */
        fun normaliseUuid(uuid: String): String {
            val trimmed = uuid.trim().removeSurrounding("{", "}").lowercase()
            return when (trimmed.length) {
                4 -> "0000$trimmed$BASE_UUID_SUFFIX"
                8 -> "$trimmed$BASE_UUID_SUFFIX"
                else -> trimmed
            }
        }
    }
}
