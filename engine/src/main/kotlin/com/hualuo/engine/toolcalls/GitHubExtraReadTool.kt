package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubActionsClient
import com.hualuo.engine.github.GitHubArtifactList
import com.hualuo.engine.github.GitHubCommitClient
import com.hualuo.engine.github.GitHubCommitDetail
import com.hualuo.engine.github.GitHubDiffText
import com.hualuo.engine.github.GitHubIssueClient
import com.hualuo.engine.github.GitHubIssueDetail
import com.hualuo.engine.github.GitHubIssueList
import com.hualuo.engine.github.GitHubPullDetail
import com.hualuo.engine.github.GitHubPullFileList
import com.hualuo.engine.github.GitHubPullList
import com.hualuo.engine.github.GitHubPrReadClient
import com.hualuo.engine.github.GitHubReleaseClient
import com.hualuo.engine.github.GitHubReleasePage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * GitHub 扩展读类工具（P0 十四件里的「看得见」那一半）：
 * PR 清单 / PR 详情 / PR 改动文件 / commit diff / issue 清单与详情 / 发布列表 / artifact 列表。
 *
 * 为什么这些必须有：原来只有「建/合 PR」和「列提交」，Agent 想知道「这版改了什么、
 * 这次 CI 吐了什么产物、用户报的 issue 说了什么」全都没有入口，闭环只能断在最后一步。
 *
 * 与 [GitHubToolFamily] 同一套纪律：
 *  - 只读，不改变远端状态，所以不需要确认闸门；
 *  - 令牌现场读、只进请求头、绝不进结果文本；
 *  - 结果本身省着写（长正文/长 patch 在客户端已截断并出声）。
 */
object GitHubExtraReadTool {

