package com.hualuo.engine.automation

import java.io.File
import java.util.TimeZone

/**
 * 后台任务账本（M4 第四刀，引擎件纯 JVM）：一行一个任务的 jsonl 账本，
 * 与会话仓（SessionStore）同款落盘纪律。
 *
 * 一条任务 = 名字 + 每次跑的提示词 + 五字段 cron + 可选模型 + 启停位 + 账目时间戳。
 *
 * 家规（对齐旧 Agora TaskManager 的可移植子集）：
 *  - **cron 建时验**：坏表达式在建任务那一刻拒，不许留一条永远不响的哑任务；
 *  - **nextRunMs 建时就算**：账本里永远带着下一次触发时刻（可解析时），
 *    调度侧只管对表，不用自己会算；
 *  - **删除按 id 或唯一名**：重名就要求用 id（不许猜）；
 *  - 全操作 @Synchronized + 整文件重写（原子换名落盘）——账本小，整写比补丁稳；
 *  - 账本损坏（半行坏 JSON）降级：坏行单独数出来报，不许拖死整个库。
 *
 * Android 侧调度（AlarmReceiver/WakeLock/到点真跑生成）不在本件——
 * 引擎件只管账与时刻，到点叫醒是 app 生态的事。
 */
class TaskStore(
    private val file: File,
    private val zone: TimeZone = TimeZone.getDefault(),
) {

    /** 一条后台任务。nextRunMs=0 表示 cron 解析得出但没有可算的下一次（8 年无匹配）。 */
    data class TaskRecord(
        val id: String,
        val name: String,
        val prompt: String,
        val cron: String,
        val model: String,
        val enabled: Boolean,
        val createdAtMs: Long,
        val lastRunMs: Long,
        val nextRunMs: Long,
    )

    /** 整本回执：好行 + 坏行数（不许装作没看见）。 */
    data class TaskListing(val tasks: List<TaskRecord>, val unreadable: Int)

    private var seq: Long = 0

    @Synchronized
    fun create(name: String, prompt: String, cron: String, model: String, nowMs: Long): TaskRecord {
        require(name.isNotBlank()) { "任务名不许空" }
        require(prompt.isNotBlank()) { "提示词不许空（任务每次要跑的就是它）" }
        val parsed = CronExpression.parse(cron)
            ?: throw IllegalArgumentException("cron 不合法（$cron）：要五字段「分 时 日 月 周」，如「0 9 * * *」每天九点")
        val id = "t$nowMs-${++seq}"
        val record = TaskRecord(
            id = id,
            name = name,
            prompt = prompt,
            cron = cron,
            model = model,
            enabled = true,
            createdAtMs = nowMs,
            lastRunMs = 0L,
            nextRunMs = parsed.next(nowMs, zone) ?: 0L,
        )
        writeAll(readAll().tasks + record)
        return record
    }

    @Synchronized
    fun list(): TaskListing {
        val (tasks, bad) = readAll()
        return TaskListing(tasks.sortedByDescending { it.createdAtMs }, bad)
    }

    @Synchronized
    fun setEnabled(idOrName: String, enabled: Boolean, nowMs: Long): TaskRecord {
        val (record, index) = find(idOrName)
        var updated = record.copy(enabled = enabled)
        updatedNextIfEnabled(updated, nowMs)?.let { updated = it }
        val all = readAll().tasks.toMutableList()
        all[index] = updated
        writeAll(all)
        return updated
    }

    @Synchronized
    fun delete(idOrName: String): TaskRecord {
        val (record, index) = find(idOrName)
        val all = readAll().tasks.toMutableList()
        all.removeAt(index)
        writeAll(all)
        return record
    }

    /** 跑完一趟记账：lastRun=now，nextRun 重算（任务还开着的话）。 */
    @Synchronized
    fun recordRun(id: String, nowMs: Long): TaskRecord? {
        val all = readAll().tasks.toMutableList()
        val index = all.indexOfFirst { it.id == id }
        if (index < 0) return null
        var updated = all[index].copy(lastRunMs = nowMs)
        updatedNextIfEnabled(updated, nowMs)?.let { updated = it }
        all[index] = updated
        writeAll(all)
        return updated
    }

    // ---------- 内部件 ----------

    private fun find(idOrName: String): Pair<TaskRecord, Int> {
        val all = readAll().tasks
        all.forEachIndexed { i, t ->
            if (t.id == idOrName) return t to i
        }
        val byName = all.filter { it.name == idOrName }
        require(byName.size == 1) {
            if (byName.isEmpty()) "没有这条任务：$idOrName"
            else "名字「$idOrName」有 ${byName.size} 条重名——用 id 指名（list_tasks 可查）"
        }
        return byName[0] to all.indexOfFirst { it.id == byName[0].id }
    }

    private fun updatedNextIfEnabled(record: TaskRecord, nowMs: Long): TaskRecord? {
        if (!record.enabled) return null
        val parsed = CronExpression.parse(record.cron) ?: return null
        return record.copy(nextRunMs = parsed.next(nowMs, zone) ?: 0L)
    }

    private fun readAll(): TaskListing {
        if (!file.exists()) return TaskListing(emptyList(), 0)
        val tasks = mutableListOf<TaskRecord>()
        var bad = 0
        file.readLines().forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEach
            parseLine(trimmed)?.let { tasks.add(it) } ?: run { bad++ }
        }
        return TaskListing(tasks, bad)
    }

    private fun writeAll(tasks: List<TaskRecord>) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.bufferedWriter().use { w ->
            tasks.forEach { w.write(lineOf(it)); w.write("\n") }
        }
        if (!tmp.renameTo(file)) {
            // 换名失败（被占用之类的怪事）：退回直写，宁可慢不许丢
            file.writeText(tasks.joinToString("\n") { lineOf(it) } + if (tasks.isEmpty()) "" else "\n")
            tmp.delete()
        }
    }

    companion object {
        /** 解析一行为记录；字段名对旧 Agora TaskEntity 的可移植子集。 */
        internal fun parseLine(line: String): TaskRecord? = try {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(line) as? kotlinx.serialization.json.JsonObject ?: return null
            fun s(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""
            fun l(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L
            fun b(k: String) = (obj[k] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
            val id = s("id")
            if (id.isEmpty()) null
            else TaskRecord(id, s("name"), s("prompt"), s("cron"), s("model"), b("enabled"), l("created_at_ms"), l("last_run_ms"), l("next_run_ms"))
        } catch (e: kotlinx.serialization.SerializationException) {
            null
        }

        internal fun lineOf(t: TaskRecord): String {
            fun q(v: String) = kotlinx.serialization.json.JsonPrimitive(v).toString()
            fun n(v: Long) = v.toString()
            return """{"id":${q(t.id)},"name":${q(t.name)},"prompt":${q(t.prompt)},"cron":${q(t.cron)},"model":${q(t.model)},"enabled":${t.enabled},"created_at_ms":${n(t.createdAtMs)},"last_run_ms":${n(t.lastRunMs)},"next_run_ms":${n(t.nextRunMs)}}"""
        }
    }
}
