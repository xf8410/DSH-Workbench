package com.hualuo.engine.github

import java.io.File

/** 一条 workflow 定义（手动触发前要先知道文件名）。 */
data class GitHubWorkflow(val id: Long, val name: String, val path: String, val state: String)

data class GitHubWorkflowList(val workflows: List<GitHubWorkflow>, val badEntries: Int, val error: String?)

/** 一次「动手 CI」的收场：成功/失败 + 状态码 + 人话。 */
data class GitHubActionResult(val ok: Boolean, val httpStatus: Int?, val error: String?)

/** 一件 artifact 的界面字段（就展示这些）。 */
data class GitHubArtifact(
    val id: Long,
    val name: String,
    val sizeBytes: Long,
    val expired: Boolean,
    val runId: Long?,
)

/** artifact 清单回执：坏条目计数不静默。 */
data class GitHubArtifactList(val artifacts: List<GitHubArtifact>, val badEntries: Int, val error: String?)

/** 下载收场：落盘成功给路径与字节数；失败给原因（半截文件已在底层删掉）。 */
data class GitHubArtifactDownload(val saved: Boolean, val path: String, val bytes: Long, val error: String?)

/**
 * GitHub Actions 的**动手**小客户端（P0 十四件之一）：重跑、取消、artifact 清单、artifact 下载。
 *
 * 与 GitHubCiClient（只读列表）分工：
 *  - 读列表仍走 GitHubCiClient（双端共一份解析）；
 *  - 这里只放会改变远端状态的动件（rerun/cancel）与二进制出口（artifact zip）。
 *
 * 纪律：
 *  - 令牌只进请求头；错误里只有状态码与出路，不回显令牌；
 *  - rerun 的成功码是 **201**、cancel 是 **202**，别的状态码一律当失败（不猜「可能也行」）；
 *  - artifact 下载走两跳：第一跳带令牌拿 302，第二跳**摘掉令牌**写到本地（签名 URL 自带凭据）；
 *  - 下载有硬上限，超限删半截文件再报（半个 zip 比没有更坑）。
 */
