package com.airgrab.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Ported case for case from `desktop/tests/test_protocol.py`.
 *
 * The point is not coverage for its own sake: every case here is one where the
 * two implementations could plausibly drift, and drift in the wire format
 * shows up in the field as a connection that dies without explanation.
 * (The SAS cases from the Python file live in SasVectorsTest.)
 */
class ProtocolTest {

    private val fpA = "a".repeat(64)

    @Test
    fun `protocol version is one`() {
        assertEquals(1, PROTOCOL_VERSION)
    }

    @Test
    fun `encode decode round trip`() {
        val raw = Protocol.encode(MessageType.HELLO, 1, mapOf("id" to fpA))
        val env = Protocol.decode(raw)
        assertEquals(MessageType.HELLO, env.type)
        assertEquals(1, env.seq)
        assertEquals(mapOf<String, Any?>("id" to fpA), env.payload)
    }

    @Test
    fun `encode produces json with expected keys`() {
        val env = Protocol.decode(Protocol.encode(MessageType.PING, 7, emptyMap()))
        assertEquals(MessageType.PING, env.type)
        assertEquals(7, env.seq)
        assertEquals(emptyMap<String, Any?>(), env.payload)
    }

    @Test
    fun `encode is byte compatible with the python implementation`() {
        // Python: json.dumps(..., separators=(",", ":"), ensure_ascii=False)
        assertEquals(
            """{"type":"hello","seq":1,"payload":{"v":1,"id":"abc","name":"My Phone"}}""",
            Protocol.encode(
                MessageType.HELLO,
                1,
                linkedMapOf("v" to 1, "id" to "abc", "name" to "My Phone"),
            ),
        )
        assertEquals(
            """{"type":"ping","seq":7,"payload":{}}""",
            Protocol.encode(MessageType.PING, 7, emptyMap()),
        )
    }

    @Test
    fun `encode carries ints longs and booleans`() {
        val raw = Protocol.encode(
            MessageType.OFFER,
            2,
            linkedMapOf("size" to 5_000_000_000L, "count" to 3, "trusted" to true),
        )
        assertEquals(
            """{"type":"offer","seq":2,"payload":{"size":5000000000,"count":3,"trusted":true}}""",
            raw,
        )
        val payload = Protocol.decode(raw).payload
        assertEquals(5_000_000_000L, payload["size"])
        assertEquals(3L, payload["count"])
        assertEquals(true, payload["trusted"])
    }

    @Test
    fun `decode rejects non json`() {
        assertFailsWith<ProtocolException> { Protocol.decode("not json at all") }
    }

    @Test
    fun `decode rejects a non object`() {
        assertFailsWith<ProtocolException> { Protocol.decode("[1,2,3]") }
        assertFailsWith<ProtocolException> { Protocol.decode("\"hello\"") }
    }

    @Test
    fun `decode rejects missing type`() {
        assertFailsWith<ProtocolException> { Protocol.decode("""{"seq":1,"payload":{}}""") }
    }

    @Test
    fun `decode rejects blank or non string type`() {
        assertFailsWith<ProtocolException> { Protocol.decode("""{"type":"","seq":1}""") }
        assertFailsWith<ProtocolException> { Protocol.decode("""{"type":5,"seq":1}""") }
    }

    @Test
    fun `decode rejects non integer seq`() {
        assertFailsWith<ProtocolException> {
            Protocol.decode("""{"type":"ping","seq":"one","payload":{}}""")
        }
        assertFailsWith<ProtocolException> { Protocol.decode("""{"type":"ping","seq":1.5}""") }
    }

    @Test
    fun `decode rejects boolean seq`() {
        // bool is a subclass of int in Python, so the reference implementation
        // rejects this explicitly. Kotlin has no such trap, but both sides must
        // agree on what a valid message is.
        assertFailsWith<ProtocolException> {
            Protocol.decode("""{"type":"ping","seq":true,"payload":{}}""")
        }
    }

    @Test
    fun `decode rejects a non object payload`() {
        assertFailsWith<ProtocolException> {
            Protocol.decode("""{"type":"ping","seq":1,"payload":[1,2]}""")
        }
    }

    @Test
    fun `decode defaults missing payload to empty`() {
        assertEquals(
            emptyMap<String, Any?>(),
            Protocol.decode("""{"type":"ping","seq":1}""").payload,
        )
        // Python coerces an explicit null payload to {} as well.
        assertEquals(
            emptyMap<String, Any?>(),
            Protocol.decode("""{"type":"ping","seq":1,"payload":null}""").payload,
        )
    }

    @Test
    fun `decode defaults missing seq to zero`() {
        assertEquals(0, Protocol.decode("""{"type":"ping"}""").seq)
    }

    @Test
    fun `message types and error codes carry the wire strings`() {
        assertEquals("hello_ack", MessageType.HELLO_ACK)
        assertEquals("auth_challenge", MessageType.AUTH_CHALLENGE)
        assertEquals("offer_reject", MessageType.OFFER_REJECT)
        assertEquals("version_mismatch", ErrorCode.VERSION_MISMATCH)
        assertEquals("ticket_expired", ErrorCode.TICKET_EXPIRED)
        assertEquals("transfer_aborted", ErrorCode.TRANSFER_ABORTED)
        assertTrue(MessageType.PONG == "pong" && ErrorCode.INTERNAL == "internal")
    }
}
