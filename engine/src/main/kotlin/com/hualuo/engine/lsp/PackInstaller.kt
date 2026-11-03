package com.hualuo.engine.lsp

import com.hualuo.engine.io.sanitizeForLog
import com.hualuo.engine.io.streamingCopy
import com.hualuo.engine.io.toHexLower
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * 语言包安装器（LSP 刀·切片②主体）：把 [LanguagePack] 定义落成磁盘上一个可用的包文件。
 *
 * 三步硬纪律，缺一条都可能把坏包放进来：
 *  1) 下载只写临时文件（目标名加 .part 后缀），没验完不许有人看见它，半截包永远不叫「已安装」；
 *  2) 边写边算 SHA-256，写完与定义里的期望值逐位对；不匹配就整包作废、临时文件当场删，
 *     报错里把期望值与实际值都摆出来（哈希不是秘密，诊断全靠这两个值）；
 *  3) 全部核对过了才改名生效（同一目录内改名是原子替换），改完名文件才算存在。
 *
 * 体积上限是防「下载把磁盘写满」的闸：跨过上限的那一块一个字节都不写（先记账后落盘），整包作废。
 *
 * 幂等：目标文件在、哈希又对，直接报账走人，不碰网络。本地哈希不符（半截残留或被换过）
 * 就删掉重下——这个目录里文件名与内容都是可预期的，自愈比报错更对；删也删不掉才明确报错，不静默硬上。
 *
 * 下载走 [PackFetch] 缝隙注入：真网实现是 [httpPackFetch]，测试注入假实现即可整链路纯 JVM 验证。
 */
class PackInstaller(
    private val targetDir: File,
    private val fetch: PackFetch,
    private val limits: PackLimits = PackLimits(),
) {

    /** 已安装文件的预计落点（幂等检查与调用方认的都是这一个名字）。 */
    fun targetOf(pack: LanguagePack): File = File(targetDir, pack.id + "-" + pack.version + PACK_SUFFIX)

    /**
     * 安装一个包。成功返回落盘账；失败抛 [PackInstallReject]（消息含原因与出路），
     * 且保证不留半截文件：要么旧的在，要么新的整个在。
     */
    fun install(pack: LanguagePack, onProgress: (Long, Long) -> Unit = { _, _ -> }): InstallReport {
        if (!targetDir.isDirectory && !targetDir.mkdirs()) {
            throw PackInstallReject("装机目录建不出来，检查存储权限或路径：" + sanitizeForLog(targetDir.path))
        }
        val finalFile = targetOf(pack)
        val tmpFile = File(targetDir, finalFile.name + ".part")

        if (tmpFile.exists() && !tmpFile.delete()) {
            throw PackInstallReject("旧的半截文件删不掉，清掉它再装：" + sanitizeForLog(tmpFile.path))
        }
        val local = localHash(finalFile)
        if (local != null && local.bytes > 0L && local.hash == pack.sha256Hex) {
            return InstallReport(finalFile, local.bytes, local.hash, downloaded = false)
        }
        if (finalFile.exists() && !finalFile.delete()) {
            throw PackInstallReject("本地这份哈希不对、又删不掉，清掉它再装：" + sanitizeForLog(finalFile.path))
        }

        val sink = HashingSink(tmpFile, limits.maxBytes)
        val fetched: Long = try {
            fetch.fetch(pack.downloadUrl, sink, onProgress)
        } catch (e: Exception) {
            sink.closeQuietly()
            cleanupTmp(tmpFile)
            throw when (e) {
                is PackInstallReject -> e
                is IOException -> PackInstallReject(
                    "下载断了：" + sanitizeForLog(e.message ?: "网络不通") + "。文件没生效，重试即可"
                )
                else -> PackInstallReject(
                    "下载器说：" + sanitizeForLog(e.message ?: e.javaClass.simpleName) + "。文件没生效"
                )
            }
        }
        try {
            sink.close()
        } catch (e: IOException) {
            cleanupTmp(tmpFile)
            throw PackInstallReject("收尾写盘失败：" + sanitizeForLog(e.message ?: "IO 错") + "。文件没生效")
        }

        if (fetched != sink.written) {
            cleanupTmp(tmpFile)
            throw PackInstallReject(
                "下载器账对不上：它说交了 $fetched 字节，实际落到临时文件 ${sink.written} 字节。整包作废"
            )
        }
        if (sink.written == 0L) {
            cleanupTmp(tmpFile)
            throw PackInstallReject("下载得到 0 字节（空包），拒收。检查包地址或网络")
        }
        val actual = sink.hex()
        if (actual != pack.sha256Hex) {
            cleanupTmp(tmpFile)
            throw PackInstallReject(
                "哈希不匹配：期望 ${pack.sha256Hex}，实际 $actual（${sink.written} 字节）。" +
                    "这个包要么坏了要么被换过，整包作废，重下"
            )
        }
        if (!tmpFile.renameTo(finalFile)) {
            cleanupTmp(tmpFile)
            throw PackInstallReject("临时文件改不了名，文件没生效：" + sanitizeForLog(finalFile.path))
        }
        return InstallReport(finalFile, sink.written, actual, downloaded = true)
    }

    private data class LocalHash(val hash: String, val bytes: Long)

    /** 本地文件流式算哈希（不整文件进内存）。读不出来或不是文件就当没有，走重下。 */
    private fun localHash(file: File): LocalHash? {
        if (!file.isFile) return null
        return try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            file.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                    size += n
                }
            }
            LocalHash(digest.digest().toHexLower(), size)
        } catch (e: IOException) {
            null
        }
    }

    private fun cleanupTmp(file: File) {
        runCatching { file.delete() }
    }

    private companion object {
        const val PACK_SUFFIX = ".pack"
    }
}

