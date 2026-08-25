package com.airgrab.core

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.ClientWebSocketSession
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.writeFully
import io.ktor.utils.io.readAvailable
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import java.io.File
import java.security.cert.X509Certificate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.coroutines.launch

/**
 * One AirGrab device: server and client in a single object.
 *
 * Ported from `desktop/airgrab/node.py`. Both sides of every exchange live
 * here because the protocol is symmetric — a device that can only receive is
 * half an implementation, and keeping the two halves adjacent is what stops
 * them drifting apart. On a phone that symmetry is the whole point: the
 * handset must be able to accept a file from the desktop, not only push one.
 *
 * The control channel is a WebSocket at `/control`; file bytes travel
 * separately to `/transfer/{ticket}` on the same TLS port. Small coordination
 * messages therefore cannot be stalled behind a multi-gigabyte upload, which
 * matters most exactly when a gesture needs to be cancelled mid-transfer.
 */

const val DEFAULT_PORT = 53421
private const val UPLOAD_CHUNK = 256 * 1024
private const val PROGRESS_INTERVAL_MILLIS = 250L

private val GESTURE_TYPES = setOf(
    GestureMessage.HOLD, GestureMessage.HOLD_END, GestureMessage.RELEASE
)

data class NodeConfig(
    val dataDir: File,
    val downloadDir: File,
    val displayName: String,
    val platform: String = "android",
    val port: Int = DEFAULT_PORT,
    val host: String = "0.0.0.0",
    val autoAccept: Boolean = true,
    val trustFilename: String = "trust.json",
)

/** Per-connection state on the server side. */
private class Session {
    var peerFp: String = ""
    var peerName: String = "Unknown"
    var peerPlatform: String = "unknown"
    var nonce: String = ""
    var authenticated: Boolean = false
    var pairAccepted: Boolean = false
    private val seq = AtomicInteger(0)

    fun nextSeq(): Int = seq.incrementAndGet()
}

class Node(val config: NodeConfig, val identity: FileIdentity) {

    val trust = TrustStore(File(config.dataDir, config.trustFilename))
    val tickets = TicketStore()

    var onIncomingFile: ((File) -> Unit)? = null
    var onPairRequest: ((sas: String, peerName: String) -> Boolean)? = null

    /**
     * Gesture messages arrive over whichever direction the control channel was
     * opened in, so both the server side and outbound links feed this.
     */
    var onPeerGesture: ((peerFp: String, type: String, payload: Map<String, Any?>) -> Unit)? = null

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    // Control channels currently open, by peer fingerprint.
    private val inbound = ConcurrentHashMap<String, Pair<WebSocketSession, Session>>()
    private val links = ConcurrentHashMap<String, PeerLink>()

    // Per-instance, never shared: two Nodes in one process (the loopback
    // tests, and any future multi-peer support) must not share in-flight
    // transfer state.
    private val pendingNames = ConcurrentHashMap<String, String>()
    private val pendingSockets = ConcurrentHashMap<String, Pair<WebSocketSession, Session>>()

    private var server: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    @Volatile
    private var boundPort: Int = config.port

    val port: Int get() = boundPort

    // ---------------------------------------------------------------- server

    suspend fun start() {
        val engine = embeddedServer(
            Netty,
            applicationEnvironment { },
            configure = {
                sslConnector(
                    keyStore = identity.keyStore,
                    keyAlias = identity.keyAlias,
                    keyStorePassword = { identity.keyStorePassword },
                    privateKeyPassword = { identity.keyStorePassword },
                ) {
                    port = config.port
                    host = config.host
                }
            },
        ) {
            install(ServerWebSockets) { pingPeriodMillis = 15_000 }
            routing {
                webSocket("/control") { handleControl(this) }
                post("/transfer/{ticket}") { handleUpload(call) }
            }
        }
        engine.start(wait = false)
        server = engine
        boundPort = engine.engine.resolvedConnectors().firstOrNull()?.port ?: config.port
    }

    suspend fun stop() {
        closeLinks()
        server?.stop(gracePeriodMillis = 0, timeoutMillis = 2000)
        server = null
    }

