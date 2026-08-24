package com.airgrab

import android.util.Log
import com.airgrab.core.Action
import com.airgrab.core.DiscoveredPeer
import com.airgrab.core.GestureCoordinator
import com.airgrab.core.GestureEvent
import com.airgrab.core.Node
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The part that turns a gesture into a transfer.
 *
 * [GestureCoordinator] in core decides *what* should happen and returns
 * Actions; nothing in it touches the network, so every race in the grab/release
 * handshake is tested on the JVM. This class is the other half: it carries
 * those Actions out, and holds the things that only exist on a real device —
 * a socket, a file, a peer's address.
 *
 * Keeping the decision and the doing apart is what makes the awkward cases
 * testable. "Two devices grab at the same moment", "the peer releases after
 * disconnecting", "nobody ever catches the hold" are all decided by pure code.
 */
class GestureOrchestrator(
    private val node: Node,
    private val scope: CoroutineScope,
    private val coordinator: GestureCoordinator = GestureCoordinator(),
    private val peerLookup: (String) -> DiscoveredPeer?,
    private val onContentWanted: () -> File?,
    private val onStatus: (String) -> Unit = {},
) {
    companion object {
        const val TAG = "AirGrabGesture"

        /** How often a forgotten hold is checked for expiry. */
        private const val TICK_MILLIS = 1000L
    }

    /** What a grab picked up, waiting for someone to catch it. */
    private val captured = AtomicReference<File?>(null)

    /** Peers we hold an open control channel to, so gestures arrive unprompted. */
    private val linked = ConcurrentHashMap<String, Boolean>()

    val holding: Boolean get() = coordinator.holding

    fun start() {
        node.onPeerGesture = { peerFp, type, payload ->
            // Payload values are whatever the peer sent. The coordinator only
            // reads keys it knows, and nulls are dropped rather than trusted.
            val safe = payload.filterValues { it != null }.mapValues { it.value!! }
            scope.launch { run(coordinator.onPeerMessage(peerFp, type, safe)) }
        }

        scope.launch {
            while (isActive) {
                delay(TICK_MILLIS)
                // A hold nobody caught must expire, or the phone stays armed
                // forever and the next release on the PC sends a file the user
                // grabbed twenty minutes ago.
                run(coordinator.tick())
            }
        }
    }

    /** A gesture seen by this device's own camera. */
    fun onLocalEvent(event: GestureEvent) {
        scope.launch { run(coordinator.onLocalEvent(event)) }
    }

    /**
     * Hold a control channel open to every trusted peer we can see.
     *
     * Dialling on demand would add a TLS handshake to an interaction measured
     * in tenths of a second, and worse, the *receiving* device has no reason
     * to dial at all — it has to already be listening when the release
     * happens.
     */
    fun connectTo(peer: DiscoveredPeer) {
        if (!node.trust.isTrusted(peer.fingerprint)) return
        if (linked.putIfAbsent(peer.fingerprint, true) != null) return

        scope.launch {
            try {
                node.openLink(peer.host, peer.port, peer.fingerprint)
                Log.i(TAG, "linked to ${peer.name}")
                onStatus("Connected to ${peer.name}")
            } catch (exc: Throwable) {
                // Expected and routine: the peer may have gone before we
                // dialled. Forgetting it lets the next discovery retry.
                Log.i(TAG, "could not link to ${peer.name}: ${exc.message}")
                linked.remove(peer.fingerprint)
            }
        }
    }

    fun peerLost(fingerprint: String) {
        linked.remove(fingerprint)
        coordinator.peerDisconnected(fingerprint)
    }

    // --------------------------------------------------------------- actions

    private suspend fun run(actions: List<Action>) {
        for (action in actions) {
            try {
                perform(action)
            } catch (exc: Throwable) {
                // One failing action must not abandon the rest. Skipping a
                // ClearContent after a failed send would leave the device
                // holding a file it can never deliver.
                Log.w(TAG, "action ${action::class.simpleName} failed: ${exc.message}")
            }
        }
    }

    private suspend fun perform(action: Action) {
        when (action) {
            is Action.CaptureContent -> {
                val file = onContentWanted()
                captured.set(file)
                onStatus(
                    if (file == null) {
                        "Nothing to send - share a file to AirGrab first"
                    } else {
                        "Holding ${file.name}"
                    }
                )
            }

            is Action.ClearContent -> {
                captured.set(null)
                onStatus("Ready")
            }

            is Action.Broadcast -> {
                node.broadcast(action.type, action.payload)
            }

            is Action.SendMessage -> {
                node.sendToPeer(action.peerFp, action.type, action.payload)
            }

            is Action.SendCapturedFile -> {
                val file = captured.get()
                if (file == null || !file.exists()) {
                    onStatus("Nothing was held")
                    return
                }
                val peer = peerLookup(action.peerFp)
                if (peer == null) {
                    // The catcher announced itself over the control channel but
                    // is no longer in the discovery list, so there is no address
                    // to upload to.
                    onStatus("Lost the other device")
                    return
                }

                onStatus("Sending ${file.name}...")
                val sent = node.sendFile(
                    host = peer.host,
                    port = peer.port,
                    file = file,
                    // The fingerprint is pinned: the device that caught the
                    // gesture must be the device that receives the file.
                    expectFp = action.peerFp,
                ) { received, total ->
                    if (total > 0) {
                        onStatus("Sending ${file.name} - ${received * 100 / total}%")
                    }
                }
                onStatus(if (sent) "Sent ${file.name}" else "Send failed")
            }
        }
    }
}
