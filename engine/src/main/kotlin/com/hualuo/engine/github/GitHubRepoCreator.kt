package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** 新建仓库的回执：成功带仓名/链接/默认分支；失败 error 带人话（绝不静默）。 */
data class GitHubRepoCreated(
    val fullName: String?,
    val htmlUrl: String?,
    val defaultBranch: String?,
    val error: String?,
)

/**
 * 新建仓库（POST /user/repos）。纯 JVM；postJson 缝隙注入，JVM 测试不碰网络。
 *
 *  - 令牌只进请求头，绝不进任何报错文本；
 *  - 名字按 GitHub 的硬约束先拦一道（字母数字点划线，1..100 位），写法不对在碰网前就拒；
 *  - 名字被占用时 GitHub 回 422，这里翻成可行动的话；401/403 分别说清是令牌还是权限；
 *  - 只建个人名下的仓（/user/repos），不碰组织仓与转移，避免把别人的命名空间带进来。
 */
class GitHubRepoCreator(
    private val postJson: (String, String?, String) -> GitHubHttpResult = ::githubHttpPostJson,
) {
    fun create(
        name: String,
        private: Boolean,
        description: String,
        autoInit: Boolean,
        token: String?,
    ): GitHubRepoCreated {
        val clean = name.trim()
        if (!clean.matches(NAME_RULE)) {
            return GitHubRepoCreated(
                null, null, null,
                "仓库名不合法（$clean）：只能用字母、数字、点、下划线、横线，1 到 100 位",
            )
        }
        if (token.isNullOrBlank()) {
            return GitHubRepoCreated(null, null, null, "建仓要令牌：用 github_set_token 保存用户明确提供的令牌")
        }
        val body = buildJsonObject {
            put("name", clean)
            put("private", private)
            if (description.isNotBlank()) put("description", description.take(350))
            put("auto_init", autoInit)
        }.toString()
        val result = postJson("$GITHUB_API_ROOT/user/repos", token, body)
        val obj = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
        return when {
            result.status == 201 -> GitHubRepoCreated(
                fullName = (obj?.get("full_name") as? JsonPrimitive)?.contentOrNull ?: clean,
                htmlUrl = (obj?.get("html_url") as? JsonPrimitive)?.contentOrNull,
                defaultBranch = (obj?.get("default_branch") as? JsonPrimitive)?.contentOrNull,
                error = null,
            )
            result.status == 401 -> GitHubRepoCreated(null, null, null, "令牌无效（401）：重新保存一枚有建仓权限的令牌")
            result.status == 403 -> GitHubRepoCreated(null, null, null, "没有建仓权限或到了配额（403）：${brief(result)}")
            result.status == 422 -> GitHubRepoCreated(null, null, null, "名字「$clean」被占用或格式不对（422）：换一个名字")
            else -> GitHubRepoCreated(null, null, null, httpIssue(result))
        }
    }

    private fun brief(result: GitHubHttpResult): String = runCatching {
        val obj = json.parseToJsonElement(result.body) as? JsonObject
        (obj?.get("message") as? JsonPrimitive)?.contentOrNull.orEmpty()
    }.getOrNull().orEmpty().ifBlank { "GitHub 回了 ${result.status}" }.take(160)

    private fun httpIssue(result: GitHubHttpResult): String = when {
        result.status == 0 -> "网络不通：${result.body.take(120)}"
        else -> "GitHub 回了 ${result.status}：${brief(result)}"
    }

    private companion object {
        val NAME_RULE = Regex("[A-Za-z0-9._-]{1,100}")
        val json = Json { ignoreUnknownKeys = true }
    }
}