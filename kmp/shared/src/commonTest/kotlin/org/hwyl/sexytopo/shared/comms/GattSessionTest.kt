package org.hwyl.sexytopo.shared.comms

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The connection lifecycle, one test per defect an adversarial review found in the first draft of
 * the iOS transport — a lifecycle question each time, rather than a Bluetooth one, so all of it
 * runs here with no radio, Mac or instrument needed.
 */
class GattSessionTest {

    private fun session(profile: InstrumentProfile = InstrumentProfile.FCL) = GattSession(profile)

    /** Drives a session all the way to READY, returning it. */
    private fun connected(profile: InstrumentProfile = InstrumentProfile.FCL): GattSession {
        val session = GattSession(profile)
        val generation = session.generation.let { session.start(0); session.generation }
        session.radioStateChanged(poweredOn = true, description = "on", generation = generation)
        session.peripheralDiscovered("${profile.namePrefix}0001", generation)
        session.peripheralConnected(generation)
        session.characteristicDiscovered(profile.writeCharacteristicUuid, generation)
        for (uuid in profile.notifyCharacteristicUuids) {
            session.characteristicDiscovered(uuid, generation)
        }
        session.serviceDiscoveryFinished(generation)
        for (uuid in profile.notifyCharacteristicUuids) {
            session.subscriptionConfirmed(uuid, error = null, generation = generation)
        }
        return session
    }

    @Test
    fun aWholeConnectionRunsThroughItsPhasesInOrder() {
        val session = session()
        assertEquals(GattSession.Phase.IDLE, session.phase)

        assertEquals(GattSession.Action.SCAN, session.start(0))
        assertEquals(GattSession.Phase.SCANNING, session.phase)

        val generation = session.generation
        assertEquals(
            GattSession.Action.CONNECT,
            session.peripheralDiscovered("FCL-0001", generation),
        )
        assertEquals(GattSession.Phase.CONNECTING, session.phase)

        assertEquals(
            GattSession.Action.DISCOVER_SERVICES,
            session.peripheralConnected(generation),
        )
        assertEquals(GattSession.Phase.DISCOVERING, session.phase)

        session.characteristicDiscovered(InstrumentProfile.FCL.writeCharacteristicUuid, generation)
        for (uuid in InstrumentProfile.FCL.notifyCharacteristicUuids) {
            session.characteristicDiscovered(uuid, generation)
        }
        assertEquals(GattSession.Action.NONE, session.serviceDiscoveryFinished(generation))
        assertEquals(GattSession.Phase.SUBSCRIBING, session.phase)

        // Connected is reported only once the *last* subscription is confirmed.
        assertEquals(
            GattSession.Action.NONE,
            session.subscriptionConfirmed(
                InstrumentProfile.FCL.notifyCharacteristicUuids[0],
                error = null,
                generation = generation,
            ),
        )
        assertFalse(session.isConnected)
        assertEquals(
            GattSession.Action.REPORT_CONNECTED,
            session.subscriptionConfirmed(
                InstrumentProfile.FCL.notifyCharacteristicUuids[1],
                error = null,
                generation = generation,
            ),
        )
        assertTrue(session.isConnected)
        assertNull(session.failure)
    }

    @Test
    fun aPeripheralWithAnotherNameIsIgnored() {
        val session = session()
        session.start(0)
        assertEquals(
            GattSession.Action.NONE,
            session.peripheralDiscovered("Someone's Headphones", session.generation),
        )
        assertEquals(GattSession.Phase.SCANNING, session.phase, "still looking")
    }

    /** `connect()` twice used to build a second central manager and leave the first scanning. */
    @Test
    fun startingTwiceDoesNotBeginASecondAttempt() {
        val session = session()
        assertEquals(GattSession.Action.SCAN, session.start(0))
        val generation = session.generation

        assertEquals(GattSession.Action.NONE, session.start(10), "already scanning")
        assertEquals(generation, session.generation, "and it is still the same attempt")
    }

    /** A callback already in flight when disconnect was pressed used to still report a connection. */
    @Test
    fun aCallbackFromAnAbandonedAttemptCannotReportAConnection() {
        val session = session()
        session.start(0)
        val stale = session.generation
        session.peripheralDiscovered("FCL-0001", stale)
        session.peripheralConnected(stale)

        session.stop()

        assertEquals(
            GattSession.Action.NONE,
            session.subscriptionConfirmed(
                InstrumentProfile.FCL.notifyCharacteristicUuids[0],
                error = null,
                generation = stale,
            ),
        )
        assertEquals(GattSession.Phase.IDLE, session.phase)
        assertFalse(session.isConnected)
    }

