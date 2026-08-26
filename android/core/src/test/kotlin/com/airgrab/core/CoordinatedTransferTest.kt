package com.airgrab.core

import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * The whole thing: a grab on one device, a release on another, a file arrives.
 *
 * Every layer below this has been proven separately — the state machine
 * without a camera, the coordinator without a network, the node without a
 * gesture. This is the first test where they are wired together and the claim
 * is the one the user actually cares about.
 *
 * It stands in for the Android orchestrator, which cannot run on a JVM. The
 * action-executing code here is deliberately the same shape as
 * `GestureOrchestrator`, so a mistake in how Actions are carried out shows up
 * on a laptop rather than on a handset.
 */
class CoordinatedTransferTest {

    private val roots = mutableListOf<File>()
    private val running = mutableListOf<Node>()

    @AfterTest
    fun cleanUp() = runBlocking {
        running.forEach { runCatching { it.stop() } }
        roots.forEach { it.deleteRecursively() }
    }

    /** One device: a node, a coordinator, and the glue between them. */
    private inner class Device(val name: String) {
        lateinit var node: Node
        val coordinator = GestureCoordinator()
        var captured: File? = null

        /** Where the peer can be reached. Discovery is not under test here. */
        var peerHost: String = "127.0.0.1"
        var peerPort: Int = 0

        /** What a grab picks up on this device. */
        var contentToSend: File? = null

        val sent = mutableListOf<String>()

        suspend fun start(): Device {
            val root = Files.createTempDirectory("airgrab-coord").toFile().also { roots.add(it) }
            val downloads = File(root, "downloads").apply { mkdirs() }
            node = Node(
                NodeConfig(
                    dataDir = root,
                    downloadDir = downloads,
                    displayName = name,
                    platform = "test",
                    port = 0,
                    host = "127.0.0.1",
                ),
                FileIdentity.loadOrCreate(root, name),
            )
            node.start()
            running.add(node)

            node.onPeerGesture = { peerFp, type, payload ->
                val safe = payload.filterValues { it != null }.mapValues { it.value!! }
                // Fired from the socket's own coroutine, exactly as the
                // Android orchestrator receives it.
                runBlocking { run(coordinator.onPeerMessage(peerFp, type, safe)) }
            }
            return this
        }

        suspend fun grab() = run(
            coordinator.onLocalEvent(
                GestureEvent(GestureEventType.GRABBED, 0, GestureState.ARMED, GestureState.HOLDING)
            )
        )

        suspend fun release() = run(
            coordinator.onLocalEvent(
                GestureEvent(GestureEventType.RELEASED, 0, GestureState.CATCHING, GestureState.IDLE)
            )
        )

        val downloads: List<File>
            get() = node.config.downloadDir.listFiles().orEmpty()
                .filterNot { it.name.startsWith(".") }

        private suspend fun run(actions: List<Action>) {
            for (action in actions) {
                when (action) {
                    is Action.CaptureContent -> captured = contentToSend
                    is Action.ClearContent -> captured = null
                    is Action.Broadcast -> node.broadcast(action.type, action.payload)
                    is Action.SendMessage ->
                        node.sendToPeer(action.peerFp, action.type, action.payload)
                    is Action.SendCapturedFile -> {
                        val file = captured ?: continue
                        val ok = node.sendFile(
                            peerHost, peerPort, file, expectFp = action.peerFp
                        )
                        sent.add("${file.name}:$ok")
                    }
                }
            }
        }
    }

