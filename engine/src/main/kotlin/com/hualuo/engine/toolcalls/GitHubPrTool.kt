package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubPrClient
import com.hualuo.engine.github.PrRequestFailed
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * PR 提议（摆给人核对卡片的形状）：建/合共一张卡，字段按 action 取用。
 */
data class GitHubPrProposal(
    val action: String,
    val repo: String,
    /** 建：head->base+标题；合：PR 号+钉死的 head sha。 */
    val head: String,
    val base: String,
    val title: String,
    val draft: Boolean,
    val number: Long,
    val expectedHeadSha: String,
    val method: String,
)

/** PR 闸门：与 [WriteConfirmer] 同纪律——拿不准一律按「没同意」处理。 */
fun interface PrConfirmer {
    fun confirm(proposal: GitHubPrProposal): Boolean
}

/**
 * PR 工具族（M4 第一刀）：github_create_pull_request + github_merge_pull_request。
 *
 * 与 GitHub 写类同一条铁律：**给闸门才存在**——[confirmer] 为空两件都不注册
 * （默认拒执行）。执行时先把完整提议（哪个仓、哪条分支进哪条、什么标题/钉哪个 sha）
 * 摆给用户，点头才真动网络。
 *
 * 合并的 SHA 钉死语义：模型要先拉 PR 当前 head sha，连同 PR 号一起进确认卡；
 * 卡上那个人点头时看到的 sha 才是会被合的 sha——确认后分支又进了新提交，GitHub 会 409，
 * 这里翻成中文失败带原话，绝不合错版本（对齐旧 Agora 的 fail-closed）。
 */
object GitHubPrTool {

    const val MAX_TITLE_CHARS = 200
    const val MAX_BODY_CHARS = 20_000
    val MERGE_METHODS = setOf("merge", "squash", "rebase")