    private suspend fun handleControl(ws: WebSocketSession) {
        val session = Session()
        try {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val envelope = try {
                    Protocol.decode(frame.readText())
                } catch (_: ProtocolException) {
                    continue // unparseable messages are ignored, per PROTOCOL.md
                }
                try {
                    if (!dispatch(ws, session, envelope)) break
                } catch (_: Throwable) {
                    runCatching {
                        send(ws, session, MessageType.ERROR, mapOf(
                            "code" to ErrorCode.INTERNAL, "message" to "server error",
                        ))
                    }
                    break
                }
            }
        } catch (_: ClosedReceiveChannelException) {
            // Ordinary disconnection.
        } finally {
            if (session.peerFp.isNotEmpty() && inbound[session.peerFp]?.first === ws) {
                inbound.remove(session.peerFp)
            }
        }
    }

    private suspend fun send(
        ws: WebSocketSession, session: Session, type: String, payload: Map<String, Any?>,
    ) {
        ws.send(Frame.Text(Protocol.encode(type, session.nextSeq(), payload)))
    }

    /** Returns false when the connection should close. */
    private suspend fun dispatch(ws: WebSocketSession, session: Session, env: Envelope): Boolean {
        val payload = env.payload

        when (env.type) {
            MessageType.HELLO -> {
                if ((payload["v"] as? Number)?.toInt() != PROTOCOL_VERSION) {
                    send(ws, session, MessageType.ERROR, mapOf(
                        "code" to ErrorCode.VERSION_MISMATCH, "message" to "protocol version",
                    ))
                    return false
                }

                session.peerFp = payload["id"]?.toString() ?: ""
                session.peerName = payload["name"]?.toString() ?: "Unknown"
                session.peerPlatform = payload["plat"]?.toString() ?: "unknown"
                session.nonce = Auth.newNonce()
                val clientNonce = payload["nonce"]?.toString() ?: ""

                // Prove possession of our own key over the client's nonce, so
                // the client can pin us. Without this the client has no way to
                // tell us apart from a machine-in-the-middle.
                val serverSig = identity.sign(
                    Auth.signingPayload(clientNonce, identity.fingerprint, session.peerFp)
                )
                send(ws, session, MessageType.HELLO_ACK, mapOf(
                    "v" to PROTOCOL_VERSION,
                    "id" to identity.fingerprint,
                    "name" to identity.displayName,
                    "plat" to config.platform,
                    "trusted" to trust.isTrusted(session.peerFp),
                    "cert" to identity.certDer.toHex(),
                    "sig" to serverSig.toHex(),
                ))
                send(ws, session, MessageType.AUTH_CHALLENGE, mapOf("nonce" to session.nonce))
                return true
            }

            MessageType.AUTH_RESPONSE -> {
                val certDer = payload["cert"]?.toString().fromHexOrEmpty()
                val signature = payload["signature"]?.toString().fromHexOrEmpty()

                val result = Auth.verifyAuthResponse(
                    certDer = certDer,
                    signature = signature,
                    nonce = session.nonce,
                    serverFp = identity.fingerprint,
                    claimedClientFp = session.peerFp,
                )
                session.authenticated = result.ok
                if (result.ok && trust.isTrusted(session.peerFp)) {
                    inbound[session.peerFp] = ws to session
                }
                if (!result.ok) {
                    send(ws, session, MessageType.ERROR, mapOf(
                        "code" to ErrorCode.AUTH_FAILED, "message" to result.reason,
                    ))
                    return false
                }
                return true
            }
        }

        if (!session.authenticated) {
            send(ws, session, MessageType.ERROR, mapOf(
                "code" to ErrorCode.AUTH_FAILED, "message" to "not authenticated",
            ))
            return false
        }

        when (env.type) {
            MessageType.PAIR_REQUEST -> {
                val sas = Sas.compute(identity.fingerprint, session.peerFp)
                val accepted = onPairRequest?.invoke(sas, session.peerName) ?: true
                session.pairAccepted = accepted
                send(ws, session, MessageType.PAIR_CHALLENGE, mapOf("sas" to sas))
            }

            MessageType.PAIR_CONFIRM -> {
                val accepted = (payload["accepted"] as? Boolean == true) && session.pairAccepted
                if (accepted) {
                    trust.add(session.peerFp, session.peerName, session.peerPlatform)
                }
                send(ws, session, MessageType.PAIR_RESULT, mapOf("accepted" to accepted))
            }

            MessageType.OFFER -> {
                if (!trust.isTrusted(session.peerFp)) {
                    send(ws, session, MessageType.OFFER_REJECT,
                        mapOf("reason" to ErrorCode.NOT_TRUSTED))
                    return true
                }
                if (!config.autoAccept) {
                    send(ws, session, MessageType.OFFER_REJECT,
                        mapOf("reason" to ErrorCode.TRANSFER_ABORTED))
                    return true
                }

                val ticket = tickets.issue(
                    session.peerFp,
                    payload["sha256"]?.toString() ?: "",
                    (payload["size"] as? Number)?.toLong() ?: 0L,
                )
                pendingNames[ticket] = payload["name"]?.toString() ?: "received"
                pendingSockets[ticket] = ws to session
                send(ws, session, MessageType.OFFER_ACCEPT, mapOf("ticket" to ticket))
            }

            in GESTURE_TYPES -> {
                // Only paired devices may drive gestures. An unpaired peer
                // cannot reach here anyway, but the check keeps the rule local.
                if (trust.isTrusted(session.peerFp)) {
                    onPeerGesture?.invoke(session.peerFp, env.type, payload)
                }
            }

            MessageType.PING -> send(ws, session, MessageType.PONG, emptyMap())

            // Unknown types are ignored so v1 and a future v2 can coexist.
            else -> Unit
        }
        return true
    }

    private suspend fun handleUpload(call: io.ktor.server.application.ApplicationCall) {
        val ticketValue = call.parameters["ticket"] ?: ""
        val entry = pendingSockets[ticketValue]
        if (entry == null) {
            call.respondText(
                """{"code":"${ErrorCode.TICKET_INVALID}"}""",
                status = HttpStatusCode.Forbidden,
            )
            return
        }
        val (ws, session) = entry

        val ticket = try {
            tickets.redeem(ticketValue, session.peerFp)
        } catch (exc: TicketError) {
            cleanupTicket(ticketValue)
            call.respondText("""{"code":"${exc.code}"}""", status = HttpStatusCode.Forbidden)
            return
        }

        val filename = pendingNames[ticketValue] ?: "received"
        val destination = uniqueDestination(config.downloadDir, filename)

        var lastReport = 0L
        val channel = call.receiveChannel()
        val buffer = ByteArray(UPLOAD_CHUNK)

        val source = ChunkSource {
            val read = try {
                channel.readAvailable(buffer, 0, buffer.size)
            } catch (exc: Throwable) {
                // The peer, not the disk. Reported as such so the user is not
                // told their storage failed when their phone left Wi-Fi.
                throw PeerDisconnected(exc)
            }
            if (read <= 0) null else buffer.copyOf(read)
        }

        val path = try {
            receiveToFile(source, destination, ticket.expectedSha256) { received ->
                val now = System.nanoTime() / 1_000_000
                if (now - lastReport >= PROGRESS_INTERVAL_MILLIS) {
                    lastReport = now
                    // Launched rather than awaited: a slow control channel
                    // must not throttle the file stream.
                    scope.launch {
                        runCatching {
                            send(ws, session, MessageType.PROGRESS, mapOf(
                                "received" to received, "total" to ticket.size,
                            ))
                        }
                    }
                }
            }
        } catch (exc: TransferError) {
            cleanupTicket(ticketValue)
            runCatching {
                send(ws, session, MessageType.COMPLETE,
                    mapOf("ok" to false, "code" to exc.code))
            }
            call.respondText("""{"code":"${exc.code}"}""", status = HttpStatusCode.BadRequest)
            return
        }

        cleanupTicket(ticketValue)

        // A final 100% report before COMPLETE. Without it the last throttled
        // update can race the completion message and the progress bar visibly
        // stops short of the end.
        runCatching {
            send(ws, session, MessageType.PROGRESS,
                mapOf("received" to ticket.size, "total" to ticket.size))
            send(ws, session, MessageType.COMPLETE,
                mapOf("ok" to true, "path" to path.absolutePath))
        }

        onIncomingFile?.invoke(path)
        call.respondText("""{"ok":true}""")
    }

    private fun cleanupTicket(ticketValue: String) {
        tickets.discard(ticketValue)
        pendingNames.remove(ticketValue)
        pendingSockets.remove(ticketValue)
    }

    // ------------------------------------------------------------- messaging

    fun connectedPeers(): List<String> = (inbound.keys + links.keys).distinct().sorted()

    /**
     * Deliver a message over whichever control channel reaches this peer.
     *
     * A pair of devices may be connected in either direction depending on who
     * discovered whom first, so both are tried.
     */
    suspend fun sendToPeer(peerFp: String, type: String, payload: Map<String, Any?>): Boolean {
        links[peerFp]?.let { link ->
            try {
                link.send(type, payload)
                return true
            } catch (_: Throwable) {
                links.remove(peerFp)
            }
        }

        inbound[peerFp]?.let { (ws, session) ->
            try {
                send(ws, session, type, payload)
                return true
            } catch (_: Throwable) {
                inbound.remove(peerFp)
            }
        }

        return false
    }

    suspend fun broadcast(type: String, payload: Map<String, Any?>): Int =
        connectedPeers().count { sendToPeer(it, type, payload) }

    /**
     * Open a control channel and keep it open, reading messages as they
     * arrive. This is what gesture events travel over.
     */
    suspend fun openLink(host: String, port: Int, expectFp: String? = null): PeerLink {
        val link = PeerLink(this, host, port, expectFp)
        link.open()
        links[link.peerFp] = link
        return link
    }

    suspend fun closeLinks() {
        links.values.toList().forEach { it.close() }
        links.clear()
    }

    internal fun forgetLink(peerFp: String) {
        links.remove(peerFp)
    }

    // ---------------------------------------------------------------- client

    /**
     * TLS for confidentiality only.
     *
     * Certificate validation is deliberately disabled: peers use self-signed
     * certificates that no CA can vouch for, so there is nothing for a trust
     * manager to check. Identity is NOT skipped — it is established by the
     * signed-nonce exchange below, which pins the peer's certificate
     * fingerprint against the trust store.
     */
    private object TrustEverything : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    private fun newClient(): HttpClient = HttpClient(CIO) {
        install(ClientWebSockets)
        engine {
            https { trustManager = TrustEverything }

            // CIO's default is fifteen seconds for the whole request, which
            // is not a timeout on reaching the peer — it covers the upload
            // body as well. Any file that takes longer than fifteen seconds
            // to send is cut off, which is most videos on most networks.
            //
            // Long.MAX_VALUE, and NOT zero. Zero looks like the idiomatic
            // way to say "no limit" and is not: setting it made every
            // transfer fail, caught by the loopback test that actually moves
            // a file between two nodes. The unit tests around it all passed,
            // because none of them send anything.
            //
            // There is no honest ceiling here anyway: how long a legitimate
            // transfer takes depends on the file and the link, and a peer
            // going away already surfaces as a socket failure. Reaching a
            // device that is not there is bounded separately, below.
            requestTimeout = Long.MAX_VALUE
            endpoint.connectTimeout = 15_000
            endpoint.keepAliveTime = 30_000
        }
    }

    /** A connected, mutually authenticated control channel. */
    internal class Connection(
        val http: HttpClient,
        val ws: ClientWebSocketSession,
        val peerFp: String,
        val ack: Map<String, Any?>,
    ) {
        private val seq = AtomicInteger(0)

        suspend fun send(type: String, payload: Map<String, Any?>) {
            ws.send(Frame.Text(Protocol.encode(type, seq.incrementAndGet(), payload)))
        }

        suspend fun recv(vararg expected: String): Envelope {
            for (frame in ws.incoming) {
                if (frame !is Frame.Text) continue
                val envelope = Protocol.decode(frame.readText())
                if (expected.isEmpty() ||
                    envelope.type in expected ||
                    envelope.type == MessageType.ERROR
                ) {
                    return envelope
                }
            }
            throw ConnectionError("control channel closed")
        }
    }

    /**
     * Connect, authenticate in both directions, and run [block].
     *
     * The mutual part is not decoration. The server proves itself first so the
     * client can pin it; only then does the client sign the server's
     * challenge. A client that authenticated itself to an unverified server
     * would hand a valid signature to whoever answered the address.
     */
    internal suspend fun <T> connect(
        host: String,
        port: Int,
        expectFp: String? = null,
        block: suspend (Connection) -> T,
    ): T {
        val http = newClient()
        try {
            var result: T? = null
            var thrown: Throwable? = null

            http.webSocket("wss://$host:$port/control") {
                try {
                    val seq = AtomicInteger(0)
                    suspend fun rawSend(type: String, payload: Map<String, Any?>) {
                        send(Frame.Text(Protocol.encode(type, seq.incrementAndGet(), payload)))
                    }

                    suspend fun rawRecv(vararg expected: String): Envelope {
                        for (frame in incoming) {
                            if (frame !is Frame.Text) continue
                            val envelope = Protocol.decode(frame.readText())
                            if (expected.isEmpty() ||
                                envelope.type in expected ||
                                envelope.type == MessageType.ERROR
                            ) return envelope
                        }
                        throw ConnectionError("control channel closed")
                    }

                    val clientNonce = Auth.newNonce()
                    rawSend(MessageType.HELLO, mapOf(
                        "v" to PROTOCOL_VERSION,
                        "id" to identity.fingerprint,
                        "name" to identity.displayName,
                        "plat" to config.platform,
                        "nonce" to clientNonce,
                    ))

                    val ack = rawRecv(MessageType.HELLO_ACK)
                    if (ack.type == MessageType.ERROR) {
                        throw ConnectionError(ack.payload["code"]?.toString() ?: "error")
                    }

                    // Authenticate the SERVER before revealing anything
                    // further. peerFp comes from the presented certificate,
                    // never from ack.payload["id"] — that field is attacker
                    // controlled.
                    //
                    // The pin comes from the CALLER's intent, never from what
                    // the peer claims about itself. Deriving it from the ack
                    // would let an attacker skip the check simply by claiming
                    // a fingerprint that is not in the trust store.
                    val certDer = ack.payload["cert"]?.toString().fromHexOrEmpty()
                    val serverSig = ack.payload["sig"]?.toString().fromHexOrEmpty()

                    val (serverResult, peerFp) = Auth.verifyServerIdentity(
                        certDer = certDer,
                        signature = serverSig,
                        nonce = clientNonce,
                        clientFp = identity.fingerprint,
                        pinnedFp = expectFp,
                    )
                    if (!serverResult.ok) {
                        throw ConnectionError("server authentication failed: ${serverResult.reason}")
                    }

                    val challenge = rawRecv(MessageType.AUTH_CHALLENGE)
                    if (challenge.type == MessageType.ERROR) {
                        throw ConnectionError(challenge.payload["code"]?.toString() ?: "error")
                    }

                    val nonce = challenge.payload["nonce"]?.toString() ?: ""
                    val signature = identity.sign(
                        Auth.signingPayload(nonce, peerFp, identity.fingerprint)
                    )
                    rawSend(MessageType.AUTH_RESPONSE, mapOf(
                        "cert" to identity.certDer.toHex(),
                        "signature" to signature.toHex(),
                    ))

                    result = block(Connection(http, this, peerFp, ack.payload))
                } catch (exc: Throwable) {
                    thrown = exc
                }
            }

            thrown?.let { throw it }
            @Suppress("UNCHECKED_CAST")
            return result as T
        } finally {
            http.close()
        }
    }

    /**
     * Pair with a peer, showing the user a six-digit code to compare.
     *
     * Before trust exists there is no pin, so that comparison is the only
     * defence against a machine in the middle — which would have to present
     * its own certificate to each side, yielding two different codes.
     */
    suspend fun pairWith(host: String, port: Int, onSas: (String) -> Boolean): Boolean =
        connect(host, port) { connection ->
            connection.send(MessageType.PAIR_REQUEST, emptyMap())
            val challenge = connection.recv(MessageType.PAIR_CHALLENGE)

            // A bare `false` here used to cover three unrelated outcomes: the
            // peer rejected us, the peer disagreed about the code, and the
            // user declined. From the outside they were indistinguishable,
            // which made a real failure look like a decline.
            if (challenge.type == MessageType.ERROR) {
                throw ConnectionError(
                    "peer refused pairing: " +
                        (challenge.payload["code"]?.toString() ?: "unknown") + " " +
                        (challenge.payload["message"]?.toString() ?: "")
                )
            }
            if (challenge.type != MessageType.PAIR_CHALLENGE) {
                throw ConnectionError("unexpected reply to pairing: ${challenge.type}")
            }

            val sas = challenge.payload["sas"]?.toString() ?: ""
            // Recomputed locally from the fingerprint derived from the
            // certificate. A mismatch means the peer computed it against a
            // different identity than the one it proved it holds.
            val expected = Sas.compute(identity.fingerprint, connection.peerFp)
            if (sas != expected) {
                throw ConnectionError("pairing code mismatch: peer said $sas, we computed $expected")
            }

            val accepted = onSas(sas)
            connection.send(MessageType.PAIR_CONFIRM, mapOf("accepted" to accepted))
            val result = connection.recv(MessageType.PAIR_RESULT)
            val granted = accepted && result.payload["accepted"] as? Boolean == true

            if (granted) {
                trust.add(
                    connection.peerFp,
                    connection.ack["name"]?.toString() ?: "Unknown",
                    connection.ack["plat"]?.toString() ?: "unknown",
                )
            }
            granted
        }

    /**
     * Send one file. Returns true only if the peer confirmed receipt.
     *
     * [expectFp] names the device the caller intends to reach. When given, a
     * peer presenting any other certificate raises rather than returning
     * false: something is impersonating a device you named, which is an alarm,
     * not a routine refusal.
     */
    suspend fun sendFile(
        host: String,
        port: Int,
        file: File,
        expectFp: String? = null,
        onProgress: ((received: Long, total: Long) -> Unit)? = null,
    ): Boolean {
        val size = file.length()
        val digest = hashFile(file)

        return connect(host, port, expectFp) { connection ->
            // Never hand a file to a device we have not paired with. The
            // fingerprint checked here is derived from the peer's certificate,
            // so an impostor cannot satisfy it.
            if (!trust.isTrusted(connection.peerFp)) return@connect false

            connection.send(MessageType.OFFER, mapOf(
                "name" to file.name,
                "size" to size,
                "mime" to "application/octet-stream",
                "sha256" to digest,
            ))
            val reply = connection.recv(MessageType.OFFER_ACCEPT, MessageType.OFFER_REJECT)
            if (reply.type != MessageType.OFFER_ACCEPT) return@connect false
            val ticket = reply.payload["ticket"]?.toString() ?: ""

            val upload: HttpResponse = connection.http.post(
                "https://$host:$port/transfer/$ticket"
            ) {
                setBody(object : io.ktor.http.content.OutgoingContent.WriteChannelContent() {
                    override val contentLength: Long = size
                    override suspend fun writeTo(channel: io.ktor.utils.io.ByteWriteChannel) {
                        // Streamed in chunks so a multi-gigabyte video never
                        // has to fit in a phone's heap.
                        file.inputStream().use { input ->
                            val buffer = ByteArray(UPLOAD_CHUNK)
                            while (true) {
                                val read = input.read(buffer)
                                if (read <= 0) break
                                channel.writeFully(buffer, 0, read)
                            }
                        }
                        channel.flush()
                    }
                })
            }
            if (upload.status != HttpStatusCode.OK) return@connect false

            while (true) {
                val env = connection.recv()
                when (env.type) {
                    MessageType.PROGRESS -> onProgress?.invoke(
                        (env.payload["received"] as? Number)?.toLong() ?: 0L,
                        (env.payload["total"] as? Number)?.toLong() ?: size,
                    )
                    MessageType.COMPLETE -> return@connect env.payload["ok"] as? Boolean == true
                    MessageType.ERROR -> return@connect false
                    else -> Unit
                }
            }
            @Suppress("UNREACHABLE_CODE") false
        }
    }
}