    private suspend fun pairedPair(): Pair<Device, Device> {
        val phone = Device("PHONE").start()
        val desktop = Device("DESKTOP").start()

        desktop.node.onPairRequest = { _, _ -> true }
        assertTrue(phone.node.pairWith("127.0.0.1", desktop.node.port) { true })

        phone.peerPort = desktop.node.port
        desktop.peerPort = phone.node.port

        // Both hold a control channel open. The receiving device has to be
        // listening before the release happens, so this cannot be deferred.
        phone.node.openLink("127.0.0.1", desktop.node.port, desktop.node.identity.fingerprint)
        desktop.node.openLink("127.0.0.1", phone.node.port, phone.node.identity.fingerprint)

        return phone to desktop
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ the point

    @Test
    fun `a grab on one device and a release on the other moves the file`() = runBlocking {
        withTimeout(120_000) {
            val (phone, desktop) = pairedPair()

            val payload = ByteArray(300_000) { (it % 251).toByte() }
            phone.contentToSend = File(phone.node.config.dataDir, "photo.jpg")
                .apply { writeBytes(payload) }

            // The user closes their fist at the phone...
            phone.grab()
            withTimeout(10_000) {
                while (desktop.coordinator.peersHolding().isEmpty()) delay(20)
            }

            // ...and opens their palm at the PC.
            desktop.release()

            withTimeout(30_000) {
                while (desktop.downloads.isEmpty()) delay(50)
            }

            val received = desktop.downloads.single()
            assertEquals("photo.jpg", received.name)
            assertContentEquals(payload, received.readBytes())
            assertEquals(sha256(payload), hashFile(received))

            // The hold is over on both sides, or the next release would send
            // the same file again.
            //
            // The two sides clear at different moments, and deliberately so:
            // the sender drops `holding` the instant it decides to send, which
            // is what makes a second release arriving mid-upload a no-op. The
            // peer only learns of it from the HOLD_END that follows the
            // upload, so the wait here is real rather than defensive.
            withTimeout(10_000) {
                while (phone.coordinator.holding) delay(20)
            }
            withTimeout(10_000) {
                while (desktop.coordinator.peersHolding().isNotEmpty()) delay(20)
            }
            assertEquals(null, phone.captured)
        }
    }

    @Test
    fun `releasing on the same device that grabbed sends nothing`() = runBlocking {
        withTimeout(120_000) {
            val (phone, desktop) = pairedPair()
            phone.contentToSend = File(phone.node.config.dataDir, "note.txt")
                .apply { writeText("private") }

            phone.grab()
            withTimeout(10_000) {
                while (desktop.coordinator.peersHolding().isEmpty()) delay(20)
            }

            // The user changed their mind and opened their hand where they
            // stood. Treating that as a send would fire a transfer every time
            // someone thought better of it.
            phone.release()
            delay(1500)

            assertTrue(desktop.downloads.isEmpty(), "a file was sent by a cancelled grab")
            assertFalse(phone.coordinator.holding)
            assertEquals(null, phone.captured)
        }
    }

    @Test
    fun `releasing with nothing held sends nothing`() = runBlocking {
        withTimeout(120_000) {
            val (phone, desktop) = pairedPair()

            // A palm opened at the PC when nobody grabbed anything. Without
            // this rule a peer could ask for a file at any moment.
            desktop.release()
            delay(1500)

            assertTrue(desktop.downloads.isEmpty())
            assertTrue(phone.sent.isEmpty())
        }
    }

    @Test
    fun `the transfer goes to the device that caught it`() = runBlocking {
        withTimeout(120_000) {
            val (phone, desktop) = pairedPair()
            val payload = "for the desktop".toByteArray()
            phone.contentToSend = File(phone.node.config.dataDir, "doc.txt")
                .apply { writeBytes(payload) }

            phone.grab()
            withTimeout(10_000) {
                while (desktop.coordinator.peersHolding().isEmpty()) delay(20)
            }
            desktop.release()

            withTimeout(30_000) {
                while (desktop.downloads.isEmpty()) delay(50)
            }

            // The sender records the outcome only after the COMPLETE message
            // comes back over the control channel, which is milliseconds
            // AFTER the file is visible at the destination. Asserting the
            // instant the download appeared lost that race roughly one run
            // in ten.
            withTimeout(10_000) {
                while (phone.sent.isEmpty()) delay(50)
            }

            // The fingerprint is pinned during the upload, so the file cannot
            // land anywhere but the device whose gesture asked for it.
            assertEquals(listOf("doc.txt:true"), phone.sent)
            assertTrue(phone.downloads.isEmpty(), "the sender received its own file")
        }
    }

    @Test
    fun `a hold nobody catches expires`() = runBlocking {
        withTimeout(120_000) {
            val phone = Device("PHONE").start()
            // A short window so the test does not wait twenty seconds.
            val coordinator = GestureCoordinator(holdWindowMillis = 300)

            coordinator.onLocalEvent(
                GestureEvent(GestureEventType.GRABBED, 0, GestureState.ARMED, GestureState.HOLDING)
            )
            assertTrue(coordinator.holding)

            delay(400)
            val actions = coordinator.tick()

            // Otherwise the device stays armed and the next release anywhere
            // sends a file the user grabbed long ago.
            assertFalse(coordinator.holding)
            assertTrue(actions.any { it is Action.ClearContent })
            assertTrue(actions.any { it is Action.Broadcast })
        }
    }
}
