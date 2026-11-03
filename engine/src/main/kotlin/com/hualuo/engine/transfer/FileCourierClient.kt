package com.hualuo.engine.transfer

import com.hualuo.engine.io.streamingCopy
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.FilterOutputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** 一份待投递文件：名字与现场开流（不提前读进内存），sizeBytes 用于配卷（未知给 0，按独占卷处理）。 */
data class CourierFile(val name: String, val sizeBytes: Long, val open: () -> InputStream)

/**
 * 上传一个卷的缝隙：路径已含前缀，内容在 [File] 里（落过盘的卷，不要求整卷进内存）。
 * 抛异常 = 这卷没传上，由 client 统一重试；临时文件归 client 清，实现方用完不删。
 */
fun interface VolumeUploader {
    fun upload(path: String, file: File, message: String)
}

/** 投递收场：成功带账（卷数/文件数/原始字节），失败给一句能行动的话。绝不静默丢卷。 */
sealed class CourierOutcome {
    data class Ok(
        val manifestPath: String,
        val volumeCount: Int,
        val fileCount: Int,
        val totalRawBytes: Long,
    ) : CourierOutcome()

    data class Failed(val reason: String) : CourierOutcome()
}

/**
 * 文件投递（courier）引擎件：把一批文件按**文件边界**打成 zip 卷，逐卷投到私有仓，
 * 收尾写一份 manifest.json（每卷内容、每文件 SHA-256、总账）——收方按账还原与校验。
 *
 * 对齐旧仓验证过的形状：默认卷配额 32MB，单文件**不劈开**（超配额独占一卷，zip 后
 * 可能略超 32MB，是压缩率带来的偏差，诚实记录在 manifest 里，不假装精确）。
 *
 * 内存纪律（SourceHygiene 闸门盯着的这条）：**任何时刻都不把整卷读进内存**。
 * 卷用临时文件流式落地（zip 直写文件、64KiB 缓冲），上传收 File，真网实现里
 * base64 也是流式直写 HTTP 输出——内存曲线是平的，与卷大小无关。
 *
 *  - 上传动作经 [VolumeUploader] 缝隙注入：纯 JVM 测试喂假 uploader，绝不碰真网；
 *  - 每卷失败自动重试 [maxRetries] 次（退避由 [sleeper] 注入），**成功即停**——测试
 *    立过功的一条：repeat 的 return@repeat 是「下一轮」不是「收工」，写错了会把
 *    传成功的卷原样再传两遍（CI 抓过，改成 while 条件收口）；
 *  - 进度回调 [onProgress]（已完成卷数, 总卷数），接线层拿它画进度行（0.6.0 同款）；
 *  - 卷数封顶 [maxVolumes]：防止把仓库当无限盘打爆，超了直接拒绝出声。
 */
