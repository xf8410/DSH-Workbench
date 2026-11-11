package com.hualuo.engine.toolcalls

import com.hualuo.engine.github.GitHubRepoCreator
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull

/**
 * 建仓工具（0.8.0）：把 GitHubRepoCreator 接到工具表上。
 *
 * 默认私有——「建个仓」最容易手滑成公开；要公开得在参数里明写 private=false。
 * 名字被占用/没权限/没令牌的全部失败都在结果文本里点名，绝不静默。
 */
object GitHubCreateRepoTool {

    fun register(
        registry: ToolRegistry,
        loadToken: () -> String?,
        creator: GitHubRepoCreator = GitHubRepoCreator(),
    ) {
        registry.register(
            ToolSpec(
                name = "github_create_repo",
                description = "在 GitHub 上新建一个仓库（默认私有）。" +
                    "名字被占用、没有权限或没有令牌时会明确报错；新建后默认带一份 README（auto_init）",
                parametersJson = """{"type":"object","properties":{"name":{"type":"string","description":"仓库名（字母数字点划线，1..100 位）"},"private":{"type":"boolean","description":"true=私有（默认），false=公开"},"description":{"type":"string","description":"仓库简介（可空）"},"auto_init":{"type":"boolean","description":"true=顺手建 README（默认 true）"}},"required":["name"]}""",
            ),
        ) { argumentsJson ->
            val args = runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
                ?: JsonObject(emptyMap())
            val name = opt(args, "name") ?: fail("缺参数 name：要建的仓库名")
            val isPrivate = boolOf(args, "private") ?: true
            val autoInit = boolOf(args, "auto_init") ?: true
            val description = (args["description"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
            val token = loadToken()?.trim()?.takeIf { it.isNotEmpty() }
                ?: fail("建仓要令牌：用 github_set_token 保存用户明确提供的令牌")
            val created = creator.create(name, isPrivate, description, autoInit, token)
            val problem = created.error
            if (problem != null) fail(problem)
            buildString {
                append("已创建仓库 ").append(created.fullName ?: name)
                append(if (isPrivate) "（私有）" else "（公开）")
                created.htmlUrl?.takeIf { it.isNotBlank() }?.let { append("：").append(it) }
                created.defaultBranch?.takeIf { it.isNotBlank() }?.let { append("，默认分支 ").append(it) }
            }
        }
    }

    private fun opt(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun boolOf(args: JsonObject, key: String): Boolean? =
        (args[key] as? JsonPrimitive)?.booleanOrNull

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}