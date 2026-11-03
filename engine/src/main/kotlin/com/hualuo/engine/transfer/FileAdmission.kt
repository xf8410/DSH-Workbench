package com.hualuo.engine.transfer

/**
 * 文件准入：**没有类型白名单，一条都没有**。
 *
 * 这条是照用户原话写的：「我要所有的文件都可以上传的，所有的格式，无论是什么，哪怕是代码也可以看。」
 * 旧 Agora 的病就是白名单 —— 不认识的附件 type 直接丢，用户的文件静默消失。所以这里的拒绝理由
 * 是一个**封闭的小枚举**，装的全是"硬事实"（读不到、不在授权范围内、不是普通文件……），
 * 结构上就**没有**「扩展名不认识 / MIME 不支持 / 格式太怪」这一类选项：
 * 想加这类理由，必须先改这个枚举 —— 而测试 `fileAdmissionRejectsOnlyHardFacts`
 * 会红着拦「新增一个带类型味道的理由」。
 *
 * 体积也不在这里当借口：[FileCandidate.SIZE_UNKNOWN] 表示"还没 stat 到"，
 * **未知不等于太大**，不许因此拒收；只有调用方明确设过上限才可能报 [RejectReason.TooLarge]。
 */

/** 收集阶段的一个候选文件：只放事实，不放判断。 */
data class FileCandidate(
    val path: String,
    val name: String,
    /** 大小未知时用 [SIZE_UNKNOWN]（例如边读边知道，或者 stat 失败）。 */
    val sizeBytes: Long = SIZE_UNKNOWN,
    /** 有没有读权限（SAF 授权过期、SELinux 挡住、root 掉线都会让它为假）。 */
    val readable: Boolean = true,
    /** 是不是普通文件：目录、FIFO、套接字、设备节点为假。 */
    val isRegularFile: Boolean = true,
    /** 路径是否落在用户授权的那棵树里面（越界不能偷偷读）。 */
    val insideGrant: Boolean = true,
    /** 符号链接照收（按目标读），但要在报告里数出来 —— 它可能指向授权范围外。 */
    val isSymbolicLink: Boolean = false,
) {
    companion object {
        /** 大小未知。注意：**未知不是超限**。 */
        const val SIZE_UNKNOWN = -1L

        /** 零字节文件是合法上传对象（旧仓丢过空文件，这里钉住）。 */
        const val ZERO_BYTES = 0L
    }
}

/** 拒绝理由。加新项必须是硬事实，不许是"类型/格式不认识"那类判断。 */
enum class RejectReason {
    /** 读不到：没权限、授权过期、设备掉线。 */
    NotReadable,

    /** 不在本次授权范围内（防止顺着路径爬出用户给的根）。 */
    OutsideGrant,

    /** 不是普通文件（目录、FIFO、套接字、设备节点）。 */
    NotAFile,

    /** 超过**调用方明说过的**上限。没设上限就永远不会用这个理由。 */
    TooLarge,

    /** 名字放不进产物（空名、含斜杠、含 NUL、只有点号）：卷清单要靠它寻址。 */
    Unnameable,
}

/** 一个候选文件的准入结论。 */
sealed class Admission {
    /** 收。不认识的扩展名、没有扩展名、二进制、代码、日志、apk —— 全走这条。 */
    object Accepted : Admission()

    /** 拒。必须带路径与大白话原因，界面要原样摊给用户看，不许静默消失。 */
    data class Rejected(
        val reason: RejectReason,
        val path: String,
        val detail: String,
    ) : Admission()
}

/**
 * 一次收集的结果。规矩：**每一条被跳过或被拒绝的都得能在报告里数出来**，
 * 这样"上传了 12 个文件"与"其实候选 15 个、3 个进不去"是两句不同的话，
 * 不许把后者说成前者。
 */
class CollectionReport(
    val admitted: List<FileCandidate>,
    val skipped: List<Admission.Rejected>,
    /** 有多少入选文件的体积还不知道：影响进度能不能报准。 */
    val unknownSizeCount: Int,
    /** 入选文件里有多少是符号链接：得让用户知道我们顺着链接读了别处的内容。 */
    val symlinkCount: Int,
) {
    /** 已知字节之和；未知按 0 计，但 [hasEstimate] 会为真，界面必须标"约"。 */
    val knownBytes: Long = admitted.sumOf { if (it.sizeBytes < 0L) 0L else it.sizeBytes }

    /** 总数是不是估算（有未知体积或有跳过项时为真）。 */
    val hasEstimate: Boolean = unknownSizeCount > 0 || skipped.isNotEmpty()

    val candidateCount: Int get() = admitted.size + skipped.size

    /** 一句大白话汇总，界面与 toast 直接用。 */
    fun summary(): String = buildString {
        append("入选 ").append(admitted.size).append(" 个文件")
        if (skipped.isNotEmpty()) {
            append("；跳过 ").append(skipped.size).append(" 个：")
            append(skipped.take(3).joinToString("、") { "${it.reason.name}（${shortName(it.path)}）" })
            if (skipped.size > 3) append(" 等")
        }
        if (unknownSizeCount > 0) append("；").append(unknownSizeCount).append(" 个大小未知（进度只能估算）")
        if (symlinkCount > 0) append("；").append(symlinkCount).append(" 个是符号链接（按链接目标读）")
    }
}

