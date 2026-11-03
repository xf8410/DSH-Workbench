package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一处代码命中：文件名 + 仓库内路径（点开就是文件预览）。 */
data class GitHubCodeHit(val name: String, val path: String)

/**
 * 仓库内代码搜索的回执：hits（本页命中）+ totalCount（GitHub 的总账）+
 * incomplete（GitHub 自己标「结果可能不全」）+ badEntries（读不懂的条目数）；
 * error 非 null 时 hits 必为空——失败绝不拿半份名单冒充成功。
 */
data class GitHubCodeSearchResult(
    val hits: List<GitHubCodeHit>,
    val totalCount: Int,
    val incomplete: Boolean,
    val badEntries: Int,
    val error: String?,
)

/**
 * 仓库内代码搜索客户端（纯 JVM；fetch 注入，JVM 测试不碰网络）。
 *
 * 走 GitHub 的 /search/code 接口。两条硬规矩（GitHub 定的，不是我们的选择）：
 *  - **必须带令牌**：没令牌不发请求，先出声指路；
 *  - **只搜默认分支**：切了分支也还是默认分支的账，界面要如实说。
 * 配额紧（每分钟十来回）：403 多数是配额到了，归因成「等一分钟」而不是含糊说
 * 「没权限」；422 = 搜索词写法不对。query 逐段编码（空格不吃成加号），
 * 令牌只进请求头，响应有界读，失败一律人话（状态码，绝无令牌）。
 */
class GitHubSearchClient(
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
) {

    /** 搜 [repo] 里的 [keyword]；无令牌、空词、坏仓写法都在发网之前拦下并说人话。 */
    fun searchCode(repo: String, keyword: String, token: String?, limit: Int = 20): GitHubCodeSearchResult {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubCodeSearchResult(emptyList(), 0, false, 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val query = keyword.trim()
        if (query.isEmpty()) {
            return GitHubCodeSearchResult(emptyList(), 0, false, 0, "搜索词是空的：先写点什么（类名、函数名、报错原文都行）")
        }
        if (token.isNullOrBlank()) {
            return GitHubCodeSearchResult(
                emptyList(), 0, false, 0,
                "代码搜索必须带令牌（GitHub 搜索接口的死规矩）：去设置「GitHub 工作台」填",
            )
        }
        val capped = limit.coerceIn(1, 50)
        val encoded = java.net.URLEncoder.encode("$query repo:$full", "UTF-8").replace("+", "%20")
        val result = fetch("$GITHUB_API_ROOT/search/code?q=$encoded&per_page=$capped", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status == 403) {
            return GitHubCodeSearchResult(
                emptyList(), 0, false, 0,
                "GitHub 不让搜（403）：多半是搜索配额到了（这接口限得紧），等一分钟再来；私有仓还要令牌有该仓权限",
            )
        }
        if (result.status == 422) {
            return GitHubCodeSearchResult(emptyList(), 0, false, 0, "搜索词写法不对（422）：换个词再试")
        }
        if (result.status != 200) {
            return GitHubCodeSearchResult(emptyList(), 0, false, 0, httpIssue(result))
        }
        val root = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubCodeSearchResult(emptyList(), 0, false, 0, "GitHub 回的内容读不懂（200 但不是搜索结果）")
        val total = (root["total_count"] as? JsonPrimitive)?.contentOrNull?.toIntOrNull() ?: 0
        val incomplete = (root["incomplete_results"] as? JsonPrimitive)?.contentOrNull?.toBooleanStrictOrNull() ?: false
        val items = root["items"] as? kotlinx.serialization.json.JsonArray
            ?: return GitHubCodeSearchResult(emptyList(), total, incomplete, 0, "GitHub 回的形状变了（没找到 items）")
        val hits = ArrayList<GitHubCodeHit>()
        var bad = 0
        for (element in items) {
            if (hits.size >= capped) break
            if (element !is JsonObject) { bad += 1; continue }
            val name = (element["name"] as? JsonPrimitive)?.contentOrNull
            val path = (element["path"] as? JsonPrimitive)?.contentOrNull
            if (name.isNullOrEmpty() || path.isNullOrEmpty()) { bad += 1; continue }
            hits += GitHubCodeHit(name, path)
        }
        return GitHubCodeSearchResult(hits, total, incomplete, bad, null)
    }

    /** 失败的人话：只说状态码与该干什么，原文不抄（错误页可能一整页 HTML）。 */
    private fun httpIssue(result: GitHubHttpResult): String = when {
        result.status == 0 -> "连不上 GitHub：${brief(result.body)}"
        result.status == 401 -> "GitHub 不认这把令牌（401）：去设置里核对或换新令牌"
        result.status == 404 -> "GitHub 说没这个仓库（404）：核对仓库名；私有仓还要令牌有权限"
        else -> "GitHub 回了 ${result.status}，稍后再试"
    }

    private fun brief(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 160) flat else flat.take(160) + "…（已截断）"
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
