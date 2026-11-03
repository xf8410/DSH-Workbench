package com.hualuo.engine.settings

import java.io.File
import java.io.IOException

/**
 * 设置的存放后端。逻辑一律不碰文件系统，这样键值规则、坏消息、保留未知键这些
 * 最容易出错的部分可以在纯 JVM 里测；应用侧接文件后端（本文件的 [FileSettingsStorage]）。
 */
interface SettingsStorage {
    /** 读出现有文本。从没写过就返回 null —— 不许返回空串假装读过。 */
    fun read(): String?

    /** 覆盖写入。失败必须抛异常并带上原因，不许吞掉后返回成功。 */
    fun write(text: String)
}

/** 设置文件大得离谱：拒绝整份读入，让人去看是什么把它写炸了。 */
class SettingsTooLargeException(
    val actualBytes: Long,
    val limitBytes: Long,
    val path: String,
) : IOException("设置文件 $actualBytes 字节，超过上限 $limitBytes 字节，拒绝整份读入：$path")

/**
 * 文件后端。写入是「先在同目录写临时文件，再改名盖过目标」：POSIX（安卓）的 rename 会
 * **原子替换**目标，所以正常路径上任何时刻盘上都有一份完整文件，要么旧的要么新的。
 *
 * 只有在改名失败时（跨设备、目标被别的程序占住等）才退回「删旧，再改名」——那一步**确实存在
 * 两份都没有的窗口**，所以失败信息里会把临时文件路径写清楚：内容没丢，丢的只是自动恢复的机会，
 * 让人能手动救回来，比静默清空设置强。临时文件一律不清理（保留现场）。
 */
class FileSettingsStorage(
    private val file: File,
    private val maxBytes: Long = DEFAULT_MAX_BYTES,
) : SettingsStorage {

    override fun read(): String? {
        if (!file.exists()) return null
        if (!file.isFile) {
            throw IOException("设置路径不是普通文件（被目录占了？）：${file.path}")
        }
        val size = file.length()
        if (size > maxBytes) throw SettingsTooLargeException(size, maxBytes, file.path)
        return file.inputStream().bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
    }

    override fun write(text: String) {
        val dir = file.absoluteFile.parentFile
            ?: throw IOException("设置文件必须有父目录，当前=$file")
        if (!dir.isDirectory && !dir.mkdirs()) {
            throw IOException("建不出设置目录：${dir.path}")
        }
        val tmp = File.createTempFile(file.name, ".tmp", dir)
        tmp.outputStream().buffered().writer(Charsets.UTF_8).use { writer -> writer.write(text) }

        if (tmp.renameTo(file)) return

        // 改名没成（少见）：只有退成「先删再改名」，才可能有丢文件窗口。
        val hadOld = file.isFile
        val deleted = !hadOld || file.delete()
        if (!deleted) {
            throw IOException(
                "旧设置删不掉且改名失败，旧文件与新内容都在盘上（新内容：${tmp.path}）：${file.path}",
            )
        }
        if (!tmp.renameTo(file)) {
            throw IOException(
                "改名失败；旧文件已不在，新内容留在临时文件里没丢：临时文件 ${tmp.path} 应为 ${file.path}",
            )
        }
    }

    companion object {
        /** 设置文件的硬上限：1 MiB。超过就是哪里写坏了，不是「用户设置多」。 */
        const val DEFAULT_MAX_BYTES = 1_048_576L
    }
}

/** 载入/存取过程中遇到的坏消息。规矩：一条都不许吞，全部进 [SettingsStore.issues]。 */
sealed class SettingsIssue {
    abstract val detail: String

    /** 第 [lineNumber] 行没有等号，不像键值行。 */
    data class MissingEquals(val lineNumber: Int, val raw: String) : SettingsIssue() {
        override val detail: String get() = "第 $lineNumber 行没有等号：$raw"
    }

    /** 等号左边是空的。 */
    data class EmptyKey(val lineNumber: Int) : SettingsIssue() {
        override val detail: String get() = "第 $lineNumber 行的键名是空的"
    }

    /**
     * 键名不符合 ASCII 点分规则。仍然**原样保留**并能存回去 ——
     * 手改文件的人不该被存储层悄悄删掉一行。
     */
    data class IllegalKey(val lineNumber: Int, val key: String) : SettingsIssue() {
        override val detail: String get() = "第 $lineNumber 行键名不合规则（已保留未改）：$key"
    }

    /** 同一个键出现多次，后写的覆盖了前面的。 */
    data class DuplicateKey(val lineNumber: Int, val key: String) : SettingsIssue() {
        override val detail: String get() = "第 $lineNumber 行键重复，后者生效：$key"
    }

    /** 值里有不认识的转义（含结尾多一个反斜杠）。按字面保留，不猜意思。 */
    data class BadEscape(val lineNumber: Int, val reason: String) : SettingsIssue() {
        override val detail: String get() = "第 $lineNumber 行转义有问题，按字面保留：$reason"
    }

    /**
     * 值里有**孤立代理位**（半个表情）：UTF-8 根本编不出它，留在内存里迟早把写库的路炸掉
     * （旧仓「v11f 烂字节」同款）。内容照旧保留，但必须出声，不许静默修成别的样子。
     */
    data class LoneSurrogate(val key: String, val codeUnit: Int) : SettingsIssue() {
        override val detail: String get() =
            "设置「$key」里有孤立代理位 U+${"%04X".format(codeUnit)}（半个表情），已原样保留"
    }

    /** 值读不成目标类型：用默认值继续跑，但这条必须出声。 */
    data class BadValue(val key: String, val typeName: String, val raw: String) : SettingsIssue() {
        override val detail: String get() = "设置「$key」不是合法$typeName（原文=$raw），本次按默认值"
    }

    /** 值超出允许区间：夹到边界继续使用，同样要出声。 */
    data class OutOfRange(
        val key: String,
        val raw: String,
        val clampedTo: Long,
        val min: Long,
        val max: Long,
    ) : SettingsIssue() {
        override val detail: String get() = "设置「$key」=$raw 越界（$min..$max），已夹到 $clampedTo"
    }

    /** 文件声明的格式版本比本代码还新：照旧解析，但提醒可能是降级安装。 */
    data class NewerFormat(val lineNumber: Int, val declared: Int, val supported: Int) : SettingsIssue() {
        override val detail: String get() = "第 $lineNumber 行声明格式 $declared，本程序只认到 $supported，按现有规则继续读"
    }

    /** `#format=` 后面的数字读不出来。 */
    data class BadFormatMarker(val lineNumber: Int, val raw: String) : SettingsIssue() {
        override val detail: String get() = "第 $lineNumber 行的 #format= 标记读不懂：$raw"
    }
}

/** [SettingsStore.reload] 的统计。 */
data class LoadReport(
    val hadContent: Boolean,
    val keyCount: Int,
    val issueCount: Int,
)

/**
 * [SettingsStore.save] 的结果。[persisted] 为假时 [failure] 必带原因，
 * 绝不再造第二种「步骤绿了但没落盘」的假成功。
 */
data class SaveResult(
    val persisted: Boolean,
    val keyCount: Int,
    val charCount: Int,
    val failure: String?,
)
