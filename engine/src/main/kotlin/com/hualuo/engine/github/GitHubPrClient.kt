package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * PR 客户端（M4 第一件）：建 PR 与合 PR。语义对齐旧 Agora GitHubPullRequestToolProvider：
 *  - **SHA 钉死**（merge）：合之前带 expectedHeadSha，GitHub 比对不上就拒——分支在确认卡
 *    摆出来之后又进了新提交时，绝不合错版本；
 *  - **fail-closed**：合并响应里 merged!=true 一律当失败（405 已合并/409 冲突带原话）；
 *  - 令牌只进请求头；错误文本原样透传（零脱敏纪律）。
 *
 * 确认闸门不在这一层——引擎件只做真请求与真解析，点头那道在工具族（GitHubPrTool）。
 */
class GitHubPrClient(
    private val postJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPostJson,
    private val putJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPutJson,
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
) {

    /** 建好的 PR（给人与模型看的关键字段，不带噪声）。 */
    data class CreatedPull(
        val number: Long,
        val state: String,
        val draft: Boolean,
        val headRef: String,
        val headSha: String,
        val baseRef: String,
        val htmlUrl: String,
    )

    /** 合并的结果。 */
    data class MergeOutcome(val merged: Boolean, val sha: String?, val message: String)

    /** 建 PR。参数在调用方（工具族）校验过，这里只做请求与解析；失败带对方原话。 */
    fun createPullRequest(
        repo: String,
        head: String,
        base: String,
        title: String,
        body: String,
        draft: Boolean,
        token: String?,
    ): CreatedPull {
        val json = Json { ignoreUnknownKeys = true }
        val payload = buildString {
            append("{\"title\":").append(q(title))
            append(",\"head\":").append(q(head))
            append(",\"base\":").append(q(base))
            if (body.isNotEmpty()) append(",\"body\":").append(q(body))
            append(",\"draft\":").append(draft).append("}")
        }
        val result = postJson("$GITHUB_API_ROOT/repos/$repo/pulls", token, payload)
        if (result.status != 201) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（建 PR）"))
        }
        val obj = json.parseToJsonElement(result.body).jsonObject
        val headObj = obj["head"]?.jsonObject ?: JsonObject(emptyMap())
        val baseObj = obj["base"]?.jsonObject ?: JsonObject(emptyMap())
        return CreatedPull(
            number = obj.longOf("number"),
            state = obj.strOf("state"),
            draft = obj.boolOf("draft"),
            headRef = headObj.strOf("ref"),
            headSha = headObj.strOf("sha"),
            baseRef = baseObj.strOf("ref"),
            htmlUrl = obj.strOf("html_url"),
        )
    }

    /**
     * 合 PR（SHA 钉死）：[expectedHeadSha] 是确认卡上那个人点头时看到的提交。
     * GitHub 在 head 已前移时会返回 409，这里把它翻成中文带原话的失败——不合错版本。
     */
    fun mergePullRequest(
        repo: String,
        number: Long,
        expectedHeadSha: String,
        method: String,
        commitTitle: String,
        token: String?,
    ): MergeOutcome {
        val payload = buildString {
            append("{\"sha\":").append(q(expectedHeadSha))
            append(",\"merge_method\":").append(q(method))
            if (commitTitle.isNotEmpty()) {
                append(",\"commit_title\":").append(q(commitTitle))
            }
            append("}")
        }
        val result = putJson("$GITHUB_API_ROOT/repos/$repo/pulls/$number/merge", token, payload)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（合 PR #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        val merged = obj.boolOf("merged")
        return MergeOutcome(
            merged = merged,
            sha = (obj["sha"] as? kotlinx.serialization.json.JsonPrimitive)?.content,
            message = obj.strOf("message"),
        )
    }

    /** 取一个 PR 的当前 head sha（工具族在合并前对账用；404 带原话）。 */
    fun pullHeadSha(repo: String, number: Long, token: String?): String? {
        val result = fetch("$GITHUB_API_ROOT/repos/$repo/pulls/$number", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（读 PR #$number）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        return (obj["head"] as? JsonObject)?.strOf("sha")?.takeIf { it.isNotEmpty() }
    }

    /** 仓库的默认分支（建 PR 缺 base 时用；读不到回 null 由调用方出声）。 */
    fun repoDefaultBranch(repo: String, token: String?): String? {
        val result = fetch("$GITHUB_API_ROOT/repos/$repo", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) {
            throw PrRequestFailed(result.status, briefOf(result.body, repoSuffix = "（读仓库信息）"))
        }
        val obj = Json { ignoreUnknownKeys = true }.parseToJsonElement(result.body).jsonObject
        return obj.strOf("default_branch").takeIf { it.isNotEmpty() }
    }

    private fun q(s: String): String = kotlinx.serialization.json.JsonPrimitive(s).toString()

    private fun briefOf(raw: String, repoSuffix: String): String {
        // 对方错误体原文透传（零脱敏）；只折行压长，超长带账
        val flat = raw.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 300) "$flat$repoSuffix"
        else flat.take(300) + "…（已截断，原文 ${flat.length} 字符）$repoSuffix"
    }

    private fun JsonObject.strOf(key: String): String =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content ?: ""

    private fun JsonObject.longOf(key: String): Long =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content?.toLongOrNull() ?: 0L

    private fun JsonObject.boolOf(key: String): Boolean =
        (this[key] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true"
}

/** PR 请求失败：状态码 + 对方原话（带出路由调用方拼）。 */
class PrRequestFailed(val status: Int, val detail: String) : Exception("GitHub 状态 $status：$detail")
