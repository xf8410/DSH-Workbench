package com.hualuo.engine.github

import java.net.URLEncoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 一个仓库的界面字段（就展示这些）。 */
data class GitHubRepoSummary(
    val fullName: String,
    val isPrivate: Boolean,
    val description: String,
    val defaultBranch: String,
    val updatedAt: String,
    val sizeKb: Long,
)

/** 仓库清单回执：error 非 null 时 repos 必为空；读不动的条目在 badEntries 里数出来。 */
data class GitHubRepoList(val repos: List<GitHubRepoSummary>, val badEntries: Int, val error: String?)

/** 浏览目录的一条：目录在前排序由 client 做。 */
data class GitHubEntry(
    val name: String,
    val path: String,
    val isDir: Boolean,
    val sizeBytes: Long,
)

/** 浏览回执：error 非 null 时 entries 必为空。 */
data class GitHubBrowse(val entries: List<GitHubEntry>, val badEntries: Int, val error: String?)

/**
 * 读一个文件的回执：text 为 null 就是没有内容可给（二进制/太大/出错），理由在 error；
 * truncated = 内容被有界读截断（只读了前一段）；sha = 文件当前 blob sha（改码提交要用，
 * null = 这份内容不许改）；tooBig = 超过 contents 接口单文件上限（只给预览不给改）。
 */
data class GitHubFileContent(
    val path: String,
    val text: String?,
    val charCount: Int,
    val truncated: Boolean,
    val sha: String?,
    val tooBig: Boolean,
    val error: String?,
    /** true = 二进制文件：内容走 [base64Content]（查看器给 hex 页，不再"不给预览"）。 */
    val isBinary: Boolean = false,
    /** 二进制内容的 base64（≤1MB 全文；超限时是 raw 档前段，truncated 会说）。 */
    val base64Content: String? = null,
)

/** 一个分支：名字 + 头指针 sha（界面切换分支用）。 */
data class GitHubBranch(val name: String, val commitSha: String)

/** 分支清单回执。 */
data class GitHubBranchList(val branches: List<GitHubBranch>, val badEntries: Int, val error: String?)

/** 一条提交的界面字段：sha + 首行消息 + 作者 + 日期（维护记录看这些就够）。 */
data class GitHubCommitSummary(
    val sha: String,
    val messageFirstLine: String,
    val author: String,
    val date: String,
)

/** 提交历史回执。 */
data class GitHubCommitList(val commits: List<GitHubCommitSummary>, val badEntries: Int, val error: String?)

/** 改码提交的回执：成功带新内容 sha 与提交 sha；失败 error 带人话（绝不静默）。 */
data class GitHubCommitWritten(
    val commitSha: String?,
    val contentSha: String?,
    val error: String?,
)

/** 一次 CI job 的界面字段（run 里点开看的就是这些）。 */
data class GitHubJob(
    val id: Long,
    val name: String,
    val status: String,
    val conclusion: String?,
    val startedAt: String,
)

/** run 的 jobs 回执。 */
data class GitHubJobList(val jobs: List<GitHubJob>, val badEntries: Int, val error: String?)

/** 一段 job 日志：有界读（512K 封顶），truncated 明说——日志再长也不许整段吞内存。 */
data class GitHubJobLog(
    val text: String?,
    val charCount: Int,
    val truncated: Boolean,
    val error: String?,
)

/**
 * 仓库工作台客户端（纯 JVM；fetch/putJson 缝隙注入，JVM 测试不碰网络）。
 *
 *  - 清单：自己的仓走 /user/repos（要令牌才看得到私有），别人的公开仓走 /users/{u}/repos；
 *  - 浏览：contents API 逐目录列（路径逐段编码，空格不会变加号）；
 *  - 读文件：先走 JSON 档（拿 sha 与是否超限），没超限再把 base64 解成原文——一次请求
 *    同时拿到「内容 + 改码要用的 sha」；超限（encoding=none）退回 raw 档只给前一段预览；
 *  - 分支/提交历史：branches 与 commits 接口（维护记录与分支切换的账本）；
 *  - 改码提交：PUT contents（message 必填、旧 sha 对账防覆盖别人），409/422 = 有并发改动，
 *    必须原话出声让人重开重改，绝不硬盖；
 *  - CI 深看：任意仓的 runs（解析与 CI 客户端共用一份）/ run 的 jobs / job 日志（有界读）；
 *  - 令牌只进请求头；失败一律人话（状态码，绝无令牌），绝不拿半份清单冒充成功。
 */