    fun register(
        registry: ToolRegistry,
        loadToken: () -> String?,
        defaultRepo: () -> String? = { null },
        prClient: GitHubPrReadClient = GitHubPrReadClient(),
        commitClient: GitHubCommitClient = GitHubCommitClient(),
        issueClient: GitHubIssueClient = GitHubIssueClient(),
        releaseClient: GitHubReleaseClient = GitHubReleaseClient(),
        actionsClient: GitHubActionsClient = GitHubActionsClient(),
    ) {
        val token: () -> String? = { loadToken()?.trim()?.takeIf { it.isNotEmpty() } }

        registry.register(
            ToolSpec(
                name = "github_pr_list",
                description = "列仓库的 Pull Request（编号、标题、状态、作者、源分支->目标分支、更新时间）。" +
                    "回答「有哪些 PR 还开着」用这个；repo 缺省用默认仓库",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"state":{"type":"string","description":"open / closed / all，缺省 open"},"limit":{"type":"integer","description":"最多几条，默认 10，范围 1-30"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val state = optStr(args, "state") ?: "open"
            val limit = (optInt(args, "limit") ?: 10).coerceIn(1, 30)
            formatPullList(prClient.list(repo, state, token(), limit), limit)
        }

        registry.register(
            ToolSpec(
                name = "github_pr_read",
                description = "读一个 Pull Request 的详情：标题、正文、作者、head/base、改动行数、是否可合。" +
                    "number 从 github_pr_list 的结果里拿",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"number":{"type":"integer","description":"PR 编号"}},"required":["number"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number") ?: fail("缺参数 number（PR 编号，从 github_pr_list 里拿）")
            formatPullDetail(prClient.detail(repo, number, token()))
        }

        registry.register(
            ToolSpec(
                name = "github_pr_files",
                description = "读一个 Pull Request 改了哪些文件（每文件状态、增删行数、patch）。" +
                    "「这版到底动了什么」用这个；patch 过长会截断并说明",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"number":{"type":"integer","description":"PR 编号"},"limit":{"type":"integer","description":"最多几个文件，默认 30，范围 1-100"}},"required":["number"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number") ?: fail("缺参数 number（PR 编号）")
            val limit = (optInt(args, "limit") ?: 30).coerceIn(1, 100)
            formatPullFiles(prClient.files(repo, number, token(), limit))
        }

        registry.register(
            ToolSpec(
                name = "github_commit_diff",
                description = "看一个提交改了什么。format=json（缺省，按文件给 patch）或 text（整段 unified diff）。" +
                    "sha 从 github_list_commits 的结果里拿",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"sha":{"type":"string","description":"提交 sha（完整或短 sha）"},"format":{"type":"string","description":"json（缺省）或 text"},"max_files":{"type":"integer","description":"json 档最多几个文件，默认 20，范围 1-100"}},"required":["sha"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val sha = reqStr(args, "sha", "提交 sha，从 github_list_commits 里拿")
            val format = (optStr(args, "format") ?: "json").lowercase()
            if (format == "text") {
                formatDiffText(commitClient.diffText(repo, sha, token()))
            } else {
                val maxFiles = (optInt(args, "max_files") ?: 20).coerceIn(1, 100)
                formatCommitDetail(commitClient.detail(repo, sha, token(), maxFiles))
            }
        }

        registry.register(
            ToolSpec(
                name = "github_issue_list",
                description = "列仓库的 issue（编号、标题、状态、作者、标签、评论数）。" +
                    "GitHub 的 issue 接口会把 PR 混进来，这里已筛掉并报出筛了几条",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"state":{"type":"string","description":"open / closed / all，缺省 open"},"limit":{"type":"integer","description":"最多几条，默认 10，范围 1-50"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val state = optStr(args, "state") ?: "open"
            val limit = (optInt(args, "limit") ?: 10).coerceIn(1, 50)
            formatIssueList(issueClient.list(repo, state, token(), limit))
        }

        registry.register(
            ToolSpec(
                name = "github_issue_read",
                description = "读一个 issue 的详情（标题、正文、作者、标签、评论数、页面地址）",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"number":{"type":"integer","description":"issue 编号"}},"required":["number"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number") ?: fail("缺参数 number（issue 编号，从 github_issue_list 里拿）")
            formatIssueDetail(issueClient.get(repo, number, token()))
        }

        registry.register(
            ToolSpec(
                name = "github_release_list",
                description = "列仓库的发布版（tag、名称、是否草稿/预发布、发布时间、附件数、页面地址）",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"limit":{"type":"integer","description":"最多几条，默认 10，范围 1-30"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val limit = (optInt(args, "limit") ?: 10).coerceIn(1, 30)
            formatReleasePage(releaseClient.list(repo, token(), limit), limit)
        }

        registry.register(
            ToolSpec(
                name = "github_artifact_list",
                description = "列 GitHub Actions 的构建产物（artifact：id、名字、大小、是否过期）。" +
                    "给 run_id 就只看那次运行；不给就看仓库最近的全部",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"run_id":{"type":"integer","description":"只看这次运行（从 github_ci_runs 拿）；可空"},"limit":{"type":"integer","description":"最多几条，默认 10，范围 1-30"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val limit = (optInt(args, "limit") ?: 10).coerceIn(1, 30)
            val runId = optLong(args, "run_id")
            val list = if (runId != null) actionsClient.runArtifacts(repo, runId, token(), limit)
            else actionsClient.artifacts(repo, token(), limit)
            formatArtifacts(list, limit)
        }
    }

    // ── 结果文本（省着写；账目出声） ────────────────────────────────

    private fun formatPullList(list: GitHubPullList, limit: Int): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.pulls.isEmpty()) return "一个 PR 也没列出来（state 不对，或这个仓没有 PR）"
        val sb = StringBuilder("共 ${list.pulls.size} 个 PR（本页最多 $limit）：")
        list.pulls.take(limit).forEach { pr ->
            sb.append("\n- #").append(pr.number).append(" [").append(pr.state)
            if (pr.draft) sb.append("/draft")
            sb.append("] ").append(pr.title)
            if (pr.headRef.isNotEmpty()) sb.append("（").append(pr.headRef).append(" -> ").append(pr.baseRef).append("）")
            if (pr.user.isNotEmpty()) sb.append(" @").append(pr.user)
        }
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatPullDetail(detail: GitHubPullDetail): String {
        val error = detail.error
        if (error != null) fail(error)
        val sb = StringBuilder("PR #${detail.number}「${detail.title}」")
        sb.append("\n- 状态：").append(detail.state)
        if (detail.draft) sb.append("（草稿）")
        sb.append("｜作者 @").append(detail.user.ifEmpty { "?" })
        sb.append("\n- 分支：").append(detail.headRef).append(" -> ").append(detail.baseRef)
        if (detail.headSha.isNotEmpty()) sb.append("（head ").append(detail.headSha.take(8)).append("）")
        sb.append("\n- 改动：+").append(detail.additions).append(" / −").append(detail.deletions)
        sb.append("，").append(detail.changedFiles).append(" 个文件")
        val mergeable = detail.mergeable
        sb.append("｜可合：").append(if (mergeable == null) "GitHub 还在算" else if (mergeable) "是" else "否")
        if (detail.htmlUrl.isNotEmpty()) sb.append("\n- 页面：").append(detail.htmlUrl)
        if (detail.body.isNotBlank()) {
            val body = if (detail.body.length <= BODY_CHARS) detail.body
            else detail.body.take(BODY_CHARS) + "…（正文被截断，原文 ${detail.body.length} 字符）"
            sb.append("\n- 正文：\n").append(body)
        }
        return sb.toString()
    }

    private fun formatPullFiles(list: GitHubPullFileList): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.files.isEmpty()) return "这个 PR 没有改动文件（或者只有空提交）"
        val sb = StringBuilder("共 ${list.files.size} 个文件：")
        list.files.forEach { file ->
            sb.append("\n\n### ").append(file.filename)
            sb.append(" [").append(file.status).append("] +").append(file.additions).append(" / −").append(file.deletions)
            if (file.previousFilename.isNotEmpty()) sb.append("（原 ").append(file.previousFilename).append("）")
            if (file.patch.isNotBlank()) sb.append("\n").append(file.patch)
        }
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        val text = sb.toString()
        return if (text.length <= MAX_TEXT_CHARS) text
        else text.take(MAX_TEXT_CHARS) + "\n…（整个清单被截到 $MAX_TEXT_CHARS 字符：要剩下的就分段读）"
    }

    private fun formatCommitDetail(detail: GitHubCommitDetail): String {
        val error = detail.error
        if (error != null) fail(error)
        val sb = StringBuilder("提交 ${detail.sha.take(12)}")
        if (detail.date.isNotEmpty()) sb.append(" ").append(detail.date)
        if (detail.author.isNotEmpty()) sb.append(" @").append(detail.author)
        sb.append("\n- 说明：").append(detail.message.lineSequence().firstOrNull()?.take(200) ?: "")
        sb.append("\n- 统计：+").append(detail.additions).append(" / −").append(detail.deletions)
        sb.append("（合计 ").append(detail.total).append(" 行）｜").append(detail.files.size).append(" 个文件")
        detail.files.forEach { file ->
            sb.append("\n\n### ").append(file.filename).append(" [").append(file.status).append("]")
            sb.append(" +").append(file.additions).append(" / −").append(file.deletions)
            if (file.patch.isNotBlank()) sb.append("\n").append(file.patch)
        }
        if (detail.badEntries > 0) sb.append("\n（另有 ${detail.badEntries} 条读不懂，已跳过）")
        val text = sb.toString()
        return if (text.length <= MAX_TEXT_CHARS) text
        else text.take(MAX_TEXT_CHARS) + "\n…（被截到 $MAX_TEXT_CHARS 字符：要剩下的就单看某个文件）"
    }

    private fun formatDiffText(diff: GitHubDiffText): String {
        val error = diff.error
        if (error != null) fail(error)
        if (diff.text.isBlank()) return "这个提交没有文本 diff（可能是空提交，或只有二进制文件改动）"
        val note = if (diff.truncated) "\n…（diff 超出有界读上限，后面没给）" else ""
        return diff.text + note
    }

    private fun formatIssueList(list: GitHubIssueList): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.issues.isEmpty()) {
            val skipped = if (list.skippedPullRequests > 0) "（另有 ${list.skippedPullRequests} 条是 PR，已筛掉）" else ""
            return "一个 issue 也没列出来$skipped"
        }
        val sb = StringBuilder("共 ${list.issues.size} 个 issue：")
        list.issues.forEach { issue ->
            sb.append("\n- #").append(issue.number).append(" [").append(issue.state).append("] ").append(issue.title)
            if (issue.user.isNotEmpty()) sb.append(" @").append(issue.user)
            if (issue.labels.isNotEmpty()) sb.append("（").append(issue.labels.joinToString("、")).append("）")
            if (issue.comments > 0) sb.append(" 评论 ").append(issue.comments)
        }
        if (list.skippedPullRequests > 0) sb.append("\n（另有 ${list.skippedPullRequests} 条是 PR，已筛掉）")
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatIssueDetail(detail: GitHubIssueDetail): String {
        val error = detail.error
        if (error != null) fail(error)
        val sb = StringBuilder("#${detail.number}「${detail.title}」")
        sb.append("\n- 状态：").append(detail.state)
        if (detail.user.isNotEmpty()) sb.append("｜作者 @").append(detail.user)
        if (detail.labels.isNotEmpty()) sb.append("｜标签：").append(detail.labels.joinToString("、"))
        sb.append("｜评论 ").append(detail.comments)
        if (detail.htmlUrl.isNotEmpty()) sb.append("\n- 页面：").append(detail.htmlUrl)
        if (detail.body.isNotBlank()) {
            val body = if (detail.body.length <= BODY_CHARS) detail.body
            else detail.body.take(BODY_CHARS) + "…（正文被截断，原文 ${detail.body.length} 字符）"
            sb.append("\n- 正文：\n").append(body)
        }
        return sb.toString()
    }

    private fun formatReleasePage(page: GitHubReleasePage, limit: Int): String {
        val error = page.error
        if (error != null) fail(error)
        if (page.releases.isEmpty()) return "这个仓库还没有发布版"
        val sb = StringBuilder("共 ${page.releases.size} 个发布版（本页最多 $limit）：")
        page.releases.take(limit).forEach { release ->
            sb.append("\n- ").append(release.tag)
            if (release.name.isNotEmpty()) sb.append("「").append(release.name).append("」")
            sb.append("（id=").append(release.id)
            if (release.draft) sb.append("，草稿")
            if (release.prerelease) sb.append("，预发布")
            sb.append("，附件 ").append(release.assetCount)
            if (release.publishedAt.isNotEmpty()) sb.append("，发布于 ").append(release.publishedAt)
            sb.append("）")
        }
        if (page.badEntries > 0) sb.append("\n（另有 ${page.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatArtifacts(list: GitHubArtifactList, limit: Int): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.artifacts.isEmpty()) return "没有 artifact（可能这次运行没上传产物，或已经过期被清）"
        val sb = StringBuilder("共 ${list.artifacts.size} 件 artifact（本页最多 $limit）：")
        list.artifacts.take(limit).forEach { artifact ->
            sb.append("\n- artifact ").append(artifact.id).append("「").append(artifact.name).append("」")
            sb.append(" ").append(formatBytes(artifact.sizeBytes))
            if (artifact.expired) sb.append("（已过期，下不了）")
            artifact.runId?.let { sb.append(" run=").append(it) }
        }
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    /** 结果文本总量上限（再往上由 ToolResultTrimmer 兜底，这里先自己收口）。 */
    private const val MAX_TEXT_CHARS = 12_000

    /** PR / issue 正文的展示上限。 */
    private const val BODY_CHARS = 3_000

    // ── 参数解析小件 ───────────────────────────────────────────────

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun optStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun reqStr(args: JsonObject, key: String, hint: String): String =
        optStr(args, key) ?: fail("缺参数 $key（$hint）")

    private fun optInt(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun optLong(args: JsonObject, key: String): Long? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

    private fun repoArg(args: JsonObject, defaultRepo: () -> String?): String =
        optStr(args, "repo") ?: defaultRepo()?.trim()?.takeIf { it.isNotEmpty() }
        ?: fail("缺参数 repo（owner/name 写法；也可以省略以使用默认仓库）")

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
