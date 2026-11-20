package com.hualuo.engine.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** commit 改动里的一个文件。 */
data class GitHubCommitFile(
    val filename: String,
    val status: String,
    val additions: Int,
    val deletions: Int,
    val patch: String,
)

/** 一个提交的详情：谁、什么时候、说了什么、改了多少、改了哪些文件。 */
data class GitHubCommitDetail(
    val sha: String,
    val message: String,
    val author: String,
    val date: String,
    val additions: Int,
    val deletions: Int,
    val total: Int,
    val files: List<GitHubCommitFile>,
    val badEntries: Int,
    val error: String?,
)

/** 纯文本 diff 回执（GitHub 的 .diff 档，已按上限截断并出声）。 */
data class GitHubDiffText(val text: String, val truncated: Boolean, val error: String?)

/**
 * 提交 diff 客户端（P0 十四件之一）。
 *
 * 断点背景：github_list_commits 只给 message，Agent 想看「这版改了什么」只能盲猜；
 * 这里两条路都开：
 *  - [detail] 走 JSON（结构化：每文件 +/− 行数与 patch），适合「定位到某几个文件」；
 *  - [diffText] 走 `application/vnd.github.diff` 纯文本，适合「整段贴给人看」。
 *
 * 纪律：patch 与 diff 都会截断，截断必须出声（truncated / 尾部说明），不许当完整账。
 */
class GitHubCommitClient(
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
) {

    fun detail(repo: String, sha: String, token: String?, maxFiles: Int = 20): GitHubCommitDetail {
        val full = normalizeGitHubRepo(repo)
            ?: return emptyDetail(sha, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val clean = sha.trim()
        if (clean.isEmpty()) return emptyDetail(sha, "缺 commit sha")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/commits/$clean", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) return emptyDetail(sha, ghIssue(result.status, result.body, "读提交 $clean "))
        val root = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return emptyDetail(sha, "提交详情读不懂（200 但不是 JSON 对象）")
        val stats = root.obj("stats")
        val commit = root.obj("commit")
        val array = root.arr("files") ?: JsonArray(emptyList())
        val files = ArrayList<GitHubCommitFile>()
        var bad = 0
        for (element in array) {
            if (files.size >= maxFiles.coerceIn(1, 100)) break
            if (element !is JsonObject) { bad += 1; continue }
            val item = element
            val name = item.str("filename")
            if (name.isEmpty()) { bad += 1; continue }
            val raw = item.str("patch")
            files += GitHubCommitFile(
                filename = name,
                status = item.str("status"),
                additions = item.num("additions") ?: 0,
                deletions = item.num("deletions") ?: 0,
                patch = if (raw.length <= MAX_PATCH_CHARS) raw
                else raw.take(MAX_PATCH_CHARS) + "\n…（这个文件的 patch 被截断，原文 ${raw.length} 字符）",
            )
        }
        return GitHubCommitDetail(
            sha = root.str("sha").ifEmpty { clean },
            message = commit?.str("message") ?: "",
            author = commit?.obj("author")?.str("name") ?: root.obj("author")?.str("login") ?: "",
            date = commit?.obj("author")?.str("date") ?: "",
            additions = stats?.num("additions") ?: 0,
            deletions = stats?.num("deletions") ?: 0,
            total = stats?.num("total") ?: 0,
            files = files,
            badEntries = bad,
            error = null,
        )
    }

    /**
     * 整个提交的纯文本 diff（`application/vnd.github.diff`）。
     * [maxChars] 是硬上限：GitHub 的 diff 可能几 MB，超出部分直接不要并标 truncated。
     */
    fun diffText(repo: String, sha: String, token: String?, maxChars: Int = DEFAULT_MAX_DIFF_CHARS): GitHubDiffText {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubDiffText("", false, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val clean = sha.trim()
        if (clean.isEmpty()) return GitHubDiffText("", false, "缺 commit sha")
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/commits/$clean",
            token,
            "application/vnd.github.diff",
            maxChars.coerceIn(1_000, GITHUB_MAX_BODY_CHARS),
        )
        if (result.status != 200) return GitHubDiffText("", false, ghIssue(result.status, result.body, "读提交 $clean 的 diff "))
        return GitHubDiffText(result.body, result.truncated, null)
    }

    private fun emptyDetail(sha: String, error: String): GitHubCommitDetail = GitHubCommitDetail(
        sha = sha,
        message = "",
        author = "",
        date = "",
        additions = 0,
        deletions = 0,
        total = 0,
        files = emptyList(),
        badEntries = 0,
        error = error,
    )

    companion object {
        /** 单文件 patch 的上下文上限。 */
        const val MAX_PATCH_CHARS = 6_000

        /** 整段 diff 的默认上限（48 万字符，接近有界读封顶）。 */
        const val DEFAULT_MAX_DIFF_CHARS = 480_000
    }
}