/** 准入规则本体。 */
class FileAdmission(
    /** 单个文件上限：只有调用方**明确设过**才生效；null = 不设上限。 */
    private val maxFileBytes: Long? = null,
    /** 整批上限（卷预算用），同样 null = 不设。 */
    private val maxTotalBytes: Long? = null,
) {

    init {
        require(maxFileBytes == null || maxFileBytes > 0L) { "单文件上限要么不设要么是正数，当前=$maxFileBytes" }
        require(maxTotalBytes == null || maxTotalBytes > 0L) { "整批上限要么不设要么是正数，当前=$maxTotalBytes" }
    }

    /** 判一个候选。没有扩展名/类型相关分支，也不该有。 */
    fun admit(candidate: FileCandidate): Admission {
        if (!candidate.insideGrant) {
            return Admission.Rejected(
                RejectReason.OutsideGrant,
                candidate.path,
                "不在本次授权范围内：${candidate.path}",
            )
        }
        if (!candidate.isRegularFile) {
            return Admission.Rejected(
                RejectReason.NotAFile,
                candidate.path,
                "不是普通文件（目录、管道或设备节点），不能当附件传：${candidate.name}",
            )
        }
        if (!candidate.readable) {
            return Admission.Rejected(
                RejectReason.NotReadable,
                candidate.path,
                "读不到它（没权限或授权已过期）：${candidate.path}",
            )
        }
        if (!nameIsUsable(candidate.name)) {
            return Admission.Rejected(
                RejectReason.Unnameable,
                candidate.path,
                "名字放不进产物（空名、含斜杠或 NUL、只有点号）：${candidate.path}",
            )
        }
        val limit = maxFileBytes
        // 体积未知时**不判**超限：宁可收进来，再在进度里报"这是估算"。
        if (limit != null && candidate.sizeBytes != FileCandidate.SIZE_UNKNOWN && candidate.sizeBytes > limit) {
            return Admission.Rejected(
                RejectReason.TooLarge,
                candidate.path,
                "超过你设的单文件上限 $limit 字节（实际 ${candidate.sizeBytes} 字节）：${candidate.name}",
            )
        }
        return Admission.Accepted
    }

    /** 批量收集：入选与跳过分开列，一条都不许悄悄丢。 */
    fun collect(candidates: List<FileCandidate>): CollectionReport {
        val kept = ArrayList<FileCandidate>(candidates.size)
        val dropped = ArrayList<Admission.Rejected>()
        for (candidate in candidates) {
            when (val verdict = admit(candidate)) {
                Admission.Accepted -> kept += candidate
                is Admission.Rejected -> dropped += verdict
            }
        }

        val totalLimit = maxTotalBytes
        val trimmed = ArrayList<Admission.Rejected>()
        var running = 0L
        val accepted = ArrayList<FileCandidate>(kept.size)
        for (candidate in kept) {
            val size = if (candidate.sizeBytes == FileCandidate.SIZE_UNKNOWN) 0L else candidate.sizeBytes
            if (totalLimit != null && running + size > totalLimit) {
                trimmed += Admission.Rejected(
                    RejectReason.TooLarge,
                    candidate.path,
                    "到整批上限 $totalLimit 字节为止没收下（你设的总量上限）：${candidate.name}",
                )
                continue
            }
            running += size
            accepted += candidate
        }

        return CollectionReport(
            admitted = accepted,
            skipped = dropped + trimmed,
            unknownSizeCount = accepted.count { it.sizeBytes == FileCandidate.SIZE_UNKNOWN },
            symlinkCount = accepted.count { it.isSymbolicLink },
        )
    }

    /** 文件名能不能进产物清单。只卡"放不进去"这几种，不看类型。 */
    private fun nameIsUsable(name: String): Boolean {
        if (name.isEmpty()) return false
        if (name.indexOf('/') >= 0 || name.indexOf('\u0000') >= 0) return false
        if (name == "." || name == "..") return false
        return true
    }
}

/** 报告里显示用的短名。 */
private fun shortName(path: String): String = path.substringAfterLast('/')