    /** Treating every poweredOn as "scan" meant a disconnected app reconnected itself on its own. */
    @Test
    fun turningBluetoothBackOnDoesNotReviveAStoppedSession() {
        val session = session()
        session.start(0)
        session.stop()

        assertEquals(
            GattSession.Action.NONE,
            session.radioStateChanged(poweredOn = true, description = "on", session.generation),
        )
        assertEquals(GattSession.Phase.IDLE, session.phase)
    }

    @Test
    fun bluetoothBeingOffIsOnlyReportedIfWeWereTryingToUseIt() {
        val idle = session()
        assertEquals(
            GattSession.Action.NONE,
            idle.radioStateChanged(poweredOn = false, description = "off", idle.generation),
            "nobody asked for the radio, so nobody needs telling about it",
        )

        val scanning = session()
        scanning.start(0)
        assertEquals(
            GattSession.Action.REPORT_FAILURE,
            scanning.radioStateChanged(false, "off", scanning.generation),
        )
        assertEquals(GattSession.Phase.FAILED, scanning.phase)
        assertTrue(scanning.failure!!.contains("bluetooth unavailable"))
    }

    /** Reporting a connection when characteristics were merely *found* let a failed subscribe go unnoticed. */
    @Test
    fun aFailedSubscribeIsAFailureRatherThanASilentDeadLink() {
        val session = session()
        session.start(0)
        val generation = session.generation
        session.peripheralDiscovered("FCL-0001", generation)
        session.peripheralConnected(generation)
        session.characteristicDiscovered(InstrumentProfile.FCL.writeCharacteristicUuid, generation)
        for (uuid in InstrumentProfile.FCL.notifyCharacteristicUuids) {
            session.characteristicDiscovered(uuid, generation)
        }
        session.serviceDiscoveryFinished(generation)

        val action =
            session.subscriptionConfirmed(
                InstrumentProfile.FCL.notifyCharacteristicUuids[0],
                error = "GATT error 133",
                generation = generation,
            )

        assertEquals(GattSession.Action.DISCONNECT_AND_REPORT_FAILURE, action)
        assertFalse(session.isConnected)
        assertTrue(session.failure!!.contains("could not subscribe"))
    }

    /** A device missing a characteristic the profile names used to produce no failure at all. */
    @Test
    fun aDeviceMissingACharacteristicFailsWithAReasonRatherThanHanging() {
        val session = session()
        session.start(0)
        val generation = session.generation
        session.peripheralDiscovered("FCL-0001", generation)
        session.peripheralConnected(generation)
        session.characteristicDiscovered(InstrumentProfile.FCL.writeCharacteristicUuid, generation)
        session.characteristicDiscovered(
            InstrumentProfile.FCL.notifyCharacteristicUuids[0],
            generation,
        )

        val action = session.serviceDiscoveryFinished(generation)

        assertEquals(GattSession.Action.DISCONNECT_AND_REPORT_FAILURE, action)
        assertEquals(GattSession.Phase.FAILED, session.phase)
        assertTrue(
            session.failure!!.contains(InstrumentProfile.FCL.notifyCharacteristicUuids[1]),
            "the message should name what is absent; was ${session.failure}",
        )
    }

