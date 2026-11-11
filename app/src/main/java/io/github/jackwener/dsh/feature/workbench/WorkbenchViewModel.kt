package io.github.jackwener.dsh.feature.workbench

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import io.github.jackwener.dsh.core.capability.CapabilityHub
import io.github.jackwener.dsh.core.capability.Content
import io.github.jackwener.dsh.core.capability.ToolDescriptor
import io.github.jackwener.dsh.core.data.SecretStore
import io.github.jackwener.dsh.core.model.Risk
import io.github.jackwener.dsh.core.workbench.HualuoTools
import io.github.jackwener.dsh.core.workbench.UmaFrameStore
import io.github.jackwener.dsh.core.workbench.UmaPushReceiver
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal val workbenchJson = Json { prettyPrint = true }

internal fun parseWorkbenchArgs(raw: String): JsonObject {
    val element = Json.parseToJsonElement(raw.ifBlank { "{}" })
    return element as? JsonObject ?: throw IllegalArgumentException("参数必须是 JSON 对象，例如 {\"repo\":\"owner/name\"}")
}

internal fun requiresWorkbenchConfirmation(risk: Risk): Boolean = risk != Risk.read

internal fun defaultWorkbenchArgs(name: String, repo: String): JsonObject = buildJsonObject {
    when (name) {
        "github_list_user_repos" -> put("owner", repo.substringBefore('/'))
        "github_auth_status", "github_list_my_repos" -> Unit
        "github_read_file" -> {
            put("repo", repo)
            put("path", "app/build.gradle.kts")
        }
        "github_search_code" -> {
            put("repo", repo)
            put("keyword", "CapabilityHub")
        }
        "github_browse_repo" -> {
            put("repo", repo)
            put("path", "")
        }
        "uma_get_snapshot", "uma_get_changes" -> put("max_chars", 12_000)
        "github_create_repo" -> {
            put("name", "new-repository")
            put("private", true)
        }
        "github_upload_files" -> {
            put("repo", repo)
            put("prefix", "courier/")
            put("files", buildJsonArray { add("/sdcard/Download/example.apk") })
        }
        else -> if (name.startsWith("github_")) put("repo", repo)
    }
}

data class WorkbenchCallReport(
    val id: Long,
    val tool: String,
    val ok: Boolean,
    val output: String,
    val finishedAtMs: Long,
)

data class PendingWorkbenchCall(
    val tool: ToolDescriptor,
    val args: JsonObject,
)

data class WorkbenchUiState(
    val defaultRepo: String = HualuoTools.DEFAULT_REPO,
    val selectedTool: String? = null,
    val argsText: String = "{}",
    val busy: Boolean = false,
    val result: String = "",
    val resultOk: Boolean = true,
    val pending: PendingWorkbenchCall? = null,
    val tokenStored: Boolean = false,
    val notice: String = "",
    val reports: List<WorkbenchCallReport> = emptyList(),
    val observationStatus: String = "",
    val snapshot: String = "",
    val changes: String = "",
)

