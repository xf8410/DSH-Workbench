package io.github.jackwener.dsh.core.workbench

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** One accepted SO push and its top-level JSON diff. */
data class UmaFrame(
    val sequence: Long,
    val receivedAtMs: Long,
    val current: JsonObject,
    val previous: JsonObject?,
    val changes: JsonObject,
) {
    val changedFields: List<String> = changes.keys.toList()
}

/**
 * Process-local latest-frame cache. The SO remains the source of truth; DSH only parses,
 * caches and diffs the summary JSON posted to its loopback receiver.
 */
@Singleton
class UmaFrameStore @Inject constructor() {
    private var clock: () -> Long = { System.currentTimeMillis() }
    @Volatile private var latest: UmaFrame? = null

    internal constructor(clock: () -> Long) : this() {
        this.clock = clock
    }

    fun latest(): UmaFrame? = latest

    /** Accepts only a JSON object. A bad frame never replaces the last good one. */
    fun accept(raw: String): UmaFrame {
        val element = Json.parseToJsonElement(raw)
        val current = element as? JsonObject
            ?: throw IllegalArgumentException("SO summary must be a JSON object")
        synchronized(this) {
            val old = latest
            val frame = UmaFrame(
                sequence = (old?.sequence ?: 0L) + 1L,
                receivedAtMs = clock(),
                current = current,
                previous = old?.current,
                changes = diff(old?.current, current),
            )
            latest = frame
            return frame
        }
    }

    fun snapshot(field: String? = null): JsonObject? {
        val frame = latest ?: return null
        val data: JsonElement = if (field == null) frame.current else frame.current[field] ?: JsonNull
        return buildJsonObject {
            put("sequence", frame.sequence)
            put("receivedAtMs", frame.receivedAtMs)
            if (field != null) put("field", field)
            put("data", data)
        }
    }

    fun changes(): JsonObject? {
        val frame = latest ?: return null
        return buildJsonObject {
            put("sequence", frame.sequence)
            put("receivedAtMs", frame.receivedAtMs)
            if (frame.previous != null) put("previousSequence", frame.sequence - 1L)
            else put("previousSequence", JsonNull)
            putJsonArray("changedFields") { frame.changedFields.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } }
            put("changes", frame.changes)
        }
    }

    /** Generic top-level diff, so stats/trainings/ramen/matsuri/ai and future fields share one path. */
    private fun diff(before: JsonObject?, after: JsonObject): JsonObject {
        if (before == null) return buildJsonObject {
            after.forEach { (key, value) -> putJsonObject(key) { put("before", JsonNull); put("after", value) } }
        }
        val keys = LinkedHashSet<String>().apply { addAll(after.keys); addAll(before.keys) }
        return buildJsonObject {
            keys.forEach { key ->
                val oldValue = before[key]
                val newValue = after[key]
                if (oldValue != newValue) putJsonObject(key) {
                    put("before", oldValue ?: JsonNull)
                    put("after", newValue ?: JsonNull)
                }
            }
        }
    }
}
