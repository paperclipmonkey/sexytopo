package org.hwyl.sexytopo.shared.comms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The BLE connection logic, tested without a radio.
 *
 * This is the point of pulling [GattLink] out of the iOS transport. The transport itself cannot be
 * compiled on a machine without Xcode, so anything left inside it is unverifiable until someone
 * opens a Mac; everything asserted here is logic that would otherwise have been discovered wrong on
 * an iPhone, underground, with a BRIC in one hand.
 *
 * These run on Kotlin/Wasm as well as the JVM, which is the standing proof that none of it depends
 * on `java.*`.
 */
class GattLinkTest {

    // -----------------------------------------------------------------------------------------
    // UUID normalisation — the bug this extraction found
    // -----------------------------------------------------------------------------------------

    /**
     * CoreBluetooth hands back the *short* form of an assigned-number UUID. BRIC4 and BRIC5 use
     * assigned-number UUIDs for all four of their characteristics, so a naive string comparison
     * against the profile table matches none of them.
     */
    @Test
    fun theShortFormOfAnAssignedNumberUuidMatchesTheLongForm() {
        assertEquals(
            GattLink.normaliseUuid("000058d1-0000-1000-8000-00805f9b34fb"),
            GattLink.normaliseUuid("58D1"),
        )
        assertEquals(
            GattLink.normaliseUuid("000058d1-0000-1000-8000-00805f9b34fb"),
            GattLink.normaliseUuid("000058D1"),
        )
    }

    @Test
    fun normalisationIsCaseInsensitiveAndStripsBraces() {
        val expected = GattLink.normaliseUuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e")
        assertEquals(expected, GattLink.normaliseUuid("6E400003-B5A3-F393-E0A9-E50E24DCCA9E"))
        assertEquals(expected, GattLink.normaliseUuid("{6e400003-b5a3-f393-e0a9-e50e24dcca9e}"))
        assertEquals(expected, GattLink.normaliseUuid("  6e400003-b5a3-f393-e0a9-e50e24dcca9e  "))
    }

    /** A UUID outside the assigned-number space is left alone, and still equals itself. */
    @Test
    fun anUnrecognisedIdentifierIsPassedThroughRatherThanMangled() {
        assertEquals("not-a-uuid", GattLink.normaliseUuid("NOT-A-UUID"))
        assertEquals(
            GattLink.normaliseUuid("6e400003-b5a3-f393-e0a9-e50e24dcca9e"),
            "6e400003-b5a3-f393-e0a9-e50e24dcca9e",
            "a 128-bit UUID that is not in the base space must not gain a suffix",
        )
    }

    /**
     * The whole point, end to end: a BRIC reporting its characteristics the way CoreBluetooth does
     * must reach a connectable state.
     */
    @Test
    fun aBricAdvertisingShortFormUuidsStillConnects() {
        val link = GattLink(InstrumentProfile.BRIC4)

        assertEquals(GattLink.Role.WRITE, link.discovered("58E1"))
        assertEquals(GattLink.Role.NOTIFY, link.discovered("58D1"))
        assertEquals(GattLink.Role.NOTIFY, link.discovered("58D2"))
        assertFalse(link.isReady, "two of BRIC's three indications is not enough")
        assertEquals(GattLink.Role.NOTIFY, link.discovered("58D3"))

        assertTrue(link.isReady)
        assertEquals(emptyList(), link.missing)
    }

    // -----------------------------------------------------------------------------------------
    // Readiness
    // -----------------------------------------------------------------------------------------

    /**
     * A link is not ready until every characteristic is present. Reporting a connection early is
     * worse than failing: on an FCL, primary packets would arrive and be held forever waiting for
     * an extended half that never came, so the surveyor sees a connected instrument that silently
     * records nothing.
     */
    @Test
    fun aLinkMissingOneNotifyCharacteristicIsNotReady() {
        val link = GattLink(InstrumentProfile.FCL)
        link.discovered(InstrumentProfile.FCL.writeCharacteristicUuid)
        link.discovered(InstrumentProfile.FCL.notifyCharacteristicUuids[0])

        assertFalse(link.isReady)
        assertEquals(
            listOf(InstrumentProfile.FCL.notifyCharacteristicUuids[1]),
            link.missing,
            "and it should say precisely what is absent",
        )
    }

    @Test
    fun aLinkWithNoWriteCharacteristicIsNotReady() {
        val link = GattLink(InstrumentProfile.DISTOX_BLE)
        link.discovered(InstrumentProfile.DISTOX_BLE.notifyCharacteristicUuids[0])

        assertFalse(link.isReady, "commands could not be sent, so this is not a usable link")
        assertEquals(listOf(InstrumentProfile.DISTOX_BLE.writeCharacteristicUuid), link.missing)
    }

    @Test
    fun everyProfileBecomesReadyWhenItsOwnCharacteristicsTurnUp() {
        for (profile in InstrumentProfile.ALL) {
            val link = GattLink(profile)
            link.discovered(profile.writeCharacteristicUuid)
            for (uuid in profile.notifyCharacteristicUuids) {
                link.discovered(uuid)
            }
            assertTrue(link.isReady, "${profile.name} should be ready")
        }
    }

    /** Characteristics nothing in the profile asks for are ignored, not mistaken for data. */
    @Test
    fun anUnrelatedCharacteristicIsIgnored() {
        val link = GattLink(InstrumentProfile.SAP6)
        assertEquals(GattLink.Role.IGNORED, link.discovered("2a19")) // battery level
        assertFalse(link.isReady)
    }