/** Android UI over DSH's existing capability bus; no second model, repository or task runtime. */
@HiltViewModel
class WorkbenchViewModel @Inject constructor(
    private val capabilities: CapabilityHub,
    private val secrets: SecretStore,
    private val frames: UmaFrameStore,
    private val receiver: UmaPushReceiver,
) : ViewModel() {
    private val catalog = capabilities.allTools.values.map { it.descriptor }
        .filter { it.name.startsWith("github_") || it.name.startsWith("uma_") }
        .filterNot { it.name in HIDDEN_AUTH_TOOLS }
        .sortedBy { it.name }
    val githubTools = catalog.filter { it.name.startsWith("github_") }
    val observationTools = catalog.filter { it.name.startsWith("uma_") && !it.name.startsWith("uma_push_") }
    val pushTools = catalog.filter { it.name.startsWith("uma_push_") }

    private val _ui = MutableStateFlow(WorkbenchUiState(tokenStored = hasToken()))
    val ui: StateFlow<WorkbenchUiState> = _ui.asStateFlow()
    private var reportId = 0L

    init {
        selectTool("github_ci_runs")
        refreshObservation()
    }

    fun updateRepo(value: String) = _ui.update { it.copy(defaultRepo = value.trim()) }

    fun selectTool(name: String, repo: String = _ui.value.defaultRepo) {
        val descriptor = catalog.firstOrNull { it.name == name } ?: return
        val args = defaultWorkbenchArgs(descriptor.name, repo.trim().ifBlank { HualuoTools.DEFAULT_REPO })
        _ui.update {
            it.copy(
                selectedTool = name,
                argsText = workbenchJson.encodeToString(JsonElement.serializer(), args),
                result = "",
                pending = null,
            )
        }
    }

    fun updateArgs(value: String) = _ui.update { it.copy(argsText = value) }

    /** Read-only calls run immediately. Every write/dangerous call must pass DSH's risk dialog first. */
    fun requestExecute() {
        val state = _ui.value
        if (state.busy) return
        val descriptor = catalog.firstOrNull { it.name == state.selectedTool } ?: return
        val args = try {
            parseWorkbenchArgs(state.argsText)
        } catch (e: Exception) {
            _ui.update { it.copy(resultOk = false, result = "参数格式错误：${e.message ?: "请输入 JSON 对象"}") }
            return
        }
        if (requiresWorkbenchConfirmation(descriptor.risk)) {
            _ui.update { it.copy(pending = PendingWorkbenchCall(descriptor, args)) }
        } else {
            execute(descriptor, args)
        }
    }

    fun dismissConfirmation() = _ui.update { it.copy(pending = null) }

    fun confirmExecute() {
        val pending = _ui.value.pending ?: return
        _ui.update { it.copy(pending = null) }
        execute(pending.tool, pending.args)
    }

    private fun execute(tool: ToolDescriptor, args: JsonObject) {
        viewModelScope.launch {
            _ui.update { it.copy(busy = true, result = "正在执行 ${tool.name}…", resultOk = true) }
            val outcome = try {
                capabilities.invoke(buildJsonObject {
                    put("tool", tool.name)
                    put("callId", java.util.UUID.randomUUID().toString())
                    put("args", args)
                })
            } catch (e: Exception) {
                null
            }
            val ok = outcome?.ok == true
            val output = when {
                outcome == null -> "调用失败：工具总线异常"
                outcome.content.isNotEmpty() -> outcome.content.mapNotNull { (it as? Content.Text)?.text }
                    .joinToString("\n").ifBlank { outcome.errorMessage ?: "工具没有返回文本" }
                else -> outcome.errorMessage ?: if (ok) "执行完成" else "执行失败"
            }.take(MAX_RESULT_CHARS)
            _ui.update { it.copy(busy = false, result = output, resultOk = ok) }
            addReport(tool.name, ok, output)
            refreshObservation()
        }
    }

    fun saveToken(token: String) {
        val clean = token.trim()
        if (clean.length !in 8..512 || clean.any { it.isWhitespace() }) {
            _ui.update { it.copy(notice = "令牌格式不正确：应为 8–512 个非空白字符") }
            return
        }
        secrets.write(HualuoTools.GITHUB_TOKEN_SLOT, clean)
        _ui.update { it.copy(tokenStored = true, notice = "GitHub 令牌已加密保存；页面和报告不会显示明文") }
        addReport("github_set_token", true, "GitHub 令牌已写入 DSH SecretStore")
    }

    fun clearToken() {
        secrets.clear(HualuoTools.GITHUB_TOKEN_SLOT)
        _ui.update { it.copy(tokenStored = false, notice = "GitHub 令牌已删除；仓库内容没有改变") }
        addReport("github_clear_token", true, "GitHub 令牌已从 DSH SecretStore 删除")
    }

    fun refreshObservation() {
        val status = receiver.status()
        val snapshot = frames.snapshot()
        val changes = frames.changes()
        _ui.update {
            it.copy(
                observationStatus = pretty(status),
                snapshot = snapshot?.let(::pretty) ?: "尚未收到 18766 summary JSON。DSH 只接收和解析现有 SO 推送，不安装或注入 SO。",
                changes = changes?.let(::pretty) ?: "暂无可比较的快照。",
            )
        }
    }

    fun clearReports() = _ui.update { it.copy(reports = emptyList()) }

    private fun addReport(tool: String, ok: Boolean, output: String) {
        reportId += 1
        val report = WorkbenchCallReport(reportId, tool, ok, output, System.currentTimeMillis())
        _ui.update { it.copy(reports = (listOf(report) + it.reports).take(MAX_REPORTS)) }
    }

    private fun hasToken() = secrets.read(HualuoTools.GITHUB_TOKEN_SLOT)?.isNotBlank() == true

    private fun pretty(element: JsonElement): String =
        workbenchJson.encodeToString(JsonElement.serializer(), element).take(MAX_RESULT_CHARS)

    private companion object {
        const val MAX_REPORTS = 30
        const val MAX_RESULT_CHARS = 100_000
        val HIDDEN_AUTH_TOOLS = setOf("github_set_token", "github_clear_token")
    }
}