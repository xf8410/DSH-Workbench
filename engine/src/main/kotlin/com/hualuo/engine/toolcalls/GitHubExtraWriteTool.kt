package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubActionsClient
import com.hualuo.engine.github.GitHubCiClient
import com.hualuo.engine.github.GitHubIssueClient
import com.hualuo.engine.github.GitHubReleaseClient
import com.hualuo.engine.github.GitHubRepoClient
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 一次「动手」的提议：重跑、取消、触发、下载、建 issue、评论、关 issue、建发布、传附件共用。
 */
data class GitHubActionProposal(
    val action: String,
    val repo: String,
    /** 动作指向的对象：run 1234 / PR #7 / tag v1.2.0 / artifact 987。 */
    val target: String,
    /** 给人核对的补充（分支、标题、文件路径、字节数等）。 */
    val detail: String,
)

/** 动作闸门：与 [WriteConfirmer] 同纪律——拿不准一律按「没同意」处理。 */
fun interface ActionConfirmer {
    fun confirm(proposal: GitHubActionProposal): Boolean
}

/**
 * GitHub 扩展写类工具（P0 十四件里的「动得了」那一半）：
 * CI 重跑/取消/手动触发、artifact 下载、issue 建/评论/关、release 建/传附件。
 *
 * 与 [GitHubWriteTool] 同一条铁律：**给闸门才存在**——[confirmer] 为空这一族一个都不注册
 * （默认拒执行）。DSH 侧还会再做一次风险审批，两道闸不互相替代。
 *
 * 边界：
 *  - artifact 下载只落 `.zip`、只允许绝对路径且不含 `..`；超过 200MB 直接删半截再报；
 *  - 附件上传从本机读文件，单件上限 [MAX_ASSET_BYTES]（20MB），超了在碰网络之前就拒；
 *  - 令牌只进请求头；所有失败都是人话（状态码 + 出路）。
 */
object GitHubExtraWriteTool {

    /** 单件附件上传上限（字节）。 */
    const val MAX_ASSET_BYTES = 20L * 1024 * 1024

