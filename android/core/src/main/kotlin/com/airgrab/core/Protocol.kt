package com.airgrab.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * The control-channel wire format, ported from `desktop/airgrab/protocol.py`.
 *
 * Both halves of AirGrab must agree on this byte for byte: a message this side
 * encodes differently, or accepts where the other side rejects, turns into a
 * connection that dies with no useful diagnosis. `protocol/PROTOCOL.md` is the
 * authoritative definition; this file and the Python module are two renderings
 * of it, and where they disagree the document wins.
 *
 * Deliberately free of I/O and of any Android API so it can be unit-tested on
 * a plain JVM against the same cases as the Python implementation.
 */
const val PROTOCOL_VERSION = 1

object MessageType {
    const val HELLO = "hello"
    const val HELLO_ACK = "hello_ack"
    const val AUTH_CHALLENGE = "auth_challenge"
    const val AUTH_RESPONSE = "auth_response"
    const val PAIR_REQUEST = "pair_request"
    const val PAIR_CHALLENGE = "pair_challenge"
    const val PAIR_CONFIRM = "pair_confirm"
    const val PAIR_RESULT = "pair_result"
    const val OFFER = "offer"
    const val OFFER_ACCEPT = "offer_accept"
    const val OFFER_REJECT = "offer_reject"
    const val PROGRESS = "progress"
    const val COMPLETE = "complete"
    const val CANCEL = "cancel"
    const val ERROR = "error"
    const val PING = "ping"
    const val PONG = "pong"
}

object ErrorCode {
    const val VERSION_MISMATCH = "version_mismatch"
    const val NOT_TRUSTED = "not_trusted"
    const val AUTH_FAILED = "auth_failed"
    const val TICKET_INVALID = "ticket_invalid"
    const val TICKET_EXPIRED = "ticket_expired"
    const val HASH_MISMATCH = "hash_mismatch"
    const val STORAGE_FULL = "storage_full"
    const val STORAGE_DENIED = "storage_denied"
    const val TRANSFER_ABORTED = "transfer_aborted"
    const val INTERNAL = "internal"
}

/** A message that cannot be parsed as a valid v1 envelope. */
class ProtocolException(message: String) : Exception(message)

data class Envelope(
    val type: String,
    val seq: Int,
    val payload: Map<String, Any?> = emptyMap(),
)

object Protocol {

    // Python encodes with separators=(",", ":") and ensure_ascii=False, which
    // is exactly kotlinx's default: no spaces, non-ASCII left as literal UTF-8.
    private val json = Json

    fun encode(type: String, seq: Int, payload: Map<String, Any?> = emptyMap()): String {
        // A LinkedHashMap keeps key order stable, so the same input produces
        // the same bytes on both sides — worth having when comparing captures.
        val fields = LinkedHashMap<String, JsonElement>()
        fields["type"] = JsonPrimitive(type)
        fields["seq"] = JsonPrimitive(seq)
        fields["payload"] = JsonObject(
            payload.entries.associateTo(LinkedHashMap()) { (key, value) ->
                key to toJson(value)
            }
        )
        return json.encodeToString(JsonObject.serializer(), JsonObject(fields))
    }

    fun decode(raw: String): Envelope {
        val element = try {
            json.parseToJsonElement(raw)
        } catch (exc: Exception) {
            throw ProtocolException("message is not valid JSON")
        }

        val obj = element as? JsonObject
            ?: throw ProtocolException("message is not a JSON object")

        val typeElement = obj["type"]
        val type = (typeElement as? JsonPrimitive)
            ?.takeIf { it.isString }
            ?.content
            ?.takeIf { it.isNotEmpty() }
            ?: throw ProtocolException("message has no type")

        val seq = when (val seqElement = obj["seq"]) {
            null, JsonNull -> 0
            is JsonPrimitive -> {
                // Python rejects a boolean seq explicitly because bool is a
                // subclass of int there. Kotlin has no such trap, but a peer
                // sending `"seq": true` must be rejected by both halves or the
                // two implementations disagree about what is a valid message.
                if (seqElement.isString || seqElement.booleanOrNull != null) {
                    throw ProtocolException("seq must be an integer")
                }
                seqElement.longOrNull?.toInt()
                    ?: throw ProtocolException("seq must be an integer")
            }
            else -> throw ProtocolException("seq must be an integer")
        }

        val payload = when (val payloadElement = obj["payload"]) {
            null, JsonNull -> emptyMap()
            is JsonObject -> payloadElement.mapValues { (_, value) -> fromJson(value) }
            else -> throw ProtocolException("payload must be an object")
        }

        return Envelope(type = type, seq = seq, payload = payload)
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> JsonNull
        is String -> JsonPrimitive(value)
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value)
        else -> JsonPrimitive(value.toString())
    }

    private fun fromJson(value: JsonElement): Any? = when (value) {
        is JsonNull -> null
        is JsonPrimitive ->
            if (value.isString) value.content
            else value.booleanOrNull ?: value.longOrNull ?: value.doubleOrNull ?: value.content
        is JsonObject -> value.mapValues { (_, nested) -> fromJson(nested) }
        is JsonArray -> value.map { fromJson(it) }
    }
}
