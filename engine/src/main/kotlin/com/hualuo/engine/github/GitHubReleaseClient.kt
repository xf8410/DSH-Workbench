package com.hualuo.engine.github

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject

/** 发布版的一条（与 GitHubCiClient 的 GitHubRelease 保持独立：这里多带 id 与资产数）。 */
data class GitHubReleaseInfo(
    val id: Long,
    val tag: String,
    val name: String,
    val draft: Boolean,
    val prerelease: Boolean,
    val publishedAt: String,
    val htmlUrl: String,
    val assetCount: Int,
)

data class GitHubReleasePage(val releases: List<GitHubReleaseInfo>, val badEntries: Int, val error: String?)

/** 建发布版的收场：成功给 id（上传附件要用）与页面地址。 */
data class GitHubReleaseCreated(val id: Long, val tag: String, val htmlUrl: String, val error: String?)

/** 附件上传的收场。 */
data class GitHubAssetUploaded(val id: Long, val name: String, val sizeBytes: Long, val downloadUrl: String, val error: String?)

/**
 * Release 客户端（P0 十四件）：列发布、建发布、传附件。
 *
 * 断点背景：之前只有「读最新发布」这一件（界面用），发版还得靠外部工具；
 * 这里把「建 tag 发布 + 上传构建产物」补齐。
 *
 * 纪律：
 *  - 建发布与传附件都是**改远端状态**的动作，闸门在工具族（GitHubExtraWriteTool），
 *    这一层只做请求与解析；
 *  - 附件上传走 uploads.github.com + 二进制 POST（不带 JSON 包装），大小上限在工具层卡；
 *  - 同名附件已存在时 GitHub 回 422，这里翻成「换个名字」的出路，不静默重试。
 */
class GitHubReleaseClient(
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
    private val postJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPostJson,
    private val postBinary: (String, String?, String, ByteArray) -> GitHubHttpResult = ::githubHttpPostBinary,
) {

    fun list(repo: String, token: String?, limit: Int = 10): GitHubReleasePage {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubReleasePage(emptyList(), 0, repoProblem(repo))
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/releases?per_page=${limit.coerceIn(1, 30)}",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubReleasePage(emptyList(), 0, ghIssue(result.status, result.body, "列发布 "))
        val array = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubReleasePage(emptyList(), 0, "发布列表读不懂（200 但不是 JSON 数组）")
        val out = ArrayList<GitHubReleaseInfo>()
        var bad = 0
        for (element in array) {
            if (out.size >= limit.coerceIn(1, 30)) break
            if (element !is JsonObject) { bad += 1; continue }
            val item = element
            val tag = item.str("tag_name")
            val id = item.lng("id")
            if (tag.isEmpty() || id == null) { bad += 1; continue }
            out += GitHubReleaseInfo(
                id = id,
                tag = tag,
                name = item.str("name"),
                draft = item.flag("draft"),
                prerelease = item.flag("prerelease"),
                publishedAt = item.str("published_at"),
                htmlUrl = item.str("html_url"),
                assetCount = item.arr("assets")?.size ?: 0,
            )
        }
        return GitHubReleasePage(out, bad, null)
    }

    /**
     * 建一个发布版。[tag] 已存在时 GitHub 直接把发布挂到那个 tag 上（这是官方语义，不是错误）。
     * draft=true 建草稿（不会出现在 releases/latest 里）。
     */
    fun create(
        repo: String,
        tag: String,
        name: String,
        body: String,
        draft: Boolean,
        prerelease: Boolean,
        targetCommitish: String?,
        token: String?,
    ): GitHubReleaseCreated {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubReleaseCreated(0, tag, "", repoProblem(repo))
        if (tag.isBlank()) return GitHubReleaseCreated(0, tag, "", "版本号（tag）不能为空，如 v1.2.0")
        val payload = buildString {
            append("{\"tag_name\":").append(ghQuote(tag))
            if (name.isNotBlank()) append(",\"name\":").append(ghQuote(name))
            if (body.isNotBlank()) append(",\"body\":").append(ghQuote(body))
            append(",\"draft\":").append(draft)
            append(",\"prerelease\":").append(prerelease)
            if (!targetCommitish.isNullOrBlank()) append(",\"target_commitish\":").append(ghQuote(targetCommitish))
            append("}")
        }
        val result = postJson("$GITHUB_API_ROOT/repos/$full/releases", token, payload)
        if (result.status != 201) {
            return GitHubReleaseCreated(0, tag, "", ghIssue(result.status, result.body, "建发布 $tag "))
        }
        val root = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubReleaseCreated(0, tag, "", "发布建好了但回执读不懂（201 但不是 JSON 对象）：去网页核对")
        return GitHubReleaseCreated(
            id = root.lng("id") ?: 0,
            tag = root.str("tag_name").ifEmpty { tag },
            htmlUrl = root.str("html_url"),
            error = null,
        )
    }

    /** 往一个发布版上传附件（[bytes] 已经在工具层卡过大小）。 */
    fun uploadAsset(
        repo: String,
        releaseId: Long,
        name: String,
        contentType: String,
        bytes: ByteArray,
        token: String?,
    ): GitHubAssetUploaded {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubAssetUploaded(0, name, 0, "", repoProblem(repo))
        if (releaseId <= 0) return GitHubAssetUploaded(0, name, 0, "", "缺 release_id：先建发布或列发布拿到 id")
        if (name.isBlank()) return GitHubAssetUploaded(0, name, 0, "", "附件名不能为空")
        val encoded = java.net.URLEncoder.encode(name, "UTF-8").replace("+", "%20")
        val url = "https://uploads.github.com/repos/$full/releases/$releaseId/assets?name=$encoded"
        val result = postBinary(url, token, contentType.ifBlank { "application/octet-stream" }, bytes)
        if (result.status != 201) {
            return GitHubAssetUploaded(
                0,
                name,
                bytes.size.toLong(),
                "",
                when (result.status) {
                    422 -> "同名附件已经存在（422）：换个名字再传"
                    else -> ghIssue(result.status, result.body, "传附件 $name ")
                },
            )
        }
        val root = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubAssetUploaded(0, name, bytes.size.toLong(), "", "附件传上去了但回执读不懂（201 但不是 JSON 对象）：去网页核对")
        return GitHubAssetUploaded(
            id = root.lng("id") ?: 0,
            name = root.str("name").ifEmpty { name },
            sizeBytes = root.lng("size") ?: bytes.size.toLong(),
            downloadUrl = root.str("browser_download_url"),
            error = null,
        )
    }

    private fun repoProblem(raw: String): String = "仓库写法不对：要 owner/name（现在是「$raw」）"
}