class GitHubActionsClient(
    private val postJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPostJson,
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
    private val resolveRedirect: (String, String?) -> String? = ::githubResolveRedirect,
    private val downloadToFile: (String, File, Long) -> Pair<Long, String?> = ::githubDownloadToFile,
) {

    /** 重跑整次运行（failedOnly=true 只重跑失败的 job）。 */
    fun rerun(repo: String, runId: Long, token: String?, failedOnly: Boolean = false): GitHubActionResult {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubActionResult(false, null, repoProblem(repo))
        val tail = if (failedOnly) "rerun-failed-jobs" else "rerun"
        val result = postJson("$GITHUB_API_ROOT/repos/$full/actions/runs/$runId/$tail", token, "{}")
        return when {
            result.status == 201 -> GitHubActionResult(true, 201, null)
            result.status == 403 -> GitHubActionResult(false, 403, "GitHub 不给重跑（403）：令牌要 actions:write 权限")
            result.status == 404 -> GitHubActionResult(false, 404, "找不到这次运行（404）：核对 run_id")
            result.status == 409 -> GitHubActionResult(false, 409, "这次运行现在不能重跑（409）：可能还在队列里或已经完成且不可重跑")
            else -> GitHubActionResult(false, result.status, ghIssue(result.status, result.body, "重跑 run $runId "))
        }
    }

    /** 取消一次正在跑（或排队中）的运行。 */
    fun cancel(repo: String, runId: Long, token: String?): GitHubActionResult {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubActionResult(false, null, repoProblem(repo))
        val result = postJson("$GITHUB_API_ROOT/repos/$full/actions/runs/$runId/cancel", token, "{}")
        return when {
            result.status == 202 -> GitHubActionResult(true, 202, null)
            result.status == 403 -> GitHubActionResult(false, 403, "GitHub 不给取消（403）：令牌要 actions:write 权限")
            result.status == 404 -> GitHubActionResult(false, 404, "找不到这次运行（404）：核对 run_id")
            result.status == 409 -> GitHubActionResult(false, 409, "这次运行已经结束了（409）：没什么可取消的")
            else -> GitHubActionResult(false, result.status, ghIssue(result.status, result.body, "取消 run $runId "))
        }
    }

    /**
     * 仓库里有哪些 workflow（手动触发前要先知道 .github/workflows/ 下的文件名）。
     * 只取 id/name/path/state 四项，坏条目计数不静默。
     */
    fun workflows(repo: String, token: String?, limit: Int = 20): GitHubWorkflowList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubWorkflowList(emptyList(), 0, repoProblem(repo))
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/actions/workflows?per_page=${limit.coerceIn(1, 50)}",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubWorkflowList(emptyList(), 0, ghIssue(result.status, result.body, "列 workflow "))
        val root = runCatching { ghJson.parseToJsonElement(result.body) }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return GitHubWorkflowList(emptyList(), 0, "workflow 清单读不懂（200 但不是 JSON 对象）")
        val array = root.arr("workflows")
            ?: return GitHubWorkflowList(emptyList(), 0, "workflow 清单形状变了（没有 workflows 数组）")
        val out = ArrayList<GitHubWorkflow>()
        var bad = 0
        for (element in array) {
            if (out.size >= limit.coerceIn(1, 50)) break
            if (element !is kotlinx.serialization.json.JsonObject) { bad += 1; continue }
            val id = element.lng("id")
            if (id == null) { bad += 1; continue }
            out += GitHubWorkflow(
                id = id,
                name = element.str("name"),
                path = element.str("path"),
                state = element.str("state"),
            )
        }
        return GitHubWorkflowList(out, bad, null)
    }

    /** 仓库最近产生的 artifact（新在前是 GitHub 的顺序）。 */
    fun artifacts(repo: String, token: String?, limit: Int = 10): GitHubArtifactList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubArtifactList(emptyList(), 0, repoProblem(repo))
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/actions/artifacts?per_page=${limit.coerceIn(1, 30)}",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubArtifactList(emptyList(), 0, ghIssue(result.status, result.body, "列 artifact "))
        return parseArtifacts(result.body, limit)
    }

    /** 某一次运行产生的 artifact（按 run 筛更准）。 */
    fun runArtifacts(repo: String, runId: Long, token: String?, limit: Int = 10): GitHubArtifactList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubArtifactList(emptyList(), 0, repoProblem(repo))
        val result = fetch(
            "$GITHUB_API_ROOT/repos/$full/actions/runs/$runId/artifacts?per_page=${limit.coerceIn(1, 30)}",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubArtifactList(emptyList(), 0, ghIssue(result.status, result.body, "列 run $runId 的 artifact "))
        return parseArtifacts(result.body, limit)
    }

    /**
     * 下载一件 artifact 的 zip 到 [target]。
     *
     * 两跳：第一跳 `/actions/artifacts/{id}/zip` 带令牌，正常是 302；
     * 第二跳去签名 URL 拉字节，**不带令牌**。目标目录不存在就建。
     */
    fun downloadArtifact(
        repo: String,
        artifactId: Long,
        token: String?,
        target: File,
        maxBytes: Long = DEFAULT_MAX_DOWNLOAD_BYTES,
    ): GitHubArtifactDownload {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubArtifactDownload(false, target.path, 0, repoProblem(repo))
        val entry = "$GITHUB_API_ROOT/repos/$full/actions/artifacts/$artifactId/zip"
        val location = resolveRedirect(entry, token)
            ?: return GitHubArtifactDownload(
                false,
                target.path,
                0,
                "GitHub 没给下载跳转：artifact 可能已过期（默认留 90 天）、或令牌看不到这个仓",
            )
        val (bytes, problem) = downloadToFile(location, target, maxBytes)
        return if (problem != null) {
            GitHubArtifactDownload(false, target.path, 0, problem)
        } else {
            GitHubArtifactDownload(true, target.path, bytes, null)
        }
    }

    private fun parseArtifacts(body: String, limit: Int): GitHubArtifactList {
        val root = runCatching { ghJson.parseToJsonElement(body) }.getOrNull() as? kotlinx.serialization.json.JsonObject
            ?: return GitHubArtifactList(emptyList(), 0, "artifact 清单读不懂（200 但不是 JSON 对象）")
        val array = root.arr("artifacts")
            ?: return GitHubArtifactList(emptyList(), 0, "artifact 清单形状变了（没有 artifacts 数组）")
        val out = ArrayList<GitHubArtifact>()
        var bad = 0
        for (element in array) {
            if (out.size >= limit.coerceIn(1, 30)) break
            if (element !is kotlinx.serialization.json.JsonObject) { bad += 1; continue }
            val item = element
            val id = item.lng("id")
            if (id == null) { bad += 1; continue }
            out += GitHubArtifact(
                id = id,
                name = item.str("name"),
                sizeBytes = item.lng("size_in_bytes") ?: 0L,
                expired = item.flag("expired"),
                runId = item.obj("workflow_run")?.lng("id"),
            )
        }
        return GitHubArtifactList(out, bad, null)
    }

    private fun repoProblem(raw: String): String = "仓库写法不对：要 owner/name（现在是「$raw」）"

    companion object {
        /** 单次下载上限：200MB（zip 解压前的包体，手机存储与时间都够用）。 */
        const val DEFAULT_MAX_DOWNLOAD_BYTES = 200L * 1024 * 1024
    }
}
