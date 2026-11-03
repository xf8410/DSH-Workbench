package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubBranchList
import com.hualuo.engine.github.GitHubBrowse
import com.hualuo.engine.github.GitHubCiSnapshot
import com.hualuo.engine.github.GitHubCodeSearchResult
import com.hualuo.engine.github.GitHubCommitList
import com.hualuo.engine.github.GitHubFileContent
import com.hualuo.engine.github.GitHubJobList
import com.hualuo.engine.github.GitHubRepoClient
import com.hualuo.engine.github.GitHubRepoList
import com.hualuo.engine.github.GitHubSearchClient
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * GitHub 工具族（读类第一批，0.7.0 刀②）：把界面层已经跑通的 GitHub 引擎件
 * 注册成模型可调用的工具（tool_calls 协议；刀①把管道打通，本刀把工具接上）。
 *
 * 为什么只上读类：读是随手可用（列仓、看目录、读文件、搜代码、看分支历史与 CI）；
 * 写（改码提交）必须有独立的确认与对账通道，不许混进这张随对话自动执行的表——
 * 否则模型能不经任何人点头就改仓库。写类工具名字一个都不许出现在本族里，测试钉死。
 *
 * 钥匙与默认仓库都在**执行那一刻**现场读（两个 lambda）：设置页改了令牌，下一句就用新的，
 * 不用重启；钥匙只进请求头（引擎件老规矩），绝不进任何结果文本。
 *
 * 结果文本的形状：短清单直给（带条数账）；读到的长正文由 ToolResultTrimmer 那道
 * 上下文闸兜底（4000 字符封顶带账），本族在源头也省着写（截断、坏条目计数都必须出声）。
 */
object GitHubToolFamily {

