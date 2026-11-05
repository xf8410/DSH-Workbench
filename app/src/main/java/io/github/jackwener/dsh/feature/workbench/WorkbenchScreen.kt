package io.github.jackwener.dsh.feature.workbench

import androidx.annotation.DrawableRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.github.jackwener.dsh.R
import io.github.jackwener.dsh.core.capability.ToolDescriptor
import io.github.jackwener.dsh.core.designsystem.BtnSize
import io.github.jackwener.dsh.core.designsystem.BtnVariant
import io.github.jackwener.dsh.core.designsystem.DButton
import io.github.jackwener.dsh.core.designsystem.DIcon
import io.github.jackwener.dsh.core.designsystem.DInput
import io.github.jackwener.dsh.core.designsystem.DTopBar
import io.github.jackwener.dsh.core.designsystem.Dsh
import io.github.jackwener.dsh.core.designsystem.DshType
import io.github.jackwener.dsh.core.designsystem.EmptyNote
import io.github.jackwener.dsh.core.designsystem.IconBtn
import io.github.jackwener.dsh.core.designsystem.Radius
import io.github.jackwener.dsh.core.designsystem.RiskConfirmDialog
import io.github.jackwener.dsh.core.designsystem.SettingsCard
import io.github.jackwener.dsh.core.designsystem.SettingsGroupTitle
import io.github.jackwener.dsh.core.designsystem.SettingsRow
import io.github.jackwener.dsh.core.designsystem.Spinner
import io.github.jackwener.dsh.core.designsystem.Tag
import io.github.jackwener.dsh.core.designsystem.Tone
import io.github.jackwener.dsh.core.designsystem.pressable
import io.github.jackwener.dsh.core.model.Risk
import java.text.DateFormat
import java.util.Date

private enum class WorkbenchTab(val title: String, @DrawableRes val icon: Int) {
    Chat("回合流", R.drawable.dsw_new_chat_outline),
    Tasks("长任务", R.drawable.dsw_clock_outline),
    Tools("工具", R.drawable.dsw_sliders_two_outline),
    Repo("仓库CI", R.drawable.dsw_branch_outline),
    Observe("观测", R.drawable.dsw_inspect_outline),
    Report("报告", R.drawable.dsw_checklist_outline),
}

/** Hualuo's bottom-tab workbench, embedded as one additive DSH destination. */
@Composable
fun WorkbenchScreen(
    onBack: () -> Unit,
    onChat: () -> Unit,
    onTasks: () -> Unit,
    onModels: () -> Unit,
    vm: WorkbenchViewModel = hiltViewModel(),
) {
    val state by vm.ui.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(WorkbenchTab.Repo) }
    Column(Modifier.fillMaxSize().background(Dsh.colors.bgBase).statusBarsPadding()) {
        DTopBar("自动仓库", onBack, actions = {
            IconBtn(R.drawable.dsw_settings_outline, onModels, contentDescription = "模型服务")
        })
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (tab) {
                WorkbenchTab.Tools -> ToolsPage(vm, state, onModels)
                WorkbenchTab.Repo -> RepoPage(vm, state)
                WorkbenchTab.Observe -> ObservePage(vm, state)
                WorkbenchTab.Report -> ReportPage(vm, state)
                else -> Unit
            }
        }
        WorkbenchBottom(tab) { target ->
            when (target) {
                WorkbenchTab.Chat -> onChat()
                WorkbenchTab.Tasks -> onTasks()
                else -> {
                    if (target == WorkbenchTab.Observe) vm.refreshObservation()
                    tab = target
                }
            }
        }
    }
    state.pending?.let { pending ->
        RiskConfirmDialog(
            title = "确认 ${pending.tool.name}",
            body = "此操作会修改 GitHub 仓库或 Pull Request。请核对工具与参数；取消不会执行任何写入。\n\n${pending.args}",
            check = "我确认参数无误，并授权本次操作",
            confirm = "执行",
            cancel = stringResource(R.string.cancel),
            onConfirm = vm::confirmExecute,
            onDismiss = vm::dismissConfirmation,
        )
    }
}

