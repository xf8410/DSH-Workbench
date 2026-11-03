package io.github.jackwener.dsh.core.workbench

import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.int
import io.github.jackwener.dsh.core.capability.str
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.serialization.json.JsonObject
import javax.inject.Inject
import javax.inject.Singleton

/** Model-facing access to the push cache; no SO code and no game-control endpoint. */
@Singleton
class UmaPushTools @Inject constructor(
    private val store: UmaFrameStore,
    private val receiver: UmaPushReceiver,
) : ToolSet {
    override val tools: List<Tool> = listOf(
        object : Tool("uma_push_status", ToolGroup.System, Risk.read, false,
            "Show the loopback SO push receiver state and latest accepted frame metadata.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult =
                ToolResult.text(receiver.status().toString())
        },
        object : Tool("uma_get_snapshot", ToolGroup.System, Risk.read, false,
            "Read the latest summary JSON received from the game SO on 127.0.0.1:18766. Optionally select one top-level field such as stats, trainings, ramen, matsuri or ai.", {
                string("field", "Optional top-level JSON field; omit for the complete snapshot")
                int("max_chars", "Output limit, 1000-100000; default 32000")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                val field = args.str("field")?.trim()?.takeIf { it.isNotEmpty() }
                val frame = store.latest() ?: return ToolResult.error("no_snapshot",
                    "No SO push has been received yet. Start the game with its existing push plugin enabled; DSH does not install or inject that plugin.")
                if (field != null && field !in frame.current) return ToolResult.error("invalid_args",
                    "Field '$field' is absent. Available top-level fields: ${frame.current.keys.joinToString()}")
                return bounded(store.snapshot(field).toString(), args.int("max_chars"))
            }
        },
        object : Tool("uma_get_changes", ToolGroup.System, Risk.read, false,
            "Read generic top-level before/after changes between the latest two SO summary pushes.", {
                int("max_chars", "Output limit, 1000-100000; default 32000")
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult {
                store.latest() ?: return ToolResult.error("no_snapshot", "No SO push has been received yet.")
                return bounded(store.changes().toString(), args.int("max_chars"))
            }
        },
    )

    private fun bounded(json: String, requested: Int?): ToolResult {
        val limit = (requested ?: DEFAULT_CHARS).coerceIn(1_000, 100_000)
        if (json.length <= limit) return ToolResult.text(json)
        return ToolResult.text(json.take(limit) + "\n… truncated ${json.length - limit} chars; select a top-level field or request a focused diff.")
    }

    private companion object { const val DEFAULT_CHARS = 32_000 }
}