    fun register(
        registry: ToolRegistry,
        loadToken: () -> String?,
        defaultRepo: () -> String? = { null },
        prClient: GitHubPrClient = GitHubPrClient(),
        confirmer: PrConfirmer? = null,
    ): ToolRegistry {
        if (confirmer == null) return registry // 不给闸门就不存在这两件工具（默认拒）

        registry.register(
            ToolSpec(
                name = "github_create_pull_request",
                description = "建一个 Pull Request（需要用户确认）。把分支里的改动请求合进另一条分支：" +
                    "先读 PR 现状再提议，用户点头才真建",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"head":{"type":"string","description":"来源分支（改动在这条分支上）"},""" +
                    """"base":{"type":"string","description":"目标分支（改动要合进这条）；缺省用仓库默认分支"},""" +
                    """"title":{"type":"string","description":"PR 标题（1-200 字符）"},""" +
                    """"body":{"type":"string","description":"PR 说明（可空，最多 2 万字符）"},""" +
                    """"draft":{"type":"boolean","description":"true=草稿 PR（缺省 false）"}},""" +
                    """"required":["head","title"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val head = refArg(args, "head")
            var base = optStr(args, "base") ?: ""
            if (base.isEmpty()) {
                base = try {
                    prClient.repoDefaultBranch(repo, token(loadToken))
                } catch (e: PrRequestFailed) {
                    null
                } ?: return@register "读不到 $repo 的默认分支（令牌没填或网络不通）：把 base 参数写明确再试。终态：未建。"
            }
            requireRefName(base)
            if (head == base) {
                return@register "head 和 base 是同一条分支（$head）：PR 没有方向，不建。终态：未建。"
            }
            val title = reqStr(args, "title", "PR 标题，1-200 字符").trim()
            if (title.length !in 1..MAX_TITLE_CHARS) {
                return@register "标题 ${title.length} 字符，合法区间 1-$MAX_TITLE_CHARS。终态：未建。"
            }
            val body = optStr(args, "body") ?: ""
            if (body.length > MAX_BODY_CHARS) {
                return@register "PR 说明 ${body.length} 字符，超了上限 $MAX_BODY_CHARS：删减或拆分。终态：未建。"
            }
            val draft = (args["draft"] as? JsonPrimitive)?.content == "true"
            val approved = confirmer.confirm(
                GitHubPrProposal("建 PR", repo, head, base, title, draft, 0, "", ""),
            )
            if (!approved) {
                return@register "用户没有确认这次建 PR（或等待超时）：什么都没建。终态：未建。"
            }
            val pull = prClient.createPullRequest(repo, head, base, title, body, draft, token(loadToken))
            "已建 PR #${pull.number}：$head -> $pull.baseRef（${if (pull.draft) "草稿" else "正式"}）" +
                "head ${pull.headSha.take(8)}；${pull.htmlUrl}。终态：建 PR 成功。"
        }

        registry.register(
            ToolSpec(
                name = "github_merge_pull_request",
                description = "合并一个 Pull Request（需要用户确认，SHA 钉死）。模型先读 PR 号与当前 head 提交，" +
                    "用户对着确认卡点头才真合；确认后分支又动了会失败，不合错版本",
                parametersJson = """{"type":"object","properties":{""" +
                    """"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},""" +
                    """"number":{"type":"integer","description":"PR 号（从建 PR 的结果或 PR 列表里拿）"},""" +
                    """"expected_head_sha":{"type":"string","description":"确认时 PR head 的完整 40 位 sha（钉死版本用）"},""" +
                    """"method":{"type":"string","description":"合并方式：merge/squash/rebase（缺省 merge）"},""" +
                    """"commit_title":{"type":"string","description":"合并提交的标题（可空）"}},""" +
                    """"required":["number","expected_head_sha"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = (args["number"] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull() ?: 0L
            if (number <= 0) {
                return@register "PR 号必须是正整数（现在是 $number）。终态：未合。"
            }
            val sha = reqStr(args, "expected_head_sha", "确认时的 PR head 完整 40 位 sha")
            if (!sha.matches(Regex("[0-9a-fA-F]{40}"))) {
                return@register "expected_head_sha 要 40 位十六进制（现在是 $sha）：先拉 PR 现状再提议。终态：未合。"
            }
            val method = (optStr(args, "method") ?: "merge").lowercase()
            if (method !in MERGE_METHODS) {
                return@register "合并方式只认 ${MERGE_METHODS.joinToString("/")}（现在是 $method）。终态：未合。"
            }
            val commitTitle = optStr(args, "commit_title") ?: ""
            // 对账：卡片上的 sha 必须就是 PR 现在的 sha——对不上（确认期间又进了提交）直接停
            val live = try {
                prClient.pullHeadSha(repo, number, token(loadToken))
            } catch (e: PrRequestFailed) {
                return@register "读 PR #$number 失败：${e.message}。终态：未合（先看 PR 现状）。"
            }
            if (live == null || !live.equals(sha, ignoreCase = true)) {
                return@register "PR #$number 的 head 已经不是确认时的版本（现在 ${live?.take(8) ?: "读不到"}，" +
                    "卡片上是 ${sha.take(8)}）：重新读 PR 现状再提议。终态：未合（防合错版本）。"
            }
            val approved = confirmer.confirm(
                GitHubPrProposal("合 PR", repo, "", "", "", false, number, sha, method),
            )
            if (!approved) {
                return@register "用户没有确认这次合并（或等待超时）：什么都没合。终态：未合。"
            }
            val outcome = prClient.mergePullRequest(repo, number, sha, method, commitTitle, token(loadToken))
            if (!outcome.merged) {
                return@register "GitHub 回了没合上（merged=false）：${outcome.message}。终态：合并失败（仓库没变）。"
            }
            "已合 PR #$number（$method，合并提交 ${outcome.sha?.take(8) ?: "?"}）。终态：合并成功。"
        }
        return registry
    }

    // ---------- 内部件 ----------

    private fun token(loadToken: () -> String?): String? =
        loadToken()?.trim()?.takeIf { it.isNotEmpty() }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { Json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun optStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun reqStr(args: JsonObject, key: String, hint: String): String =
        optStr(args, key) ?: throw IllegalArgumentException("缺参数 $key（$hint）")

    private fun repoArg(args: JsonObject, defaultRepo: () -> String?): String =
        optStr(args, "repo") ?: defaultRepo()?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw IllegalArgumentException("缺参数 repo（owner/name 写法；也可以先去设置「GitHub 工作台」填默认仓库）")

    private fun refArg(args: JsonObject, key: String): String {
        val v = reqStr(args, key, "分支名，如 workbench/xxx")
        requireRefName(v)
        return v
    }

    /** git ref 名的硬校验：禁控制字符、空格、.. 、以 - 开头、尾部 .lock（对齐旧 Agora requireValidRef）。 */
    private fun requireRefName(ref: String) {
        val ok = ref.isNotEmpty() && ref.length <= 200 &&
            !ref.startsWith("-") && !ref.endsWith(".lock") &&
            ".." !in ref && ref.none { it.code < 0x20 || it == ' ' || it == '~' || it == '^' || it == ':' || it == '?' || it == '[' || it == '*' || it == '\\' }
        if (!ok) throw IllegalArgumentException("分支名不合法（$ref）：不许空格/控制字符/双点/波浪号/反斜杠，不许 - 开头，不许 .lock 结尾")
    }

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