class ConnectionError(message: String) : Exception(message)

/**
 * A control channel held open for as long as the peer is around.
 *
 * Transfers open their own short-lived connection; this one exists so that
 * gesture events can arrive unprompted. Re-dialling per event would add
 * handshake latency to an interaction measured in tenths of a second.
 */
class PeerLink internal constructor(
    private val node: Node,
    private val host: String,
    private val port: Int,
    private val expectFp: String?,
) {
    var peerFp: String = ""
        private set

    private var connection: Node.Connection? = null
    private var job: Job? = null
    private val ready = CompletableDeferred<Unit>()

    @Volatile
    private var closing = false

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    suspend fun open() {
        job = scope.launch { run() }
        ready.await() // rethrows whatever the handshake threw
    }

    private suspend fun run() {
        try {
            node.connect(host, port, expectFp) { established ->
                peerFp = established.peerFp
                connection = established
                ready.complete(Unit)

                while (!closing) {
                    val envelope = established.recv()
                    if (envelope.type in GESTURE_TYPES && node.trust.isTrusted(peerFp)) {
                        node.onPeerGesture?.invoke(peerFp, envelope.type, envelope.payload)
                    }
                }
            }
        } catch (exc: Throwable) {
            if (!ready.isCompleted) ready.completeExceptionally(exc)
        } finally {
            node.forgetLink(peerFp)
        }
    }

    suspend fun send(type: String, payload: Map<String, Any?>) {
        val established = connection ?: throw ConnectionError("link is not open")
        established.send(type, payload)
    }

    suspend fun close() {
        closing = true
        job?.cancelAndJoin()
        job = null
        connection = null
    }
}

private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

/**
 * Hex from an untrusted peer. A malformed string yields no bytes rather than
 * throwing, so a garbage certificate field fails authentication like any other
 * bad credential instead of taking down the connection handler.
 */
private fun String?.fromHexOrEmpty(): ByteArray {
    if (this == null || length % 2 != 0) return ByteArray(0)
    return runCatching {
        ByteArray(length / 2) { substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }.getOrElse { ByteArray(0) }
}
