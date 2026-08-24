package com.airgrab.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Discovery's shared half: what is advertised, and what is accepted back.
 *
 * The network machinery differs per platform and is tested on a device. What
 * is tested here is the part that must be byte-identical between the phone and
 * the desktop, and the peer bookkeeping — which is where the awkward
 * behaviour actually lives, because mDNS responders repeat themselves
 * constantly and a naive implementation produces a flickering device list.
 */
class DiscoveryTest {

    private val self = "a".repeat(64)
    private val other = "b".repeat(64)

    private fun advert(
        fingerprint: String = other,
        name: String = "PADMA-PC",
        platform: String = "windows",
    ) = Discovery.properties(fingerprint, name, platform)

    private fun peer(
        fingerprint: String = other,
        host: String = "192.168.29.23",
        port: Int = DEFAULT_PORT,
    ) = DiscoveredPeer(fingerprint, "PADMA-PC", "windows", host, port)

    // ------------------------------------------------------- what we publish

    @Test
    fun `the service type matches the desktop`() {
        // Getting a trailing dot wrong fails as silence, not as an error:
        // the two implementations simply never see one another.
        assertEquals("_airgrab._tcp", SERVICE_TYPE_SHORT)
        assertEquals("_airgrab._tcp.local.", SERVICE_TYPE_FULL)
    }

    @Test
    fun `the advertised keys match the desktop`() {
        assertEquals(setOf("v", "id", "name", "plat"), advert().keys)
        assertEquals(PROTOCOL_VERSION.toString(), advert()["v"])
        assertEquals(other, advert()["id"])
    }

    @Test
    fun `the instance name fits an mDNS label`() {
        val name = Discovery.instanceName(other)
        // mDNS instance names cap at 63 bytes; a fingerprint is 64 characters.
        assertTrue(name.length <= 63)
        assertEquals(16, name.length)
        assertTrue(other.startsWith(name))
    }

    // -------------------------------------------------------- what we accept

    @Test
    fun `a well-formed advertisement becomes a peer`() {
        val resolved = Discovery.peerFrom(advert(), "192.168.29.23", DEFAULT_PORT, self)
        assertEquals(peer(), resolved)
    }

    @Test
    fun `our own advertisement is ignored`() {
        // Every mDNS responder hears itself. Without this the user's own
        // device appears in their list of peers.
        assertNull(Discovery.peerFrom(advert(self), "192.168.29.23", DEFAULT_PORT, self))
    }

    @Test
    fun `a missing or malformed fingerprint is rejected`() {
        val cases = listOf(null, "", "short", "z".repeat(64), other.uppercase())
        for (value in cases) {
            val properties = advert().toMutableMap().apply {
                if (value == null) remove("id") else put("id", value)
            }
            assertNull(
                Discovery.peerFrom(properties, "192.168.29.23", DEFAULT_PORT, self),
                "accepted a bad fingerprint: $value",
            )
        }
    }

    @Test
    fun `a missing address or port is rejected`() {
        assertNull(Discovery.peerFrom(advert(), null, DEFAULT_PORT, self))
        assertNull(Discovery.peerFrom(advert(), "  ", DEFAULT_PORT, self))
        assertNull(Discovery.peerFrom(advert(), "192.168.29.23", 0, self))
        assertNull(Discovery.peerFrom(advert(), "192.168.29.23", 70_000, self))
    }

    @Test
    fun `a missing name or platform falls back rather than failing`() {
        val sparse = mapOf<String, String?>("id" to other)
        val resolved = Discovery.peerFrom(sparse, "10.0.0.5", 1234, self)

        // Cosmetic fields. A peer with no label is still reachable, and
        // dropping it would leave the user unable to see a device that works.
        assertEquals("Unknown", resolved?.name)
        assertEquals("unknown", resolved?.platform)
    }

    @Test
    fun `a future protocol version still appears`() {
        val future = advert().toMutableMap().apply { put("v", "99") }
        val resolved = Discovery.peerFrom(future, "192.168.29.23", DEFAULT_PORT, self)

        // Deliberately visible. Hiding it would leave the user staring at an
        // empty screen instead of being told their other device needs
        // updating.
        assertEquals(other, resolved?.fingerprint)
        assertEquals(99, Discovery.versionOf(future))
    }

    @Test
    fun `an unreadable version reads as null rather than throwing`() {
        assertNull(Discovery.versionOf(mapOf("v" to "not a number")))
        assertNull(Discovery.versionOf(emptyMap()))
    }

    // ------------------------------------------------------------- registry

    @Test
    fun `a peer is announced once, not on every re-announcement`() {
        val found = mutableListOf<DiscoveredPeer>()
        val registry = PeerRegistry(onFound = { found.add(it) })

        // mDNS responders repeat themselves on every network change, on a
        // timer, and whenever they feel like it.
        assertTrue(registry.found("svc", peer()))
        assertFalse(registry.found("svc", peer()))
        assertFalse(registry.found("svc", peer()))

        assertEquals(1, found.size)
    }

    @Test
    fun `a changed address is announced again`() {
        val found = mutableListOf<DiscoveredPeer>()
        val registry = PeerRegistry(onFound = { found.add(it) })

        registry.found("svc", peer(host = "192.168.29.23"))
        assertTrue(registry.found("svc", peer(host = "192.168.29.44")))

        assertEquals(2, found.size)
        assertEquals("192.168.29.44", registry.byFingerprint(other)?.host)
    }

    @Test
    fun `losing a peer announces it once`() {
        val lost = mutableListOf<String>()
        val registry = PeerRegistry(onLost = { lost.add(it) })
        registry.found("svc", peer())

        assertTrue(registry.lost("svc"))
        assertFalse(registry.lost("svc"))

        assertEquals(listOf(other), lost)
        assertTrue(registry.peers().isEmpty())
    }

    @Test
    fun `losing one of two addresses does not report the device gone`() {
        val lost = mutableListOf<String>()
        val registry = PeerRegistry(onLost = { lost.add(it) })

        // A phone with Wi-Fi and a hotspot advertises twice. Losing one route
        // to it does not mean it left.
        registry.found("wifi", peer(host = "192.168.29.23"))
        registry.found("hotspot", peer(host = "10.0.0.5"))

        registry.lost("wifi")

        assertTrue(lost.isEmpty(), "reported a device gone while it was still reachable")
        assertEquals(1, registry.peers().size)

        registry.lost("hotspot")
        assertEquals(listOf(other), lost)
    }

    @Test
    fun `one device seen twice is listed once`() {
        val registry = PeerRegistry()
        registry.found("wifi", peer(host = "192.168.29.23"))
        registry.found("hotspot", peer(host = "10.0.0.5"))

        assertEquals(1, registry.peers().size)
    }

    @Test
    fun `losing an unknown service is harmless`() {
        val lost = mutableListOf<String>()
        assertFalse(PeerRegistry(onLost = { lost.add(it) }).lost("never-seen"))
        assertTrue(lost.isEmpty())
    }
}
