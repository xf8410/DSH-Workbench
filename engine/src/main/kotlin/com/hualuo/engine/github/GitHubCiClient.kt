package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次 GitHub HTTP 的最小回执：状态码 + 有界读进的 body + 是否被截断。status=0 = 连都没连上。
 *
 * [scopes] 是 X-OAuth-Scopes 响应头的原文（登录验证用：这枚令牌实际拥有的权限清单）。
 * 默认空串 = 这次响应没有这份声明（细粒度令牌通常就没有）——**空不是失败**，
 * 调用方别把「没声明」当成「没权限」。
 */
data class GitHubHttpResult(
    val status: Int,
    val body: String,
    val truncated: Boolean = false,
    val scopes: String = "",
)

/** 一条 workflow run 的界面字段（就展示这些，别的字段不进内存）。 */
data class GitHubRun(
    val id: Long,
    val name: String,
    val headSha: String,
    val status: String,
    /** null = 还没跑完（queued/in_progress），界面拿 status 说事。 */
    val conclusion: String?,
    val createdAt: String,
)

/** 拉一次 run 列表的回执：读不动的条目单独计数，错误带人话（状态码，绝无令牌）。 */
data class GitHubCiSnapshot(val runs: List<GitHubRun>, val badEntries: Int, val error: String?)

data class GitHubRelease(val tag: String, val name: String?, val publishedAt: String?)

/** 最新发布版查询回执：404 单独说（还没发布过 ≠ 网络坏）。 */
data class GitHubReleaseResult(val release: GitHubRelease?, val notFound: Boolean, val error: String?)

/** 发布列表回执（448 续刀）：读不动的条目单独计数，错误带人话。 */
data class GitHubReleaseList(val releases: List<GitHubRelease>, val badEntries: Int, val error: String?)

/** 手动触发 workflow（449）回执：204=触发成功，其他状态码带人话。 */
data class GitHubDispatchResult(val dispatched: Boolean, val httpStatus: Int?, val error: String?)

/**
 * workflow_runs 清单的**共用解析**（CI 客户端与仓库工作台客户端双端共一份，防双源坑）：
 * 只取界面字段（id/name/head_sha/status/conclusion/created_at），读不动的条目计数不静默。
 */
