package io.github.jackwener.dsh.core.workbench

import com.hualuo.engine.api.UrlConnTransport
import com.hualuo.engine.observe.ObserveClient
import com.hualuo.engine.observe.ObserveState
import com.hualuo.engine.observe.UmaTool
import com.hualuo.engine.toolcalls.GitHubPrTool
import com.hualuo.engine.toolcalls.GitHubToolFamily
import com.hualuo.engine.toolcalls.GitHubWriteTool
import com.hualuo.engine.toolcalls.PrConfirmer
import com.hualuo.engine.toolcalls.ToolRegistry
import com.hualuo.engine.toolcalls.ToolResultTrimmer
import com.hualuo.engine.toolcalls.ToolSpec
import com.hualuo.engine.toolcalls.WriteConfirmer
import io.github.jackwener.dsh.core.capability.CallContext
import io.github.jackwener.dsh.core.capability.Tool
import io.github.jackwener.dsh.core.capability.ToolResult
import io.github.jackwener.dsh.core.capability.ToolSet
import io.github.jackwener.dsh.core.capability.reqStr
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.model.ToolGroup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

/** Adapts Hualuo's JVM registries to DSH's single capability bus. No Hualuo UI is used. */
@Singleton
class HualuoTools @Inject constructor(
    private val secrets: SecretStore,
) : ToolSet {
    private val loadToken = { secrets.read(GITHUB_TOKEN_SLOT) }
    private val defaultRepo = { DEFAULT_REPO }
    private val github = GitHubToolFamily.build(loadToken, defaultRepo)
    private val githubWrite = ToolRegistry().apply {
        // CapabilityHub/DSH's engine upgrades every non-read tool to its own approval flow.
        // Reaching these synchronous gates therefore means that DSH already authorized the call.
        GitHubWriteTool.register(this, loadToken, defaultRepo, com.hualuo.engine.github.GitHubRepoClient(), WriteConfirmer { true })
        GitHubPrTool.register(this, loadToken, defaultRepo, com.hualuo.engine.github.GitHubPrClient(), PrConfirmer { true })
    }
    private val observeState = ObserveState()
    private val observeClient = ObserveClient(OBSERVE_URL, UrlConnTransport())
    private val uma = ToolRegistry().apply {
        UmaTool.register(this, { observeClient }, observeState)
    }
    private val routes: Map<String, ToolRegistry> = buildMap {
        github.specs().forEach { put(it.name, github) }
        githubWrite.specs().forEach { put(it.name, githubWrite) }
        uma.specs().filter { it.name in UMA_WHITELIST }.forEach { put(it.name, uma) }
    }

    override val tools: List<Tool> = routes.map { (name, registry) ->
        val spec = when (registry) {
            github -> github.specs().single { it.name == name }
            githubWrite -> githubWrite.specs().single { it.name == name }
            else -> uma.specs().single { it.name == name }
        }
        adapt(spec, registry)
    } + authTools()

    private fun adapt(spec: ToolSpec, registry: ToolRegistry): Tool {
        val schema = runCatching { Json.parseToJsonElement(spec.parametersJson) as JsonObject }
            .getOrElse { buildJsonObject { } }
        val properties = schema["properties"] as? JsonObject ?: JsonObject(emptyMap())
        val required = (schema["required"] as? JsonArray).orEmpty().mapNotNull { it.jsonPrimitive.contentOrNull }.toSet()
        val risk = when (spec.name) {
            "github_merge_pull_request" -> Risk.dangerous
            "github_update_file", "github_create_pull_request" -> Risk.write
            else -> Risk.read
        }
        return object : Tool(spec.name, ToolGroup.System, risk, false, dshDescription(spec.description), {
            properties.forEach { (name, value) -> (value as? JsonObject)?.let { raw(name, it, name in required) } }
        }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = withContext(Dispatchers.IO) {
                if (registry === uma) ensureObservation()
                val outcome = registry.execute(spec.name, args.toString())
                val text = dshResult(ToolResultTrimmer.trim(outcome.text))
                if (outcome.ok) ToolResult.text(text) else ToolResult.error("failed", text)
            }
        }
    }

    /** The Hualuo state starts disconnected; probe lazily instead of adding Android polling/UI. */
    private fun ensureObservation() {
        if (!observeState.tryBeginProbe()) return
        val probe = observeClient.probe()
        observeState.settleProbe(
            healthOk = probe.health?.ok == true,
            statusOk = probe.status?.ok == true,
            healthStatus = probe.health?.httpStatus,
            note = probe.health?.error ?: probe.status?.error ?: "DSH lazy probe completed",
        )
    }

    private fun authTools(): List<Tool> = listOf(
        object : Tool("github_auth_status", ToolGroup.System, Risk.read, false,
            "Report whether a GitHub token is stored and which repository is used by default. Never returns the token.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = withContext(Dispatchers.IO) {
                ToolResult.text(buildJsonObject {
                    put("tokenStored", secrets.read(GITHUB_TOKEN_SLOT)?.isNotBlank() == true)
                    put("defaultRepo", DEFAULT_REPO)
                    put("storage", "DSH SecretStore / Android Keystore AES-GCM")
                }.toString())
            }
        },
        object : Tool("github_set_token", ToolGroup.System, Risk.write, false,
            "Store a GitHub token only when the user explicitly provides it for GitHub tools. The token is encrypted and never returned by this tool.", {
                string("token", "The user-provided GitHub token", required = true)
            }) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = withContext(Dispatchers.IO) {
                val token = args.reqStr("token").trim()
                if (token.length !in 8..512 || token.any { it.isWhitespace() }) {
                    return@withContext ToolResult.error("invalid_args", "Token must be 8-512 non-whitespace characters; nothing was stored.")
                }
                secrets.write(GITHUB_TOKEN_SLOT, token)
                ToolResult.text("GitHub token stored in DSH encrypted storage. It was not included in this result.")
            }
        },
        object : Tool("github_clear_token", ToolGroup.System, Risk.write, false,
            "Delete the GitHub token used by the repository tools; repository contents are not changed.", {}) {
            override suspend fun invoke(args: JsonObject, call: CallContext): ToolResult = withContext(Dispatchers.IO) {
                secrets.clear(GITHUB_TOKEN_SLOT)
                ToolResult.text("GitHub token cleared. Repository contents were not changed.")
            }
        },
    )

    private fun dshDescription(text: String): String = text
        .replace("App 会弹确认卡：用户点头才真提交，拒绝或超时什么都不写。", "必须经过 DSH 风险审批；未获批准不会提交。")
        .replace("（需要用户确认，SHA 钉死）", "（需要 DSH 风险审批，SHA 钉死）")
        .replace("（需要用户确认）", "（需要 DSH 风险审批）")
        .replace("用户对着确认卡点头才真合", "经 DSH 风险审批后才合")
        .replace("用户点头才真建", "经 DSH 风险审批后才建")
        .replace("设置里的默认仓库", "默认仓库 $DEFAULT_REPO")
        .replace("设置「GitHub 工作台」填默认仓库", "在 repo 参数中指定 owner/name")

    private fun dshResult(text: String): String = text
        .replace("去设置「GitHub 工作台」或「文件投递」填", "用 github_set_token 保存用户明确提供的令牌")
        .replace("也可以先去设置「GitHub 工作台」填默认仓库", "也可以省略 repo 以使用 $DEFAULT_REPO")
        .replace("先到「观测」页点「探测」确认", "检查游戏和现有 hlpatch 配置后重试")

    companion object {
        const val GITHUB_TOKEN_SLOT = "github-token"
        const val DEFAULT_REPO = "xf8410/DSH-Workbench"
        private const val OBSERVE_URL = "http://127.0.0.1:18765"
        private val UMA_WHITELIST = setOf(
            "uma_health", "uma_status", "uma_summary", "uma_event_choices",
            "uma_event_observations", "uma_scenario", "uma_training_result",
            "uma_ramen_transitions", "uma_ramen_planner_state", "uma_turn_probe",
        )
    }
}
