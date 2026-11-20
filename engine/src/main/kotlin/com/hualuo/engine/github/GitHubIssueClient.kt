package com.hualuo.engine.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** issue 清单里的一条。 */
data class GitHubIssueSummary(
    val number: Long,
    val title: String,
    val state: String,
    val user: String,
    val labels: List<String>,
    val comments: Int,
    val updatedAt: String,
)

/**
 * issue 清单回执。
 *
 * [skippedPullRequests] 是**必须报**的数：GitHub 的 /issues 接口会把 PR 混进来，
 * 不筛掉的话「列 issue」会莫名其妙冒出 PR——这是该接口的著名坑，不是读失败。
 */
data class GitHubIssueList(
    val issues: List<GitHubIssueSummary>,
    val badEntries: Int,
    val skippedPullRequests: Int,
    val error: String?,
)

/** 单个 issue 的详情。 */
data class GitHubIssueDetail(
    val number: Long,
    val title: String,
    val state: String,
    val user: String,
    val labels: List<String>,
    val body: String,
    val comments: Int,
    val htmlUrl: String,
    val error: String?,
)

/** 写 issue 的收场（建 / 评论 / 关闭共用）。 */
data class GitHubIssueWritten(val number: Long, val htmlUrl: String, val error: String?)

/**
 * issue 客户端（P0 十四件最后一块）：列、读、建、评论、关。
 *
 * 断点背景：issue 全家此前为零——用户贴一个 bug 报告链接，Agent 连读都读不到。
 *
 * 纪律：
 *  - /issues 会混进 PR，必须按 `pull_request` 字段筛掉并**报出筛了几条**（坑要点名）；
 *  - 正文（body）在工具层截断，这一层原样带回；
 *  - 关闭走 PATCH {"state":"closed"}（GitHub 的关法），不是 DELETE。
 */
class GitHubIssueClient(
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
    private val postJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPostJson,
    private val patchJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPatchJson,
) {

    fun list(repo: String, state: String = "open", token: String?, limit: Int = 10): GitHubIssueList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubIssueList(emptyList(), 0, 0, repoProblem(repo))
        val safeState = when (state.trim().lowercase()) {
            "open", "closed", "all" -> state.trim().lowercase()
            else -> "open"
        }
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/issues?state=$safeState&per_page=${limit.coerceIn(1, 50)}&sort=updated&direction=desc",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubIssueList(emptyList(), 0, 0, ghIssue(result.status, result.body, "列 issue "))
        val array = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubIssueList(emptyList(), 0, 0, "issue 清单读不懂（200 但不是 JSON 数组）")
        val out = ArrayList<GitHubIssueSummary>()
        var bad = 0
        var pulls = 0
        for (element in array) {
            if (out.size >= limit.coerceIn(1, 50)) break
            if (element !is JsonObject) { bad += 1; continue }
            val item = element
            if (item["pull_request"] != null) { pulls += 1; continue }
            val number = item.lng("number")
            if (number == null) { bad += 1; continue }
            out += GitHubIssueSummary(
                number = number,
                title = item.str("title"),
                state = item.str("state"),
                user = item.obj("user")?.str("login") ?: "",
                labels = labelsOf(item),
                comments = item.num("comments") ?: 0,
                updatedAt = item.str("updated_at"),
            )
        }
        return GitHubIssueList(out, bad, pulls, null)
    }

    fun get(repo: String, number: Long, token: String?): GitHubIssueDetail {
        val full = normalizeGitHubRepo(repo)
            ?: return emptyDetail(number, repoProblem(repo))
        val result = fetch("$GITHUB_API_ROOT/repos/$full/issues/$number", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) return emptyDetail(number, ghIssue(result.status, result.body, "读 issue #$number "))
        val item = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return emptyDetail(number, "issue 详情读不懂（200 但不是 JSON 对象）")
        if (item["pull_request"] != null) {
            return emptyDetail(number, "这个号是 Pull Request，不是 issue：读 PR 用 github_pr_read")
        }
        return GitHubIssueDetail(
            number = item.lng("number") ?: number,
            title = item.str("title"),
            state = item.str("state"),
            user = item.obj("user")?.str("login") ?: "",
            labels = labelsOf(item),
            body = item.str("body"),
            comments = item.num("comments") ?: 0,
            htmlUrl = item.str("html_url"),
            error = null,
        )
    }

    fun create(repo: String, title: String, body: String, labels: List<String>, token: String?): GitHubIssueWritten {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubIssueWritten(0, "", repoProblem(repo))
        if (title.isBlank()) return GitHubIssueWritten(0, "", "标题不能为空")
        val payload = buildString {
            append("{\"title\":").append(ghQuote(title))
            if (body.isNotBlank()) append(",\"body\":").append(ghQuote(body))
            if (labels.isNotEmpty()) {
                append(",\"labels\":[").append(labels.joinToString(",") { ghQuote(it) }).append("]")
            }
            append("}")
        }
        val result = postJson("$GITHUB_API_ROOT/repos/$full/issues", token, payload)
        if (result.status != 201) return GitHubIssueWritten(0, "", ghIssue(result.status, result.body, "建 issue "))
        val root = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubIssueWritten(0, "", "建好了但回执读不懂（201 但不是 JSON 对象）：去网页核对")
        return GitHubIssueWritten(root.lng("number") ?: 0, root.str("html_url"), null)
    }

    fun comment(repo: String, number: Long, body: String, token: String?): GitHubIssueWritten {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubIssueWritten(0, "", repoProblem(repo))
        if (body.isBlank()) return GitHubIssueWritten(0, "", "评论内容不能为空")
        val payload = "{\"body\":${ghQuote(body)}}"
        val result = postJson("$GITHUB_API_ROOT/repos/$full/issues/$number/comments", token, payload)
        if (result.status != 201) return GitHubIssueWritten(0, "", ghIssue(result.status, result.body, "评论 issue #$number "))
        val root = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
        val url = root?.str("html_url") ?: ""
        return GitHubIssueWritten(number, url, null)
    }

    fun close(repo: String, number: Long, token: String?): GitHubIssueWritten {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubIssueWritten(0, "", repoProblem(repo))
        val result = patchJson("$GITHUB_API_ROOT/repos/$full/issues/$number", token, "{\"state\":\"closed\"}")
        if (result.status != 200) return GitHubIssueWritten(0, "", ghIssue(result.status, result.body, "关 issue #$number "))
        val root = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
        return GitHubIssueWritten(root?.lng("number") ?: number, root?.str("html_url") ?: "", null)
    }

    private fun labelsOf(item: JsonObject): List<String> {
        val array = item.arr("labels") ?: return emptyList()
        val out = ArrayList<String>()
        for (element in array) {
            val label = element as? JsonObject ?: continue
            val name = label.str("name")
            if (name.isNotEmpty()) out += name
        }
        return out
    }

    private fun emptyDetail(number: Long, error: String): GitHubIssueDetail = GitHubIssueDetail(
        number = number,
        title = "",
        state = "",
        user = "",
        labels = emptyList(),
        body = "",
        comments = 0,
        htmlUrl = "",
        error = error,
    )

    private fun repoProblem(raw: String): String = "仓库写法不对：要 owner/name（现在是「$raw」）"
}