class FileCourierClient(
    private val uploader: VolumeUploader,
    private val volumeBytes: Int = DEFAULT_VOLUME_BYTES,
    private val maxVolumes: Int = DEFAULT_MAX_VOLUMES,
    private val maxRetries: Int = 2,
    private val sleeper: (Long) -> Unit = {},
    private val onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
) {

    /** 全量投递。路径形如 courier/20260917-093000/（前缀由调用方给，本类不抢时间戳的活）。 */
    fun deliver(files: List<CourierFile>, targetPrefix: String): CourierOutcome {
        if (files.isEmpty()) return CourierOutcome.Failed("没有要投递的文件")
        if (!targetPrefix.endsWith('/')) {
            return CourierOutcome.Failed("目标前缀要以斜杠结尾（如 courier/20260917-093000/）：$targetPrefix")
        }
        val plans = planVolumes(files)
        if (plans.size > maxVolumes) {
            return CourierOutcome.Failed("这批要打成 ${plans.size} 卷，超过上限 $maxVolumes：分批投或先清掉大件")
        }
        var totalRaw = 0L
        files.forEach { totalRaw += it.sizeBytes.coerceAtLeast(0L) }
        val volumeEntries = ArrayList<JsonObject>(plans.size)
        plans.forEachIndexed { index, plan ->
            val volumeName = "part_" + "%03d".format(index + 1) + ".zip"
            val volumePath = targetPrefix + volumeName
            val packed = packVolume(plan)
            val attempts = maxRetries + 1
            var lastError: String? = null
            var sent = false
            try {
                var attempt = 0
                while (attempt < attempts && !sent) {
                    if (attempt > 0) sleeper(1_000L * attempt)
                    try {
                        uploader.upload(volumePath, packed, "courier: $volumeName（${plan.files.size} 个文件，投递卷）")
                        sent = true
                        lastError = null
                    } catch (e: Exception) {
                        lastError = "${e.javaClass.simpleName}: ${e.message ?: "（无消息）"}"
                    }
                    attempt += 1
                }
            } finally {
                packed.delete()
            }
            if (!sent) {
                return CourierOutcome.Failed("卷 $volumeName 连试 $attempts 次都没传上（$lastError）：网络或令牌出问题了，之前已传的卷都在仓里，稍后整批重投即可")
            }
            volumeEntries += buildJsonObject {
                put("name", volumeName)
                put("bytes", packed.length())
                put("fileCount", plan.files.size)
                put("files", buildJsonArray { plan.files.forEach { add(kotlinx.serialization.json.JsonPrimitive(it.file.name)) } })
            }
            onProgress(index + 1, plans.size)
        }
        val manifest = buildJsonObject {
            put("kind", "hualuo-courier")
            put("createdAt", Instant.now().toString())
            put("fileCount", files.size)
            put("totalRawBytes", totalRaw)
            put("volumes", buildJsonArray { volumeEntries.forEach { add(it) } })
            put("files", buildJsonArray {
                files.forEach { f ->
                    add(buildJsonObject {
                        put("name", f.name)
                        put("sizeBytes", f.sizeBytes)
                        put("sha256", sha256Of(f))
                    })
                }
            })
        }.toString()
        val manifestPath = targetPrefix + "manifest.json"
        val manifestFile = File.createTempFile("courier-manifest-", ".json")
        try {
            manifestFile.writeText(manifest, StandardCharsets.UTF_8)
            try {
                uploader.upload(manifestPath, manifestFile, "courier: manifest.json（${files.size} 个文件的还原账）")
            } catch (e: Exception) {
                return CourierOutcome.Failed(
                    "数据卷 ${plans.size} 个全部传完，只有 manifest 没传上（${e.message ?: "出错"}）：把同一批重投一遍即可，卷会原样覆盖",
                )
            }
        } finally {
            manifestFile.delete()
        }
        return CourierOutcome.Ok(manifestPath, plans.size, files.size, totalRaw)
    }

    /** 配卷：按原始字节配额切；装不进当前卷就从新卷开始，单文件永不劈开。 */
    private fun planVolumes(files: List<CourierFile>): List<VolumePlan> {
        val plans = ArrayList<VolumePlan>()
        var current = VolumePlan()
        files.forEach { file ->
            val size = file.sizeBytes.coerceAtLeast(0L)
            if (current.rawBytes > 0L && current.rawBytes + size > volumeBytes) {
                plans.add(current)
                current = VolumePlan()
            }
            current.files += PlannedFile(file, size)
            current.rawBytes += size
            if (size >= volumeBytes) {
                // 大件独占一卷：塞了它这卷就封口，下一个文件开新卷
                plans.add(current)
                current = VolumePlan()
            }
        }
        if (current.files.isNotEmpty()) plans.add(current)
        return plans
    }

    /** 打一个 zip 卷：条目名用原文件名（重名加序号），内容 64KiB 流式拷贝直写临时文件。 */
    private fun packVolume(plan: VolumePlan): File {
        val target = File.createTempFile("courier-volume-", ".zip")
        ZipOutputStream(FileOutputStream(target).buffered()).use { zip ->
            val seen = HashSet<String>()
            plan.files.forEachIndexed { index, planned ->
                var entryName = planned.file.name
                if (!seen.add(entryName)) {
                    entryName = "%03d-".format(index + 1) + entryName
                    seen.add(entryName)
                }
                zip.putNextEntry(ZipEntry(entryName))
                planned.file.open().use { streamingCopy(it, zip) }
                zip.closeEntry()
            }
        }
        return target
    }

    private fun sha256Of(file: CourierFile): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.open().use { stream ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = stream.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private class VolumePlan {
        val files = ArrayList<PlannedFile>()
        var rawBytes = 0L
    }

    private class PlannedFile(val file: CourierFile, val sizeBytes: Long)

    companion object {
        /** 旧仓验证过的卷配额：32MB。 */
        const val DEFAULT_VOLUME_BYTES = 32 * 1024 * 1024

        /** 卷数上限：20 卷 × 32MB = 640MB 一批，够用也兜得住。 */
        const val DEFAULT_MAX_VOLUMES = 20

        /**
         * 真网投递：GitHub contents API（PUT /repos/{owner}/{repo}/contents/{path}）。
         * base64 **流式**直写 HTTP 输出（Base64.encoder().wrap 包住输出流，文件 64KiB
         * 过路），整卷从头到尾不进内存。令牌只进请求头，不进 URL 不进日志。
         * 卷与 manifest 都会覆盖同名旧文件（重投 = 原地修复，家规：不留墓碑也不怕重投）。
         */
        fun defaultUploader(owner: String, repo: String, branch: String, token: String): VolumeUploader =
            VolumeUploader { path, file, message ->
                // message 会被拼进 JSON：引号与反斜杠一律换掉，免得手拼 JSON 被拆
                val safeMessage = message.replace("\"", "'").replace("\\", "/")
                val head = "{\"message\":\"$safeMessage\",\"branch\":\"$branch\",\"content\":\""
                val tail = "\"}"
                val headBytes = head.toByteArray(StandardCharsets.UTF_8)
                val tailBytes = tail.toByteArray(StandardCharsets.UTF_8)
                // base64 长度公式：每 3 字节变 4 字符，余数补齐
                val base64Chars = ((file.length() + 2L) / 3L) * 4L
                val total = headBytes.size + base64Chars + tailBytes.size
                val url = "https://api.github.com/repos/$owner/$repo/contents/$path"
                val conn = URL(url).openConnection() as HttpURLConnection
                conn.requestMethod = "PUT"
                conn.connectTimeout = 15_000
                conn.readTimeout = 120_000
                conn.doOutput = true
                conn.setFixedLengthStreamingMode(total)
                conn.setRequestProperty("Authorization", "Bearer $token")
                conn.setRequestProperty("Accept", "application/vnd.github+json")
                conn.setRequestProperty("Content-Type", "application/json")
                try {
                    val raw = conn.outputStream
                    raw.write(headBytes)
                    // 包一层「不关底层」的壳：Base64.wrap 关壳只会刷净填充，HTTP 流留给 tail
                    val shield = object : FilterOutputStream(raw) {
                        override fun close() {
                            flush()
                        }
                    }
                    Base64.getEncoder().wrap(shield).use { encoder ->
                        FileInputStream(file).use { streamingCopy(it, encoder) }
                    }
                    raw.write(tailBytes)
                    raw.flush()
                    val code = conn.responseCode
                    if (code !in 200..299) {
                        // 有界读错误体：只留前 256 字节当线索（有界读取是全仓纪律）
                        val err = conn.errorStream
                        var body = ""
                        if (err != null) {
                            val buf = ByteArray(256)
                            val n = err.read(buf)
                            if (n > 0) body = String(buf, 0, n, StandardCharsets.UTF_8)
                        }
                        throw RuntimeException("HTTP $code $body")
                    }
                } finally {
                    conn.disconnect()
                }
            }

        /** 给接线层的小工具：单文件直投（bytes 在手的小件），包成 CourierFile（拷贝防外泄改写）。 */
        fun singleFile(name: String, bytes: ByteArray): CourierFile {
            val copy = bytes.copyOf()
            return CourierFile(name, copy.size.toLong()) { copy.inputStream() }
        }
    }
}
