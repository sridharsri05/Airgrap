package com.airgrab.core

import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.network.tls.certificates.generateCertificate
import io.ktor.server.application.install
import io.ktor.server.netty.Netty as ServerNetty
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import java.nio.file.Files
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * Proves Ktor can be a TLS server and a TLS client in this project.
 *
 * This exists because the phone has to RECEIVE, not merely send, and a TLS
 * server running on Android is the part of the design most likely to turn out
 * impossible. Learning that from a short test is far cheaper than learning it
 * after the whole node has been written against the assumption.
 *
 * It earned its place immediately. The engine here is Netty, not CIO, because
 * CIO's server throws UnsupportedOperationException on sslConnector — it
 * cannot do TLS at all. CIO remains the client engine, where it works.
 *
 * The client trusts every certificate deliberately. Peers are self-signed and
 * no CA can vouch for them, so TLS here provides confidentiality only.
 * Identity is established separately by the signed-nonce exchange in [Auth],
 * which pins the peer's certificate fingerprint against the trust store.
 */
class TlsSpikeTest {

    private val temp: File = Files.createTempDirectory("airgrab-tls").toFile()

    @AfterTest
    fun cleanUp() {
        temp.deleteRecursively()
    }

    private object TrustEverything : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    @Test
    fun `a tls server serves both http and websockets to a ktor client`() = runBlocking {
        val password = "airgrab"
        val keyStore = generateCertificate(
            file = File(temp, "keystore.jks"),
            keyAlias = "airgrab",
            keyPassword = password,
            jksPassword = password,
        )

        val server = embeddedServer(
            ServerNetty,
            applicationEnvironment { },
            configure = {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = "airgrab",
                    keyStorePassword = { password.toCharArray() },
                    privateKeyPassword = { password.toCharArray() },
                ) {
                    // Port 0 lets the OS choose, so running this while the real
                    // app holds 53421 does not produce a confusing bind failure.
                    port = 0
                    host = "127.0.0.1"
                }
            },
        ) {
            install(ServerWebSockets)
            routing {
                get("/probe") { call.respondText("http-ok") }
                webSocket("/control") {
                    val received = (incoming.receive() as Frame.Text).readText()
                    send(Frame.Text("echo:$received"))
                }
            }
        }
        server.start(wait = false)

        val port = server.engine.resolvedConnectors().first().port
        assertTrue(port > 0, "the server did not bind a port")

        val client = HttpClient(io.ktor.client.engine.cio.CIO) {
            install(ClientWebSockets)
            engine {
                https { trustManager = TrustEverything }
            }
        }

        try {
            // Both channels on one port: small control messages must not be
            // stuck behind a multi-gigabyte upload, which is the whole reason
            // the design splits them.
            assertEquals("http-ok", client.get("https://127.0.0.1:$port/probe").bodyAsText())

            client.webSocket("wss://127.0.0.1:$port/control") {
                send(Frame.Text("hello"))
                assertEquals("echo:hello", (incoming.receive() as Frame.Text).readText())
            }
        } finally {
            client.close()
            server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
        }
    }
}
