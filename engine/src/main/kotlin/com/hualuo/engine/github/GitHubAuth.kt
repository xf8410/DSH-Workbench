package com.hualuo.engine.github

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 令牌验证成功后的身份：登录名 + 权限清单。
 *
 * [scopesDeclared]=false 表示这次响应没带 X-OAuth-Scopes 头（细粒度令牌常见）：
 * 「没声明」不是「没权限」，界面别把这句话说岔了。
 */
data class GitHubIdentity(
    val login: String,
    val scopes: List<String>,
    val scopesDeclared: Boolean,
)

/** 登录验证的收场：一张身份账，或一句带出路的人话。 */
sealed class GitHubAuthOutcome {
    data class Ok(val identity: GitHubIdentity) : GitHubAuthOutcome()
    data class Failed(val reason: String) : GitHubAuthOutcome()
}

/**
 * GitHub 令牌登录验证（2026-09-22 补；纯 JVM，fetch 可注入，测试不碰网）。
 *
 * 干什么：拿调用方给的令牌打一次 GET /user——GitHub 官方的「你是谁」接口，
 * 顺带读 X-OAuth-Scopes 响应头拿权限清单。验证过了才算「已登录」，界面才知道
 * 这个令牌好不好使、能干什么。
 *
 * 边界与全仓一致：
 *  - 令牌只进请求头（引擎件老规矩），绝不进任何报错、日志与界面文本；
 *  - 空令牌在发网之前拦下（没填不是错误，是还没填）；
 *  - 失败原话带出路（401 指去核对与重生成，403 说限流或撤权），绝不静默。
 */
class GitHubAuthClient(
    private val fetch: (String, String?, String?, Int) -> GitHubHttpResult = ::githubHttpGet,
) {

    /** 验证一枚令牌。任何内部岔子都折成 [GitHubAuthOutcome.Failed] 人话，不许抛穿。 */
    fun verify(token: String?): GitHubAuthOutcome {
        val clean = token?.trim().orEmpty()
        if (clean.isEmpty()) {
            return GitHubAuthOutcome.Failed("令牌是空的：把 GitHub 访问令牌粘进上面那格再点登录")
        }
        val result = try {
            fetch("$GITHUB_API_ROOT/user", clean, null, GITHUB_MAX_BODY_CHARS)
        } catch (e: Exception) {
            return GitHubAuthOutcome.Failed("验证没发出去（${e.message ?: "内部出错"}）：稍后再试")
        }
        return when {
            result.status == 0 -> GitHubAuthOutcome.Failed("连不上 GitHub：${brief(result.body)}")
            result.status == 401 -> GitHubAuthOutcome.Failed(
                "GitHub 不认这枚令牌（401）：核对有没有粘漏字符、有没有过期，或重新生成一枚" +
                    "（classic 令牌勾 repo 与 workflow 就够用）",
            )
            result.status == 403 -> GitHubAuthOutcome.Failed(
                "GitHub 不让验证（403）：可能被限流或令牌权限被撤，等一分钟或换一枚再试",
            )
            result.status != 200 -> GitHubAuthOutcome.Failed("GitHub 回了 ${result.status}，稍后再试")
            else -> {
                val obj = runCatching { json.parseToJsonElement(result.body) }.getOrNull() as? JsonObject
                    ?: return GitHubAuthOutcome.Failed("GitHub 回的内容读不懂（200 但不是用户账）")
                val login = (obj["login"] as? JsonPrimitive)?.contentOrNull?.trim().orEmpty()
                if (login.isEmpty()) {
                    return GitHubAuthOutcome.Failed("GitHub 回的用户账里没有 login：稍后再验证一次")
                }
                val declared = result.scopes.isNotBlank()
                val scopes = result.scopes.split(',').map { it.trim() }.filter { it.isNotEmpty() }
                GitHubAuthOutcome.Ok(GitHubIdentity(login, scopes, declared))
            }
        }
    }

    private fun brief(text: String): String {
        val flat = text.replace('\n', ' ').replace('\r', ' ').trim()
        return if (flat.length <= 160) flat else flat.take(160) + "…（已截断）"
    }

    private companion object {
        val json = Json { ignoreUnknownKeys = true }
    }
}
