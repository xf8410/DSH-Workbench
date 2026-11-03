package com.hualuo.engine.sandbox

/**
 * 沙盒操作失败的结构化原因（对齐 docs/SANDBOX-LOGIC 修复方案第 4 条：错误对象化）。
 *
 * 为什么必须有它：旧实现里「python 秒报成功，然后环境错误，且没有错误码」的根因之一
 * 就是失败原因只有一行字符串（甚至只有 -1 哨兵），调用方与人都无法区分
 * 「网络挂了」「哈希不对」「索引里没有这个包」「上一条命令还在跑」。
 * 从这里开始，每次失败都带 kind + 现场证据（退出码 / 输出尾 / 包名 / 来源地址）。
 */
data class SandboxError(
    val kind: Kind,
    val exitCode: Int? = null,
    val stderrSnippet: String = "",
    val pkg: String? = null,
    val url: String? = null,
) {
    enum class Kind {
        /** 上一条命令还没跑完（busy 门，不静默不排队）。 */
        BUSY,

        /** rootfs 尚未安装，此操作需要先装环境。 */
        NOT_INSTALLED,

        /** rootfs 或 apk 包下载失败（含换完全部镜像仍失败）。 */
        DOWNLOAD,

        /** sha256 校验不过，或 apk info 复验对不上。 */
        VERIFY,

        /** 依赖解析失败：索引里查不到、依赖环、或计划为空但复验不通过。 */
        PLAN,

        /** 命令执行层失败（进程起不来、被信号打断等）。 */
        EXEC,

        /** 超时被强杀。 */
        TIMEOUT,

        /** 参数被拒：空命令、超长命令、越界路径。 */
        REJECTED,

        /** 文件系统与流层面的意外失败。 */
        IO,
    }

    /** 单行、给人看也给模型看；证据截到 200 字，防一条 stderr 刷满上下文。 */
    override fun toString(): String = buildString {
        append("沙盒失败[").append(kind.name).append(']')
        pkg?.let { append(" 包=").append(it) }
        exitCode?.let { append(" 退出码=").append(it) }
        url?.let { append(" 来源=").append(it) }
        if (stderrSnippet.isNotBlank()) append(" 输出尾=").append(stderrSnippet.take(200))
    }

    fun toException(): SandboxException = SandboxException(this)
}

/** 带 [SandboxError] 的异常壳：引擎内部统一抛它，门面捕获后转结果文本。 */
class SandboxException(val error: SandboxError) : Exception(error.toString())
