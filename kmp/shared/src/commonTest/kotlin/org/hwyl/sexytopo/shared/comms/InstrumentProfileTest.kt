package org.hwyl.sexytopo.shared.comms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The BLE device matrix. These UUIDs are the contract with real hardware, so they are pinned
 * against the Android drivers they were read from.
 */
class InstrumentProfileTest {

    @Test
    fun advertisedNamesMatchTheAndroidPrefixes() {
        assertEquals(InstrumentProfile.DISTOX_BLE, InstrumentProfile.forAdvertisedName("DistoXBLE-1234"))
        assertEquals(InstrumentProfile.CAVWAY_X1, InstrumentProfile.forAdvertisedName("CavwayX1-0007"))
        assertEquals(InstrumentProfile.BRIC4, InstrumentProfile.forAdvertisedName("BRIC4_0421"))
        assertEquals(InstrumentProfile.BRIC5, InstrumentProfile.forAdvertisedName("BRIC5_0099"))
        assertEquals(InstrumentProfile.SAP6, InstrumentProfile.forAdvertisedName("SAP6-abc"))
        assertEquals(InstrumentProfile.FCL, InstrumentProfile.forAdvertisedName("FCL-1"))
    }

    @Test
    fun anUnknownInstrumentIsNotMatched() {
        assertNull(InstrumentProfile.forAdvertisedName("Some Random Headphones"))
        // The classic DistoX is deliberately absent: Bluetooth Classic SPP has no iOS route.
        assertNull(InstrumentProfile.forAdvertisedName("DistoX-4242"))
    }

    @Test
    fun distoXBleAndCavwayShareTheNordicUartService() {
        assertEquals(
            InstrumentProfile.DISTOX_BLE.serviceUuid,
            InstrumentProfile.CAVWAY_X1.serviceUuid,
        )
        assertEquals("6e400001-b5a3-f393-e0a9-e50e24dcca9e", InstrumentProfile.DISTOX_BLE.serviceUuid)
        assertEquals("6e400002-b5a3-f393-e0a9-e50e24dcca9e", InstrumentProfile.DISTOX_BLE.writeCharacteristicUuid)
        assertEquals(listOf("6e400003-b5a3-f393-e0a9-e50e24dcca9e"), InstrumentProfile.DISTOX_BLE.notifyCharacteristicUuids)
    }

    @Test
    fun bricListensOnThreeCharacteristicsAndWritesOnAnotherService() {
        val bric = InstrumentProfile.BRIC4
        assertEquals(3, bric.notifyCharacteristicUuids.size)
        assertEquals("000058d0-0000-1000-8000-00805f9b34fb", bric.serviceUuid)
        assertEquals("000058e0-0000-1000-8000-00805f9b34fb", bric.writeServiceUuid)
        assertEquals("000058e1-0000-1000-8000-00805f9b34fb", bric.writeCharacteristicUuid)
        assertTrue(bric.writeServiceUuid != bric.serviceUuid, "BRIC control is a separate service")
    }

    @Test
    fun fclsTwoStreamsAreDistinguishable() {
        val fcl = InstrumentProfile.FCL
        assertEquals(2, fcl.notifyCharacteristicUuids.size)
        assertEquals(listOf(FrameChannel.PRIMARY, FrameChannel.EXTENDED), fcl.notifyChannels)
        assertTrue(fcl.notifyCharacteristicUuids[0].endsWith("c504"))
        assertTrue(fcl.notifyCharacteristicUuids[1].endsWith("c505"))
    }

    @Test
    fun bric5IsBric4WithADifferentName() {
        // The Android app has no BRIC5 driver: the prefix maps to Bric4Communicator.
        assertEquals(InstrumentProfile.BRIC4.serviceUuid, InstrumentProfile.BRIC5.serviceUuid)
        assertEquals(
            InstrumentProfile.BRIC4.notifyCharacteristicUuids,
            InstrumentProfile.BRIC5.notifyCharacteristicUuids,
        )
    }

    @Test
    fun everyProfileIsSelfConsistent() {
        for (profile in InstrumentProfile.ALL) {
            assertTrue(profile.namePrefix.isNotEmpty(), "${profile.name} needs a prefix")
            assertTrue(profile.notifyCharacteristicUuids.isNotEmpty(), "${profile.name} must listen")
            assertEquals(
                profile.notifyCharacteristicUuids.size,
                profile.notifyChannels.size,
                "${profile.name} channel mapping",
            )
            assertNotNull(InstrumentProfile.forAdvertisedName(profile.namePrefix + "0"))
        }
    }
}
