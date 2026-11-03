package com.hualuo.engine.memory

import java.io.File
import java.io.IOException

/**
 * 记忆库存储件（M4 第二刀，语义对齐旧 Agora MemoryManager）：
 *  - 目录：memory_db 下的 .md 文件 + memory_meta.json（名字到一句话描述）+ active_memory.md（活动记忆单文件）；
 *  - **防逃逸**：文件名里的斜杠/反斜杠一律换下划线，canonical 之后必须还落在记忆目录里——`../` 写不出去；
 *  - **patch 唯一匹配**：old_string 必须恰好命中一次，零次或多次都拒（防改错地方）；
 *  - 全部操作 @Synchronized：工具线程与界面同时动记忆库不打架；
 *  - 目录由调用方注入（引擎件不碰 Android Context）。
 *
 * 零脱敏纪律：读什么写什么，原文进出，不做任何过滤、截断与替换。
 */
class MemoryStore(
    memoryDir: File,
    private val activeFile: File? = null,
) {
    private val memoryDir: File = memoryDir.apply { mkdirs() }
    private val metaFile: File = File(memoryDir, "memory_meta.json")

    /** 一条记忆的账：文件名 + 一句话描述（描述可空）。 */
    data class MemoryFileInfo(val name: String, val description: String)

    // ---------- 活动记忆（单文件，进每次生成的上下文） ----------
    // activeFile 可空（M4 第六刀）：技能库复用本件但**没有**活动记忆概念——
    // 不给文件的库，读回空串、写直接拒，不许悄悄落到别处。

    @Synchronized
    fun readActiveMemory(): String =
        activeFile?.takeIf { it.exists() }?.readText() ?: ""

    @Synchronized
    fun writeActiveMemory(
        mode: String,
        content: String,
        oldString: String? = null,
        newString: String? = null,
    ): String {
        requireNotNull(activeFile) { "这个库没配活动记忆文件（技能库没有活动记忆概念）" }
        val existing = readActiveMemory()
        val updated = when (mode) {
            "replace" -> content
            "append" -> if (existing.isEmpty()) content else existing + "\n" + content
            "prepend" -> if (existing.isEmpty()) content else content + "\n" + existing
            "patch" -> {
                require(!oldString.isNullOrEmpty()) { "patch 模式必须带 old_string" }
                val hits = existing.countOccurrences(oldString)
                require(hits == 1) {
                    if (hits == 0) "old_string 在活动记忆里一次都没命中"
                    else "old_string 命中 $hits 次，必须唯一"
                }
                existing.replace(oldString, newString.orEmpty())
            }
            else -> throw IllegalArgumentException("mode 只认 replace/append/prepend/patch，现在是：$mode")
        }
        activeFile.writeText(updated)
        return "活动记忆已更新（$mode，${updated.length} 字符）"
    }

    // ---------- 记忆库（多文件 + 描述账） ----------

    @Synchronized
    fun listFiles(): List<MemoryFileInfo> {
        val meta = readMeta()
        return memoryDir.listFiles()
            ?.filter { it.isFile && it.extension == "md" }
            ?.map { MemoryFileInfo(it.name, meta[it.name].orEmpty()) }
            ?.sortedBy { it.name }
            .orEmpty()
    }

    @Synchronized
    fun readFile(name: String): String {
        val file = resolveFile(name)
        require(file.exists()) { "记忆文件不存在：${file.name}" }
        return file.readText()
    }

    @Synchronized
    fun createFile(name: String, content: String, description: String): String {
        val file = resolveFile(name)
        require(!file.exists()) { "记忆文件已存在：${file.name}" }
        file.writeText(content)
        if (description.isNotBlank()) writeMeta(readMeta() + (file.name to description))
        return "已建 ${file.name}"
    }

    /**
     * 编辑一件：operation 决定动哪格。
     *  - replace：整文件换新内容（content 必填）；
     *  - patch：old_string 唯一命中换 new_string（删就给空串）；
     *  - rename：换文件名（new_name 必填，目标不许已存在，描述跟过去）；
     *  - describe：只改描述。
     */
    @Synchronized
    fun editFile(
        name: String,
        operation: String,
        content: String? = null,
        oldString: String? = null,
        newString: String? = null,
        newName: String? = null,
        description: String? = null,
    ): String {
        val file = resolveFile(name)
        require(file.exists()) { "记忆文件不存在：${file.name}" }
        when (operation) {
            "replace" -> {
                requireNotNull(content) { "replace 必须带 content" }
                file.writeText(content)
            }
            "patch" -> {
                require(!oldString.isNullOrEmpty()) { "patch 必须带非空 old_string" }
                val existing = file.readText()
                val hits = existing.countOccurrences(oldString)
                require(hits == 1) {
                    if (hits == 0) "old_string 在 ${file.name} 里一次都没命中"
                    else "old_string 命中 $hits 次，必须唯一"
                }
                file.writeText(existing.replace(oldString, newString.orEmpty()))
            }
            "rename" -> {
                require(!newName.isNullOrBlank()) { "rename 必须带 new_name" }
                val target = resolveFile(newName)
                require(target != file) { "新名字与旧名字一样：$newName" }
                require(!target.exists()) { "目标记忆文件已存在：${target.name}" }
                if (!file.renameTo(target)) throw IOException("改名失败：${file.name} -> ${target.name}")
                val meta = readMeta().toMutableMap()
                meta[target.name] = meta.remove(file.name).orEmpty()
                writeMeta(meta)
            }
            "describe" -> {
                requireNotNull(description) { "describe 必须带 description（清空描述就给空串）" }
                val meta = readMeta().toMutableMap()
                if (description.isBlank()) meta.remove(file.name) else meta[file.name] = description
                writeMeta(meta)
            }
            else -> throw IllegalArgumentException("operation 只认 replace/patch/rename/describe，现在是：$operation")
        }
        return "已编辑 ${file.name}（$operation）"
    }

    @Synchronized
    fun deleteFile(name: String): String {
        val file = resolveFile(name)
        require(file.exists()) { "记忆文件不存在：${file.name}" }
        if (!file.delete()) throw IOException("删除失败：${file.name}")
        val meta = readMeta().toMutableMap()
        meta.remove(file.name)
        writeMeta(meta)
        return "已删 ${file.name}"
    }

    // ---------- 内部件 ----------

    /** 防逃逸：斜杠反斜杠换下划线，canonical 后必须还是记忆目录的直接子文件。 */
    private fun resolveFile(name: String): File {
        val sanitized = name.replace(Regex("[/\\\\]"), "_")
        if (sanitized.isBlank() || sanitized == "." || sanitized == "..") {
            throw IllegalArgumentException("文件名不合法：$name")
        }
        val file = File(memoryDir, if (sanitized.endsWith(".md")) sanitized else "$sanitized.md")
        val canonicalDir = memoryDir.canonicalFile
        val canonicalFile = file.canonicalFile
        require(canonicalFile.parentFile == canonicalDir) { "文件名不合法：$name" }
        return canonicalFile
    }

    /** meta 是平铺 JSON（{文件名:描述}）；坏文件当空账起步，不让半截 JSON 拖死整个库。 */
    private fun readMeta(): Map<String, String> {
        if (!metaFile.exists()) return emptyMap()
        val text = metaFile.readText().trim()
        if (text.isEmpty()) return emptyMap()
        return try {
            kotlinx.serialization.json.Json.parseToJsonElement(text).let { root ->
                (root as? kotlinx.serialization.json.JsonObject)?.mapValues { (_, v) ->
                    (v as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
                } ?: emptyMap()
            }
        } catch (e: kotlinx.serialization.SerializationException) {
            emptyMap()
        }
    }

    private fun writeMeta(values: Map<String, String>) {
        val sb = StringBuilder("{")
        values.entries.sortedBy { it.key }.forEachIndexed { i, (k, v) ->
            if (i > 0) sb.append(',')
            sb.append(kotlinx.serialization.json.JsonPrimitive(k))
                .append(':')
                .append(kotlinx.serialization.json.JsonPrimitive(v))
        }
        sb.append('}')
        metaFile.writeText(sb.toString())
    }
}

private fun String.countOccurrences(needle: String): Int {
    if (needle.isEmpty()) return 0
    var count = 0
    var idx = indexOf(needle)
    while (idx >= 0) {
        count++
        idx = indexOf(needle, idx + needle.length)
    }
    return count
}
