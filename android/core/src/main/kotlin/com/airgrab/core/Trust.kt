package com.airgrab.core

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The list of devices this device has paired with, ported from
 * `desktop/airgrab/trust.py`.
 *
 * The on-disk shape is shared with the Python implementation, so a store
 * written by either side is readable by the other.
 *
 * Two decisions are deliberate and worth keeping:
 *
 *  - A corrupt or unreadable store is treated as EMPTY rather than fatal. The
 *    worst outcome of that is re-pairing; refusing to start is a far worse
 *    experience than asking for six digits again.
 *  - Writes go to a temp file in the same directory and are then moved over
 *    the target, so a half-written trust store is never observable — a
 *    truncated file read at the wrong moment would silently revoke devices.
 */
data class TrustedPeer(
    val fingerprint: String,
    val name: String,
    val platform: String,
)

class TrustStore(private val path: File) {

    // LinkedHashMap so `all()` returns peers in the order they were added,
    // which keeps the persisted file stable across saves.
    private val peers = LinkedHashMap<String, TrustedPeer>()

    private val json = Json { prettyPrint = true }

    init {
        load()
    }

    fun isTrusted(fingerprint: String): Boolean = peers.containsKey(fingerprint)

    fun get(fingerprint: String): TrustedPeer? = peers[fingerprint]

    fun all(): List<TrustedPeer> = peers.values.toList()

    fun add(fingerprint: String, name: String, platform: String) {
        peers[fingerprint] = TrustedPeer(fingerprint, name, platform)
        save()
    }

    fun remove(fingerprint: String) {
        if (peers.remove(fingerprint) != null) {
            save()
        }
    }

    private fun load() {
        if (!path.exists()) return
        try {
            val root = Json.parseToJsonElement(path.readText(Charsets.UTF_8)) as? JsonObject
                ?: return
            val entries = root["peers"] as? JsonArray ?: return
            for (entry in entries) {
                val obj = entry as? JsonObject ?: continue
                // A missing fingerprint makes the entry meaningless; missing
                // name/platform are only cosmetic, so they get placeholders,
                // matching the Python side's defaults.
                val fingerprint = obj.string("fingerprint") ?: continue
                val peer = TrustedPeer(
                    fingerprint = fingerprint,
                    name = obj.string("name") ?: "Unknown",
                    platform = obj.string("platform") ?: "unknown",
                )
                peers[peer.fingerprint] = peer
            }
        } catch (exc: Exception) {
            peers.clear()
        }
    }

    private fun save() {
        path.absoluteFile.parentFile?.mkdirs()
        val payload = JsonObject(
            mapOf(
                "peers" to JsonArray(
                    peers.values.map { peer ->
                        JsonObject(
                            linkedMapOf(
                                "fingerprint" to JsonPrimitive(peer.fingerprint),
                                "name" to JsonPrimitive(peer.name),
                                "platform" to JsonPrimitive(peer.platform),
                            )
                        )
                    }
                )
            )
        )
        val text = json.encodeToString(JsonObject.serializer(), payload)

        val directory = path.absoluteFile.parentFile
        val temp = File.createTempFile("trust", ".tmp", directory)
        try {
            temp.writeText(text, Charsets.UTF_8)
            // ATOMIC_MOVE rather than File.renameTo: renameTo will not
            // overwrite an existing file on Windows, and a delete-then-rename
            // fallback would leave a window with no trust store at all.
            java.nio.file.Files.move(
                temp.toPath(),
                path.absoluteFile.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        } catch (exc: Exception) {
            temp.delete()
            throw exc
        }
    }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