@Composable
private fun WorkbenchBottom(selected: WorkbenchTab, onSelect: (WorkbenchTab) -> Unit) {
    val c = Dsh.colors
    Row(
        Modifier.fillMaxWidth().background(c.bgLayer2).navigationBarsPadding().padding(horizontal = 2.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkbenchTab.entries.forEach { item ->
            val active = item == selected
            Column(
                Modifier.weight(1f).heightIn(min = 54.dp).pressable({ onSelect(item) }, Radius.md).padding(vertical = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                DIcon(item.icon, size = 19.dp, tint = if (active) c.business else c.labelTertiary, contentDescription = item.title)
                Spacer(Modifier.height(2.dp))
                Text(item.title, style = DshType.tiny10, color = if (active) c.business else c.labelTertiary, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ToolsPage(vm: WorkbenchViewModel, state: WorkbenchUiState, onModels: () -> Unit) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { ModelCard(onModels) }
        item { GithubTokenCard(vm, state) }
        item { SettingsGroupTitle("已注册并接线的工具族") }
        items(vm.githubTools + vm.observationTools + vm.pushTools, key = { "tool-${it.name}" }) { tool ->
            ExpandableTool(tool, vm, state)
        }
    }
}

@Composable
private fun ModelCard(onModels: () -> Unit) {
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型与 Key", style = DshType.base16m, color = Dsh.colors.labelPrimary)
            Text(
                "Agora 式供应商、多 Key 命名、活动 Key 切换与安全删除已接入 DSH。这里只打开原模型服务页面，不启动第二套模型运行时。",
                style = DshType.xs13,
                color = Dsh.colors.labelTertiary,
            )
            DButton("打开模型服务", onModels, size = BtnSize.Sm, variant = BtnVariant.Outline)
        }
    }
}

@Composable
private fun GithubTokenCard(vm: WorkbenchViewModel, state: WorkbenchUiState) {
    var token by rememberSaveable { mutableStateOf("") }
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("GitHub 工作台", Modifier.weight(1f), style = DshType.base16m, color = Dsh.colors.labelPrimary)
                Tag(if (state.tokenStored) "令牌已保存" else "未保存令牌", if (state.tokenStored) Tone.Success else Tone.Warn)
            }
            Text("默认仓库 ${state.defaultRepo}。私有仓、代码搜索、CI 和写操作使用 DSH SecretStore 中的 github-token。", style = DshType.xs13, color = Dsh.colors.labelTertiary)
            DInput(token, { token = it }, placeholder = "粘贴 GitHub token", secret = true)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DButton("加密保存", {
                    vm.saveToken(token)
                    if (token.trim().length >= 8) token = ""
                }, size = BtnSize.Sm, enabled = token.isNotBlank())
                DButton("删除令牌", vm::clearToken, size = BtnSize.Sm, variant = BtnVariant.Danger, enabled = state.tokenStored)
            }
            if (state.notice.isNotBlank()) Text(state.notice, style = DshType.xs13, color = Dsh.colors.labelSecondary)
        }
    }
}

@Composable
private fun RepoPage(vm: WorkbenchViewModel, state: WorkbenchUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("默认仓库", style = DshType.base16m, color = Dsh.colors.labelPrimary)
                    DInput(state.defaultRepo, vm::updateRepo, placeholder = "owner/name")
                    Text("目录、文件、代码搜索、分支、提交、CI 日志、写文件和 PR 均调用真实 GitHub 工具。", style = DshType.xs13, color = Dsh.colors.labelTertiary)
                }
            }
        }
        item { SettingsGroupTitle("仓库与 CI") }
        items(vm.githubTools.filter { !it.name.contains("pull_request") && it.name != "github_update_file" }, key = { "repo-${it.name}" }) { tool ->
            ExpandableTool(tool, vm, state)
        }
        item { SettingsGroupTitle("写入与 Pull Request") }
        items(vm.githubTools.filter { it.name.contains("pull_request") || it.name == "github_update_file" }, key = { "write-${it.name}" }) { tool ->
            ExpandableTool(tool, vm, state)
        }
    }
}

