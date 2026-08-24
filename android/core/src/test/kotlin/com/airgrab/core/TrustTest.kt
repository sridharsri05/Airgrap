package com.airgrab.core

import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Ported case for case from `desktop/tests/test_trust.py`, plus a check that a
 * store written by this implementation can be read back — the file is shared
 * with the Python side, so its shape is part of the protocol.
 */
class TrustTest {

    private val fpA = "a".repeat(64)
    private val fpB = "b".repeat(64)

    private val tempDir = createTempDirectory("airgrab-trust").toFile()
    private val path = File(tempDir, "trust.json")

    @AfterTest
    fun cleanUp() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `unknown peer is not trusted`() {
        assertFalse(TrustStore(path).isTrusted(fpA))
    }

    @Test
    fun `added peer is trusted`() {
        val store = TrustStore(path)
        store.add(fpA, "My Phone", "android")
        assertTrue(store.isTrusted(fpA))
    }

    @Test
    fun `trust persists across instances`() {
        TrustStore(path).add(fpA, "My Phone", "android")
        assertTrue(TrustStore(path).isTrusted(fpA))
    }

    @Test
    fun `a written store is readable by a fresh instance in full`() {
        val store = TrustStore(path)
        store.add(fpA, "My Phone", "android")
        store.add(fpB, "Desk", "linux")

        val reloaded = TrustStore(path)
        assertEquals(store.all().toSet(), reloaded.all().toSet())
        assertEquals(TrustedPeer(fpB, "Desk", "linux"), reloaded.get(fpB))

        // The on-disk shape is shared with the Python implementation.
        val text = path.readText()
        assertTrue(text.contains("\"peers\""))
        assertTrue(text.contains("\"fingerprint\""))
        assertTrue(text.contains("\"platform\""))
    }

    @Test
    fun `get returns peer details`() {
        val store = TrustStore(path)
        store.add(fpA, "My Phone", "android")
        val peer = store.get(fpA)
        assertNotNull(peer)
        assertEquals("My Phone", peer.name)
        assertEquals("android", peer.platform)
    }

    @Test
    fun `adding twice updates rather than duplicates`() {
        val store = TrustStore(path)
        store.add(fpA, "Old Name", "android")
        store.add(fpA, "New Name", "android")
        assertEquals(1, store.all().size)
        assertEquals("New Name", store.get(fpA)?.name)
        assertEquals(1, TrustStore(path).all().size)
    }

    @Test
    fun `remove revokes trust`() {
        val store = TrustStore(path)
        store.add(fpA, "My Phone", "android")
        store.remove(fpA)
        assertFalse(store.isTrusted(fpA))
        assertFalse(TrustStore(path).isTrusted(fpA))
    }

    @Test
    fun `removing unknown peer is harmless`() {
        val store = TrustStore(path)
        store.remove(fpB)
        assertEquals(emptyList(), store.all())
    }

    @Test
    fun `corrupt store is treated as empty`() {
        path.writeText("this is not json")
        assertEquals(emptyList(), TrustStore(path).all())
    }

    @Test
    fun `store with the wrong shape is treated as empty`() {
        // Better to ask for six digits again than to refuse to start.
        path.writeText("""{"peers":"nonsense"}""")
        assertEquals(emptyList(), TrustStore(path).all())
        path.writeText("""{"peers":[{"name":"no fingerprint"}]}""")
        assertEquals(emptyList(), TrustStore(path).all())
    }

    @Test
    fun `missing name and platform fall back to placeholders`() {
        path.writeText("""{"peers":[{"fingerprint":"$fpA"}]}""")
        val peer = TrustStore(path).get(fpA)
        assertNotNull(peer)
        assertEquals("Unknown", peer.name)
        assertEquals("unknown", peer.platform)
    }

    @Test
    fun `saving leaves no temp files behind`() {
        val store = TrustStore(path)
        store.add(fpA, "My Phone", "android")
        store.add(fpB, "Desk", "linux")
        store.remove(fpA)
        assertEquals(listOf("trust.json"), tempDir.list()!!.sorted())
    }
}
