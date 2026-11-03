package com.hualuo.engine.toolcalls

import com.hualuo.engine.sandbox.SandboxException
import com.hualuo.engine.sandbox.SandboxManager
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * 沙盒确认提议：摆给用户卡片的形状（对齐 [GitHubWriteProposal] 的「人能核对」标准）。
 */
data class SandboxConfirmProposal(
    val action: String,
    /** 人能核对的明文全文：命令全文 / 包名清单（截 500 字 + 明说截了多少）。 */
    val preview: String,
)

/**
 * 沙盒闸门：与 [WriteConfirmer] 同一条纪律——拿不准一律按「没同意」处理。
 */
fun interface SandboxConfirmer {
    fun confirm(proposal: SandboxConfirmProposal): Boolean
}

/**
 * 沙盒工具族（0.9.0 刀：沙盒引擎接进对话）。
 *
 * 对齐 560 功能目录 414「Shell 命令审批」与地基写类纪律：**给闸门才存在**——
 * [confirmer] 为空时 run/install/remove 三件一律不注册（默认拒执行，不是默认放行）；
 * 只有只读的 status/list_packages 随对话可用（看状态不是执行）。
 *
 * 与 GitHub 写类同一条哲学：模型可以提议，人点头才真跑；拒绝或超时什么都没发生，
 * 把「没确认」作为结果文本喂回模型。命令与包名都是明文原样摆给人核对，不截内容语义
 * （预览截断是「带账的预览」，全文在执行时原样进引擎）。
 */
object SandboxToolFamily {

    /** 预览给用户核对的字符数。 */
    const val PREVIEW_CHARS = 500

    fun register(
        registry: ToolRegistry,
        manager: SandboxManager,
        confirmer: SandboxConfirmer? = null,
    ): ToolRegistry {
        registry.register(
            ToolSpec(
                name = "sandbox_status",
                description = "查看沙盒环境状态（rootfs 是否已装、当前是否忙）。回答「沙盒装了吗」用这个",
                parametersJson = """{"type":"object","properties":{}}""",
            ),
        ) { _ ->
            manager.statusLine()
        }

        registry.register(
            ToolSpec(
                name = "sandbox_list_packages",
                description = "列出沙盒里已装的 Alpine 包（明文完整清单）。回答「沙盒里有什么包」用这个",
                parametersJson = """{"type":"object","properties":{}}""",
            ),
        ) { _ ->
            manager.listPackages()
        }

        if (confirmer != null) {
            fun gate(action: String, preview: String): Boolean =
                try {
                    confirmer.confirm(SandboxConfirmProposal(action, preview.take(PREVIEW_CHARS)))
                } catch (e: Exception) {
                    false
                }

            registry.register(
                ToolSpec(
                    name = "sandbox_run_command",
                    description = "在 Alpine 沙盒里执行一条 shell 命令（需要用户确认）。用于编译、跑脚本、检查文件等；命令明文原样执行，用户先看到命令全文",
                    parametersJson = """{"type":"object","properties":{"command":{"type":"string","description":"要执行的命令行，明文完整写出"},"timeout_ms":{"type":"integer","description":"超时毫秒，默认 60000，范围 1000-300000"}},"required":["command"]}""",
                ),
            ) { argsJson ->
                val args = argsOf(argsJson)
                val command = reqStr(args, "command", "要执行的命令，明文完整写出")
                val timeout = (optLong(args, "timeout_ms") ?: 60_000L).coerceIn(1_000L, 300_000L)
                if (!gate("沙盒执行命令", command)) {
                    return@register "用户没有确认这条命令（或等待超时）：什么都没执行。终态：已拒绝。"
                }
                outcomeText { manager.execCommand(command, timeout) }
            }

            registry.register(
                ToolSpec(
                    name = "sandbox_install_packages",
                    description = "在沙盒里安装 Alpine 包（apk，走索引解析依赖，需要用户确认）。成功结果带退出码与复验证据",
                    parametersJson = """{"type":"object","properties":{"packages":{"type":"array","items":{"type":"string"},"description":"包名数组，如 [\"python3\",\"git\"]；用主包名，不要用 so: 别名"},"timeout_ms_per_package":{"type":"integer","description":"每包超时毫秒，默认 300000"}},"required":["packages"]}""",
                ),
            ) { argsJson ->
                val args = argsOf(argsJson)
                val packages = optStringList(args, "packages")
                if (packages.isEmpty()) {
                    return@register "缺参数 packages（包名数组；例如 [\"python3\",\"git\"]）。终态：已拒绝。"
                }
                val timeout = (optLong(args, "timeout_ms_per_package") ?: 300_000L).coerceIn(1_000L, 600_000L)
                if (!gate("沙盒安装包", packages.joinToString(", "))) {
                    return@register "用户没有确认这次安装（或等待超时）：什么都没装。终态：已拒绝。"
                }
                outcomeText { manager.installPackages(packages, timeout) }
            }

            registry.register(
                ToolSpec(
                    name = "sandbox_remove_package",
                    description = "从沙盒里删除一个已装包（apk del，需要用户确认）",
                    parametersJson = """{"type":"object","properties":{"package":{"type":"string","description":"要删除的包名"}},"required":["package"]}""",
                ),
            ) { argsJson ->
                val args = argsOf(argsJson)
                val pkg = reqStr(args, "package", "要删除的包名")
                if (!gate("沙盒删除包", pkg)) {
                    return@register "用户没有确认这次删除（或等待超时）：什么都没删。终态：已拒绝。"
                }
                outcomeText { manager.removePackage(pkg) }
            }
        }
        return registry
    }

    /** 引擎异常转结果文本：SandboxError 单行带证据（kind/退出码/包名/输出尾）。 */
    private fun outcomeText(block: () -> String): String =
        try {
            block()
        } catch (e: SandboxException) {
            e.error.toString()
        }

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun optStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun reqStr(args: JsonObject, key: String, hint: String): String =
        optStr(args, key) ?: throw IllegalArgumentException("缺参数 $key（$hint）")

    private fun optLong(args: JsonObject, key: String): Long? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toLongOrNull()

    private fun optStringList(args: JsonObject, key: String): List<String> =
        (args[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { s -> s.isNotEmpty() } }
            ?: emptyList()

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
