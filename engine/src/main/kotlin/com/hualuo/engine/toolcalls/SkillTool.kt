package com.hualuo.engine.toolcalls

import com.hualuo.engine.memory.MemoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 技能文件工具族（M4 第六刀，语义对齐旧 Agora SkillToolProvider/SkillManager）：
 * 五件——list / read / create / edit / delete skill file。
 *
 * **复用 [MemoryStore]**：旧仓 SkillManager 与 MemoryManager 同形
 * （skill_db 目录 + skill_meta.json + 同款 replace/patch/rename/describe 编辑语义、
 * 同款防逃逸与唯一匹配家规），不抄第二份实现——技能库就是「没有活动记忆文件的记忆库」。
 *
 * [catalog] 给 app 侧拼系统提示词用（对齐旧仓 GenerationRequestBuilder 的
 * available_skills 注入）：只报名字与一句话描述，正文让模型按需 read。
 *
 * **给 [store] 才存在**（闸门纪律同全族）；零脱敏：技能原文进出。
 */
object SkillTool {

    fun register(registry: ToolRegistry, store: MemoryStore?) {
        if (store == null) return

        registry.register(
            ToolSpec(
                name = "list_skill_files",
                description = "列出技能库里的全部文件（文件名 + 一句话描述）。技能是跨对话可复用的操作指南 markdown。",
                parametersJson = """{"type":"object","properties":{},"required":[]}""",
            )
        ) {
            val files = store.listFiles()
            if (files.isEmpty()) {
                """{"type":"list_skill_files","count":0,"note":"技能库是空的，可以用 create_skill_file 建第一份"}"""
            } else {
                val rows = files.joinToString(",") { f ->
                    """{"name":${JsonPrimitive(f.name)},"description":${JsonPrimitive(f.description)}}"""
                }
                """{"type":"list_skill_files","count":${files.size},"files":[$rows]}"""
            }
        }

        registry.register(
            ToolSpec(
                name = "read_skill_file",
                description = "读一份技能文件的全文（配合 list_skill_files 的名字用）。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"技能文件名"}},"required":["name"]}""",
            )
        ) { argumentsJson ->
            store.readFile(reqStr(argsOf(argumentsJson), "name", "技能文件名"))
        }

        registry.register(
            ToolSpec(
                name = "create_skill_file",
                description = "在技能库里建一份新技能（markdown 指南；已存在的名字会被拒，改内容用 edit_skill_file）。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"技能文件名"},"content":{"type":"string","description":"markdown 全文"},"description":{"type":"string","description":"一句话说明这份技能是什么（可选）"}},"required":["name","content"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            store.createFile(
                name = reqStr(args, "name", "技能文件名"),
                content = reqStr(args, "content", "markdown 全文"),
                description = (args["description"] as? JsonPrimitive)?.contentOrNull ?: "",
            )
        }

        registry.register(
            ToolSpec(
                name = "edit_skill_file",
                description = "编辑一份技能文件。operation 只认 replace/patch/rename/describe。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"技能文件名"},"operation":{"type":"string","description":"replace 整文件换 | patch 唯一命中替换（带 old_string/new_string） | rename 改名（带 new_name） | describe 改描述"},"content":{"type":"string","description":"replace 的新全文"},"old_string":{"type":"string","description":"patch 要唯一命中的原文"},"new_string":{"type":"string","description":"patch 的替换串（空串=删掉命中段）"},"new_name":{"type":"string","description":"rename 的新文件名"},"description":{"type":"string","description":"describe 的新描述"}},"required":["name","operation"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            store.editFile(
                name = reqStr(args, "name", "技能文件名"),
                operation = reqStr(args, "operation", "replace/patch/rename/describe"),
                content = (args["content"] as? JsonPrimitive)?.contentOrNull,
                oldString = (args["old_string"] as? JsonPrimitive)?.contentOrNull,
                newString = (args["new_string"] as? JsonPrimitive)?.contentOrNull,
                newName = (args["new_name"] as? JsonPrimitive)?.contentOrNull,
                description = (args["description"] as? JsonPrimitive)?.contentOrNull,
            )
        }

        registry.register(
            ToolSpec(
                name = "delete_skill_file",
                description = "删一份技能文件（不可恢复，用户明确要删才动）。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"技能文件名"}},"required":["name"]}""",
            )
        ) { argumentsJson ->
            store.deleteFile(reqStr(argsOf(argumentsJson), "name", "技能文件名"))
        }
    }

    /**
     * 技能目录（进系统提示词的一段）：空库回空串（不占提示词）；只报名字+描述，
     * 原文让模型按需 read——对齐旧仓 catalog() 的形状。
     */
    fun catalog(store: MemoryStore): String {
        val files = store.listFiles()
        if (files.isEmpty()) return ""
        return buildString {
            appendLine("<available_skills>")
            appendLine("Use the skill file tools to read a relevant skill before following it. Only names and descriptions are preloaded.")
            files.forEach { f ->
                append("- ")
                append(f.name)
                if (f.description.isNotBlank()) {
                    append(": ")
                    append(f.description.replace('\n', ' ').trim())
                }
                appendLine()
            }
            append("</available_skills>")
        }
    }

    // ---------- 内部件 ----------

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun reqStr(args: JsonObject, key: String, what: String): String =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺参数 $key（$what）")
}