    fun register(
        registry: ToolRegistry,
        loadToken: () -> String?,
        defaultRepo: () -> String? = { null },
        confirmer: ActionConfirmer? = null,
        actionsClient: GitHubActionsClient = GitHubActionsClient(),
        issueClient: GitHubIssueClient = GitHubIssueClient(),
        releaseClient: GitHubReleaseClient = GitHubReleaseClient(),
        ciClient: GitHubCiClient = GitHubCiClient(),
        repoClient: GitHubRepoClient = GitHubRepoClient(),
    ): ToolRegistry {
        if (confirmer == null) return registry // 不给闸门就一个都不注册（默认拒）
        val token: () -> String? = { loadToken()?.trim()?.takeIf { it.isNotEmpty() } }

        registry.register(
            ToolSpec(
                name = "github_ci_rerun",
                description = "重跑一次 GitHub Actions 运行（需要 DSH 风险审批）。" +
                    "failed_only=true 只重跑失败的 job。run_id 从 github_ci_runs 拿",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"run_id":{"type":"integer","description":"运行 id（从 github_ci_runs 拿）"},"failed_only":{"type":"boolean","description":"true=只重跑失败的 job"}},"required":["run_id"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val runId = optLong(args, "run_id") ?: fail("缺参数 run_id（数字，从 github_ci_runs 拿）")
            val failedOnly = optBool(args, "failed_only")
            gate(confirmer, GitHubActionProposal("重跑 CI", repo, "run $runId", if (failedOnly) "只重跑失败的 job" else "重跑全部 job"))
            val result = actionsClient.rerun(repo, runId, token(), failedOnly)
            val problem = result.error
            if (problem != null) fail(problem)
            "已请求重跑 run $runId（${if (failedOnly) "只跑失败 job" else "全部 job"}）：去 github_ci_runs 看新状态"
        }

        registry.register(
            ToolSpec(
                name = "github_ci_cancel",
                description = "取消一次正在跑或排队中的 GitHub Actions 运行（需要 DSH 风险审批）",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"run_id":{"type":"integer","description":"运行 id（从 github_ci_runs 拿）"}},"required":["run_id"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val runId = optLong(args, "run_id") ?: fail("缺参数 run_id（数字，从 github_ci_runs 拿）")
            gate(confirmer, GitHubActionProposal("取消 CI", repo, "run $runId", "取消这次运行"))
            val result = actionsClient.cancel(repo, runId, token())
            val problem = result.error
            if (problem != null) fail(problem)
            "已请求取消 run $runId"
        }

        registry.register(
            ToolSpec(
                name = "github_ci_dispatch",
                description = "手动触发一个配置了 workflow_dispatch 的 workflow（需要 DSH 风险审批）。" +
                    "workflow 是 .github/workflows/ 下的文件名（如 ci.yml）",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"workflow":{"type":"string","description":"workflow 文件名，如 ci.yml"},"ref":{"type":"string","description":"在哪个分支/标签上跑，如 main"},"inputs":{"type":"string","description":"workflow 输入，逗号分隔 k=v,k2=v2；可空"}},"required":["workflow","ref"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val workflow = reqStr(args, "workflow", ".github/workflows/ 下的文件名，如 ci.yml")
            val ref = reqStr(args, "ref", "分支或标签，如 main")
            val inputs = parsePairs(optStr(args, "inputs"))
            gate(
                confirmer,
                GitHubActionProposal(
                    "触发 workflow",
                    repo,
                    "$workflow @ $ref",
                    if (inputs.isEmpty()) "无 inputs" else inputs.entries.joinToString("，") { "${it.key}=${it.value}" },
                ),
            )
            val result = ciClient.dispatch(repo, workflow, ref, token(), inputs)
            val problem = result.error
            if (problem != null) fail(problem)
            "已触发 $workflow（ref=$ref）：运行记录要等几秒才出现在 github_ci_runs"
        }

        registry.register(
            ToolSpec(
                name = "github_artifact_download",
                description = "把一件 Actions artifact（zip）下载到手机本地（需要 DSH 风险审批）。" +
                    "artifact_id 从 github_artifact_list 拿；path 要绝对路径，如 /sdcard/Download/build.zip",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"artifact_id":{"type":"integer","description":"artifact id（从 github_artifact_list 拿）"},"path":{"type":"string","description":"保存到的绝对路径，必须以 .zip 结尾"}},"required":["artifact_id","path"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val artifactId = optLong(args, "artifact_id")
                ?: fail("缺参数 artifact_id（从 github_artifact_list 拿）")
            val target = downloadTarget(reqStr(args, "path", "绝对路径，如 /sdcard/Download/build.zip"))
            gate(
                confirmer,
                GitHubActionProposal("下载 artifact", repo, "artifact $artifactId", "保存到 ${target.path}"),
            )
            val result = actionsClient.downloadArtifact(repo, artifactId, token(), target)
            val problem = result.error
            if (problem != null) fail(problem)
            "已下载 artifact $artifactId -> ${result.path}（${formatBytes(result.bytes)}）"
        }

        registry.register(
            ToolSpec(
                name = "github_issue_create",
                description = "新建一个 issue（需要 DSH 风险审批）。标题必填，正文与标签可空",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"title":{"type":"string","description":"issue 标题"},"body":{"type":"string","description":"正文（可空，markdown）"},"labels":{"type":"string","description":"标签，逗号分隔；可空"}},"required":["title"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val title = reqStr(args, "title", "issue 标题")
            val body = optStr(args, "body") ?: ""
            val labels = (optStr(args, "labels") ?: "").split(',').map { it.trim() }.filter { it.isNotEmpty() }
            gate(
                confirmer,
                GitHubActionProposal("建 issue", repo, title.take(80), "标签：${if (labels.isEmpty()) "无" else labels.joinToString("、")}"),
            )
            val result = issueClient.create(repo, title, body, labels, token())
            val problem = result.error
            if (problem != null) fail(problem)
            "已建 issue #${result.number}${if (result.htmlUrl.isEmpty()) "" else "：${result.htmlUrl}"}"
        }

        registry.register(
            ToolSpec(
                name = "github_issue_comment",
                description = "在一个 issue（或 PR）下留评论（需要 DSH 风险审批）",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"number":{"type":"integer","description":"issue/PR 编号"},"body":{"type":"string","description":"评论正文（markdown）"}},"required":["number","body"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number") ?: fail("缺参数 number（issue/PR 编号）")
            val body = reqStr(args, "body", "评论正文")
            gate(confirmer, GitHubActionProposal("评论", repo, "#$number", "${body.length} 字符"))
            val result = issueClient.comment(repo, number, body, token())
            val problem = result.error
            if (problem != null) fail(problem)
            "已评论 #$number${if (result.htmlUrl.isEmpty()) "" else "：${result.htmlUrl}"}"
        }

        registry.register(
            ToolSpec(
                name = "github_issue_close",
                description = "关闭一个 issue（需要 DSH 风险审批）。拿不准就先读它再决定",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"number":{"type":"integer","description":"issue 编号"}},"required":["number"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number") ?: fail("缺参数 number（issue 编号）")
            gate(confirmer, GitHubActionProposal("关 issue", repo, "#$number", "state -> closed"))
            val result = issueClient.close(repo, number, token())
            val problem = result.error
            if (problem != null) fail(problem)
            "已关闭 #$number"
        }

        registry.register(
            ToolSpec(
                name = "github_release_create",
                description = "建一个 GitHub 发布版（需要 DSH 风险审批）。tag 必填；draft=true 建草稿，" +
                    "prerelease=true 标预发布；target_commitish 可指定从哪个分支/提交打 tag",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"tag":{"type":"string","description":"版本号/tag，如 v1.2.0"},"name":{"type":"string","description":"发布标题（可空）"},"body":{"type":"string","description":"发布说明 markdown（可空）"},"draft":{"type":"boolean","description":"true=草稿"},"prerelease":{"type":"boolean","description":"true=预发布"},"target_commitish":{"type":"string","description":"从哪个分支/提交打 tag（可空，缺省默认分支）"}},"required":["tag"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val tag = reqStr(args, "tag", "版本号，如 v1.2.0")
            val name = optStr(args, "name") ?: ""
            val body = optStr(args, "body") ?: ""
            val draft = optBool(args, "draft")
            val prerelease = optBool(args, "prerelease")
            val target = optStr(args, "target_commitish")
            gate(
                confirmer,
                GitHubActionProposal(
                    "建发布",
                    repo,
                    tag,
                    buildString {
                        append(if (draft) "草稿" else "正式")
                        if (prerelease) append("、预发布")
                        append("，说明 ${body.length} 字符")
                        if (target != null) append("，目标 $target")
                    },
                ),
            )
            val result = releaseClient.create(repo, tag, name, body, draft, prerelease, target, token())
            val problem = result.error
            if (problem != null) fail(problem)
            "已建发布 $tag（id=${result.id}）${if (result.htmlUrl.isEmpty()) "" else "：${result.htmlUrl}"}"
        }

        registry.register(
            ToolSpec(
                name = "github_release_upload_asset",
                description = "把手机上的一个文件传成发布版附件（需要 DSH 风险审批）。" +
                    "release_id 从 github_release_create 或 github_release_list 拿；单件上限 20MB",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"release_id":{"type":"integer","description":"发布版 id"},"path":{"type":"string","description":"本机文件的绝对路径"},"name":{"type":"string","description":"附件名（可空，缺省用文件名）"},"content_type":{"type":"string","description":"MIME，如 application/zip；可空"}},"required":["release_id","path"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val releaseId = optLong(args, "release_id") ?: fail("缺参数 release_id（从 github_release_create / github_release_list 拿）")
            val path = reqStr(args, "path", "本机文件绝对路径，如 /sdcard/Download/dsh.apk")
            val file = sourceFile(path)
            val name = optStr(args, "name") ?: file.name
            val contentType = optStr(args, "content_type") ?: "application/octet-stream"
            gate(
                confirmer,
                GitHubActionProposal("传附件", repo, "release $releaseId", "$name（${formatBytes(file.length())}）"),
            )
            val bytes = file.readBytes()
            val result = releaseClient.uploadAsset(repo, releaseId, name, contentType, bytes, token())
            val problem = result.error
            if (problem != null) fail(problem)
            "已上传 $name（${formatBytes(result.sizeBytes)}）${if (result.downloadUrl.isEmpty()) "" else "：${result.downloadUrl}"}"
        }

        registry.register(
            ToolSpec(
                name = "github_edit_file",
                description = "局部改仓库里的一个文本文件：给一段原文与替换文（需要 DSH 风险审批）。" +
                    "比 github_update_file 省 token：只传改动而不传整文件。默认要求 old 只出现一次；" +
                    "重复就加长锚点，或传 replace_all=true 全改",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"path":{"type":"string","description":"仓库内路径"},"old":{"type":"string","description":"要被替换掉的原文（尽量带上下文，保证唯一）"},"new":{"type":"string","description":"替换成什么；空串=删掉这段"},"message":{"type":"string","description":"提交说明（必填）"},"branch":{"type":"string","description":"分支，缺省 main"},"replace_all":{"type":"boolean","description":"true=把出现的每一处都换掉"}},"required":["path","old","message"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val path = reqStr(args, "path", "仓库内路径")
            val oldText = reqStr(args, "old", "要被替换掉的原文")
            val newText = optStr(args, "new") ?: ""
            val message = reqStr(args, "message", "提交说明（会进仓库历史）")
            val branch = optStr(args, "branch") ?: "main"
            val replaceAll = optBool(args, "replace_all")
            val tk = token()
            val current = repoClient.readFile(repo, path, branch, tk)
            val readError = current.error
            if (readError != null) fail(readError)
            val text = current.text ?: fail("这个文件没有可编辑的文本（二进制或读不出来）：换 github_update_file 整文件覆盖")
            val sha = current.sha ?: fail("读不到文件 sha：没法做覆盖对账，先 github_read_file 再看一次")
            val count = text.split(oldText).size - 1
            if (count == 0) fail("原文没找到：old 要跟文件里的字符一字不差（含缩进），先 github_read_file 核对")
            if (count > 1 && !replaceAll) {
                fail("原文出现 $count 次：加长锚点让它唯一，或传 replace_all=true 全改")
            }
            val updated = if (replaceAll) {
                text.split(oldText).joinToString(newText)
            } else {
                val idx = text.indexOf(oldText)
                text.substring(0, idx) + newText + text.substring(idx + oldText.length)
            }
            if (updated.length > GitHubWriteTool.MAX_WRITE_CHARS) {
                fail("改完 ${updated.length} 字符，超了单次写入上限 ${GitHubWriteTool.MAX_WRITE_CHARS}：拆成几次小改动")
            }
            gate(
                confirmer,
                GitHubActionProposal(
                    "改文件",
                    repo,
                    path,
                    "$count 处替换，${text.length} -> ${updated.length} 字符，分支 $branch",
                ),
            )
            val result = repoClient.updateFile(repo, path, branch, updated, message, sha, tk)
            val problem = result.error
            if (problem != null) fail(problem)
            "已提交：$repo/$path（分支 $branch，改 $count 处，commit ${result.commitSha?.take(8) ?: "?"}）"
        }

        registry.register(
            ToolSpec(
                name = "github_pr_comment",
                description = "在一个 PR（或 issue）下留评论（需要 DSH 风险审批）。正文支持 markdown",
                parametersJson = """{"type":"object","properties":{"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"number":{"type":"integer","description":"PR/issue 编号"},"body":{"type":"string","description":"评论正文（markdown）"}},"required":["number","body"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val repo = repoArg(args, defaultRepo)
            val number = optLong(args, "number") ?: fail("缺参数 number（PR/issue 编号）")
            val body = reqStr(args, "body", "评论正文")
            gate(confirmer, GitHubActionProposal("评论 PR", repo, "#$number", "${body.length} 字符"))
            val result = issueClient.comment(repo, number, body, token())
            val problem = result.error
            if (problem != null) fail(problem)
            "已评论 #$number${if (result.htmlUrl.isEmpty()) "" else "：${result.htmlUrl}"}"
        }

        return registry
    }

    // ── 闸门与小件 ────────────────────────────────────────────────

    private fun gate(confirmer: ActionConfirmer, proposal: GitHubActionProposal) {
        val approved = runCatching { confirmer.confirm(proposal) }.getOrDefault(false)
        if (!approved) {
            fail("用户没有确认这次「${proposal.action}」（或等待超时、或确认通道故障）：什么都没做。" +
                "若确实要做，把目标、理由说清楚，再提议一次")
        }
    }

    private fun downloadTarget(raw: String): File {
        val path = raw.trim()
        if (path.isEmpty()) fail("缺参数 path（下载到手机的绝对路径，如 /sdcard/Download/build.zip）")
        if (!path.startsWith("/")) fail("path 要是绝对路径（如 /sdcard/Download/build.zip）")
        if (path.contains("..")) fail("path 里不许有 ..（防写到别处）")
        if (!path.lowercase().endsWith(".zip")) fail("artifact 是 zip 包：path 必须以 .zip 结尾")
        return File(path)
    }

    private fun sourceFile(raw: String): File {
        val path = raw.trim()
        if (!path.startsWith("/")) fail("path 要是绝对路径（如 /sdcard/Download/dsh.apk）")
        if (path.contains("..")) fail("path 里不许有 ..（防读到别处）")
        val file = File(path)
        if (!file.isFile) fail("找不到这个文件：$path")
        val size = file.length()
        if (size <= 0L) fail("这是个空文件（0 字节）：$path")
        if (size > MAX_ASSET_BYTES) {
            fail("文件 ${formatBytes(size)} 超过单件上限 ${formatBytes(MAX_ASSET_BYTES)}：先拆包或用别的通道")
        }
        return file
    }

    private fun parsePairs(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        val out = LinkedHashMap<String, String>()
        raw.split(',').forEach { pair ->
            val idx = pair.indexOf('=')
            if (idx > 0) {
                val key = pair.substring(0, idx).trim()
                val value = pair.substring(idx + 1).trim()
                if (key.isNotEmpty()) out[key] = value
            }
        }
        return out
    }

    private fun formatBytes(bytes: Long): String = when {
        bytes >= 1024L * 1024 * 1024 -> String.format("%.1fGB", bytes / (1024.0 * 1024 * 1024))
        bytes >= 1024L * 1024 -> String.format("%.1fMB", bytes / (1024.0 * 1024))
        bytes >= 1024 -> String.format("%.1fKB", bytes / 1024.0)
        else -> "${bytes}B"
    }

    // ── 参数解析小件 ───────────────────────────────────────────────

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun optStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun reqStr(args: JsonObject, key: String, hint: String): String =
        optStr(args, key) ?: fail("缺参数 $key（$hint）")

    private fun optLong(args: JsonObject, key: String): Long? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

    private fun optBool(args: JsonObject, key: String): Boolean =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim() == "true"

    private fun repoArg(args: JsonObject, defaultRepo: () -> String?): String =
        optStr(args, "repo") ?: defaultRepo()?.trim()?.takeIf { it.isNotEmpty() }
        ?: fail("缺参数 repo（owner/name 写法；也可以省略以使用默认仓库）")

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
