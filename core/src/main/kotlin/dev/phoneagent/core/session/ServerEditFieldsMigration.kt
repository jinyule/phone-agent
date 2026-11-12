package dev.phoneagent.core.session

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * v1 → v2: server-side context edits (`contextEdits` of type `server`) gained `clearAtLeastTokens`
 * and `compactInstructions` (WP17). A v1 request sent neither, so a v1 record gets both as `null`,
 * which is how v2 writes "not sent". Touches the edits of `request/header` (`data.contextEdits`)
 * and of `side/request` (`data.request.contextEdits`); every other record, and client-side edits,
 * pass unchanged.
 */
internal object ServerEditFieldsMigration : SessionMigration {
    override val fromVersion: Int = 1

    override fun migrate(envelope: JsonObject): JsonObject {
        val data = envelope["data"] as? JsonObject
        val type = envelope["type"]
        val migrated: Map<String, JsonElement>? =
            when {
                data == null -> null
                type == REQUEST_HEADER -> withServerFields(data)
                type == SIDE_REQUEST -> sideRequest(data)
                else -> null
            }
        return if (migrated == null) envelope else JsonObject(envelope + ("data" to JsonObject(migrated)))
    }

    /** The `side/request` payload [data] with its request migrated; `null` when it has no request object. */
    private fun sideRequest(data: JsonObject): Map<String, JsonElement>? {
        val request = data["request"] as? JsonObject ?: return null
        return data + ("request" to withServerFields(request))
    }

    /** [holder] with its `contextEdits`, when server-side, given the two v2 fields as `null`. */
    private fun withServerFields(holder: JsonObject): JsonObject {
        val edits = holder["contextEdits"] as? JsonObject
        if (edits?.get("type") != SERVER) return holder
        // The fields go first, so a key the record has keeps its value (a v1 record has neither).
        val added = mapOf("clearAtLeastTokens" to JsonNull, "compactInstructions" to JsonNull) + edits
        return JsonObject(holder + ("contextEdits" to JsonObject(added)))
    }

    private val REQUEST_HEADER = JsonPrimitive("request/header")
    private val SIDE_REQUEST = JsonPrimitive("side/request")
    private val SERVER = JsonPrimitive("server")
}
