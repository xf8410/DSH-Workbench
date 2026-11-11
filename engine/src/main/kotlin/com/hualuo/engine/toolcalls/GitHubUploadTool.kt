package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubCommitWritten
import com.hualuo.engine.github.GitHubRepoClient
import com.hualuo.engine.github.normalizeGitHubRepo
import com.hualuo.engine.language.HexDump
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/** 一次文件上传：repo 内目标路径、来源、提交说明；失败也回 GitHubCommitWritten（带人话错误）。 */
fun interface GitHubFileUpload {
    fun upload(
        repo: String,
        path: String,
        branch: String?,
        message: String,
        source: GitHubRepoClient.UploadSource,
        token: String?,
    ): GitHubCommitWritten
}

/**
 * 传文件工具（0.8.0）：把手机上的文件（APK/软件包/图片/压缩包，全格式）传进仓库。
 *
 * 引擎件 GitHubRepoClient.uploadFile 已经是流式 + 分卷 + 断点续传（大文件写 manifest 全账），
 * 本工具只做三件事：把本地路径变成可重开的流、把目标路径拼对、把每一个文件的收场
 * 原样报出来（成功的带 commit，失败的带人话）——绝不静默丢文件。
 */
object GitHubUploadTool {

    fun register(
        registry: ToolRegistry,
        loadToken: () -> String?,
        defaultRepo: () -> String?,
        upload: GitHubFileUpload = GitHubFileUpload { repo, path, branch, message, source, token ->
            GitHubRepoClient().uploadFile(repo, path, branch, message, source, token)
        },
    ) {
        registry.register(
            ToolSpec(
                name = "github_upload_files",
                description = "把手机上的文件（APK/软件包、图片、压缩包等）流式上传到 GitHub 仓库的指定目录；" +
                    "大文件自动分卷并写 manifest，断了重传会跳过已传的卷。files 是手机本地绝对路径（如 /sdcard/Download/x.apk）",
                parametersJson = """{"type":"object","properties":{"files":{"type":"array","items":{"type":"string"},"description":"手机本地文件绝对路径（1 到 64 个）"},"repo":{"type":"string","description":"owner/name；缺省用默认仓库"},"branch":{"type":"string","description":"分支，缺省 main"},"prefix":{"type":"string","description":"仓库内目录前缀，如 courier/（缺省空=仓库根）"},"message":{"type":"string","description":"提交说明（缺省自动生成）"}},"required":["files"]}""",
            ),
        ) { argumentsJson ->
            val args = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
                ?: JsonObject(emptyMap())
            val paths = pathsOf(args, "files")
            if (paths.isEmpty()) fail("缺参数 files：要给至少一个手机本地绝对路径（如 /sdcard/Download/app.apk）")
            if (paths.size > MAX_FILES) fail("一次最多传 $MAX_FILES 个文件：分批传")
            val repoRaw = (args["repo"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
                ?: defaultRepo()?.trim()?.takeIf { it.isNotEmpty() }
                ?: fail("缺参数 repo（owner/name 写法）")
            val repo = normalizeGitHubRepo(repoRaw)
                ?: fail("仓库写法不对：要 owner/name（现在是「$repoRaw」）")
            val token = loadToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: fail("传文件要令牌：用 github_set_token 保存用户明确提供的令牌")
            val branch = (args["branch"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() } ?: "main"
            val prefix = prefixOf((args["prefix"] as? JsonPrimitive)?.contentOrNull)
            val messageArg = (args["message"] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

            val report = StringBuilder()
            var okCount = 0
            var failCount = 0
            for (raw in paths) {
                val file = File(raw)
                val problem = when {
                    !file.exists() -> "找不到这个文件（检查路径与存储权限）"
                    !file.isFile -> "不是普通文件（目录/特殊文件不走这条通道）"
                    !file.canRead() -> "读不了（权限）"
                    file.length() <= 0L -> "是空文件（0 字节），不值得传"
                    else -> null
                }
                if (problem != null) {
                    failCount += 1
                    report.append("\n- ").append(raw).append("：失败——").append(problem)
                    continue
                }
                val target = prefix + file.name
                val message = messageArg ?: "上传文件：${file.name.take(80)}"
                val result = upload.upload(
                    repo = repo,
                    path = target,
                    branch = branch,
                    message = message,
                    source = GitHubRepoClient.UploadSource(file.name, file.length()) { file.inputStream() },
                    token = token,
                )
                val error = result.error
                if (error != null) {
                    failCount += 1
                    report.append("\n- ").append(target).append("：失败——").append(error)
                } else {
                    okCount += 1
                    report.append("\n- ").append(target)
                        .append("（").append(HexDump.humanBytes(file.length())).append("，commit ")
                        .append(result.commitSha?.take(8) ?: "?").append("）")
                }
            }
            "传文件到 $repo（分支 $branch）：成功 $okCount 个、失败 $failCount 个$report"
        }
    }

    private fun pathsOf(args: JsonObject, key: String): List<String> =
        (args[key] as? JsonArray).orEmpty()
            .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }

    private fun prefixOf(raw: String?): String {
        val p = raw?.trim().orEmpty().trim('/')
        return if (p.isEmpty()) "" else "$p/"
    }

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private const val MAX_FILES = 64
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}