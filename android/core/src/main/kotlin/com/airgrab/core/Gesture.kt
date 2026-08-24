package com.airgrab.core

/**
 * The grab/release state machine, ported from `desktop/airgrab/gesture.py`.
 *
 * Deliberately free of any Android import so it runs as a plain JVM unit test.
 * The camera, MediaPipe and the UI all sit outside this file; everything here
 * operates on a stream of already-classified poses.
 *
 * Two behaviours carried over from the reference implementation, both of which
 * are easy to get wrong:
 *
 * 1. Losing sight of the hand while holding is the SUCCESS path. The user
 *    grabbed something and is moving toward the other device.
 * 2. A fist with no preceding open palm means this device is the DESTINATION.
 *    On the sending device the gesture is palm-then-fist; on the receiving
 *    device the hand arrives already closed.
 *
 * Thresholds are durations rather than frame counts because camera frame rate
 * varies with lighting: the reference webcam measured 30fps in good light and
 * 10fps once auto-exposure lengthened indoors.
 */

enum class Pose { NONE, OPEN_PALM, CLOSED_FIST, OTHER }

enum class GestureState { IDLE, ARMED, HOLDING, CATCHING }

enum class GestureEventType { ARMED, DISARMED, GRABBED, CATCH_READY, RELEASED, CANCELLED }

data class GestureEvent(
    val type: GestureEventType,
    val at: Long,
    val fromState: GestureState,
    val toState: GestureState,
)

data class GestureConfig(
    val armMillis: Long = 200,
    val grabMillis: Long = 130,
    val releaseMillis: Long = 130,
    val catchMillis: Long = 130,
    val disarmMillis: Long = 270,
    /** A pose must be seen at least this many times however long it lasted. */
    val minFrames: Int = 2,
    /**
     * How long a grab survives with no hand in view.
     *
     * This is the walk from one device to the other. Twenty seconds sounded
     * ample and was not: on a real attempt it expired while the user was
     * still turning the phone away and crossing the room, and the grab
     * silently became a cancel. A minute costs nothing — an uncaught hold
     * still expires, just not while the user is mid-stride.
     */
    val holdTimeoutMillis: Long = 60_000,
)

/** Monotonic milliseconds. Injectable so tests need no real clock. */
fun interface Clock {
    fun nowMillis(): Long
}

class GrabStateMachine(
    private val config: GestureConfig = GestureConfig(),
    private val clock: Clock = Clock { System.nanoTime() / 1_000_000 },
) {
    var state: GestureState = GestureState.IDLE
        private set

    private var streakPose: Pose? = null
    private var streakCount = 0
    private var streakStarted = 0L
    private var heldSince = 0L
    private var refractoryPose: Pose? = null

    fun reset() {
        state = GestureState.IDLE
        streakPose = null
        streakCount = 0
        streakStarted = 0
        heldSince = 0
        refractoryPose = null
    }

    /** Feed one classified frame; returns whatever it triggered. */
    fun observe(pose: Pose): List<GestureEvent> {
        val now = clock.nowMillis()

        if (pose == streakPose) {
            streakCount += 1
        } else {
            streakPose = pose
            streakCount = 1
            streakStarted = now
        }

        // After a gesture completes the hand is usually still in the pose that
        // ended it — a palm stays open right after a release. Acting on that
        // would start a gesture the user never made.
        refractoryPose?.let { held ->
            if (pose == held) return emptyList()
            refractoryPose = null
        }

        if (state == GestureState.HOLDING || state == GestureState.CATCHING) {
            if (now - heldSince > config.holdTimeoutMillis) {
                return listOf(transition(GestureEventType.CANCELLED, GestureState.IDLE, now))
            }
        }

        return when (state) {
            GestureState.IDLE -> when {
                streakIs(Pose.OPEN_PALM, config.armMillis, now) ->
                    listOf(transition(GestureEventType.ARMED, GestureState.ARMED, now))

                streakIs(Pose.CLOSED_FIST, config.catchMillis, now) -> {
                    heldSince = now
                    listOf(transition(GestureEventType.CATCH_READY, GestureState.CATCHING, now))
                }

                else -> emptyList()
            }

            GestureState.ARMED -> when {
                streakIs(Pose.CLOSED_FIST, config.grabMillis, now) -> {
                    heldSince = now
                    listOf(transition(GestureEventType.GRABBED, GestureState.HOLDING, now))
                }

                streakIs(Pose.NONE, config.disarmMillis, now) ->
                    listOf(transition(GestureEventType.DISARMED, GestureState.IDLE, now))

                else -> emptyList()
            }

            // Pose.NONE is deliberately unhandled here: the hand leaving the
            // frame is the user carrying the file to the other device.
            GestureState.HOLDING, GestureState.CATCHING ->
                if (streakIs(Pose.OPEN_PALM, config.releaseMillis, now)) {
                    listOf(transition(GestureEventType.RELEASED, GestureState.IDLE, now))
                } else {
                    emptyList()
                }
        }
    }

    /** Advance time without a frame, so a hold expires if the camera stalls. */
    fun tick(): List<GestureEvent> {
        val now = clock.nowMillis()
        if (state == GestureState.HOLDING || state == GestureState.CATCHING) {
            if (now - heldSince > config.holdTimeoutMillis) {
                return listOf(transition(GestureEventType.CANCELLED, GestureState.IDLE, now))
            }
        }
        return emptyList()
    }

    private fun streakIs(pose: Pose, millis: Long, now: Long): Boolean =
        streakPose == pose &&
            streakCount >= config.minFrames &&
            (now - streakStarted) >= millis

    private fun transition(
        event: GestureEventType,
        target: GestureState,
        now: Long,
    ): GestureEvent {
        val from = state
        state = target
        if (target == GestureState.IDLE && from != GestureState.IDLE) {
            refractoryPose = streakPose
        }
        // A completed transition must not re-fire on the next frame of the
        // same pose, so the streak restarts here.
        streakCount = 0
        streakPose = null
        streakStarted = now
        if (target == GestureState.IDLE) heldSince = 0
        return GestureEvent(event, now, from, target)
    }
}