/** 一次安装的账：落点、字节数、实际哈希，以及这次有没有走网络（false = 本地命中）。 */
data class InstallReport(
    val file: File,
    val bytes: Long,
    val sha256Hex: String,
    val downloaded: Boolean,
)

/** 安装失败的原因。消息一律中文、带出路；里头的用户可控文本控制字符可视化。 */
class PackInstallReject(reason: String) : Exception(reason)

/**
 * 安装上限。maxBytes 可注入是为了让测试用几十字节验证，而不是真造一个超体积的包。
 */
data class PackLimits(val maxBytes: Long = 2L * 1024 * 1024 * 1024) {
    init {
        require(maxBytes > 0L) { "包体上限必须大于零，现在是 $maxBytes" }
    }
}

/**
 * 下载缝隙：把 url 的内容流式搬进 sink（一次一块，绝不整包进内存），
 * 返回写入 sink 的总字节数（安装器拿它与自己的计数对账，下载器的口头账不作数）。
 * 进度回调 (done, total)：total 未知时报 -1。
 */
fun interface PackFetch {
    fun fetch(url: String, sink: OutputStream, onProgress: (Long, Long) -> Unit): Long
}

/**
 * 真网下载器（默认档）：HttpURLConnection 流式实现。
 * 只认 http 与 https；非 2xx 当场拒收（带状态码）；连接 15 秒、读 30 秒超时
 * （读超时是「卡住不动」的超时，不是总时长上限，持续有数据的慢下载不受影响）。
 */
val httpPackFetch: PackFetch = PackFetch { url, sink, onProgress ->
    if (!isHttpOrHttps(url)) throw PackInstallReject("下载地址只认 http 与 https：" + sanitizeForLog(url))
    val conn = URL(url).openConnection() as HttpURLConnection
    conn.connectTimeout = 15_000
    conn.readTimeout = 30_000
    conn.requestMethod = "GET"
    conn.setRequestProperty("user-agent", "hualuo-repo-tool")
    try {
        val status = conn.responseCode
        if (status !in 200..299) {
            throw PackInstallReject("下载被拒：服务器返回 $status（" + sanitizeForLog(url) + "）")
        }
        val total = conn.contentLengthLong
        conn.inputStream.use { input ->
            streamingCopy(input, sink, totalBytes = total) { done, t -> onProgress(done, t) }.bytes
        }
    } finally {
        conn.disconnect()
    }
}

/**
 * 落盘 + 计数 + 哈希三合一的写入端：下载器只管往这里倒字节，
 * 记账与校验由安装器自己捏着——下载器报的字节数永远不可信，以这里为准。
 * 超限判定先于落盘，所以跨限的那一块一个字节都写不出去。
 */
private class HashingSink(file: File, private val maxBytes: Long) : OutputStream() {

    private val digest = MessageDigest.getInstance("SHA-256")
    private val out = file.outputStream()
    private val one = ByteArray(1)

    var written: Long = 0L
        private set

    override fun write(b: Int) {
        one[0] = b.toByte()
        write(one, 0, 1)
    }

    override fun write(b: ByteArray, off: Int, len: Int) {
        if (len <= 0) return
        if (written + len > maxBytes) {
            throw PackInstallReject(
                "包体超过上限 $maxBytes 字节（已写 $written），整包作废。这是防磁盘写满的闸"
            )
        }
        digest.update(b, off, len)
        out.write(b, off, len)
        written += len
    }

    override fun flush() {
        out.flush()
    }

    /** 只在全部核对路径上各调一次（digest 调过就归零，不能复用）。 */
    fun hex(): String = digest.digest().toHexLower()

    override fun close() {
        out.close()
    }

    fun closeQuietly() {
        runCatching { out.close() }
    }
}
