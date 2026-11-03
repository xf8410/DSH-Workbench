package com.hualuo.engine.backup

import com.hualuo.engine.io.streamingCopy
import com.hualuo.engine.io.streamingCopyOfText
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.time.Instant
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put

/** 备份包的版本身份：manifest 里 format 对不上就不是本家的包，一个字节都不许导进来。 */
const val BACKUP_FORMAT = "hualuo-backup"
const val BACKUP_VERSION = 1

const val MANIFEST_ENTRY = "manifest.json"
const val SETTINGS_ENTRY = "settings.properties"
const val SESSIONS_PREFIX = "sessions/"

/** 与 SettingsStore 的 1MiB 上限对齐；manifest 很小，单独给个更紧的帽。 */
const val MAX_SETTINGS_CHARS = 1 shl 20
const val MAX_MANIFEST_CHARS = 64 * 1024

/** 认不出的条目最多列 50 个名字，防一个恶意包把报错撑成炸弹。 */
const val MAX_SKIPPED_LISTED = 50

/** 一份待打包的会话：id 是会话仓里的文件名（去 .jsonl），open 现场开流（不提前读进内存）。 */
data class BackupSessionSource(val id: String, val open: () -> InputStream)

data class BackupManifest(
    val format: String,
    val version: Int,
    val createdAt: String,
    val sessionCount: Int,
    val appVersion: String?,
)

/** 读一个包的回执：manifest 认不出 = 不是本家的包；认不出的条目列名照报，不悄悄丢。 */
data class BackupReadResult(
    val manifest: BackupManifest?,
    val settingsText: String?,
    val sessionsHandled: Int,
    val skippedEntries: List<String>,
)

/**
 * 会话 id 的门：只许字母数字点下划线连字符，拒绝路径意外（../ 、斜杠、超长）。
 * id 直接拼 zip 条目名和本地文件名，这里不设防就没有第二道闸。
 */
fun isSafeSessionId(id: String): Boolean {
    if (id.isEmpty() || id.length > 128) return false
    if (id == "." || id == "..") return false
    return id.all {
        it in 'a'..'z' || it in 'A'..'Z' || it in '0'..'9' || it == '-' || it == '_' || it == '.'
    }
}

/**
 * 写一个备份包（zip，纯 JVM，流式：设置与会话都是边读边写，永不整文件进内存）。
 * 条目顺序钉死 manifest 最前——读包的一方必须先看到身份，才轮得到决定收不收会话。
 *
 * [onProgress]（0.6.0）：每写完一份会话回调一次（已写份数, 总份数）。大会计备份
 * 不能是黑盒——界面拿它画「已打包 N/M 份会话」。默认空实现，老调用方零改动。
 */
fun writeBackup(
    out: OutputStream,
    settingsText: String,
    sessions: List<BackupSessionSource>,
    appVersion: String,
    onProgress: (doneSessions: Int, totalSessions: Int) -> Unit = { _, _ -> },
) {
    val bad = sessions.firstOrNull { !isSafeSessionId(it.id) }
    if (bad != null) {
        throw IllegalArgumentException("会话 id 不安全，拒绝写进包：${bad.id.take(24)}…")
    }
    ZipOutputStream(out).use { zip ->
        val manifest = buildJsonObject {
            put("format", BACKUP_FORMAT)
            put("version", BACKUP_VERSION)
            put("createdAt", Instant.now().toString())
            put("sessionCount", sessions.size)
            put("appVersion", appVersion)
        }.toString()
        zip.putNextEntry(ZipEntry(MANIFEST_ENTRY))
        streamingCopyOfText(manifest, zip)
        zip.closeEntry()

        zip.putNextEntry(ZipEntry(SETTINGS_ENTRY))
        streamingCopyOfText(settingsText, zip)
        zip.closeEntry()

        sessions.forEachIndexed { index, source ->
            zip.putNextEntry(ZipEntry(SESSIONS_PREFIX + source.id + ".jsonl"))
            source.open().use { streamingCopy(it, zip) }
            zip.closeEntry()
            onProgress(index + 1, sessions.size)
        }
    }
}

