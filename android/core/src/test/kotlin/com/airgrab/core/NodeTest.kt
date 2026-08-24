package com.airgrab.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * Two real nodes, two real TLS servers, one real network stack.
 *
 * Everything below this point has been unit-tested in isolation, which proves
 * each part is self-consistent and proves nothing about whether they connect.
 * These tests are the first that would fail if the pieces disagreed, and they
 * are deliberately end-to-end: real certificates, real handshakes, real bytes
 * over a real socket on the loopback interface.
 *
 * They mirror the two-process loopback test that validated the Python side.
 */
class NodeTest {

    private val roots = mutableListOf<File>()
    private val running = mutableListOf<Node>()

    @AfterTest
    fun cleanUp() = runBlocking {
        running.forEach { runCatching { it.stop() } }
        roots.forEach { it.deleteRecursively() }
    }

    private fun tempRoot(): File =
        Files.createTempDirectory("airgrab-node").toFile().also { roots.add(it) }

    private suspend fun newNode(name: String, autoAccept: Boolean = true): Node {
        val root = tempRoot()
        val downloads = File(root, "downloads").apply { mkdirs() }
        val config = NodeConfig(
            dataDir = root,
            downloadDir = downloads,
            displayName = name,
            platform = "test",
            // Port 0 lets the OS assign one. A fixed port would make these
            // tests fail whenever the real app happened to be running.
            port = 0,
            host = "127.0.0.1",
            autoAccept = autoAccept,
        )
        val node = Node(config, FileIdentity.loadOrCreate(root, name))
        node.start()
        running.add(node)
        return node
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun fileOf(node: Node, name: String, bytes: ByteArray): File =
        File(node.config.dataDir, name).apply { writeBytes(bytes) }

    private fun downloads(node: Node): List<File> =
        node.config.downloadDir.listFiles().orEmpty().filterNot { it.name.startsWith(".") }

    // ------------------------------------------------------------- lifecycle

    @Test
    fun `a node binds a port and stops cleanly`() = runBlocking {
        withTimeout(30_000) {
            val node = newNode("SOLO")
            assertTrue(node.port > 0, "the node did not bind a port")
            node.stop()
            running.remove(node)
        }
    }

    @Test
    fun `two nodes have different identities`() = runBlocking {
        withTimeout(30_000) {
            assertNotEquals(
                newNode("A").identity.fingerprint,
                newNode("B").identity.fingerprint,
            )
        }
    }

    // ---------------------------------------------------------------- pairing

    @Test
    fun `two nodes pair and both record the trust`() = runBlocking {
        withTimeout(60_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")

            var codeShownToDesktop = ""
            desktop.onPairRequest = { sas, _ -> codeShownToDesktop = sas; true }

            var codeShownToPhone = ""
            val granted = phone.pairWith("127.0.0.1", desktop.port) { sas ->
                codeShownToPhone = sas
                true
            }

            assertTrue(granted, "pairing was refused")
            // The whole point of the six digits: both humans must see the same
            // number. A machine in the middle would have to present its own
            // certificate to each side, producing two different codes.
            assertEquals(codeShownToDesktop, codeShownToPhone)
            assertEquals(6, codeShownToPhone.length)

            assertTrue(phone.trust.isTrusted(desktop.identity.fingerprint))
            assertTrue(desktop.trust.isTrusted(phone.identity.fingerprint))
            assertEquals("DESKTOP", phone.trust.get(desktop.identity.fingerprint)?.name)
        }
    }

    @Test
    fun `pairing refused by the receiving side grants nothing`() = runBlocking {
        withTimeout(60_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")
            desktop.onPairRequest = { _, _ -> false }

            val granted = phone.pairWith("127.0.0.1", desktop.port) { true }

            assertFalse(granted)
            assertFalse(phone.trust.isTrusted(desktop.identity.fingerprint))
            assertFalse(desktop.trust.isTrusted(phone.identity.fingerprint))
        }
    }

    @Test
    fun `pairing refused by the initiating side grants nothing`() = runBlocking {
        withTimeout(60_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")

            // The user looked at the two screens and the digits differed.
            val granted = phone.pairWith("127.0.0.1", desktop.port) { false }

            assertFalse(granted)
            assertFalse(phone.trust.isTrusted(desktop.identity.fingerprint))
            assertFalse(desktop.trust.isTrusted(phone.identity.fingerprint))
        }
    }

    // --------------------------------------------------------------- transfer

    @Test
    fun `a paired node transfers a file intact`() = runBlocking {
        withTimeout(120_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")
            desktop.onPairRequest = { _, _ -> true }
            assertTrue(phone.pairWith("127.0.0.1", desktop.port) { true })

            // Larger than one upload chunk, so the streaming path is exercised
            // rather than a single convenient write.
            val payload = ByteArray(700_000) { (it % 251).toByte() }
            val source = fileOf(phone, "holiday.bin", payload)

            var arrived: File? = null
            desktop.onIncomingFile = { arrived = it }
            val progress = mutableListOf<Pair<Long, Long>>()

            val sent = phone.sendFile(
                "127.0.0.1", desktop.port, source,
                expectFp = desktop.identity.fingerprint,
            ) { received, total -> progress.add(received to total) }

            assertTrue(sent, "the transfer was not confirmed")

            val received = downloads(desktop)
            assertEquals(1, received.size, "expected exactly one received file")
            assertEquals("holiday.bin", received.first().name)
            assertContentEquals(payload, received.first().readBytes())
            assertEquals(sha256(payload), hashFile(received.first()))

            assertEquals(received.first().absolutePath, arrived?.absolutePath)
            // The final report must reach 100%, or the user watches a progress
            // bar stop short and assumes the transfer failed.
            assertTrue(progress.isNotEmpty(), "no progress was reported")
            assertEquals(payload.size.toLong(), progress.last().first)
        }
    }

    @Test
    fun `an unpaired node cannot send a file`() = runBlocking {
        withTimeout(60_000) {
            val stranger = newNode("STRANGER")
            val desktop = newNode("DESKTOP")

            val source = fileOf(stranger, "unwanted.bin", ByteArray(64))
            val sent = stranger.sendFile("127.0.0.1", desktop.port, source)

            assertFalse(sent, "an unpaired device managed to send a file")
            assertTrue(downloads(desktop).isEmpty(), "a file arrived from an unpaired device")
        }
    }

    @Test
    fun `naming the wrong device aborts rather than silently sending`() = runBlocking {
        withTimeout(60_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")
            desktop.onPairRequest = { _, _ -> true }
            assertTrue(phone.pairWith("127.0.0.1", desktop.port) { true })

            val source = fileOf(phone, "secret.bin", ByteArray(64))

            // Something is answering at an address where a known device should
            // be. That is an alarm, not a routine refusal, so it must raise
            // rather than return false.
            val failure = runCatching {
                phone.sendFile(
                    "127.0.0.1", desktop.port, source,
                    expectFp = "9".repeat(64),
                )
            }.exceptionOrNull()

            assertTrue(failure is ConnectionError, "expected a pin mismatch, got $failure")
            assertTrue(downloads(desktop).isEmpty())
        }
    }

    @Test
    fun `a second file with the same name does not overwrite the first`() = runBlocking {
        withTimeout(120_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")
            desktop.onPairRequest = { _, _ -> true }
            assertTrue(phone.pairWith("127.0.0.1", desktop.port) { true })

            val first = fileOf(phone, "photo.bin", "first".toByteArray())
            assertTrue(phone.sendFile("127.0.0.1", desktop.port, first))

            val second = File(phone.config.dataDir, "photo.bin").apply {
                writeBytes("second".toByteArray())
            }
            assertTrue(phone.sendFile("127.0.0.1", desktop.port, second))

            val names = downloads(desktop).map { it.name }.sorted()
            assertEquals(listOf("photo (2).bin", "photo.bin"), names)
        }
    }

    @Test
    fun `a refused offer transfers nothing`() = runBlocking {
        withTimeout(60_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP", autoAccept = false)
            desktop.onPairRequest = { _, _ -> true }
            assertTrue(phone.pairWith("127.0.0.1", desktop.port) { true })

            val source = fileOf(phone, "rejected.bin", ByteArray(128))
            assertFalse(phone.sendFile("127.0.0.1", desktop.port, source))
            assertTrue(downloads(desktop).isEmpty())
        }
    }

    // ------------------------------------------------------------- gestures

    @Test
    fun `gestures travel over a held link`() = runBlocking {
        withTimeout(60_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")
            desktop.onPairRequest = { _, _ -> true }
            assertTrue(phone.pairWith("127.0.0.1", desktop.port) { true })

            val seen = mutableListOf<Pair<String, String>>()
            desktop.onPeerGesture = { peerFp, type, _ -> seen.add(peerFp to type) }

            // Held open rather than dialled per event: a handshake per gesture
            // would add its latency to an interaction measured in tenths of a
            // second.
            val link = phone.openLink("127.0.0.1", desktop.port, desktop.identity.fingerprint)
            assertEquals(desktop.identity.fingerprint, link.peerFp)

            link.send(GestureMessage.HOLD, mapOf("kind" to "file"))
            link.send(GestureMessage.RELEASE, emptyMap())

            withTimeout(10_000) {
                while (seen.size < 2) delay(20)
            }

            assertEquals(
                listOf(GestureMessage.HOLD, GestureMessage.RELEASE),
                seen.map { it.second },
            )
            assertTrue(seen.all { it.first == phone.identity.fingerprint })

            link.close()
        }
    }

    @Test
    fun `a held link reports the peer as connected`() = runBlocking {
        withTimeout(60_000) {
            val phone = newNode("PHONE")
            val desktop = newNode("DESKTOP")
            desktop.onPairRequest = { _, _ -> true }
            assertTrue(phone.pairWith("127.0.0.1", desktop.port) { true })

            val link = phone.openLink("127.0.0.1", desktop.port, desktop.identity.fingerprint)
            assertEquals(listOf(desktop.identity.fingerprint), phone.connectedPeers())

            assertTrue(phone.sendToPeer(desktop.identity.fingerprint, MessageType.PING, emptyMap()))
            assertFalse(phone.sendToPeer("0".repeat(64), MessageType.PING, emptyMap()))

            link.close()
        }
    }
}
