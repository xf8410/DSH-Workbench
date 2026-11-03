package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubRepoClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一次「写仓库」的提议：等用户点头之前，什么都不会写。卡上核对的就这几个字段。 */
data class GitHubWriteProposal(
    val repo: String,
    val path: String,
    val branch: String,
    val message: String,
    /** true = 新建（没带 sha）；false = 覆盖已有文件（带了读到时的 sha 防硬盖）。 */
    val isNewFile: Boolean,
    val contentChars: Int,
    /** 正文开头一段（给人核对用），不是全文。 */
    val contentPreview: String,
)

/**
 * 写类闸门：返回 true 才真写。
 *
 * 实现方（App 的确认卡）负责把提议摆给人看，并**阻塞**到用户点头、拒绝或超时。
 * 纪律：拿不准一律按「没同意」处理——闸门抛异常、等超时、实现缺位，全都当拒写。
 */
fun interface WriteConfirmer {
    fun confirm(proposal: GitHubWriteProposal): Boolean
}

/**
 * 写类工具（0.7.0 刀③）：**读类之外的第一件**，形态与读类刻意不同——
 *
 *  - 默认不注册：调用方不给 [WriteConfirmer] 就不存在这个工具（默认拒写，不是默认放行）；
 *  - 提议不等于写入：执行时先把「要动哪个仓、哪个文件、哪条分支、什么说明、正文多少字符、
 *    开头长什么样」装成 [GitHubWriteProposal] 摆给用户，**点头才真走 PUT**；
 *  - 拒绝、超时、闸门崩溃：一律什么都不写，把「没确认」作为结果文本喂回模型；
 *  - 单次写入封顶 [MAX_WRITE_CHARS]（20 万字符），超了在闸门之前就拒——大改拆小步，
 *    这也是上下文与提交对账的共同边界；
 *  - 令牌、路径、提交说明在碰网络之前全部校验：缺哪格指名道姓说哪格。
 *
 * 真实写入走 GitHubRepoClient.updateFile（sha 对账、409/422 冲突出声那条老路），
 * 本类不重复造轮子，只加「用户点头」这一道闸。
 */
object GitHubWriteTool {

    /** 单次写入的字符上限。 */
    const val MAX_WRITE_CHARS = 200_000

    /** 预览给用户核对的字符数。 */
    const val PREVIEW_CHARS = 400

    fun register(
        registry: ToolRegistry,
        loadToken: () -> String?,
        defaultRepo: () -> String?,
        repoClient: GitHubRepoClient,
        confirmer: WriteConfirmer,
    ) {
        registry.register(
            ToolSpec(
                name = "github_update_file",
                description = "请求修改仓库里的一个文本文件（整文件替换）或新建文件。" +
                    "App 会弹确认卡：用户点头才真提交，拒绝或超时什么都不写。" +
                    "message 是提交说明（必填，会进仓库历史）。覆盖已有文件要带 sha（读文件时拿到的那个）；新建文件不带",
                parametersJson = WRITE_SCHEMA,
            ),
        ) { argumentsJson ->
            val args = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
                ?: JsonObject(emptyMap())
            fun opt(key: String): String? =
                (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

            val repo = opt("repo")
                ?: defaultRepo()?.trim()?.takeIf { it.isNotEmpty() }
                ?: fail("缺参数 repo（owner/name；或先去设置「GitHub 工作台」填默认仓库）")
            val path = opt("path") ?: fail("缺参数 path（仓库内路径，如 app/src/main/kotlin/X.kt）")
            val content = opt("content")
                ?: fail("缺参数 content：要写入的完整文本（本工具是整文件替换，不认补丁片段）")
            val message = opt("message")
                ?: fail("缺参数 message：提交必须有一句人话说明改了什么（它会进仓库历史）")
            val branch = opt("branch") ?: "main"
            val sha = opt("sha")

            if (content.length > MAX_WRITE_CHARS) {
                fail("内容 ${content.length} 字符，超了单次写入上限 $MAX_WRITE_CHARS：拆成几次小改动再提交")
            }
            val token = loadToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: fail("改码要令牌：去设置「GitHub 工作台」或「文件投递」填")

            val proposal = GitHubWriteProposal(
                repo = repo,
                path = path,
                branch = branch,
                message = message,
                isNewFile = sha == null,
                contentChars = content.length,
                contentPreview = if (content.length <= PREVIEW_CHARS) content
                else content.take(PREVIEW_CHARS) + "…",
            )
            val approved = runCatching { confirmer.confirm(proposal) }.getOrDefault(false)
            if (!approved) {
                fail("用户没有确认这次写入（或等待超时、或确认通道故障）：什么都没改。" +
                    "若确实要改，把目标、理由和完整改动说清楚，再提议一次")
            }

            val result = repoClient.updateFile(repo, path, branch, content, message, sha, token)
            val problem = result.error
            if (problem != null) fail(problem)
            "已提交：$repo/$path（分支 $branch，commit ${result.commitSha?.take(8) ?: "?"}）"
        }
    }

    /** 工具执行失败：抛出去由注册表折成「给模型看的失败文本」（照样回填，不算链路错）。 */
    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** 写工具的参数 schema（JSON Schema 原文）。 */
    private const val WRITE_SCHEMA = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"path":{"type":"string","description":"仓库内路径，如 app/src/main/kotlin/X.kt"},"content":{"type":"string","description":"要写入的完整文本（整文件替换）"},"message":{"type":"string","description":"提交说明（必填，会进仓库历史）"},"branch":{"type":"string","description":"分支，缺省 main"},"sha":{"type":"string","description":"当前文件 sha（覆盖已有文件带上；新建文件不带）"}},"required":["path","content","message"]}"""
}