internal fun parseWorkflowRuns(body: String, limit: Int): GitHubCiSnapshot {
    val root = runCatching { Json { ignoreUnknownKeys = true }.parseToJsonElement(body) }.getOrNull() as? JsonObject
        ?: return GitHubCiSnapshot(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是 JSON）")
    val array = root["workflow_runs"] as? JsonArray
        ?: return GitHubCiSnapshot(emptyList(), 0, "GitHub 回的形状变了（没找到 workflow_runs）")
    val runs = ArrayList<GitHubRun>()
    var bad = 0
    for (element in array) {
        if (runs.size >= limit.coerceIn(1, 20)) break
        if (element !is JsonObject) { bad += 1; continue }
        val id = (element["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
        val sha = (element["head_sha"] as? JsonPrimitive)?.contentOrNull
        val status = (element["status"] as? JsonPrimitive)?.contentOrNull
        if (id == null || sha.isNullOrEmpty() || status.isNullOrEmpty()) { bad += 1; continue }
        runs += GitHubRun(
            id = id,
            name = (element["name"] as? JsonPrimitive)?.contentOrNull ?: "",
            headSha = sha,
            status = status,
            conclusion = (element["conclusion"] as? JsonPrimitive)?.contentOrNull,
            createdAt = (element["created_at"] as? JsonPrimitive)?.contentOrNull ?: "",
        )
    }
    return GitHubCiSnapshot(runs, bad, null)
}

/**
 * 仓库写法校验：要 owner/name 一刀两段。容忍粘进来整个 GitHub 链接（自动剥前缀），
 * 拒绝多段、空段、带空白/?/#与「..」的写法——防 URL 拼出意外路径。
 * 返回 null = 写法不对，调用方必须出声，不许拿半截 URL 去撞网络。
 */
fun normalizeGitHubRepo(raw: String): String? {
    var t = raw.trim()
    listOf("https://github.com/", "http://github.com/", "github.com/").forEach { p ->
        if (t.startsWith(p, ignoreCase = true)) t = t.substring(p.length)
    }
    t = t.trimEnd('/')
    val parts = t.split('/')
    if (parts.size != 2) return null
    if (parts.any { it.isEmpty() || it == "." || it == ".." }) return null
    if (parts.any { p -> p.any { c -> c.isWhitespace() || c == '?' || c == '#' } }) return null
    return "${parts[0]}/${parts[1]}"
}

/**
 * GitHub Actions / Releases 的**只读**小客户端（纯 JVM，不碰 Android；fetch 可注入，
 * JVM 测试不碰网络）。
 *
 * 安全规矩：令牌只进请求头，绝不进任何报错、日志与界面文本——错误里只带状态码；
 * 响应有界读（512KB 封顶），GitHub 错误页再大也只取前一段。
 */
class GitHubCiClient(
    private val fetch: (String, String?) -> GitHubHttpResult = ::defaultCiFetch,
    private val fetchPost: (String, String?, String) -> GitHubHttpResult = { url, token, body ->
        githubHttpPostJson(url, token, body)
    },
) {

    /**
     * 默认分支的最近几条 workflow run（新在前是 GitHub 的顺序，这里不再重排）。
     * error 非 null 时 runs 必为空——失败就不拿半份名单冒充成功。
     */
    fun latestRuns(repo: String, token: String?, limit: Int = 3): GitHubCiSnapshot {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubCiSnapshot(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/actions/runs?per_page=${limit.coerceIn(1, 20)}", token)
        if (result.status != 200) return GitHubCiSnapshot(emptyList(), 0, httpIssue(result))
        return parseWorkflowRuns(result.body, limit)
    }

    /** 最新发布版：404 单独回 notFound（还没发布过），其余失败带状态码人话。 */
    fun latestRelease(repo: String, token: String?): GitHubReleaseResult {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubReleaseResult(null, false, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/releases/latest", token)
        return when {
            result.status == 404 -> GitHubReleaseResult(null, true, null)
            result.status != 200 -> GitHubReleaseResult(null, false, httpIssue(result))
            else -> {
                val obj = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
                    ?: return GitHubReleaseResult(null, false, "发布信息读不懂（200 但不是 JSON）")
                val tag = (obj["tag_name"] as? JsonPrimitive)?.contentOrNull
                    ?: return GitHubReleaseResult(null, false, "发布信息里没有版本号（tag_name）")
                GitHubReleaseResult(
                    GitHubRelease(
                        tag = tag,
                        name = (obj["name"] as? JsonPrimitive)?.contentOrNull,
                        publishedAt = (obj["published_at"] as? JsonPrimitive)?.contentOrNull,
                    ),
                    false,
                    null,
                )
            }
        }
    }

    /**
     * 发布版本列表（560 清单 448 续刀）：最新一条在前是 GitHub 的顺序，这里不再重排。
     * 只取界面字段（tag/name/published_at），读不动的条目计数不静默。
     */
    fun releases(repo: String, token: String?, limit: Int = 10): GitHubReleaseList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubReleaseList(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/releases?per_page=${limit.coerceIn(1, 30)}", token)
        if (result.status != 200) return GitHubReleaseList(emptyList(), 0, httpIssue(result))
        val root = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubReleaseList(emptyList(), 0, "发布列表读不懂（200 但不是 JSON 数组）")
        val out = ArrayList<GitHubRelease>()
        var bad = 0
        for (element in root) {
            if (out.size >= limit.coerceIn(1, 30)) break
            if (element !is JsonObject) { bad += 1; continue }
            val tag = (element["tag_name"] as? JsonPrimitive)?.contentOrNull
            if (tag.isNullOrEmpty()) { bad += 1; continue }
            out += GitHubRelease(
                tag = tag,
                name = (element["name"] as? JsonPrimitive)?.contentOrNull,
                publishedAt = (element["published_at"] as? JsonPrimitive)?.contentOrNull,
            )
        }
        return GitHubReleaseList(out, bad, null)
    }

    /**
     * 手动触发一次 workflow run（560 清单 449）：204=触发成功。
     * workflowFile 是 .github/workflows/ 下的文件名（如 ci.yml）——workflow id 或文件名 GitHub 都收，
     * 这里用文件名（界面里可见、可复制）。
     */
    fun dispatch(
        repo: String,
        workflowFile: String,
        ref: String,
        token: String?,
        inputs: Map<String, String> = emptyMap(),
    ): GitHubDispatchResult {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubDispatchResult(false, null, "仓库写法不对：要 owner/name（现在是「$repo」）")
        if (workflowFile.isBlank()) return GitHubDispatchResult(false, null, "workflow 文件名不能为空（.github/workflows/ 下的文件名，如 ci.yml）")
        if (ref.isBlank()) return GitHubDispatchResult(false, null, "目标分支（ref）不能为空")
        val payload = buildString {
            append("{\"ref\":\""); append(ref.replace("\"", ""))
            append("\",\"inputs\":{")
            append(inputs.entries.joinToString(",") { (k, v) ->
                "\"" + k.replace("\"", "") + "\":\"" + v.replace("\"", "") + "\""
            })
            append("}}")
        }
        val result = fetchPost(
            "$GITHUB_API_ROOT/repos/$full/actions/workflows/${workflowFile.trim().replace("/", "")}/dispatches",
            token,
            payload,
        )
        return when {
            result.status == 204 -> GitHubDispatchResult(true, 204, null)
            result.status == 404 -> GitHubDispatchResult(false, 404, "GitHub 说 404：workflow 文件名不对，或这个 workflow 没有 workflow_dispatch 触发器")
            result.status == 403 -> GitHubDispatchResult(false, 403, "GitHub 不给触发（403）：令牌要有 actions:write 权限")
            result.status == 422 -> GitHubDispatchResult(false, 422, "GitHub 拒了参数（422）：核对 ref 分支名与 inputs 字段名")
            result.status == 0 -> GitHubDispatchResult(false, 0, "连不上 GitHub：${brief(result.body)}")
            else -> GitHubDispatchResult(false, result.status, "GitHub 回了 ${result.status}，稍后再试")
        }
    }

    /** 失败的人话：只说状态码与该干什么，原文不抄（里面可能有一整页 HTML）。 */
    private fun httpIssue(result: GitHubHttpResult): String = when {
        result.status == 0 -> "连不上 GitHub：${brief(result.body)}"
        result.status == 403 -> "GitHub 不给看（403）：私有仓库要回去填「访问令牌」"
        result.status == 404 -> "GitHub 说没这个仓库（404）：去设置里核对仓库名"
        else -> "GitHub 回了 ${result.status}，稍后再试"
    }

    private fun brief(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 160) flat else flat.take(160) + "…（已截断）"
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }

        /** 老签名保留（双参）：HTTP 细节全在 GitHubHttp.kt 一个实现里，这里只做转发。 */
        fun defaultCiFetch(url: String, token: String?): GitHubHttpResult = githubHttpGet(url, token)
    }
}
