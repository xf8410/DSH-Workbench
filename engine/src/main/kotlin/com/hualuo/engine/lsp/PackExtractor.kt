package com.hualuo.engine.lsp

import com.hualuo.engine.io.ExtractGuard
import com.hualuo.engine.io.ExtractLimits
import com.hualuo.engine.io.ExtractReject
import com.hualuo.engine.io.sanitizeForLog
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream

/**
 * 语言包解包落地（LSP 刀·切片②的收尾件）：把安装器落下的 .pack(zip) 安全解到私有目录，
 * 并确认服务入口文件真在、真是文件、不为空。
 *
 * 三条硬纪律：
 *  1) 每一个条目落盘必须过 ExtractGuard（名字七重拦、四道上限、压缩比防炸弹）——
 *     这是 ExtractGuard 第一次真消费，不许有绕过它的第二条写盘路径；
 *  2) 全部解到临时兄弟目录（目标目录名加 .unpack 后缀），成功之后才改名生效——
 *     半解出来的东西永远不会冒充「已解好」；
 *  3) 解完必须点名验入口（[LanguagePack.serverBinary] 指的那个文件）：不存在、不是文件、
 *     或者 0 字节，整包作废。光「解成功」不算数，包里有真东西才算。
 *
 * 失败一律抛 [PackInstallReject]（消息中文带原因与出路，用户可控文本控制字符可视化），
 * 并把暂存目录整个清掉——磁盘上不留半截。
 *
 * 修记（推 CI 之前自查逮住的两类错，都已修）：
 *  1) 账与文件各走各的变量——曾把 verifyEntry 的返回值当 File 使、又当对象再取字段，纯名字混淆；
 *  2) 「一个条目都没读到」有两种成因：真空包，还是压根不是 zip。
 *     ZipInputStream 对垃圾字节很多情况下是**安静返回 null**而不是抛错，
 *     所以这里补一次开头两字节的 PK 魔数探测，把「空包」与「不是 zip」分开报，
 *     不拿「空包」冒充「坏包」——货不对板时说法必须对板。
 */
class PackExtractor(private val limits: ExtractLimits = ExtractLimits()) {

    /**
     * 把 [packFile] 解到 [targetDir]：[targetDir] 是这次解包的最终落点（调用方给每个包一个独立目录）。
     * 成功返回账目。
     */
    fun extract(pack: LanguagePack, packFile: File, targetDir: File): ExtractReport {
        if (!packFile.isFile) {
            throw PackInstallReject("包文件不在：" + sanitizeForLog(packFile.path) + "，先装它再解")
        }
        val staging = File(targetDir.absoluteFile.parentFile, targetDir.name + STAGING_SUFFIX)
        deleteRecursively(staging)
        if (!staging.mkdirs()) {
            throw PackInstallReject("解包暂存目录建不出来：" + sanitizeForLog(staging.path))
        }

        try {
            unpack(packFile, staging)
            val check = verifyEntry(pack, staging)
            if (!staging.renameTo(targetDir)) {
                if (!targetDir.exists()) {
                    throw PackInstallReject("解好的包改不了名，文件没生效：" + sanitizeForLog(targetDir.path))
                }
                deleteRecursively(targetDir)
                if (!staging.renameTo(targetDir)) {
                    throw PackInstallReject("解好的包覆盖不了旧目录，文件没生效：" + sanitizeForLog(targetDir.path))
                }
            }
            return ExtractReport(targetDir, check.bytes, check.entryPath)
        } catch (e: Exception) {
            deleteRecursively(staging)
            throw when (e) {
                is PackInstallReject -> e
                is ExtractReject -> PackInstallReject("解包被安全闸拦下：" + (e.message ?: "违反解包上限") + "。整包作废")
                is IOException -> PackInstallReject("解包中途读写出错：" + sanitizeForLog(e.message ?: "IO 错") + "。整包作废")
                else -> PackInstallReject("解包器说：" + sanitizeForLog(e.message ?: e.javaClass.simpleName))
            }
        }
    }

    /** 逐条目解，每条都过 ExtractGuard。 */
    private fun unpack(packFile: File, staging: File) {
        var sawAnyEntry = false
        try {
            ZipInputStream(packFile.inputStream().buffered()).use { zip ->
                val guard = ExtractGuard(limits)
                while (true) {
                    val entry = zip.nextEntry ?: break
                    if (entry.isDirectory) {
                        val dir = guard.beginEntry(staging, entry.name, 0L)
                        if (!dir.isDirectory && !dir.mkdirs()) {
                            throw PackInstallReject("目录建不出来：" + sanitizeForLog(entry.name))
                        }
                        guard.endEntry(0L)
                        continue
                    }
                    sawAnyEntry = true
                    writeEntry(guard, staging, zip, entry)
                }
            }
        } catch (e: ZipException) {
            throw PackInstallReject("这包不是有效的 zip（哈希对但结构坏）：" + sanitizeForLog(e.message ?: "格式错"))
        }
        if (!sawAnyEntry) {
            throw PackInstallReject(
                if (startsWithZipMagic(packFile)) {
                    "包里一个文件都没有（空包），作废"
                } else {
                    "这包不是有效的 zip（开头不是 PK 魔数）。整包作废"
                }
            )
        }
    }

    private fun writeEntry(guard: ExtractGuard, staging: File, zip: ZipInputStream, entry: ZipEntry) {
        val dest = guard.beginEntry(staging, entry.name, entry.compressedSize)
        dest.absoluteFile.parentFile?.mkdirs()
        dest.outputStream().buffered().use { out ->
            val buf = ByteArray(COPY_BUFFER)
            while (true) {
                val n = zip.read(buf)
                if (n < 0) break
                guard.accept(n.toLong())
                out.write(buf, 0, n)
            }
        }
        guard.endEntry(entry.compressedSize)
    }

    /** 开头两字节是不是 PK（zip 魔数）。读不出来按「不是」处理，随后由上层报「不是 zip」。 */
    private fun startsWithZipMagic(file: File): Boolean = try {
        val head = ByteArray(2)
        file.inputStream().use { it.read(head) }
        head[0] == 0x50.toByte() && head[1] == 0x4B.toByte()
    } catch (e: IOException) {
        false
    }

    private data class EntryCheck(val entryPath: String, val bytes: Long)

    /** 点名验入口：必须在、必须是文件、必须非空。 */
    private fun verifyEntry(pack: LanguagePack, staging: File): EntryCheck {
        val entry = File(staging, pack.serverBinary)
        if (!entry.isFile) {
            throw PackInstallReject(
                "包里没有服务入口文件 " + sanitizeForLog(pack.serverBinary) + "。这个包与定义不符，整包作废"
            )
        }
        val size = entry.length()
        if (size <= 0L) {
            throw PackInstallReject("服务入口文件是 0 字节（" + sanitizeForLog(pack.serverBinary) + "），整包作废")
        }
        return EntryCheck(pack.serverBinary, size)
    }

    private fun deleteRecursively(file: File) {
        if (!file.exists()) return
        if (file.isDirectory) file.listFiles()?.forEach { deleteRecursively(it) }
        runCatching { file.delete() }
    }

    private companion object {
        const val STAGING_SUFFIX = ".unpack"
        const val COPY_BUFFER = 64 * 1024
    }
}

/** 一次解包的账：落点目录、入口文件字节数、入口在包内的相对路径。 */
data class ExtractReport(val targetDir: File, val entryBytes: Long, val entryPath: String)
