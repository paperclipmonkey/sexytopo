package org.hwyl.sexytopo.shared.comms

/**
 * The platform-free half of connecting to a BLE instrument.
 *
 * Every BLE stack — Android's, CoreBluetooth, WebBluetooth — hands you the same sequence of events:
 * a device advertised this name, this service turned up, this characteristic turned up, a value
 * arrived on this characteristic. What to *do* about each of those is decided entirely by the
 * device's [InstrumentProfile], and none of that decision needs a platform type. So it lives here,
 * where it can be tested on the JVM and on Kotlin/Wasm, and the platform transports become
 * translation layers that own no logic of their own.
 *
 * That split is not tidiness for its own sake. The iOS transport cannot be compiled on a machine
 * without Xcode, so every line of logic left inside it is a line nobody can run until someone opens
 * a Mac. Pulling the logic out here is what makes "the iOS Bluetooth layer" a small, boring,
 * reviewable file instead of the riskiest part of the port.
 */
class GattLink(val profile: InstrumentProfile) {

    /** What the transport should do with a characteristic it has just discovered. */
    enum class Role {
        /** Keep it; commands are written here. */
        WRITE,

        /** Subscribe to it; measurements arrive here. */
        NOTIFY,

        /** Not in the profile — every device advertises characteristics we do not want. */
        IGNORED,
    }

    private val writeUuid = normaliseUuid(profile.writeCharacteristicUuid)
    private val notifyUuids = profile.notifyCharacteristicUuids.map { normaliseUuid(it) }

    private var writeFound = false
    private val notifyFound = mutableSetOf<String>()

    /**
     * The services to ask the peripheral for.
     *
     * Usually one; BRIC puts its write characteristic in a second service, hence the profile's
     * separate [InstrumentProfile.writeServiceUuid] and the de-duplication here.
     */
    val servicesToDiscover: List<String>
        get() = listOf(profile.serviceUuid, profile.writeServiceUuid).distinct()

    /**
     * Whether an advertised name identifies this instrument.
     *
     * Prefix matching, as `InstrumentType.byName` does on Android — the suffix is the unit's serial
     * number. Case-insensitively, because an advertised name is a firmware string and nothing
     * normalises it.
     */
    fun matches(advertisedName: String?): Boolean =
        advertisedName != null && advertisedName.startsWith(profile.namePrefix, ignoreCase = true)

    /** What [characteristicUuid] is for, without recording anything. */
    fun roleOf(characteristicUuid: String): Role {
        val uuid = normaliseUuid(characteristicUuid)
        return when {
            uuid == writeUuid -> Role.WRITE
            uuid in notifyUuids -> Role.NOTIFY
            else -> Role.IGNORED
        }
    }

    /** Records a discovered characteristic and returns what the transport must now do with it. */
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
     * Whether every characteristic the profile needs has been found.
     *
     * The transport must not report a connection before this is true. The Android drivers refuse
     * the device outright when it is not, and announcing a half-configured link is worse than
     * failing: on an FCL, primary packets would arrive and be held waiting for an extended half
     * that never came, so the surveyor would watch a connected instrument silently fail to record
     * a single shot.
     *
     * What counts as needed is per device. Every notify characteristic always does. The write one
     * does for every instrument whose Android driver checks for it — but not for BRIC, whose
     * `isRequiredServiceSupported` asks only for its three measurement characteristics, because
     * the write characteristic lives in a separate control service and a BRIC without it still
     * delivers measurements. See [InstrumentProfile.requiresWriteCharacteristic].
     */
    val isReady: Boolean
        get() =
            (writeFound || !profile.requiresWriteCharacteristic) &&
                notifyFound.size == notifyUuids.size

    /** What is still missing, for a diagnostic the surveyor can act on. Empty once [isReady]. */
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
     * This is the whole reason [FrameChannel] exists, and the one place iOS can do something
     * Android cannot. `Bric4Manager` receives three different indications through a single callback
     * that does not say which characteristic fired, so it cycles blindly through the roles and its
     * own comment admits the desync risk: drop one indication and every subsequent packet is
     * misread. CoreBluetooth passes the characteristic to every callback, so the frame can simply
     * be routed by UUID and that bug cannot occur.
     *
     * An unrecognised characteristic yields [FrameChannel.DEFAULT] rather than throwing: a stray
     * notification from some other service is not worth dropping the connection over.
     */
    fun channelFor(characteristicUuid: String): FrameChannel {
        val index = notifyUuids.indexOf(normaliseUuid(characteristicUuid))
        return if (index >= 0) profile.notifyChannels[index] else FrameChannel.DEFAULT
    }

    /** Forgets everything discovered, for a reconnect. */
    fun reset() {
        writeFound = false
        notifyFound.clear()
    }

    companion object {

        /**
         * The profile whose advertised-name prefix matches, if any.
         *
         * A scanning UI wants this rather than a per-profile [matches]: it is handed a name and has
         * to work out what it is looking at.
         */
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
         * naively, none of BRIC's characteristics match: [isReady] never becomes true, and the
         * surveyor gets an instrument that pairs and then does nothing at all. Android's
         * `UUID.toString()` is always 128-bit, which is why the original never had to think about
         * this — and why the failure would first have appeared on a real BRIC, on a real iPhone,
         * underground.
         *
         * Anything that is not a recognisable 16-, 32- or 128-bit UUID is passed through
         * lower-cased and otherwise untouched, so a device with a genuinely odd identifier still
         * compares equal to itself.
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
