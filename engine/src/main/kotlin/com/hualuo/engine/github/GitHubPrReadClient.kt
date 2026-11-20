package com.hualuo.engine.github

import kotlinx.serialization.json.JsonObject

/** PR 清单里的一条（就界面关心的字段）。 */
data class GitHubPullSummary(
    val number: Long,
    val title: String,
    val state: String,
    val draft: Boolean,
    val user: String,
    val headRef: String,
    val baseRef: String,
    val updatedAt: String,
    val htmlUrl: String,
)

data class GitHubPullList(val pulls: List<GitHubPullSummary>, val badEntries: Int, val error: String?)

/** 单个 PR 的详情（读得比清单多：正文、改动量、可合状态）。 */
data class GitHubPullDetail(
    val number: Long,
    val title: String,
    val state: String,
    val draft: Boolean,
    val user: String,
    val body: String,
    val headRef: String,
    val baseRef: String,
    val headSha: String,
    val additions: Int,
    val deletions: Int,
    val changedFiles: Int,
    val mergeable: Boolean?,
    val htmlUrl: String,
    val error: String?,
)

/** PR 改动文件清单里的一条（patch 会截断，截了就出声）。 */
data class GitHubPullFile(
    val filename: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val previousFilename: String,
    val patch: String,
)

data class GitHubPullFileList(val files: List<GitHubPullFile>, val badEntries: Int, val error: String?)

/**
 * PR 的**读**（P0 十四件）：列 PR、读一个 PR、读 PR 的 files changed。
 *
 * 为什么这三件是断点：之前只能建/合，Agent 想看「这版到底改了什么」只能盲猜，
 * 修 bug 前拿不到 diff——读不到就等于闭环断在最后一步。
 *
 * 纪律：patch 每文件截到 [MAX_PATCH_CHARS]（多文件 PR 的 patch 能到几 MB，
 * 全塞进上下文会把这一轮撑爆）；截断必须出声，不许静默当完整。
 */
class GitHubPrReadClient(
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
) {

    fun list(repo: String, state: String = "open", token: String?, limit: Int = 10): GitHubPullList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubPullList(emptyList(), 0, repoProblem(repo))
        val safeState = when (state.trim().lowercase()) {
            "open", "closed", "all" -> state.trim().lowercase()
            else -> "open"
        }
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/pulls?state=$safeState&per_page=${limit.coerceIn(1, 30)}&sort=updated&direction=desc",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubPullList(emptyList(), 0, ghIssue(result.status, result.body, "列 PR "))
        val array = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? kotlinx.serialization.json.JsonArray
            ?: return GitHubPullList(emptyList(), 0, "PR 清单读不懂（200 但不是 JSON 数组）")
        val out = ArrayList<GitHubPullSummary>()
        var bad = 0
        for (element in array) {
            if (out.size >= limit.coerceIn(1, 30)) break
            if (element !is JsonObject) { bad += 1; continue }
            val item = element
            val number = item.lng("number")
            if (number == null) { bad += 1; continue }
            out += GitHubPullSummary(
                number = number,
                title = item.str("title"),
                state = item.str("state"),
                draft = item.flag("draft"),
                user = item.obj("user")?.str("login") ?: "",
                headRef = item.obj("head")?.str("ref") ?: "",
                baseRef = item.obj("base")?.str("ref") ?: "",
                updatedAt = item.str("updated_at"),
                htmlUrl = item.str("html_url"),
            )
        }
        return GitHubPullList(out, bad, null)
    }

    fun detail(repo: String, number: Long, token: String?): GitHubPullDetail {
        val full = normalizeGitHubRepo(repo)
            ?: return emptyDetail(number, repoProblem(repo))
        val result = fetch("$GITHUB_API_ROOT/repos/$full/pulls/$number", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) return emptyDetail(number, ghIssue(result.status, result.body, "读 PR #$number "))
        val item = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return emptyDetail(number, "PR 详情读不懂（200 但不是 JSON 对象）")
        val mergeableRaw = item["mergeable"]
        return GitHubPullDetail(
            number = item.lng("number") ?: number,
            title = item.str("title"),
            state = item.str("state"),
            draft = item.flag("draft"),
            user = item.obj("user")?.str("login") ?: "",
            body = item.str("body"),
            headRef = item.obj("head")?.str("ref") ?: "",
            baseRef = item.obj("base")?.str("ref") ?: "",
            headSha = item.obj("head")?.str("sha") ?: "",
            additions = item.num("additions") ?: 0,
            deletions = item.num("deletions") ?: 0,
            changedFiles = item.num("changed_files") ?: 0,
            mergeable = when ((mergeableRaw as? kotlinx.serialization.json.JsonPrimitive)?.content) {
                "true" -> true
                "false" -> false
                else -> null
            },
            htmlUrl = item.str("html_url"),
            error = null,
        )
    }

    fun files(repo: String, number: Long, token: String?, limit: Int = 30): GitHubPullFileList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubPullFileList(emptyList(), 0, repoProblem(repo))
        val perPage = limit.coerceIn(1, 100)
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/pulls/$number/files?per_page=$perPage",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubPullFileList(emptyList(), 0, ghIssue(result.status, result.body, "读 PR #$number 的改动文件 "))
        val array = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? kotlinx.serialization.json.JsonArray
            ?: return GitHubPullFileList(emptyList(), 0, "改动文件清单读不懂（200 但不是 JSON 数组）")
        val out = ArrayList<GitHubPullFile>()
        var bad = 0
        for (element in array) {
            if (out.size >= perPage) break
            if (element !is JsonObject) { bad += 1; continue }
            val item = element
            val name = item.str("filename")
            if (name.isEmpty()) { bad += 1; continue }
            val rawPatch = item.str("patch")
            out += GitHubPullFile(
                filename = name,
                status = item.str("status"),
                additions = item.num("additions") ?: 0,
                deletions = item.num("deletions") ?: 0,
                previousFilename = item.str("previous_filename"),
                patch = if (rawPatch.length <= MAX_PATCH_CHARS) rawPatch
                else rawPatch.take(MAX_PATCH_CHARS) + "\n…（这个文件的 patch 被截断，原文 ${rawPatch.length} 字符）",
            )
        }
        return GitHubPullFileList(out, bad, null)
    }

    private fun emptyDetail(number: Long, error: String): GitHubPullDetail = GitHubPullDetail(
        number = number,
        title = "",
        state = "",
        draft = false,
        user = "",
        body = "",
        headRef = "",
        baseRef = "",
        headSha = "",
        additions = 0,
        deletions = 0,
        changedFiles = 0,
        mergeable = null,
        htmlUrl = "",
        error = error,
    )

    private fun repoProblem(raw: String): String = "仓库写法不对：要 owner/name（现在是「$raw」）"

    companion object {
        /** 单文件 patch 的上下文上限。 */
        const val MAX_PATCH_CHARS = 8_000

        /** 详情正文的上限（PR 描述可能贴一整篇日志）。 */
        const val MAX_BODY_CHARS = 8_000
    }
}
