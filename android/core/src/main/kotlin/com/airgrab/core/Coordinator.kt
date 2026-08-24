package com.airgrab.core

/**
 * Matching a grab on one device to a release on another. Ported from
 * `desktop/airgrab/coordinator.py`.
 *
 * Pure and synchronous: every method returns Actions for the caller to carry
 * out rather than performing them, so every race is testable on the JVM with
 * no network, no camera and no Android runtime.
 *
 * Clocks are never compared across devices. The holding device treats the
 * ARRIVAL of a peer's release message as the timing signal, which sidesteps
 * clock synchronisation between a phone and a PC entirely.
 */

object GestureMessage {
    const val HOLD = "gesture_hold"
    const val HOLD_END = "gesture_hold_end"
    const val RELEASE = "gesture_release"
}

sealed interface Action {
    /** Grab whatever the user is pointing at, ready to send. */
    data object CaptureContent : Action

    /** Drop any captured content; the gesture did not complete. */
    data object ClearContent : Action

    /** Send a message to every connected trusted peer. */
    data class Broadcast(val type: String, val payload: Map<String, Any> = emptyMap()) : Action

    data class SendMessage(
        val peerFp: String,
        val type: String,
        val payload: Map<String, Any> = emptyMap(),
    ) : Action

    /** Deliver the captured content to this peer. */
    data class SendCapturedFile(val peerFp: String) : Action
}

class GestureCoordinator(
    private val clock: Clock = Clock { System.nanoTime() / 1_000_000 },
    private val holdWindowMillis: Long = 20_000,
) {
    var holding: Boolean = false
        private set

    private var holdStarted = 0L
    private val peersHolding = mutableMapOf<String, Long>()

    fun peersHolding(): List<String> {
        expire()
        return peersHolding.keys.toList()
    }

    fun onLocalEvent(event: GestureEvent): List<Action> {
        expire()

        return when (event.type) {
            GestureEventType.GRABBED -> {
                holding = true
                holdStarted = clock.nowMillis()
                listOf(Action.CaptureContent, Action.Broadcast(GestureMessage.HOLD))
            }

            GestureEventType.RELEASED -> when {
                // A release on the SAME device that grabbed is the user putting
                // it back down, not a transfer. Treating it as a send would fire
                // a transfer every time someone changed their mind.
                holding -> endHold()

                else -> mostRecentHolder()
                    ?.let { listOf(Action.SendMessage(it, GestureMessage.RELEASE)) }
                    ?: emptyList()
            }

            GestureEventType.CANCELLED -> if (holding) endHold() else emptyList()

            // ARMED, DISARMED and CATCH_READY are indicator-only.
            else -> emptyList()
        }
    }

    fun onPeerMessage(peerFp: String, messageType: String, payload: Map<String, Any>): List<Action> {
        expire()

        return when (messageType) {
            GestureMessage.HOLD -> {
                peersHolding[peerFp] = clock.nowMillis()
                emptyList()
            }

            GestureMessage.HOLD_END -> {
                peersHolding.remove(peerFp)
                emptyList()
            }

            GestureMessage.RELEASE -> {
                // Only act while genuinely holding. Without this a peer could
                // ask for a file at any moment; with it, a transfer always
                // requires a deliberate grab on this device first.
                if (!holding) {
                    emptyList()
                } else {
                    holding = false
                    listOf(
                        Action.SendCapturedFile(peerFp),
                        Action.Broadcast(GestureMessage.HOLD_END),
                        Action.ClearContent,
                    )
                }
            }

            else -> emptyList()
        }
    }

    /** Expire a hold nobody ever caught. */
    fun tick(): List<Action> {
        expire()
        if (holding && clock.nowMillis() - holdStarted > holdWindowMillis) return endHold()
        return emptyList()
    }

    fun peerDisconnected(peerFp: String) {
        peersHolding.remove(peerFp)
    }

    fun reset() {
        holding = false
        holdStarted = 0
        peersHolding.clear()
    }

    private fun endHold(): List<Action> {
        holding = false
        holdStarted = 0
        return listOf(Action.Broadcast(GestureMessage.HOLD_END), Action.ClearContent)
    }

    /**
     * With more than one peer holding, the most recent announcement wins.
     * Two people grabbing simultaneously is not worth solving properly for a
     * personal two-device setup; deciding deterministically beats deciding
     * arbitrarily.
     */
    private fun mostRecentHolder(): String? =
        peersHolding.maxByOrNull { it.value }?.key

    private fun expire() {
        val now = clock.nowMillis()
        peersHolding.entries.removeAll { now - it.value > holdWindowMillis }
    }
}
