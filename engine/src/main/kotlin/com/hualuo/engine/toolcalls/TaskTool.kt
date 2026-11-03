package com.hualuo.engine.toolcalls

import com.hualuo.engine.automation.TaskStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * 后台任务工具族（M4 第四刀，语义对齐旧 Agora AutomationToolProvider 的任务半边）：
 * create / list / delete / enable / disable 五件。
 *
 * 对话内 Loop（start_loop/stop_loop）不在本刀——它要吃生成域（ChatRuntime），
 * 属 app 生态刀；引擎件先把任务账本与 cron 算术做扎实。
 *
 * **给 [store] 才存在**（闸门纪律同写类/PR/记忆/检索族）。
 * 建任务即验 cron（坏表达式当场拒，不留哑任务）；删除与启停按 id 或唯一名。
 * 零脱敏：账目原文进出。
 */
object TaskTool {

    fun register(registry: ToolRegistry, store: TaskStore?) {
        if (store == null) return

        registry.register(
            ToolSpec(
                name = "create_task",
                description = "建一条后台任务（默认启用）。五字段 cron「分 时 日 月 周」如「0 9 * * *」每天九点。只在用户明确要建时用。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"简短任务名"},"prompt":{"type":"string","description":"每次触发跑的完整提示词"},"cron":{"type":"string","description":"五字段 cron：分 时 日 月 周（0-59 0-23 1-31 1-12 0-6，0=周日）"},"model":{"type":"string","description":"可选：跑这条任务用的模型 id；缺省用应用默认"}},"required":["name","prompt","cron"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val record = store.create(
                name = reqStr(args, "name", "简短任务名"),
                prompt = reqStr(args, "prompt", "每次触发跑的完整提示词"),
                cron = reqStr(args, "cron", "五字段 cron「分 时 日 月 周」"),
                model = (args["model"] as? JsonPrimitive)?.contentOrNull ?: "",
                nowMs = System.currentTimeMillis(),
            )
            "已建任务 ${record.id}（${record.name}）：cron「${record.cron}」" +
                "，下次 ${fmtTime(record.nextRunMs)}。终态：任务已建并启用。"
        }

        registry.register(
            ToolSpec(
                name = "list_tasks",
                description = "列出全部后台任务（id、名字、cron、启停位、下次触发时刻）。",
                parametersJson = """{"type":"object","properties":{},"required":[]}""",
            )
        ) {
            val listing = store.list()
            if (listing.tasks.isEmpty() && listing.unreadable == 0) {
                "一条任务都没有。可以用 create_task 建第一条。"
            } else {
                buildString {
                    listing.tasks.forEach { t ->
                        append(t.id).append("  ").append(if (t.enabled) "[启用]" else "[停用]")
                        append("  ").append(t.name).append("  cron「").append(t.cron).append("」")
                        append("  下次 ").append(fmtTime(t.nextRunMs))
                        if (t.lastRunMs > 0) append("（上次 ").append(fmtTime(t.lastRunMs)).append("）")
                        append('\n')
                    }
                    if (listing.unreadable > 0) append("（另有 ").append(listing.unreadable).append(" 行坏账读不出来）")
                }.trim()
            }
        }

        registry.register(
            ToolSpec(
                name = "delete_task",
                description = "按精确 id 或唯一名删一条任务。这是破坏性操作，只在用户明确要求时用。",
                parametersJson = """{"type":"object","properties":{"id_or_name":{"type":"string","description":"任务 id 或唯一任务名"}},"required":["id_or_name"]}""",
            )
        ) { argumentsJson ->
            val removed = store.delete(reqStr(argsOf(argumentsJson), "id_or_name", "任务 id 或唯一任务名"))
            "已删任务 ${removed.id}（${removed.name}）。终态：任务删除。"
        }

        registry.register(
            ToolSpec(
                name = "enable_task",
                description = "启用一条停用的任务（按 id 或唯一名）。",
                parametersJson = """{"type":"object","properties":{"id_or_name":{"type":"string","description":"任务 id 或唯一任务名"}},"required":["id_or_name"]}""",
            )
        ) { argumentsJson ->
            val t = store.setEnabled(reqStr(argsOf(argumentsJson), "id_or_name", "任务 id 或唯一任务名"), true, System.currentTimeMillis())
            "已启用 ${t.id}（${t.name}），下次 ${fmtTime(t.nextRunMs)}。"
        }

        registry.register(
            ToolSpec(
                name = "disable_task",
                description = "停用一条任务（按 id 或唯一名）。停用不是删除——账还在，可再启用。",
                parametersJson = """{"type":"object","properties":{"id_or_name":{"type":"string","description":"任务 id 或唯一任务名"}},"required":["id_or_name"]}""",
            )
        ) { argumentsJson ->
            val t = store.setEnabled(reqStr(argsOf(argumentsJson), "id_or_name", "任务 id 或唯一任务名"), false, System.currentTimeMillis())
            "已停用 ${t.id}（${t.name}）。"
        }
    }

    // ---------- 内部件 ----------

    private fun fmtTime(ms: Long): String =
        if (ms <= 0) "无（cron 八年地平线内没有触发时刻）"
        else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(ms)) + "（设备时区）"

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun reqStr(args: JsonObject, key: String, what: String): String =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺参数 $key（$what）")
}