    /**
     * 组装整族工具。
     *
     * @param loadToken 现场读设置里的 GitHub 访问令牌；null/空 = 没配。
     * @param defaultRepo 现场读设置里的默认仓库；工具参数里没给 repo 时兜底。
     * @param repoClient 仓库客户端（可注入假 fetch 的实例，JVM 测试不碰真网）。
     * @param searchClient 仓内搜索客户端（同理可注入）。
     */
    fun build(
        loadToken: () -> String?,
        defaultRepo: () -> String? = { null },
        repoClient: GitHubRepoClient = GitHubRepoClient(),
        searchClient: GitHubSearchClient = GitHubSearchClient(),
    ): ToolRegistry {
        val registry = ToolRegistry()
        val token: () -> String? = { loadToken()?.trim()?.takeIf { it.isNotEmpty() } }

        registry.register(
            ToolSpec(
                name = "github_list_my_repos",
                description = "列出我自己的 GitHub 仓库（含私有；私有仓看得到要设置里填了令牌）。" +
                    "回答「我有哪些仓库」这类问题用这个",
                parametersJson = """{"type":"object","properties":{"limit":{"type":"integer","description":"最多列几个，默认 30，范围 1-100"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val limit = (optInt(args, "limit") ?: 30).coerceIn(1, 100)
            formatRepoList(repoClient.listMyRepos(token(), limit), limit)
        }

        registry.register(
            ToolSpec(
                name = "github_list_user_repos",
                description = "列出任意 GitHub 用户名下的公开仓库（看别人的仓用这个，不必带令牌）",
                parametersJson = """{"type":"object","properties":{"owner":{"type":"string","description":"GitHub 用户名，一段，如 xf8410"},"limit":{"type":"integer","description":"最多列几个，默认 30，范围 1-100"}},"required":["owner"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val owner = reqStr(args, "owner", "GitHub 用户名，一段，如 xf8410")
            val limit = (optInt(args, "limit") ?: 30).coerceIn(1, 100)
            formatRepoList(repoClient.listUserRepos(owner, token(), limit), limit)
        }

        registry.register(
            ToolSpec(
                name = "github_browse_repo",
                description = "浏览仓库目录：看某个仓里有什么文件和子目录。" +
                    "repo 缺省用设置里的默认仓库；path 空=仓库根；ref 空=默认分支",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"path":{"type":"string","description":"仓库内路径，空=仓库根"},"ref":{"type":"string","description":"分支或 tag，空=默认分支"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val path = optStr(args, "path") ?: ""
            val ref = optStr(args, "ref")
            formatBrowse(repoClient.browse(repo, path, ref, token()))
        }

        registry.register(
            ToolSpec(
                name = "github_read_file",
                description = "读仓库里的一个文本文件（源码、md、json 等）。返回正文与 blob sha；" +
                    "二进制文件不给内容。本工具只读，不改任何东西",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"path":{"type":"string","description":"仓库内路径，如 app/src/main/kotlin/X.kt"},"ref":{"type":"string","description":"分支或 tag，空=默认分支"}},"required":["path"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val path = reqStr(args, "path", "仓库内路径，如 app/src/main/kotlin/X.kt")
            val ref = optStr(args, "ref")
            formatFile(repoClient.readFile(repo, path, ref, token()))
        }

        registry.register(
            ToolSpec(
                name = "github_search_code",
                description = "在仓库内按关键词搜代码（命中给仓内路径）。GitHub 的死规矩：" +
                    "必须带令牌，且只覆盖默认分支；配额紧，403 时等一分钟再试",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"keyword":{"type":"string","description":"搜索词：类名、函数名、报错原文都行"},"limit":{"type":"integer","description":"最多几条命中，默认 20，范围 1-50"}},"required":["keyword"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val keyword = reqStr(args, "keyword", "搜索词：类名、函数名、报错原文都行")
            val limit = (optInt(args, "limit") ?: 20).coerceIn(1, 50)
            formatSearch(searchClient.searchCode(repo, keyword, token(), limit), repo, keyword)
        }

        registry.register(
            ToolSpec(
                name = "github_list_branches",
                description = "列仓库的分支（分支名与头指针 sha）：找分支、核对分支头用这个",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"limit":{"type":"integer","description":"最多几条，默认 50，范围 1-100"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val limit = (optInt(args, "limit") ?: 50).coerceIn(1, 100)
            formatBranches(repoClient.listBranches(repo, token(), limit), limit)
        }

        registry.register(
            ToolSpec(
                name = "github_list_commits",
                description = "看提交历史（新在前：短 sha、日期、作者、消息首行）。" +
                    "可选 ref 指定分支、path 只看某个文件的账",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"ref":{"type":"string","description":"分支或 tag，空=默认分支"},"path":{"type":"string","description":"只看这个文件的提交（可空）"},"limit":{"type":"integer","description":"最多几条，默认 20，范围 1-100"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val ref = optStr(args, "ref")
            val path = optStr(args, "path")
            val limit = (optInt(args, "limit") ?: 20).coerceIn(1, 100)
            formatCommits(repoClient.listCommits(repo, ref, path, token(), limit))
        }

        registry.register(
            ToolSpec(
                name = "github_ci_runs",
                description = "看仓库的 GitHub Actions 最近几次运行（run id、名字、状态、结论、时间、head sha）：" +
                    "排查 CI 红绿用这个",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"limit":{"type":"integer","description":"最多几次，默认 10，范围 1-20"}}}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val limit = (optInt(args, "limit") ?: 10).coerceIn(1, 20)
            formatRuns(repoClient.runs(repo, token(), limit))
        }

        registry.register(
            ToolSpec(
                name = "github_ci_jobs",
                description = "看一次运行里各 job 的状态（需要 run_id，从 github_ci_runs 的结果里拿）",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"run_id":{"type":"integer","description":"运行 id，从 github_ci_runs 的结果里拿"}},"required":["run_id"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val runId = optLong(args, "run_id")
                ?: fail("缺参数 run_id（数字，从 github_ci_runs 的结果里拿）")
            formatJobs(repoClient.runJobs(repo, runId, token()))
        }

        registry.register(
            ToolSpec(
                name = "github_ci_job_log",
                description = "读一个 job 的日志正文（需要 job_id，从 github_ci_jobs 的结果里拿）。" +
                    "日志有界读：超长会截断并说明",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用设置里的默认仓库"},"job_id":{"type":"integer","description":"job id，从 github_ci_jobs 的结果里拿"}},"required":["job_id"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val jobId = optLong(args, "job_id")
                ?: fail("缺参数 job_id（数字，从 github_ci_jobs 的结果里拿）")
            val log = repoClient.jobLog(repo, jobId, token())
            val logError = log.error
            if (logError != null) fail(logError)
            val text = log.text ?: fail("这段日志没有内容（job 还没吐字或日志已被清）")
            val cut = if (log.truncated) "，被有界读截断（只给了前一段）" else ""
            "job $jobId 日志（${log.charCount} 字符$cut）：\n$text"
        }

        return registry
    }

    // ── 结果文本的格式化（纯函数，短清单直给、账目出声） ────────────────────

    private fun formatRepoList(list: GitHubRepoList, limit: Int): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.repos.isEmpty()) return "清单是空的：一个仓库也没列出来（私有仓要令牌）"
        val sb = StringBuilder("共 ${list.repos.size} 个仓库（本页最多 $limit）：")
        list.repos.take(limit).forEach { repo ->
            sb.append("\n- ").append(repo.fullName)
            sb.append(if (repo.isPrivate) "（私有）" else "（公开）")
            sb.append(" 默认分支=").append(repo.defaultBranch)
            if (repo.updatedAt.isNotEmpty()) sb.append(" 更新于 ").append(repo.updatedAt)
            if (repo.description.isNotEmpty()) sb.append("：").append(repo.description.take(80))
        }
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatBrowse(browse: GitHubBrowse): String {
        val error = browse.error
        if (error != null) fail(error)
        if (browse.entries.isEmpty()) return "这个目录里没有东西（空目录，或者路径/分支不对）"
        val sb = StringBuilder("共 ${browse.entries.size} 项（目录在前）：")
        browse.entries.take(200).forEach { entry ->
            sb.append("\n- ").append(if (entry.isDir) "[目录] " else "[文件] ").append(entry.path)
            if (!entry.isDir && entry.sizeBytes > 0) sb.append("（").append(entry.sizeBytes).append(" 字节）")
        }
        if (browse.entries.size > 200) sb.append("\n（只列了前 200 项，要更全就按子目录再看一层）")
        if (browse.badEntries > 0) sb.append("\n（另有 ${browse.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatFile(file: GitHubFileContent): String {
        val error = file.error
        if (error != null) fail(error)
        val text = file.text ?: fail("这个文件没有可给的内容（二进制或读不出来），别硬猜")
        val marks = buildString {
            if (file.tooBig) append("，超 1MB 上限只给了前一段")
            if (file.truncated) append("，内容被有界读截断")
        }
        val head = "文件 ${file.path}（sha=${file.sha ?: "无"}$marks）"
        return head + "\n" + text
    }

    private fun formatSearch(result: GitHubCodeSearchResult, repo: String, keyword: String): String {
        val error = result.error
        if (error != null) fail(error)
        val incompleteNote = if (result.incomplete) "，它标记结果可能不全" else ""
        val sb = StringBuilder(
            "在 $repo 搜「$keyword」：本页 ${result.hits.size} 条命中" +
                "（GitHub 总账 ${result.totalCount} 条$incompleteNote；搜索只覆盖默认分支）",
        )
        result.hits.forEach { hit -> sb.append("\n- ").append(hit.path) }
        if (result.badEntries > 0) sb.append("\n（另有 ${result.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatBranches(list: GitHubBranchList, limit: Int): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.branches.isEmpty()) return "这个仓库一条分支都没列出来"
        val sb = StringBuilder("共 ${list.branches.size} 条分支：")
        list.branches.take(limit).forEach { branch ->
            sb.append("\n- ").append(branch.name).append("（头 ").append(branch.commitSha.take(8)).append("）")
        }
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatCommits(list: GitHubCommitList): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.commits.isEmpty()) return "没有提交记录可列"
        val sb = StringBuilder("共 ${list.commits.size} 条提交（新在前）：")
        list.commits.forEach { commit ->
            sb.append("\n- ").append(commit.sha.take(8)).append(" ")
            if (commit.date.isNotEmpty()) sb.append(commit.date).append(" ")
            if (commit.author.isNotEmpty()) sb.append(commit.author).append("：")
            sb.append(commit.messageFirstLine)
        }
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatRuns(snapshot: GitHubCiSnapshot): String {
        val error = snapshot.error
        if (error != null) fail(error)
        if (snapshot.runs.isEmpty()) return "最近没有 workflow 运行记录"
        val sb = StringBuilder("最近 ${snapshot.runs.size} 次运行（新在前）：")
        snapshot.runs.forEach { run ->
            sb.append("\n- run ").append(run.id).append("（").append(run.name).append("）：")
                .append(run.status)
            if (run.conclusion != null) sb.append("/").append(run.conclusion)
            if (run.createdAt.isNotEmpty()) sb.append(" ").append(run.createdAt)
            sb.append(" sha=").append(run.headSha.take(8))
        }
        if (snapshot.badEntries > 0) sb.append("\n（另有 ${snapshot.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    private fun formatJobs(list: GitHubJobList): String {
        val error = list.error
        if (error != null) fail(error)
        if (list.jobs.isEmpty()) return "这次运行没有 job 记录（可能还没排队，或记录已被清理）"
        val sb = StringBuilder("这次运行有 ${list.jobs.size} 个 job：")
        list.jobs.forEach { job ->
            sb.append("\n- job ").append(job.id).append("（").append(job.name).append("）：")
                .append(job.status)
            if (job.conclusion != null) sb.append("/").append(job.conclusion)
        }
        if (list.badEntries > 0) sb.append("\n（另有 ${list.badEntries} 条读不懂，已跳过）")
        return sb.toString()
    }

    // ── 参数解析小件（垃圾参数当空对象：模型手滑一次不许把整轮弄炸） ────────

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
        ?: fail("缺参数 repo（owner/name 写法；也可以先去设置「GitHub 工作台」填默认仓库）")

    /** 工具执行失败：抛出去由注册表折成「给模型看的失败文本」（照样回填，不算链路错）。 */
    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