    /** Nothing ever timed out, so a missing instrument left the app waiting with no explanation. */
    @Test
    fun anInstrumentThatNeverAppearsTimesOutAndSaysSo() {
        val session = session()
        session.start(0)

        assertEquals(GattSession.Action.NONE, session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS - 1))
        val action = session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS)

        assertEquals(GattSession.Action.REPORT_FAILURE, action)
        assertEquals(GattSession.Phase.FAILED, session.phase)
        assertTrue(
            session.failure!!.contains("switched on and in range"),
            "the message should be something a caver can act on; was ${session.failure}",
        )
    }

    /**
     * When something *was* there, the failure names it instead of asking about the batteries — an
     * instrument advertising under an unmatched name looks identical to a dead one otherwise.
     */
    @Test
    fun aScanThatFoundNothingUsableSaysWhatItDidFind() {
        val session = session()
        session.start(0)
        session.peripheralDiscovered("Someone's AirPods", session.generation)
        session.peripheralDiscovered("TPMS-4471", session.generation)

        session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS)

        val failure = session.failure.orEmpty()
        assertTrue(failure.contains("AirPods"), "did not say what it saw: $failure")
        assertTrue(failure.contains("TPMS-4471"), failure)
        assertTrue(
            !failure.contains("switched on and in range"),
            "it saw devices, so asking whether the instrument is on is the wrong question: $failure",
        )
    }

    /** And when one of them is an instrument this app knows, it says which — by name. */
    @Test
    fun anInstrumentOfAnotherKindIsNamedAsSuch() {
        val session = session(InstrumentProfile.BRIC5)
        session.start(0)
        session.peripheralDiscovered("BRIC4_0123", session.generation)

        session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS)

        val failure = session.failure.orEmpty()
        assertTrue(failure.contains("BRIC4_0123"), failure)
        assertTrue(failure.contains("a BRIC4"), "did not say what kind of thing it was: $failure")
    }

    /** An unnamed peripheral is most of a BLE scan and none of it is a clue. */
    @Test
    fun namelessDevicesAreNotListed() {
        val session = session()
        session.start(0)
        session.peripheralDiscovered(null, session.generation)
        session.peripheralDiscovered("", session.generation)

        session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS)

        assertTrue(
            session.failure!!.contains("switched on and in range"),
            "nothing identifiable was seen, so the original question is still the right one: " +
                "${session.failure}",
        )
    }

    /** And a car park full of them does not fill the screen. */
    @Test
    fun theListOfWhatWasSeenIsBounded() {
        val session = session()
        session.start(0)
        repeat(40) { session.peripheralDiscovered("Device-$it", session.generation) }

        session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS)

        val failure = session.failure.orEmpty()
        val listed = (0 until 40).count { failure.contains("Device-$it") }
        assertEquals(GattSession.MOST_NAMES_WORTH_REPORTING, listed, failure)
    }

    @Test
    fun timingOutAfterConnectingTearsTheConnectionDown() {
        val session = session()
        session.start(0)
        session.peripheralDiscovered("FCL-0001", session.generation)
        session.peripheralConnected(session.generation)

        val action = session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS)

        assertEquals(GattSession.Action.DISCONNECT_AND_REPORT_FAILURE, action)
        assertTrue(session.failure!!.contains("did not finish setting up"))
    }

    @Test
    fun aConnectedSessionNeverTimesOut() {
        val session = connected()
        assertEquals(GattSession.Action.NONE, session.tick(GattSession.DEFAULT_TIMEOUT_MILLIS * 100))
        assertTrue(session.isConnected)
    }

    /** A failed connect used to leave discovered characteristics behind for the next attempt. */
    @Test
    fun aFailureClearsWhatWasDiscovered() {
        val session = session()
        session.start(0)
        val generation = session.generation
        session.peripheralDiscovered("FCL-0001", generation)
        session.peripheralConnected(generation)
        session.characteristicDiscovered(InstrumentProfile.FCL.writeCharacteristicUuid, generation)

        session.connectionFailed("peripheral went away", generation)

        assertEquals(GattSession.Phase.FAILED, session.phase)
        assertFalse(session.link.hasFoundEverything)
        assertEquals(
            InstrumentProfile.FCL.notifyCharacteristicUuids.size + 1,
            session.link.missing.size,
            "the write characteristic it had found should have been forgotten too",
        )
    }

    @Test
    fun aFailedSessionCanBeStartedAgain() {
        val session = session()
        session.start(0)
        session.radioStateChanged(poweredOn = false, description = "off", session.generation)
        assertEquals(GattSession.Phase.FAILED, session.phase)

        assertEquals(GattSession.Action.SCAN, session.start(100))
        assertEquals(GattSession.Phase.SCANNING, session.phase)
        assertNull(session.failure, "a fresh attempt starts with a clean slate")
    }

    @Test
    fun losingTheInstrumentReturnsToIdleSoItCanBeReconnected() {
        val session = connected()
        assertTrue(session.peripheralDisconnected("out of range", session.generation))
        assertEquals(GattSession.Phase.IDLE, session.phase)
        assertFalse(session.isConnected)

        assertEquals(GattSession.Action.SCAN, session.start(1000))
    }

    @Test
    fun aDisconnectionFromAnAbandonedAttemptIsIgnored() {
        val session = connected()
        val stale = session.generation
        session.stop()
        session.start(100)

        assertFalse(
            session.peripheralDisconnected("late news", stale),
            "the old peripheral going away must not disturb the new attempt",
        )
        assertEquals(GattSession.Phase.SCANNING, session.phase)
    }

    /** BRIC is the one instrument that does not need its write characteristic; it still connects. */
    @Test
    fun aBricConnectsWithoutItsControlCharacteristic() {
        val profile = InstrumentProfile.BRIC4
        val session = GattSession(profile)
        session.start(0)
        val generation = session.generation
        session.peripheralDiscovered("BRIC4_0001", generation)
        session.peripheralConnected(generation)
        for (uuid in profile.notifyCharacteristicUuids) {
            session.characteristicDiscovered(uuid, generation)
        }

        assertEquals(GattSession.Action.NONE, session.serviceDiscoveryFinished(generation))

        var action = GattSession.Action.NONE
        for (uuid in profile.notifyCharacteristicUuids) {
            action = session.subscriptionConfirmed(uuid, error = null, generation = generation)
        }
        assertEquals(GattSession.Action.REPORT_CONNECTED, action)
        assertNotNull(session.link)
    }
}
