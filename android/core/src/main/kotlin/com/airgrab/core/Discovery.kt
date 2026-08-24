package com.airgrab.core

/**
 * Zero-configuration peer discovery: the parts that are the same everywhere.
 *
 * Ported from `desktop/airgrab/discovery.py`. The mDNS machinery itself is
 * platform-specific — python-zeroconf on the desktop, `NsdManager` on Android
 * — but what gets advertised, and how an advertisement is turned into a peer,
 * must be identical on both or the two will simply never see each other. That
 * shared part lives here, where it can be tested without a network.
 *
 * Devices advertise their fingerprint, name, platform and port. The
 * fingerprint in the TXT record is **a hint for the UI only**. It is never
 * trusted: identity is established by the signed-nonce exchange in [Auth] once
 * a connection opens, and pinned against the trust store before any file
 * moves. Anyone on the network can publish any TXT record they like.
 */

/**
 * `_airgrab._tcp` — the service type both implementations look for.
 *
 * The desktop's zeroconf API wants the fully-qualified `_airgrab._tcp.local.`;
 * Android's `NsdManager` wants it without the domain and appends its own. Both
 * forms are provided so neither platform has to build the other's by hand and
 * get a trailing dot wrong, which fails as silence rather than as an error.
 */
const val SERVICE_TYPE_SHORT = "_airgrab._tcp"
const val SERVICE_TYPE_FULL = "$SERVICE_TYPE_SHORT.local."

/** TXT record keys. Short because a TXT record has very little room. */
object DiscoveryKey {
    const val VERSION = "v"
    const val FINGERPRINT = "id"
    const val NAME = "name"
    const val PLATFORM = "plat"
}

/**
 * A peer seen on the network. Everything here is a claim until the connection
 * to [host]:[port] proves otherwise.
 */
data class DiscoveredPeer(
    val fingerprint: String,
    val name: String,
    val platform: String,
    val host: String,
    val port: Int,
)

object Discovery {

    /**
     * The instance name this device publishes under.
     *
     * Truncated because mDNS instance names are limited to 63 bytes and a full
     * fingerprint is 64 hex characters. The truncation is safe: the name is
     * only a label for the advertisement, and the full fingerprint travels in
     * the TXT record. Two devices colliding on the first sixteen characters
     * would need a deliberate SHA-256 prefix collision, and even then the
     * mismatch would be caught at authentication.
     */
    fun instanceName(fingerprint: String): String = fingerprint.take(16)

    /** The TXT record this device publishes. Mirrors the desktop exactly. */
    fun properties(fingerprint: String, name: String, platform: String): Map<String, String> =
        mapOf(
            DiscoveryKey.VERSION to PROTOCOL_VERSION.toString(),
            DiscoveryKey.FINGERPRINT to fingerprint,
            DiscoveryKey.NAME to name,
            DiscoveryKey.PLATFORM to platform,
        )

    /**
     * Turn a resolved advertisement into a peer, or null if it is not one we
     * can use.
     *
     * Rejects, in order: our own advertisement echoing back, a missing or
     * malformed fingerprint, a missing address, and an unusable port. Each of
     * these is ordinary rather than exceptional — an mDNS responder will
     * happily return partial records — so they return null instead of
     * throwing.
     *
     * A wrong protocol version is deliberately NOT rejected here. A future v2
     * device should still appear in the list, so the user is told their peer
     * needs updating rather than left staring at an empty screen.
     */
    fun peerFrom(
        properties: Map<String, String?>,
        host: String?,
        port: Int,
        ignoreFingerprint: String,
    ): DiscoveredPeer? {
        val fingerprint = properties[DiscoveryKey.FINGERPRINT]?.trim().orEmpty()

        // A fingerprint is 64 lowercase hex characters. Anything else is
        // either a different application answering on the same service type or
        // something deliberately malformed, and neither should reach the UI.
        if (fingerprint.length != 64 || !fingerprint.all { it in "0123456789abcdef" }) {
            return null
        }
        if (fingerprint == ignoreFingerprint) return null

        if (host.isNullOrBlank()) return null
        if (port !in 1..65535) return null

        return DiscoveredPeer(
            fingerprint = fingerprint,
            name = properties[DiscoveryKey.NAME]?.takeIf { it.isNotBlank() } ?: "Unknown",
            platform = properties[DiscoveryKey.PLATFORM]?.takeIf { it.isNotBlank() } ?: "unknown",
            host = host,
            port = port,
        )
    }

    /** The advertised protocol version, or null if it was absent or unreadable. */
    fun versionOf(properties: Map<String, String?>): Int? =
        properties[DiscoveryKey.VERSION]?.trim()?.toIntOrNull()
}

/**
 * The set of peers currently visible, and the found/lost callbacks that follow
 * from changes to it.
 *
 * Separate from the mDNS implementation because this is where the awkward
 * behaviour lives, and it is behaviour worth testing without a network. An
 * mDNS browser re-announces the same service repeatedly — on every network
 * change, on a timer, and whenever a responder feels like it — so naive
 * plumbing produces a device list that flickers and duplicates. Callbacks here
 * fire only on real changes.
 */
class PeerRegistry(
    private val onFound: (DiscoveredPeer) -> Unit = {},
    private val onLost: (fingerprint: String) -> Unit = {},
) {
    // Keyed by mDNS service name, not by fingerprint: that is the only handle
    // a removal notification carries, and a peer that changes address keeps
    // the same service name.
    private val byService = LinkedHashMap<String, DiscoveredPeer>()

    /** Returns true when this was new or changed information. */
    fun found(serviceName: String, peer: DiscoveredPeer): Boolean {
        if (byService[serviceName] == peer) return false
        byService[serviceName] = peer
        onFound(peer)
        return true
    }

    /** Returns true when a peer was actually removed. */
    fun lost(serviceName: String): Boolean {
        val peer = byService.remove(serviceName) ?: return false
        // Only announce the loss if no other advertisement still reaches the
        // same device. A phone with both Wi-Fi and a hotspot can appear twice,
        // and losing one address does not mean the device is gone.
        if (byService.values.none { it.fingerprint == peer.fingerprint }) {
            onLost(peer.fingerprint)
        }
        return true
    }

    fun peers(): List<DiscoveredPeer> = byService.values.distinctBy { it.fingerprint }

    fun byFingerprint(fingerprint: String): DiscoveredPeer? =
        byService.values.firstOrNull { it.fingerprint == fingerprint }

    fun clear() {
        byService.clear()
    }
}
