package com.hualuo.engine.toolcalls

import com.hualuo.engine.memory.MemoryStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 记忆工具族（M4 第二刀，语义对齐旧 Agora MemoryToolProvider）：
 * 六件——list / read / create / edit / delete / update_active_memory。
 *
 * **给 [store] 才存在**：不注入 MemoryStore 一件都不注册（对齐写类与 PR 族的闸门纪律）。
 * 读写权限的颗粒开关（旧仓 ctx.accessSavedMemories / accessActiveMemory）由调用方
 * 决定要不要注入 store 来表达——引擎件不重复造权限层。
 *
 * 零脱敏：读什么回什么，写什么存什么，原文进出。
 */
object MemoryTool {

    fun register(registry: ToolRegistry, store: MemoryStore?) {
        if (store == null) return

        registry.register(
            ToolSpec(
                name = "list_memory_files",
                description = "列出记忆库里的全部文件（文件名 + 一句话描述）。记忆库是跨对话长期有效的 markdown 文件集。",
                parametersJson = """{"type":"object","properties":{},"required":[]}""",
            )
        ) {
            val files = store.listFiles()
            if (files.isEmpty()) {
                "记忆库是空的（一个文件都没有）。可以用 create_memory_file 建第一份。"
            } else {
                files.joinToString("\n") { f ->
                    if (f.description.isBlank()) "- ${f.name}" else "- ${f.name}：${f.description}"
                }
            }
        }

        registry.register(
            ToolSpec(
                name = "read_memory_file",
                description = "读一份记忆文件的完整原文（或一次读多份，给 names 数组）。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"要读的文件名，如 notes.md"},"names":{"type":"array","items":{"type":"string"},"description":"一次读多份（与 name 二选一）"}},"required":[]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val names = (args["names"] as? kotlinx.serialization.json.JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                ?.takeIf { it.isNotEmpty() }
                ?: listOfNotNull((args["name"] as? JsonPrimitive)?.contentOrNull)
            if (names.isEmpty()) return@register "name 和 names 至少给一个。终态：没读。"
            names.joinToString("\n\n") { n ->
                val text = store.readFile(n)
                "===== $n =====\n$text"
            }
        }

        registry.register(
            ToolSpec(
                name = "create_memory_file",
                description = "在记忆库里建一份新 markdown 文件（已存在的名字会被拒，改内容用 edit_memory_file）。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"文件名（.md 可带可不带）"},"content":{"type":"string","description":"markdown 全文"},"description":{"type":"string","description":"一句话描述这份文件装什么（列表时给人看）"}},"required":["name","content"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val name = reqStr(args, "name", "文件名")
            val content = reqStr(args, "content", "markdown 全文")
            val description = (args["description"] as? JsonPrimitive)?.contentOrNull ?: ""
            store.createFile(name, content, description)
        }

        registry.register(
            ToolSpec(
                name = "edit_memory_file",
                description = "编辑记忆文件的一处：operation=replace（整文件换新内容）/patch（old_string 唯一命中处替换，删就给空 new_string）/rename（换文件名，描述跟过去）/describe（只改描述）。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"要编辑的文件名"},"operation":{"type":"string","enum":["replace","patch","rename","describe"],"description":"编辑方式"},"content":{"type":"string","description":"replace 的新全文"},"old_string":{"type":"string","description":"patch 的定位串（必须在全文里唯一）"},"new_string":{"type":"string","description":"patch 的替换串（删除就给空串）"},"new_name":{"type":"string","description":"rename 的新文件名"},"description":{"type":"string","description":"describe 的新描述（清空给空串）"}},"required":["name","operation"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val name = reqStr(args, "name", "文件名")
            val operation = reqStr(args, "operation", "replace/patch/rename/describe")
            store.editFile(
                name = name,
                operation = operation,
                content = (args["content"] as? JsonPrimitive)?.contentOrNull,
                oldString = (args["old_string"] as? JsonPrimitive)?.contentOrNull,
                newString = (args["new_string"] as? JsonPrimitive)?.contentOrNull,
                newName = (args["new_name"] as? JsonPrimitive)?.contentOrNull,
                description = (args["description"] as? JsonPrimitive)?.contentOrNull,
            )
        }

        registry.register(
            ToolSpec(
                name = "delete_memory_file",
                description = "删掉一份记忆文件（连带它的描述）。删了就没了，删前先 read 确认内容。",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"要删的文件名"}},"required":["name"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            store.deleteFile(reqStr(args, "name", "文件名"))
        }

        registry.register(
            ToolSpec(
                name = "update_active_memory",
                description = "改活动记忆（进每次生成上下文的单文件，适合放用户的长期偏好与事实）：mode=replace（整份换）/append（接在末尾）/prepend（放到开头）/patch（old_string 唯一命中处替换）。",
                parametersJson = """{"type":"object","properties":{"mode":{"type":"string","enum":["replace","append","prepend","patch"],"description":"改法"},"content":{"type":"string","description":"replace/append/prepend 的新内容"},"old_string":{"type":"string","description":"patch 的定位串（必须唯一）"},"new_string":{"type":"string","description":"patch 的替换串"}},"required":["mode"]}""",
            )
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val mode = reqStr(args, "mode", "replace/append/prepend/patch")
            store.writeActiveMemory(
                mode = mode,
                content = (args["content"] as? JsonPrimitive)?.contentOrNull ?: "",
                oldString = (args["old_string"] as? JsonPrimitive)?.contentOrNull,
                newString = (args["new_string"] as? JsonPrimitive)?.contentOrNull,
            )
        }
    }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun reqStr(args: JsonObject, key: String, what: String): String =
        (args[key] as? JsonPrimitive)?.contentOrNull?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("缺参数 $key（$what）")
}