/**
 * 读一个备份包（单趟流式）：设置读进内存（有界，1MiB 帽）；会话条目把原始流交给
 * [onSession] 现场落盘——回调结束后引擎负责把这条 entry 的剩余字节排干，
 * 调用方吃没吃完都不影响走到下一条。
 *
 * [onProgress]（0.6.0）：每收进一份会话回调一次（已收份数）。导入大包时界面
 * 靠它报「已读 N 份」，不再干瞪。默认空实现。
 *
 * **参数顺序是钉死的**：[onProgress] 必须排在 [onSession] **前面**——老调用方全部
 * 用尾随 lambda 传 onSession，尾随 lambda 永远绑最后一个参数；onProgress 若排最后，
 * 老调用整个读包编不过（run 35115150047 抓的正是这个，"No value passed for
 * parameter 'onSession'"）。谁想换顺序，先把所有调用点改成具名参数再说。
 */
fun readBackup(
    input: InputStream,
    onProgress: (handledSessions: Int) -> Unit = {},
    onSession: (id: String, stream: InputStream) -> Unit,
): BackupReadResult {
    var manifest: BackupManifest? = null
    var settingsText: String? = null
    var handled = 0
    val skipped = ArrayList<String>()
    ZipInputStream(input).use { zip ->
        while (true) {
            val entry = zip.nextEntry ?: break
            val name = entry.name
            when {
                name == MANIFEST_ENTRY -> manifest = parseManifest(readBounded(zip, MAX_MANIFEST_CHARS))
                name == SETTINGS_ENTRY -> settingsText = readBounded(zip, MAX_SETTINGS_CHARS)
                name.startsWith(SESSIONS_PREFIX) -> {
                    val id = name.removePrefix(SESSIONS_PREFIX).removeSuffix(".jsonl")
                    if (isSafeSessionId(id)) {
                        onSession(id, zip)
                        handled += 1
                        onProgress(handled)
                    } else if (skipped.size < MAX_SKIPPED_LISTED) {
                        skipped += name
                    }
                }
                else -> if (skipped.size < MAX_SKIPPED_LISTED) skipped += name
            }
            // 排干当前 entry 的剩余字节（调用方可能只读了一部分甚至没读），ZipInputStream 才能前进
            val drain = ByteArray(8 * 1024)
            while (zip.read(drain) >= 0) {
                // 原地丢弃
            }
            zip.closeEntry()
        }
    }
    return BackupReadResult(manifest, settingsText, handled, skipped)
}

/**
 * 解析 manifest；字段缺一个都算「不是本家的包」（null）。
 * **必须用块体**：函数体里要用 `return null` 提前退场，表达式体（= try {...}）禁止 return——
 * CI 编译段抓过（run 35097435110），别再图省事写成表达式体。
 */
private fun parseManifest(text: String): BackupManifest? {
    return try {
        val root = Json.parseToJsonElement(text) as? JsonObject ?: return null
        BackupManifest(
            format = (root["format"] as? JsonPrimitive)?.contentOrNull ?: return null,
            version = (root["version"] as? JsonPrimitive)?.intOrNull ?: return null,
            createdAt = (root["createdAt"] as? JsonPrimitive)?.contentOrNull ?: "",
            sessionCount = (root["sessionCount"] as? JsonPrimitive)?.intOrNull ?: 0,
            appVersion = (root["appVersion"] as? JsonPrimitive)?.contentOrNull,
        )
    } catch (e: Exception) {
        null
    }
}

private fun readBounded(stream: InputStream, maxChars: Int): String {
    val reader = InputStreamReader(stream, Charsets.UTF_8)
    val out = StringBuilder()
    val buf = CharArray(8 * 1024)
    while (out.length < maxChars) {
        val n = reader.read(buf, 0, minOf(buf.size, maxChars - out.length))
        if (n < 0) break
        out.append(buf, 0, n)
    }
    return out.toString()
}
