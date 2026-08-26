package com.airgrab.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Correlation logic on the Android side, mirroring the Python suite.
 *
 * These are the rules that decide whether a file leaves the device, so they
 * are worth asserting twice — once per implementation.
 */
class CoordinatorTest {

    private class FakeClock : Clock {
        var now = 500_000L
        override fun nowMillis() = now
        fun advance(millis: Long) { now += millis }
    }

    private val peerA = "a".repeat(64)
    private val peerB = "b".repeat(64)

    private fun event(type: GestureEventType) =
        GestureEvent(type, 0L, GestureState.IDLE, GestureState.IDLE)

    private fun rig(windowMillis: Long = 20_000): Pair<GestureCoordinator, FakeClock> {
        val clock = FakeClock()
        return GestureCoordinator(clock, windowMillis) to clock
    }

    @Test
    fun `grabbing captures content and announces the hold`() {
        val (coord, _) = rig()
        val actions = coord.onLocalEvent(event(GestureEventType.GRABBED))
        assertEquals(2, actions.size)
        assertTrue(actions[0] is Action.CaptureContent)
        assertEquals(GestureMessage.HOLD, (actions[1] as Action.Broadcast).type)
        assertTrue(coord.holding)
    }

    @Test
    fun `a peer release while holding sends the file`() {
        val (coord, _) = rig()
        coord.onLocalEvent(event(GestureEventType.GRABBED))
        val actions = coord.onPeerMessage(peerA, GestureMessage.RELEASE, emptyMap())
        assertEquals(peerA, (actions[0] as Action.SendCapturedFile).peerFp)
        assertTrue(actions.any { it is Action.ClearContent })
        assertFalse(coord.holding)
    }

    @Test
    fun `releasing on the same device cancels instead of sending`() {
        val (coord, _) = rig()
        coord.onLocalEvent(event(GestureEventType.GRABBED))
        val actions = coord.onLocalEvent(event(GestureEventType.RELEASED))
        assertTrue(actions.none { it is Action.SendCapturedFile })
        assertEquals(GestureMessage.HOLD_END, (actions[0] as Action.Broadcast).type)
        assertFalse(coord.holding)
    }

    @Test
    fun `a peer cannot pull a file when nothing was grabbed`() {
        val (coord, _) = rig()
        assertTrue(coord.onPeerMessage(peerA, GestureMessage.RELEASE, emptyMap()).isEmpty())
    }

    @Test
    fun `a second release sends nothing further`() {
        val (coord, _) = rig()
        coord.onLocalEvent(event(GestureEventType.GRABBED))
        coord.onPeerMessage(peerA, GestureMessage.RELEASE, emptyMap())
        assertTrue(coord.onPeerMessage(peerA, GestureMessage.RELEASE, emptyMap()).isEmpty())
    }

    @Test
    fun `releasing while a peer holds tells that peer`() {
        val (coord, _) = rig()
        coord.onPeerMessage(peerA, GestureMessage.HOLD, emptyMap())
        val actions = coord.onLocalEvent(event(GestureEventType.RELEASED))
        val message = actions[0] as Action.SendMessage
        assertEquals(peerA, message.peerFp)
        assertEquals(GestureMessage.RELEASE, message.type)
    }

    @Test
    fun `releasing with nobody holding does nothing`() {
        val (coord, _) = rig()
        assertTrue(coord.onLocalEvent(event(GestureEventType.RELEASED)).isEmpty())
    }

    @Test
    fun `a peer that ended its hold is not notified`() {
        val (coord, _) = rig()
        coord.onPeerMessage(peerA, GestureMessage.HOLD, emptyMap())
        coord.onPeerMessage(peerA, GestureMessage.HOLD_END, emptyMap())
        assertTrue(coord.onLocalEvent(event(GestureEventType.RELEASED)).isEmpty())
    }