@Composable
private fun ObservePage(vm: WorkbenchViewModel, state: WorkbenchUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("SO 推送接收", Modifier.weight(1f), style = DshType.base16m, color = Dsh.colors.labelPrimary)
                        DButton("刷新", vm::refreshObservation, size = BtnSize.Sm, variant = BtnVariant.Outline)
                    }
                    ResultText(state.observationStatus)
                }
            }
        }
        item { TextCard("当前快照（127.0.0.1:18766）", state.snapshot) }
        item { TextCard("当前／上一快照差分", state.changes) }
        item { SettingsGroupTitle("18765 白名单只读观测") }
        items(vm.observationTools, key = { "observe-${it.name}" }) { tool -> ExpandableTool(tool, vm, state) }
        item { SettingsGroupTitle("推送缓存工具") }
        items(vm.pushTools, key = { "push-${it.name}" }) { tool -> ExpandableTool(tool, vm, state) }
    }
}

@Composable
private fun TextCard(title: String, text: String) {
    SettingsCard {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = DshType.s14m, color = Dsh.colors.labelPrimary)
            ResultText(text)
        }
    }
}

@Composable
private fun ExpandableTool(tool: ToolDescriptor, vm: WorkbenchViewModel, state: WorkbenchUiState) {
    val expanded = state.selectedTool == tool.name
    SettingsCard {
        SettingsRow(
            title = tool.name,
            description = tool.description,
            onClick = { vm.selectTool(tool.name) },
            divider = false,
            trailing = {
                Tag(
                    when (tool.risk) {
                        Risk.read -> "只读"
                        Risk.write -> "写入"
                        else -> "高风险"
                    },
                    when (tool.risk) {
                        Risk.read -> Tone.Success
                        Risk.write -> Tone.Warn
                        else -> Tone.Error
                    },
                )
            },
        )
        if (expanded) ToolRunner(tool, vm, state)
    }
}

@Composable
private fun ToolRunner(tool: ToolDescriptor, vm: WorkbenchViewModel, state: WorkbenchUiState) {
    var showSchema by rememberSaveable(tool.name) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp).background(Dsh.colors.bgModule, Radius.md).padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        DInput(state.argsText, vm::updateArgs, placeholder = "JSON 参数", singleLine = false, minHeight = 96.dp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            DButton(if (state.busy) "执行中…" else "执行", vm::requestExecute, size = BtnSize.Sm, enabled = !state.busy)
            DButton(if (showSchema) "隐藏参数说明" else "参数说明", { showSchema = !showSchema }, size = BtnSize.Sm, variant = BtnVariant.Outline)
            if (state.busy) Spinner(14.dp)
        }
        if (showSchema) ResultText(workbenchJson.encodeToString(kotlinx.serialization.json.JsonElement.serializer(), tool.inputSchema))
        if (state.result.isNotBlank()) {
            Text(if (state.resultOk) "执行结果" else "失败", style = DshType.xs13m, color = if (state.resultOk) Dsh.colors.success else Dsh.colors.error)
            ResultText(state.result)
        }
    }
}

@Composable
private fun ResultText(text: String) {
    SelectionContainer {
        Text(text, style = DshType.mono11, color = Dsh.colors.labelSecondary)
    }
}

@Composable
private fun ReportPage(vm: WorkbenchViewModel, state: WorkbenchUiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("调用报告", Modifier.weight(1f), style = DshType.base16m, color = Dsh.colors.labelPrimary)
                DButton("清空", vm::clearReports, size = BtnSize.Sm, variant = BtnVariant.Outline, enabled = state.reports.isNotEmpty())
            }
        }
        if (state.reports.isEmpty()) item { EmptyNote("尚无调用。工具执行结果会保留在这里，切换页面后仍可查看。") }
        items(state.reports, key = { it.id }) { report ->
            SettingsCard {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(report.tool, Modifier.weight(1f), style = DshType.s14m, color = Dsh.colors.labelPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Tag(if (report.ok) "成功" else "失败", if (report.ok) Tone.Success else Tone.Error)
                    }
                    Text(DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(report.finishedAtMs)), style = DshType.xxs12, color = Dsh.colors.labelCaption)
                    ResultText(report.output)
                }
            }
        }
    }
}
