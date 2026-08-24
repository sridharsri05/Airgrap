package com.airgrab

import android.util.Log
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets as ClientWebSockets
import io.ktor.client.plugins.websocket.webSocket
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.network.tls.certificates.generateCertificate
import io.ktor.server.application.install
import io.ktor.server.engine.applicationEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.engine.sslConnector
import io.ktor.server.netty.Netty
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets as ServerWebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readText
import java.io.File
import java.security.cert.X509Certificate
import javax.net.ssl.X509TrustManager

/**
 * The TLS spike, run on a real handset.
 *
 * `TlsSpikeTest` proves the transport works on a JVM, which is not the same
 * claim. Netty pulls in native transports, reflective access, and a service
 * loader — all things Android treats differently from a desktop JVM — and a
 * phone that cannot host a TLS server cannot receive files, which would
 * invalidate half the design. Better to find that out from this probe than
 * from a finished app that silently only ever sends.
 *
 * Temporary: this is scaffolding for a decision, and comes out once the real
 * node replaces it.
 */
object TlsProbe {

    const val TAG = "AirGrabProbe"

    private object TrustEverything : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    suspend fun run(dataDir: File) {
        try {
            val password = "airgrab"
            val keyStoreFile = File(dataDir, "probe-keystore.jks")
            keyStoreFile.delete()

            Log.i(TAG, "generating certificate")
            val keyStore = generateCertificate(
                file = keyStoreFile,
                keyAlias = "airgrab",
                keyPassword = password,
                jksPassword = password,
            )
            Log.i(TAG, "certificate OK")

            val server = embeddedServer(
                Netty,
                applicationEnvironment { },
                configure = {
                    sslConnector(
                        keyStore = keyStore,
                        keyAlias = "airgrab",
                        keyStorePassword = { password.toCharArray() },
                        privateKeyPassword = { password.toCharArray() },
                    ) {
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
            Log.i(TAG, "server listening on $port")

            val client = HttpClient(CIO) {
                install(ClientWebSockets)
                engine { https { trustManager = TrustEverything } }
            }

            try {
                val body = client.get("https://127.0.0.1:$port/probe").bodyAsText()
                Log.i(TAG, "https GET -> $body")

                client.webSocket("wss://127.0.0.1:$port/control") {
                    send(Frame.Text("hello"))
                    val reply = (incoming.receive() as Frame.Text).readText()
                    Log.i(TAG, "wss echo -> $reply")
                }

                val ok = body == "http-ok"
                Log.i(TAG, if (ok) "PROBE RESULT: PASS" else "PROBE RESULT: FAIL")
            } finally {
                client.close()
                server.stop(gracePeriodMillis = 0, timeoutMillis = 1000)
            }
        } catch (exc: Throwable) {
            Log.e(TAG, "PROBE RESULT: FAIL - ${exc.javaClass.name}: ${exc.message}", exc)
        }
    }
}