    @Test
    fun `a stale peer hold is ignored`() {
        val (coord, clock) = rig(windowMillis = 5_000)
        coord.onPeerMessage(peerA, GestureMessage.HOLD, emptyMap())
        clock.advance(6_000)
        assertTrue(coord.peersHolding().isEmpty())
        assertTrue(coord.onLocalEvent(event(GestureEventType.RELEASED)).isEmpty())
    }

    @Test
    fun `the most recent holder wins when two peers hold`() {
        val (coord, clock) = rig()
        coord.onPeerMessage(peerA, GestureMessage.HOLD, emptyMap())
        clock.advance(1_000)
        coord.onPeerMessage(peerB, GestureMessage.HOLD, emptyMap())
        val actions = coord.onLocalEvent(event(GestureEventType.RELEASED))
        assertEquals(peerB, (actions[0] as Action.SendMessage).peerFp)
    }

    @Test
    fun `a hold expires on its own`() {
        val (coord, clock) = rig(windowMillis = 5_000)
        coord.onLocalEvent(event(GestureEventType.GRABBED))
        assertTrue(coord.tick().isEmpty())
        clock.advance(6_000)
        assertTrue(coord.tick().isNotEmpty())
        assertFalse(coord.holding)
    }

    @Test
    fun `an expired hold does not send on a late release`() {
        // Past the window AND past the linger. A release inside the linger
        // after an expiry is deliberately allowed -- it softens the timeout
        // cliff for the user who walked to the other device slowly.
        val (coord, clock) = rig(windowMillis = 5_000)
        coord.onLocalEvent(event(GestureEventType.GRABBED))
        clock.advance(6_000)
        coord.tick()
        clock.advance(10_000)
        coord.tick()
        assertTrue(coord.onPeerMessage(peerA, GestureMessage.RELEASE, emptyMap()).isEmpty())
    }

    @Test
    fun `a release during the linger still sends`() {
        // The race this exists for: on one desk, every camera sees every
        // palm. The grabber cancels on the same palm the catcher is
        // completing with, milliseconds apart, and without the linger the
        // file was lost every single time -- observed live, repeatedly.
        val (coord, clock) = rig()
        coord.onLocalEvent(event(GestureEventType.GRABBED))
        coord.onLocalEvent(event(GestureEventType.RELEASED))   // self-cancel

        clock.advance(2_000)
        val actions = coord.onPeerMessage(peerA, GestureMessage.RELEASE, emptyMap())
        assertTrue(actions.any { it is Action.SendCapturedFile })
    }

    @Test
    fun `a disconnected peer stops being a candidate`() {
        val (coord, _) = rig()
        coord.onPeerMessage(peerA, GestureMessage.HOLD, emptyMap())
        coord.peerDisconnected(peerA)
        assertTrue(coord.onLocalEvent(event(GestureEventType.RELEASED)).isEmpty())
    }

    @Test
    fun `indicator events produce no actions`() {
        val (coord, _) = rig()
        for (type in listOf(
            GestureEventType.ARMED,
            GestureEventType.DISARMED,
            GestureEventType.CATCH_READY,
        )) {
            assertTrue(coord.onLocalEvent(event(type)).isEmpty(), "$type produced actions")
        }
    }

    @Test
    fun `unknown peer messages are ignored`() {
        val (coord, _) = rig()
        assertTrue(coord.onPeerMessage(peerA, "something_else", emptyMap()).isEmpty())
    }

    @Test
    fun `the whole two device choreography`() {
        val (sender, _) = rig()
        val (receiver, _) = rig()

        val grab = sender.onLocalEvent(event(GestureEventType.GRABBED))
        val announcement = grab[1] as Action.Broadcast

        receiver.onPeerMessage(peerA, announcement.type, announcement.payload)
        assertEquals(listOf(peerA), receiver.peersHolding())

        val notice = receiver.onLocalEvent(event(GestureEventType.RELEASED))[0]
            as Action.SendMessage

        val final = sender.onPeerMessage(peerB, notice.type, notice.payload)
        assertEquals(peerB, (final[0] as Action.SendCapturedFile).peerFp)
        assertFalse(sender.holding)
    }
}
