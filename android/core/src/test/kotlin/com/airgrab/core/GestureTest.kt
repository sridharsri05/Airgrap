package com.airgrab.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Kotlin port of the Python gesture suite. The two must agree: a phone
 * that arms at a different moment than the PC makes the interaction feel
 * inconsistent in a way that is very hard to diagnose from the outside.
 */
class GestureTest {

    private class FakeClock : Clock {
        var now = 1_000_000L
        override fun nowMillis() = now
        fun advance(millis: Long) { now += millis }
    }

    private class Rig {
        val clock = FakeClock()
        val machine = GrabStateMachine(
            GestureConfig(
                armMillis = 100,
                grabMillis = 60,
                releaseMillis = 60,
                catchMillis = 60,
                disarmMillis = 100,
                minFrames = 2,
                holdTimeoutMillis = 10_000,
            ),
            clock,
        )

        fun feed(pose: Pose, frames: Int, intervalMillis: Long = 33): List<GestureEvent> {
            val events = mutableListOf<GestureEvent>()
            repeat(frames) {
                clock.advance(intervalMillis)
                events += machine.observe(pose)
            }
            return events
        }

        fun hold(pose: Pose, millis: Long, intervalMillis: Long = 33) =
            feed(pose, maxOf(2, (millis / intervalMillis).toInt()), intervalMillis)

        val state get() = machine.state
    }

    private fun types(events: List<GestureEvent>) = events.map { it.type }

    @Test
    fun `starts idle`() {
        assertEquals(GestureState.IDLE, Rig().state)
    }

    @Test
    fun `open palm arms once held long enough`() {
        val rig = Rig()
        assertEquals(listOf(GestureEventType.ARMED), types(rig.hold(Pose.OPEN_PALM, 500)))
        assertEquals(GestureState.ARMED, rig.state)
    }

    @Test
    fun `a brief palm does not arm`() {
        val rig = Rig()
        assertTrue(rig.feed(Pose.OPEN_PALM, 2).isEmpty())
        assertEquals(GestureState.IDLE, rig.state)
    }

    @Test
    fun `a single frame never arms however long it lasted`() {
        val rig = Rig()
        assertTrue(rig.feed(Pose.OPEN_PALM, 1, intervalMillis = 2000).isEmpty())
        assertEquals(GestureState.IDLE, rig.state)
    }

    @Test
    fun `palm then fist grabs`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        assertEquals(listOf(GestureEventType.GRABBED), types(rig.hold(Pose.CLOSED_FIST, 300)))
        assertEquals(GestureState.HOLDING, rig.state)
    }

    @Test
    fun `holding survives the hand leaving the frame`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        rig.hold(Pose.CLOSED_FIST, 300)
        rig.hold(Pose.NONE, 3000)
        assertEquals(GestureState.HOLDING, rig.state)
    }

    @Test
    fun `open palm after holding releases`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        rig.hold(Pose.CLOSED_FIST, 300)
        rig.hold(Pose.NONE, 1000)
        assertEquals(listOf(GestureEventType.RELEASED), types(rig.hold(Pose.OPEN_PALM, 300)))
        assertEquals(GestureState.IDLE, rig.state)
    }

    @Test
    fun `a fist with no preceding palm means this device receives`() {
        val rig = Rig()
        assertEquals(
            listOf(GestureEventType.CATCH_READY),
            types(rig.hold(Pose.CLOSED_FIST, 300)),
        )
        assertEquals(GestureState.CATCHING, rig.state)
    }

    @Test
    fun `catching completes on an open palm`() {
        val rig = Rig()
        rig.hold(Pose.CLOSED_FIST, 300)
        assertEquals(listOf(GestureEventType.RELEASED), types(rig.hold(Pose.OPEN_PALM, 300)))
    }

    @Test
    fun `withdrawing before grabbing disarms`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        assertEquals(listOf(GestureEventType.DISARMED), types(rig.hold(Pose.NONE, 400)))
    }

    @Test
    fun `a hold expires on its own`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        rig.hold(Pose.CLOSED_FIST, 300)
        rig.clock.advance(11_000)
        assertEquals(listOf(GestureEventType.CANCELLED), types(rig.machine.tick()))
        assertEquals(GestureState.IDLE, rig.state)
    }

    @Test
    fun `a palm left up after releasing starts nothing new`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        rig.hold(Pose.CLOSED_FIST, 300)
        assertEquals(listOf(GestureEventType.RELEASED), types(rig.hold(Pose.OPEN_PALM, 300)))
        assertTrue(rig.hold(Pose.OPEN_PALM, 2000).isEmpty())
        assertEquals(GestureState.IDLE, rig.state)
    }

    @Test
    fun `changing the hand clears the refractory period`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        rig.hold(Pose.CLOSED_FIST, 300)
        rig.hold(Pose.OPEN_PALM, 300)
        rig.hold(Pose.NONE, 300)
        assertEquals(listOf(GestureEventType.ARMED), types(rig.hold(Pose.OPEN_PALM, 500)))
    }

    @Test
    fun `arming does not refire while the palm stays up`() {
        val rig = Rig()
        rig.hold(Pose.OPEN_PALM, 500)
        assertTrue(rig.hold(Pose.OPEN_PALM, 2000).isEmpty())
    }

    @Test
    fun `other poses are ignored`() {
        val rig = Rig()
        rig.hold(Pose.OTHER, 3000)
        assertEquals(GestureState.IDLE, rig.state)
    }

    @Test
    fun `behaviour is identical at ten and sixty frames per second`() {
        fun run(intervalMillis: Long): Pair<List<GestureEventType>, GestureState> {
            val rig = Rig()
            val events = mutableListOf<GestureEvent>()
            events += rig.hold(Pose.OPEN_PALM, 500, intervalMillis)
            events += rig.hold(Pose.CLOSED_FIST, 400, intervalMillis)
            events += rig.hold(Pose.NONE, 1500, intervalMillis)
            events += rig.hold(Pose.OPEN_PALM, 400, intervalMillis)
            return types(events) to rig.state
        }

        val slow = run(100)   // 10fps
        val fast = run(16)    // ~60fps
        assertEquals(slow, fast)
        assertEquals(
            listOf(
                GestureEventType.ARMED,
                GestureEventType.GRABBED,
                GestureEventType.RELEASED,
            ),
            slow.first,
        )
    }
}