    /** A device that reports the same characteristic twice must not be counted twice. */
    @Test
    fun aRepeatedDiscoveryIsIdempotent() {
        val link = GattLink(InstrumentProfile.BRIC4)
        link.discovered(InstrumentProfile.BRIC4.writeCharacteristicUuid)
        repeat(3) { link.discovered(InstrumentProfile.BRIC4.notifyCharacteristicUuids[0]) }

        assertFalse(link.isReady, "one indication seen three times is still one indication")
        assertEquals(2, link.missing.size)
    }

    @Test
    fun resettingForgetsEverything() {
        val link = GattLink(InstrumentProfile.DISTOX_BLE)
        link.discovered(InstrumentProfile.DISTOX_BLE.writeCharacteristicUuid)
        link.discovered(InstrumentProfile.DISTOX_BLE.notifyCharacteristicUuids[0])
        assertTrue(link.isReady)

        link.reset()

        assertFalse(link.isReady, "a reconnect starts from nothing")
        assertEquals(2, link.missing.size)
    }

    // -----------------------------------------------------------------------------------------
    // Channel routing — what iOS can do and Android cannot
    // -----------------------------------------------------------------------------------------

    /**
     * `Bric4Manager` on Android receives three different indications through one callback that does
     * not say which characteristic fired, so it cycles blindly through the roles and its own
     * comment admits the desync risk. Routing by UUID is why an iOS build cannot have that bug.
     */
    @Test
    fun bricFramesAreRoutedByCharacteristicRatherThanCycled() {
        val link = GattLink(InstrumentProfile.BRIC4)
        assertEquals(FrameChannel.PRIMARY, link.channelFor("58D1"))
        assertEquals(FrameChannel.EXTENDED, link.channelFor("58D2"))
        assertEquals(FrameChannel.TERTIARY, link.channelFor("58D3"))
    }

    @Test
    fun fclFramesAreRoutedByCharacteristic() {
        val link = GattLink(InstrumentProfile.FCL)
        assertEquals(
            FrameChannel.PRIMARY,
            link.channelFor(InstrumentProfile.FCL.notifyCharacteristicUuids[0]),
        )
        assertEquals(
            FrameChannel.EXTENDED,
            link.channelFor(InstrumentProfile.FCL.notifyCharacteristicUuids[1]),
        )
    }

    /** A stray notification is not worth dropping a connection over. */
    @Test
    fun anUnknownCharacteristicRoutesToTheDefaultChannel() {
        val link = GattLink(InstrumentProfile.FCL)
        assertEquals(FrameChannel.DEFAULT, link.channelFor("2a19"))
    }

    // -----------------------------------------------------------------------------------------
    // Discovery
    // -----------------------------------------------------------------------------------------

    @Test
    fun advertisedNamesAreMatchedByPrefixIgnoringCase() {
        val link = GattLink(InstrumentProfile.SAP6)
        assertTrue(link.matches("SAP6-1234"))
        assertTrue(link.matches("sap6-1234"), "an advertised name is an unnormalised firmware string")
        assertFalse(link.matches("BRIC4_0001"))
        assertFalse(link.matches(null), "a peripheral with no name cannot be identified")
    }

    @Test
    fun scanningIdentifiesTheRightProfileFromAName() {
        assertEquals("BRIC4", GattLink.forAdvertisedName("BRIC4_0001")?.profile?.name)
        assertEquals("BRIC5", GattLink.forAdvertisedName("BRIC5_0001")?.profile?.name)
        assertEquals("DistoX-BLE", GattLink.forAdvertisedName("DistoXBLE-42")?.profile?.name)
        assertNull(GattLink.forAdvertisedName("Some Random Headphones"))
        assertNull(GattLink.forAdvertisedName(null))
    }

    /**
     * BRIC keeps its write characteristic in a second service, which is the only reason the profile
     * has a separate write service at all — and the reason this must not be de-duplicated away.
     */
    @Test
    fun bricAsksForBothOfItsServices() {
        val services = GattLink(InstrumentProfile.BRIC4).servicesToDiscover
        assertEquals(2, services.size)
        assertTrue(services.contains(InstrumentProfile.BRIC4.serviceUuid))
        assertTrue(services.contains(InstrumentProfile.BRIC4.writeServiceUuid))
    }

    @Test
    fun aSingleServiceInstrumentAsksForItOnlyOnce() {
        assertEquals(1, GattLink(InstrumentProfile.DISTOX_BLE).servicesToDiscover.size)
    }

    /** Every profile in the table has to be internally consistent, or it cannot ever connect. */
    @Test
    fun everyProfileIsWellFormed() {
        for (profile in InstrumentProfile.ALL) {
            assertTrue(profile.namePrefix.isNotEmpty(), "${profile.name} needs a name prefix")
            assertTrue(
                profile.notifyCharacteristicUuids.isNotEmpty(),
                "${profile.name} would have nowhere to receive measurements",
            )
            assertEquals(
                profile.notifyCharacteristicUuids.size,
                profile.notifyChannels.size,
                "${profile.name} maps characteristics to channels",
            )
            assertEquals(
                profile.notifyChannels.size,
                profile.notifyChannels.distinct().size,
                "${profile.name} would route two characteristics to one channel",
            )
            assertFalse(
                profile.notifyCharacteristicUuids
                    .map { GattLink.normaliseUuid(it) }
                    .contains(GattLink.normaliseUuid(profile.writeCharacteristicUuid)),
                "${profile.name} writes commands to a characteristic it also listens on",
            )
            assertNotNull(
                InstrumentProfile.forAdvertisedName(profile.namePrefix + "0001"),
                "${profile.name} should be discoverable by its own prefix",
            )
        }
    }
}