class GitHubRepoClient(
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
    private val putJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPutJson,
) {

    /** 自己的仓库（含私有）。令牌必填——没令牌就不发请求，先出声。 */
    fun listMyRepos(token: String?, limit: Int = 30): GitHubRepoList {
        if (token.isNullOrBlank()) {
            return GitHubRepoList(emptyList(), 0, "看自己的仓库要令牌：去设置「GitHub 工作台」填（看别人的不用）")
        }
        val result = fetch(
            "$GITHUB_API_ROOT/user/repos?per_page=${limit.coerceIn(1, 100)}&sort=updated&affiliation=owner",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status != 200) return GitHubRepoList(emptyList(), 0, httpIssue(result))
        return parseRepoList(result)
    }

    /** 别人的公开仓库（令牌可空）。用户名只认一段。 */
    fun listUserRepos(owner: String, token: String?, limit: Int = 30): GitHubRepoList {
        val clean = owner.trim().trimEnd('/')
        if (clean.isEmpty() || clean.any { it == '/' || it == '?' || it == '#' || it.isWhitespace() }) {
            return GitHubRepoList(emptyList(), 0, "用户名写法不对：只要一段（如 xf8410）")
        }
        val result = fetch(
            "$GITHUB_API_ROOT/users/$clean/repos?per_page=${limit.coerceIn(1, 100)}&sort=updated",
            token,
            null,
            GITHUB_MAX_BODY_CHARS,
        )
        if (result.status == 404) return GitHubRepoList(emptyList(), 0, "GitHub 说没这个用户（404）：核对一下用户名")
        if (result.status != 200) return GitHubRepoList(emptyList(), 0, httpIssue(result))
        return parseRepoList(result)
    }

    /** 浏览一个目录：[path] 空串 = 仓库根。目录在前、按名排序。 */
    fun browse(repo: String, path: String, ref: String?, token: String?): GitHubBrowse {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubBrowse(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val encoded = encodeContentsPath(path)
        val refQuery = if (ref.isNullOrBlank()) "" else "?ref=" + encodeSegment(ref)
        val url = "$GITHUB_API_ROOT/repos/$full/contents" + (if (encoded.isEmpty()) "" else "/$encoded") + refQuery
        var result = fetch(url, token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status == 404 && !ref.isNullOrBlank()) {
            // 404 自愈：ref 不存在时探默认分支重试（同 readFile，防 main 硬猜）
            val db = defaultBranchOf(full, token)
            if (db != null && db != ref) {
                val retry = fetch("$GITHUB_API_ROOT/repos/$full/contents" + (if (encoded.isEmpty()) "" else "/$encoded") + "?ref=" + encodeSegment(db), token, null, GITHUB_MAX_BODY_CHARS)
                if (retry.status in 200..299) result = retry
            }
        }
        if (result.status == 404) {
            val db = defaultBranchOf(full, token)
            return GitHubBrowse(emptyList(), 0,
                "GitHub 说没这个目录（404）" + (db?.let { "：这个仓库的默认分支是 $it（你给的 ref 是「$ref」）" } ?: "：路径或分支不对"))
        }
        if (result.status != 200) return GitHubBrowse(emptyList(), 0, httpIssue(result))
        val array = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubBrowse(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是目录清单）")
        val entries = ArrayList<GitHubEntry>()
        var bad = 0
        for (element in array) {
            if (element !is JsonObject) { bad += 1; continue }
            val name = (element["name"] as? JsonPrimitive)?.contentOrNull
            val entryPath = (element["path"] as? JsonPrimitive)?.contentOrNull
            val type = (element["type"] as? JsonPrimitive)?.contentOrNull
            val size = (element["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
            if (name.isNullOrEmpty() || entryPath.isNullOrEmpty() || (type != "file" && type != "dir")) {
                bad += 1
                continue
            }
            entries += GitHubEntry(name, entryPath, type == "dir", size)
        }
        val sorted = entries.sortedWith(compareByDescending<GitHubEntry> { it.isDir }.thenBy { it.name.lowercase() })
        return GitHubBrowse(sorted, bad, null)
    }

    /**
     * 读一个文件：JSON 档一次拿「内容 + sha + 是否超限」。没超限把 base64 解成原文；
     * 超限退回 raw 档只给前一段预览（sha 照给，但 tooBig = true，改码闸在界面拦）。
     */
    fun readFile(repo: String, path: String, ref: String?, token: String?): GitHubFileContent {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubFileContent(path, null, 0, false, null, false, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val cleanPath = path.trim().trim('/')
        if (cleanPath.isEmpty()) return GitHubFileContent(path, null, 0, false, null, false, "文件路径是空的")
        val encoded = encodeContentsPath(cleanPath)
        val refQuery = if (ref.isNullOrBlank()) "" else "?ref=" + encodeSegment(ref)
        val url = "$GITHUB_API_ROOT/repos/$full/contents/$encoded$refQuery"
        // 内容上限 1MB，base64 后约 1.37M 字符，封顶放宽到 1.6M 字符（有界读纪律不破，只是放宽）
        var result = fetch(url, token, null, 1_600_000)
        if (result.status == 404 && !ref.isNullOrBlank()) {
            // 404 自愈：给的 ref 可能不存在（模型爱传 main，撞上 master 仓）——探默认分支重试一次
            val db = defaultBranchOf(full, token)
            if (db != null && db != ref) {
                val retry = fetch("$GITHUB_API_ROOT/repos/$full/contents/$encoded?ref=" + encodeSegment(db), token, null, 1_600_000)
                if (retry.status in 200..299) result = retry
            }
        }
        if (result.status == 404) {
            val db = defaultBranchOf(full, token)
            return GitHubFileContent(cleanPath, null, 0, false, null, false,
                "GitHub 说没这个文件或分支（404）" + (db?.let { "：这个仓库的默认分支是 $it（你给的 ref 是「$ref」）" } ?: "：路径或分支不对"))
        }
        if (result.status != 200) {
            return GitHubFileContent(cleanPath, null, 0, false, null, false, httpIssue(result))
        }
        val obj = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubFileContent(cleanPath, null, 0, false, null, false, "GitHub 回的内容读不懂（200 但不是文件账）")
        val sha = (obj["sha"] as? JsonPrimitive)?.contentOrNull
        val size = (obj["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L
        val encoding = (obj["encoding"] as? JsonPrimitive)?.contentOrNull
        val encodedContent = (obj["content"] as? JsonPrimitive)?.contentOrNull ?: ""
        if (encoding == "none" || size > CONTENTS_LIMIT) {
            // 超限：raw 档只给前一段预览。sha 照带（B 段如果做整文件替换也认账），改码闸在界面拦
            val raw = fetch(url, token, GITHUB_ACCEPT_RAW, GITHUB_MAX_BODY_CHARS)
            if (raw.status != 200) {
                return GitHubFileContent(cleanPath, null, 0, false, sha, true, httpIssue(raw))
            }
            if (raw.body.contains('\u0000')) {
                return GitHubFileContent(
                    cleanPath, null, 0, raw.truncated, sha, true, null,
                    isBinary = true,
                    base64Content = java.util.Base64.getEncoder().encodeToString(raw.body.toByteArray(Charsets.ISO_8859_1)),
                )
            }
            return GitHubFileContent(cleanPath, raw.body, raw.body.length, raw.truncated, sha, true, null)
        }
        if (encoding != "base64") {
            return GitHubFileContent(cleanPath, null, 0, false, sha, false, "GitHub 回了认不出的编码（$encoding）：预览不给，免得给你看乱码")
        }
        val decoded = runCatching {
            String(java.util.Base64.getMimeDecoder().decode(encodedContent.replace("\n", "").replace("\r", "")), Charsets.UTF_8)
        }.getOrElse {
            return GitHubFileContent(cleanPath, null, 0, false, sha, false, "文件内容解不出来（base64 账对不上）：${it.message ?: "出错"}")
        }
        if (decoded.contains('\u0000')) {
            // 二进制：base64 原样透传（GitHub 给什么给什么），查看器开 hex 页——不省略任何格式
            return GitHubFileContent(
                cleanPath, null, 0, false, sha, false, null,
                isBinary = true,
                base64Content = encodedContent.replace("\n", "").replace("\r", ""),
            )
        }
        return GitHubFileContent(cleanPath, decoded, decoded.length, result.truncated, sha, false, null)
    }

    /**
     * 仓库默认分支（带进程内缓存）。读文件/目录/提交史的 404 自愈就靠它：
     * 模型习惯性传 ref="main"，撞上 master 仓就 404——这里探出真分支重试，
     * 不再让模型来回试错烧轮次（旧 Agora 的病，截图为证）。
     */
    internal fun defaultBranchOf(full: String, token: String?): String? {
        defaultBranchCache[full]?.let { return it }
        val result = fetch("$GITHUB_API_ROOT/repos/$full", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) return null
        val db = ((runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject)
            ?.get("default_branch") as? JsonPrimitive)?.contentOrNull
        if (db.isNullOrBlank()) return null
        defaultBranchCache[full] = db
        return db
    }

    private val defaultBranchCache = java.util.concurrent.ConcurrentHashMap<String, String>()

    /** 分支清单（分支切换的账本）。 */
    fun listBranches(repo: String, token: String?, limit: Int = 50): GitHubBranchList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubBranchList(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/branches?per_page=${limit.coerceIn(1, 100)}", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status == 404) return GitHubBranchList(emptyList(), 0, "GitHub 说没这个仓库（404）：核对仓库名")
        if (result.status != 200) return GitHubBranchList(emptyList(), 0, httpIssue(result))
        val array = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubBranchList(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是分支清单）")
        val branches = ArrayList<GitHubBranch>()
        var bad = 0
        for (element in array) {
            if (element !is JsonObject) { bad += 1; continue }
            val name = (element["name"] as? JsonPrimitive)?.contentOrNull
            val sha = ((element["commit"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
            if (name.isNullOrEmpty() || sha.isNullOrEmpty()) { bad += 1; continue }
            branches += GitHubBranch(name, sha)
        }
        val sorted = branches.sortedBy { it.name.lowercase() }
        return GitHubBranchList(sorted, bad, null)
    }

    /** 提交历史（维护记录）：[ref] 空走默认分支；[path] 非空只看这个文件的账。 */
    fun listCommits(repo: String, ref: String?, path: String?, token: String?, limit: Int = 20): GitHubCommitList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubCommitList(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val params = ArrayList<String>()
        if (!ref.isNullOrBlank()) params += "sha=" + encodeSegment(ref)
        if (!path.isNullOrBlank()) params += "path=" + encodeContentsPath(path)
        params += "per_page=" + limit.coerceIn(1, 100)
        val url = "$GITHUB_API_ROOT/repos/$full/commits?" + params.joinToString("&")
        var result = fetch(url, token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status == 404 && !ref.isNullOrBlank()) {
            // 404 自愈：ref 不存在时探默认分支重试（同 readFile，防 main 硬猜）
            val db = defaultBranchOf(full, token)
            if (db != null && db != ref) {
                val retry = fetch("$GITHUB_API_ROOT/repos/$full/commits?sha=" + encodeSegment(db) + "&" + params.dropWhile { it.startsWith("sha=") }.joinToString("&"), token, null, GITHUB_MAX_BODY_CHARS)
                if (retry.status in 200..299) result = retry
            }
        }
        if (result.status == 404) {
            val db = defaultBranchOf(full, token)
            return GitHubCommitList(emptyList(), 0,
                "GitHub 说没这个仓库或分支（404）" + (db?.let { "：这个仓库的默认分支是 $it（你给的 ref 是「$ref」）" } ?: "：核对一下"))
        }
        if (result.status != 200) return GitHubCommitList(emptyList(), 0, httpIssue(result))
        val array = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubCommitList(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是提交清单）")
        val commits = ArrayList<GitHubCommitSummary>()
        var bad = 0
        for (element in array) {
            if (element !is JsonObject) { bad += 1; continue }
            val sha = (element["sha"] as? JsonPrimitive)?.contentOrNull
            val commit = element["commit"] as? JsonObject
            val message = (commit?.get("message") as? JsonPrimitive)?.contentOrNull
            val author = ((commit?.get("author") as? JsonObject)?.get("name") as? JsonPrimitive)?.contentOrNull
            val date = ((commit?.get("author") as? JsonObject)?.get("date") as? JsonPrimitive)?.contentOrNull
            if (sha.isNullOrEmpty() || message.isNullOrEmpty()) { bad += 1; continue }
            commits += GitHubCommitSummary(
                sha = sha,
                messageFirstLine = message.substringBefore('\n').trim(),
                author = author ?: "",
                date = date ?: "",
            )
        }
        return GitHubCommitList(commits, bad, null)
    }

    /** 任意仓的 workflow runs（CI 深看第一层；解析与 CI 客户端共用一份，防双源坑）。 */
    fun runs(repo: String, token: String?, limit: Int = 10): GitHubCiSnapshot {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubCiSnapshot(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/actions/runs?per_page=${limit.coerceIn(1, 20)}", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status != 200) return GitHubCiSnapshot(emptyList(), 0, httpIssue(result))
        return parseWorkflowRuns(result.body, limit)
    }

    /** 一次 run 里的 jobs（CI 深看第二层）。 */
    fun runJobs(repo: String, runId: Long, token: String?): GitHubJobList {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubJobList(emptyList(), 0, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/actions/runs/$runId/jobs?per_page=20", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status == 404) return GitHubJobList(emptyList(), 0, "GitHub 说没这个 run（404）：可能已被清理")
        if (result.status != 200) return GitHubJobList(emptyList(), 0, httpIssue(result))
        val root = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubJobList(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是 jobs 账）")
        val array = root["jobs"] as? JsonArray
            ?: return GitHubJobList(emptyList(), 0, "GitHub 回的形状变了（没找到 jobs）")
        val jobs = ArrayList<GitHubJob>()
        var bad = 0
        for (element in array) {
            if (element !is JsonObject) { bad += 1; continue }
            val id = (element["id"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
            val name = (element["name"] as? JsonPrimitive)?.contentOrNull
            val status = (element["status"] as? JsonPrimitive)?.contentOrNull
            if (id == null || name.isNullOrEmpty() || status.isNullOrEmpty()) { bad += 1; continue }
            jobs += GitHubJob(
                id = id,
                name = name,
                status = status,
                conclusion = (element["conclusion"] as? JsonPrimitive)?.contentOrNull,
                startedAt = (element["started_at"] as? JsonPrimitive)?.contentOrNull ?: "",
            )
        }
        return GitHubJobList(jobs, bad, null)
    }

    /**
     * 一段 job 日志（CI 深看第三层）：GitHub 302 到日志文本，GET 跟随重定向直接拿到。
     * 有界读 512K 封顶——超长日志只给前一段并把 truncated 说出来，绝不整段吞内存。
     */
    fun jobLog(repo: String, jobId: Long, token: String?): GitHubJobLog {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubJobLog(null, 0, false, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val result = fetch("$GITHUB_API_ROOT/repos/$full/actions/jobs/$jobId/logs", token, null, GITHUB_MAX_BODY_CHARS)
        if (result.status == 404) return GitHubJobLog(null, 0, false, "GitHub 说没这段日志（404）：run 太旧或日志已被清")
        if (result.status != 200) return GitHubJobLog(null, 0, false, httpIssue(result))
        if (result.body.isEmpty()) return GitHubJobLog(null, 0, false, "这段日志是空的（job 还没吐字或日志被清了）")
        return GitHubJobLog(result.body, result.body.length, result.truncated, null)
    }

    /**
     * 改码提交：PUT contents 整文件替换。[sha] 是读文件时拿到的 blob sha——
     * GitHub 拿它对账，别人先改过就回 409/422，这里翻译成人话让人重开重改，绝不硬盖。
     * [message] 必填（提交不许没有一句人话说明），[newContent] 只收文本（二进制不进这条通道）。
     */
    fun updateFile(
        repo: String,
        path: String,
        branch: String?,
        newContent: String,
        message: String,
        sha: String?,
        token: String?,
    ): GitHubCommitWritten {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubCommitWritten(null, null, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val cleanPath = path.trim().trim('/')
        if (cleanPath.isEmpty()) return GitHubCommitWritten(null, null, "文件路径是空的")
        if (newContent.contains('\u0000')) {
            return GitHubCommitWritten(null, null, "改码通道只收文本：这份内容里有二进制字节，不走这条路")
        }
        if (message.isBlank()) {
            return GitHubCommitWritten(null, null, "commit message 不能空：写一句人话说明改了什么")
        }
        if (token.isNullOrBlank()) {
            return GitHubCommitWritten(null, null, "改码要令牌：去设置「GitHub 工作台」填")
        }
        val encoded = encodeContentsPath(cleanPath)
        val body = buildJsonObject {
            put("message", message.trim())
            put("content", java.util.Base64.getEncoder().encodeToString(newContent.toByteArray(Charsets.UTF_8)))
            put("branch", if (branch.isNullOrBlank()) "main" else branch.trim())
            if (!sha.isNullOrBlank()) put("sha", sha)
        }.toString()
        val result = putJson("$GITHUB_API_ROOT/repos/$full/contents/$encoded", token, body)
        if (result.status == 409 || result.status == 422) {
            return GitHubCommitWritten(
                null,
                null,
                "文件在我读到之后被别人改过（GitHub 报 ${result.status}）：重新打开这个文件再改一遍，别硬盖别人的账",
            )
        }
        if (result.status == 404) {
            return GitHubCommitWritten(null, null, "GitHub 说没这个文件或分支（404）：路径或分支不对")
        }
        if (result.status !in 200..299) {
            return GitHubCommitWritten(null, null, httpIssue(result))
        }
        val obj = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubCommitWritten(null, null, "GitHub 回的内容读不懂（200 但不是提交回执）")
        val commitSha = ((obj["commit"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
        val contentSha = ((obj["content"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
        return GitHubCommitWritten(commitSha, contentSha, null)
    }

    /**
     * 上传一个文件（查看器「传上去」按钮的引擎件，**全格式、无大小上限、不闪退**）。
     *
     * 内存纪律（红线二）：全程流式——文件边读边 base64 边发，内存里只有 64KB 块缓冲，
     * 2GB 的文件也一样稳。GitHub 单请求 100MB 的硬限用**分卷**绕开：
     *  - ≤90MB：整文件一次通路（≤900KB 走 contents，其余走 git blobs 流式）；
     *  - >90MB：切成 90MB 的卷，落 `原路径.parts/`，每卷独立 blob 到 tree 到 commit 到 ref。
     *    字节只发一次（blob 步）；每卷独立提交，中断了已传的卷都在，
     *    重传先查已有卷、尺寸对得上就跳过——**断点续传**；
     *  - 全部卷收尾传 manifest.json（原名/大小/卷清单/流式 sha256），可验可还原。
     *
     * [onProgress] 进度回调（已发送字节/总字节/一句人话），引擎侧节流约 0.5s 一次，
     * app 层拿去画进度条——不让人干等。
     */
    fun uploadFile(
        repo: String,
        path: String,
        branch: String?,
        message: String,
        source: UploadSource,
        token: String?,
        onProgress: ProgressSink = ProgressSink { _, _, _ -> },
    ): GitHubCommitWritten {
        val full = normalizeGitHubRepo(repo)
            ?: return GitHubCommitWritten(null, null, "仓库写法不对：要 owner/name（现在是「$repo」）")
        val cleanPath = path.trim().trim('/')
        if (cleanPath.isEmpty()) return GitHubCommitWritten(null, null, "上传路径是空的")
        if (message.isBlank()) return GitHubCommitWritten(null, null, "commit message 不能空：写一句这次传的是什么")
        if (source.sizeBytes <= 0L) return GitHubCommitWritten(null, null, "文件是空的（0 字节）：空文件不值得传")
        val total = source.sizeBytes
        return if (total <= CHUNK_BYTES) {
            uploadSingle(full, cleanPath, branch, message, source, total, token, onProgress)
        } else {
            uploadChunked(full, cleanPath, branch, message, source, total, token, onProgress)
        }
    }

    /** 上传来源：名字 + 大小 + 可重开的流（SAF 的 openInputStream 天然满足）。 */
    class UploadSource(val name: String, val sizeBytes: Long, val open: () -> java.io.InputStream)

    /** 进度回调口子：done/total 字节 + 一句人话。 */
    fun interface ProgressSink {
        fun onProgress(doneBytes: Long, totalBytes: Long, note: String)
    }

    /** 节流包装：至少隔 [intervalMs] 才真回调（收尾 force 必到）。 */
    private class ThrottledSink(
        private val sink: ProgressSink,
        private val total: Long,
        private val intervalMs: Long = 500L,
    ) {
        private var last = 0L
        fun emit(done: Long, note: String, force: Boolean = false) {
            val now = System.currentTimeMillis()
            if (!force && now - last < intervalMs && done < total) return
            last = now
            sink.onProgress(done, total, note)
        }
    }

    /** 单文件通路：≤900KB contents PUT；其余 blobs 流式一次提交。 */
    private fun uploadSingle(
        full: String,
        cleanPath: String,
        branch: String?,
        message: String,
        source: UploadSource,
        total: Long,
        token: String?,
        onProgress: ProgressSink,
    ): GitHubCommitWritten {
        val tick = ThrottledSink(onProgress, total)
        tick.emit(0, "开始上传 ${com.hualuo.engine.language.HexDump.humanBytes(total)}")
        if (total <= CONTENTS_LIMIT) {
            val bytes = runCatching {
                source.open().use { it.readBytes() }
            }.getOrElse { return GitHubCommitWritten(null, null, "读文件失败：${it.message ?: "打不开"}") }
            tick.emit(total, "内容已读齐，正在提交", force = true)
            val b64 = java.util.Base64.getEncoder().encodeToString(bytes)
            val existing = readFile(full, cleanPath, branch, token)
            return putContentsBase64(full, cleanPath, branch, message, b64, existing.sha, token)
        }
        val existing = readFile(full, cleanPath, branch, token)
        val res = uploadSingleBlobCommit(
            full, cleanPath, branch, message, source, total, token,
            tick, 0L, total, 1, 1,
        )
        return res
    }

    /** 分卷通路：切 90MB 卷、断点续传、收尾 manifest。 */
    private fun uploadChunked(
        full: String,
        cleanPath: String,
        branch: String?,
        message: String,
        source: UploadSource,
        total: Long,
        token: String?,
        onProgress: ProgressSink,
    ): GitHubCommitWritten {
        val tick = ThrottledSink(onProgress, total)
        val nameOnly = cleanPath.substringAfterLast('/')
        val dirPart = cleanPath.substringBeforeLast('/', "")
        val partsDir = (if (dirPart.isEmpty()) "" else "$dirPart/") + "$nameOnly.parts"
        val partCount = com.hualuo.engine.language.HexDump.chunkCount(total, CHUNK_BYTES)
        tick.emit(0, "大文件分卷：共 $partCount 卷（每卷 ${com.hualuo.engine.language.HexDump.humanBytes(CHUNK_BYTES)}），断点续传", force = true)
        // 断点续传：parts 目录里已有哪些尺寸对得上的卷
        val uploadedSizes = HashMap<Int, Long>()
        val existingDir = browse(full, "$partsDir/", branch, token)
        if (existingDir.error == null) {
            for (e in existingDir.entries) {
                val m = Regex("part(\\d{5})$").find(e.name)
                if (m != null && e.sizeBytes > 0) uploadedSizes[m.groupValues[1].toInt()] = e.sizeBytes
            }
        }
        var done = 0L
        for (idx in 1..partCount) {
            val partSize = if (idx == partCount) total - done else CHUNK_BYTES
            val already = uploadedSizes[idx]
            if (already != null && already == partSize) {
                done += partSize
                tick.emit(done, "跳过已传的卷 $idx/$partCount（断点续传）")
                continue
            }
            val partName = String.format("%s.part%05d", nameOnly, idx)
            val partPath = "$partsDir/$partName"
            val partMessage = "$message（卷 $idx/$partCount）"
            val partSource = UploadSource(partName, partSize) { source.open().skipFully(done) }
            val res = uploadSingleBlobCommit(
                full, partPath, branch, partMessage, partSource, partSize, token,
                tick, done, total, idx, partCount,
            )
            if (res.error != null) return res
            done += partSize
        }
        // 收尾 manifest
        tick.emit(done, "卷全部到位，写 manifest.json（含 sha256 校验账）", force = true)
        val manifest = buildManifest(full, partsDir, nameOnly, source, total, partCount, branch, token)
            ?: return GitHubCommitWritten(null, null, "manifest 生成失败：卷账读不回来（网络抖动），重传一次即可（卷会跳过）")
        val manifestRes = uploadSingleBlobCommit(
            full, "$partsDir/manifest.json", branch,
            "$message（manifest：$nameOnly 共 $partCount 卷）", manifest, manifest.sizeBytes,
            token, tick, done, total, partCount + 1, partCount + 1,
        )
        if (manifestRes.error != null) return manifestRes
        return GitHubCommitWritten(manifestRes.commitSha, null, null)
    }

    /** 一卷/清单的完整落盘：blob（流式）到 tree 到 commit 到 ref。 */
    private fun uploadSingleBlobCommit(
        full: String,
        cleanPath: String,
        branch: String?,
        message: String,
        source: UploadSource,
        sizeBytes: Long,
        token: String?,
        tick: ThrottledSink,
        doneBefore: Long,
        total: Long,
        idx: Int,
        idxTotal: Int,
    ): GitHubCommitWritten {
        val br = branch?.takeIf { it.isNotBlank() } ?: "main"
        val refResult = fetch("$GITHUB_API_ROOT/repos/$full/git/ref/heads/${encodeSegment(br)}", token, null, GITHUB_MAX_BODY_CHARS)
        if (refResult.status != 200) return GitHubCommitWritten(null, null, "分支「$br」对不上账（${refResult.status}）：核对分支名")
        val refObj = runCatching { json.parseToJsonElement(refResult.body) }.getOrNull() as? JsonObject
        val baseCommit = (((refObj?.get("object") as? JsonObject)?.get("sha")) as? JsonPrimitive)?.contentOrNull
        if (baseCommit.isNullOrBlank()) return GitHubCommitWritten(null, null, "分支「$br」的头指针读不出来：稍后重试")
        val commitObj = runCatching {
            json.parseToJsonElement(fetch("$GITHUB_API_ROOT/repos/$full/git/commits/$baseCommit", token, null, GITHUB_MAX_BODY_CHARS).body)
        }.getOrNull() as? JsonObject
        val baseTree = (commitObj?.get("tree") as? JsonObject)?.let { t -> (t["sha"] as? JsonPrimitive)?.contentOrNull }

        val counting = countingSource(source, sizeBytes, tick, doneBefore, total, idx, idxTotal)
        val blobSha = putBlobForSha(full, counting, sizeBytes, token)
            ?: return GitHubCommitWritten(null, null, "blob 上传失败（网络中断或对端拒收）：直接重传，已传的卷会自动跳过")

        val treeBody = buildString {
            append("{\"tree\":[{\"path\":\"").append(jsonStr(cleanPath))
            append("\",\"mode\":\"100644\",\"type\":\"blob\",\"sha\":\"").append(blobSha).append("\"}]")
            if (!baseTree.isNullOrBlank()) append(",\"base_tree\":\"").append(baseTree).append('"')
            append('}')
        }
        val treeResult = githubHttpPostJson("$GITHUB_API_ROOT/repos/$full/git/trees", token, treeBody)
        if (treeResult.status !in 200..299) return GitHubCommitWritten(null, null, "tree 步失败（${treeResult.status}）：${httpIssue(treeResult)}")
        val treeSha = ((runCatching { json.parseToJsonElement(treeResult.body) }.getOrNull() as? JsonObject)
            ?.get("sha") as? JsonPrimitive)?.contentOrNull ?: return GitHubCommitWritten(null, null, "tree 账里没有 sha：稍后重试")

        val commitBody = "{\"message\":${jsonStr(message)},\"tree\":\"$treeSha\",\"parents\":[\"$baseCommit\"]}"
        val commitResult = githubHttpPostJson("$GITHUB_API_ROOT/repos/$full/git/commits", token, commitBody)
        if (commitResult.status !in 200..299) return GitHubCommitWritten(null, null, "commit 步失败（${commitResult.status}）：${httpIssue(commitResult)}")
        val newCommit = ((runCatching { json.parseToJsonElement(commitResult.body) }.getOrNull() as? JsonObject)
            ?.get("sha") as? JsonPrimitive)?.contentOrNull ?: return GitHubCommitWritten(null, null, "commit 账里没有 sha：稍后重试")

        val refBody = "{\"sha\":\"$newCommit\",\"force\":false}"
        val push = githubHttpPostJson("$GITHUB_API_ROOT/repos/$full/git/refs/heads/${encodeSegment(br)}", token, refBody)
            .takeIf { it.status in 200..299 }
            ?: githubHttpPatchJson("$GITHUB_API_ROOT/repos/$full/git/refs/heads/${encodeSegment(br)}", token, refBody)
        if (push.status !in 200..299) {
            return GitHubCommitWritten(
                null, null,
                if (push.status == 422 || push.status == 409) "分支被别人先推了一步（${push.status}）：直接重传，进度会接着走"
                else "ref 步失败（${push.status}）：${httpIssue(push)}",
            )
        }
        tick.emit(doneBefore + sizeBytes, "第 $idx/$idxTotal 件已落库", force = true)
        return GitHubCommitWritten(newCommit, treeSha, null)
    }

    /** 流式发送一个 blob，返回 sha。发一半断掉会失败报账（不装成功）。 */
    private fun putBlobForSha(full: String, source: UploadSource, sizeBytes: Long, token: String?): String? {
        val head = "{\"content\":\""
        val tail = "\",\"encoding\":\"base64\"}"
        val b64Len = (sizeBytes + 2) / 3 * 4
        val result = githubHttpSendStreaming(
            "$GITHUB_API_ROOT/repos/$full/git/blobs", token, "POST",
            head.length.toLong() + b64Len + tail.length.toLong(),
        ) { out ->
            out.write(head.toByteArray(Charsets.UTF_8))
            val enc = java.util.Base64.getEncoder().wrap(out)
            source.open().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) enc.write(buf, 0, n)
                }
                enc.close()
            }
            out.write(tail.toByteArray(Charsets.UTF_8))
        }
        if (result.status !in 200..299) return null
        return ((runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject)
            ?.get("sha") as? JsonPrimitive)?.contentOrNull
    }

    /** InputStream 扩展：跳过 [n] 字节（skip 不保证跳满的老账在这里兜：循环读丢弃）。internal 供测试。 */
    internal fun java.io.InputStream.skipFully(n: Long): java.io.InputStream {
        var left = n
        val buf = ByteArray(64 * 1024)
        while (left > 0) {
            val got = read(buf, 0, minOf(left, buf.size.toLong()).toInt())
            if (got < 0) break
            left -= got
        }
        return this
    }

    /** 计数源：open() 时包一层计数流，把「发送中已写出字节」实时报进度（blob 流式即进度）。 */
    private fun countingSource(
        src: UploadSource,
        sizeBytes: Long,
        tick: ThrottledSink,
        doneBefore: Long,
        total: Long,
        idx: Int,
        idxTotal: Int,
    ): UploadSource = UploadSource(src.name, sizeBytes) {
        val raw = src.open()
        object : java.io.InputStream() {
            var sent = 0L
            override fun read(): Int {
                val v = raw.read()
                if (v >= 0) markSent(1)
                return v
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = raw.read(b, off, len)
                if (n > 0) markSent(n.toLong())
                return n
            }
            private fun markSent(n: Long) {
                sent += n
                tick.emit(doneBefore + sent, "第 $idx/$idxTotal 件：已发 ${com.hualuo.engine.language.HexDump.humanBytes(sent)}")
            }
            override fun close() { raw.close() }
        }
    }

    /** manifest 生成：卷清单 + 流式 sha256（文件再流一遍算哈希，内存不涨）。 */
    private fun buildManifest(
        full: String,
        partsDir: String,
        nameOnly: String,
        source: UploadSource,
        total: Long,
        partCount: Int,
        branch: String?,
        token: String?,
    ): UploadSource? {
        val dir = browse(full, "$partsDir/", branch, token)
        if (dir.error != null) return null
        val parts = dir.entries
            .filter { Regex("part\\d{5}$").containsMatchIn(it.name) }
            .sortedBy { it.name }
        if (parts.isEmpty()) return null
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        runCatching {
            source.open().use { input ->
                val buf = ByteArray(128 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) digest.update(buf, 0, n)
                }
            }
        }
        val sha256 = digest.digest().joinToString("") { String.format("%02x", it) }
        val json = buildString {
            append("{\"originalName\":\"").append(jsonStr(nameOnly))
            append("\",\"sizeBytes\":").append(total)
            append(",\"chunkBytes\":").append(CHUNK_BYTES)
            append(",\"parts\":").append(partCount)
            append(",\"sha256\":\"").append(sha256).append('"')
            append(",\"files\":[")
            parts.forEachIndexed { i, p ->
                if (i > 0) append(',')
                append("{\"name\":\"").append(jsonStr(p.name)).append("\",\"sizeBytes\":").append(p.sizeBytes).append('}')
            }
            append("]}")
        }
        val bytes = json.toByteArray(Charsets.UTF_8)
        return UploadSource("manifest.json", bytes.size.toLong()) { java.io.ByteArrayInputStream(bytes) }
    }

    /** contents 通路：base64 已在手，PUT 一次（sha 有=更新，无=新建）。 */
    private fun putContentsBase64(
        full: String,
        cleanPath: String,
        branch: String?,
        message: String,
        b64: String,
        sha: String?,
        token: String?,
    ): GitHubCommitWritten {
        val encoded = encodeContentsPath(cleanPath)
        val url = "$GITHUB_API_ROOT/repos/$full/contents/$encoded"
        val body = buildString {
            append("{\"message\":").append(jsonStr(message))
            append(",\"content\":\"").append(b64).append('"')
            if (!sha.isNullOrBlank()) append(",\"sha\":\"").append(sha).append('"')
            if (!branch.isNullOrBlank()) append(",\"branch\":\"").append(encodeSegment(branch)).append('"')
            append('}')
        }
        val result = githubHttpPutJson(url, token, body)
        return commitFromResult(result)
    }

    /** GitHubCommitWritten 兼容：上传成功后的回执拼装。 */
    /** GitHubCommitWritten 兼容：上传成功后的回执拼装。 */
    private fun commitFromResult(result: GitHubHttpResult): GitHubCommitWritten {
        if (result.status == 422 || result.status == 409) {
            return GitHubCommitWritten(null, null, "文件在我读到之后被别人改过（GitHub 报 ${result.status}）：重新上传一次再试，别硬盖别人的账")
        }
        if (result.status == 404) return GitHubCommitWritten(null, null, "GitHub 说没这个文件或分支（404）：路径或分支不对")
        if (result.status !in 200..299) return GitHubCommitWritten(null, null, httpIssue(result))
        val obj = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
            ?: return GitHubCommitWritten(null, null, "GitHub 回的内容读不懂（200 但不是提交回执）")
        val commitSha = ((obj["commit"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
        val contentSha = ((obj["content"] as? JsonObject)?.get("sha") as? JsonPrimitive)?.contentOrNull
        return GitHubCommitWritten(commitSha, contentSha, null)
    }

    /** JSON 字符串值转义（上传路径与 commit message 都要过这）。 */
    private fun jsonStr(s: String): String {
        val sb = StringBuilder("\"")
        for (ch in s) {
            when (ch) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (ch < ' ') sb.append(String.format("\\u%04x", ch.code)) else sb.append(ch)
            }
        }
        return sb.append('"').toString()
    }

    private fun parseRepoList(result: GitHubHttpResult): GitHubRepoList {
        val array = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonArray
            ?: return GitHubRepoList(emptyList(), 0, "GitHub 回的内容读不懂（200 但不是仓库清单）")
        val repos = ArrayList<GitHubRepoSummary>()
        var bad = 0
        for (element in array) {
            if (element !is JsonObject) { bad += 1; continue }
            val fullName = (element["full_name"] as? JsonPrimitive)?.contentOrNull
            if (fullName.isNullOrEmpty()) { bad += 1; continue }
            repos += GitHubRepoSummary(
                fullName = fullName,
                isPrivate = (element["private"] as? JsonPrimitive)?.booleanOrNull ?: false,
                description = (element["description"] as? JsonPrimitive)?.contentOrNull ?: "",
                defaultBranch = (element["default_branch"] as? JsonPrimitive)?.contentOrNull ?: "main",
                updatedAt = (element["updated_at"] as? JsonPrimitive)?.contentOrNull ?: "",
                sizeKb = (element["size"] as? JsonPrimitive)?.contentOrNull?.toLongOrNull() ?: 0L,
            )
        }
        return GitHubRepoList(repos, bad, null)
    }

    /** 失败的人话：只说状态码与该干什么，原文不抄。 */
    private fun httpIssue(result: GitHubHttpResult): String = when {
        result.status == 0 -> "连不上 GitHub：${brief(result.body)}"
        result.status == 401 -> "GitHub 不认这把令牌（401）：去设置里核对或换新令牌"
        result.status == 403 -> "GitHub 不给看（403）：私有仓库要令牌，或者令牌权限不够"
        result.status == 404 -> "GitHub 说没这个仓库（404）：去设置里核对仓库名"
        else -> "GitHub 回了 ${result.status}，稍后再试"
    }

    private fun brief(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 160) flat else flat.take(160) + "…（已截断）"
    }

    /** contents 路径逐段编码：斜杠保留当分隔符，空格变 %20（不是加号——路径里的 + 是真加号）。 */
    private fun encodeContentsPath(path: String): String =
        path.trim().trim('/').split('/').filter { it.isNotEmpty() }.joinToString("/") { encodeSegment(it) }

    private fun encodeSegment(segment: String): String =
        URLEncoder.encode(segment, "UTF-8").replace("+", "%20")

    private companion object {
        /** 分卷阈值：GitHub 单请求 100MB 硬限之下留裕量（绕限靠切卷，不靠拒绝大文件）。 */
        const val CHUNK_BYTES = 90L * 1024 * 1024

        /** contents 单请求上限（GitHub 硬限 1MB，留一点余量）。 */
        const val CONTENTS_LIMIT = 900L * 1024
        val json = Json { ignoreUnknownKeys = true }
    }
}
