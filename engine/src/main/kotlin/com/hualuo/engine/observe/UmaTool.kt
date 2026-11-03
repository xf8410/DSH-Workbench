package com.hualuo.engine.observe

import com.hualuo.engine.toolcalls.ToolHandler
import com.hualuo.engine.toolcalls.ToolRegistry
import com.hualuo.engine.toolcalls.ToolSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * uma_* 观测工具族（560 清单 381-399 + hlpatch_endpoints.txt 142 端点只读子集）：
 * 让对话模型能读本机 hlpatch SO 观测桥——嗅探/协议观测、育成数据、拉面规划、
 * 种子统计、卡库技能库、IL2CPP 类/字段/方法检索。
 *
 * 纪律：
 *  - 全只读（361-400 域红线）：只发 GET。toggle/clear/install/call/upload 这类
 *    写操作与执行入口**一个都不做工具**；
 *  - 400 红线：不提供"列全部类"工具——全量扫描只在用户经 uma_read_endpoint
 *    显式点名路径时才发生；
 *  - 单并发+冷却由 [ObserveState] 把门：对端是嵌入式 SO，一人一格；
 *  - 响应原文照给（零脱敏），尾部带【端点·耗时·字节】事实账；
 *  - **报错用人话**：不甩裸状态码，中文解释在前、数字在后，并给下一步指路
 *    （用户纪律：模型看到 "HTTP 404" 只会开始瞎编参数，看到"没这条路由，先试
 *    uma_health"才知道怎么改）。
 */
object UmaTool {

    /**
     * 无参工具批量注册表（名, 中文说明, 路径）。
     * 全部 GET 只读；名字即功能，描述写给模型看——说清"这读的是什么"。
     */
    private val NO_ARG: List<Triple<String, String, String>> = listOf(
        Triple("uma_health", "探测本机 hlpatch SO 观测桥健康与版本（先调这个确认桥在不在）。", ObserveClient.ENDPOINT_HEALTH),
        Triple("uma_status", "读 hlpatch 初始化/状态快照。", ObserveClient.ENDPOINT_STATUS),
        Triple("uma_summary", "读当前育成对局状态摘要（含账号 id，可能数 MB）。", ObserveClient.ENDPOINT_SUMMARY),
        Triple("uma_event_choices", "读当前育成事件的选择肢快照。", ObserveClient.ENDPOINT_EVENT_CHOICES),
        Triple("uma_event_reward_targets", "读事件奖励目标。", ObserveClient.ENDPOINT_EVENT_REWARD_TARGETS),
        Triple("uma_ramen_transitions", "读拉面杯转场记录（育成流程分析）。", ObserveClient.ENDPOINT_RAMEN_TRANSITION),
        Triple("uma_hook_diagnostics", "读 Hook 诊断（哪些 hook 挂上了、失败原因）。", ObserveClient.ENDPOINT_HOOK_DIAG),
        Triple("uma_sniff_status", "读嗅探器状态（是否开着、抓了多少条）。", ObserveClient.ENDPOINT_SNIFF_STATUS),
        Triple("uma_sniff_metadata", "读完整协议观测（请求体十六进制+头+路径，可能很大）。", ObserveClient.ENDPOINT_SNIFF_METADATA),
        Triple("uma_sniff_unity", "读 Unity 层请求观测。", ObserveClient.ENDPOINT_SNIFF_UNITY),
        Triple("uma_sniff_diag", "读嗅探 hook 诊断（压缩/解压/POST 链路）。", ObserveClient.ENDPOINT_SNIFF_DIAG),
        Triple("uma_md5log", "读 MakeMd5 输入输出对（协议逆向用）。", ObserveClient.ENDPOINT_MD5LOG),
        Triple("uma_data", "读游戏数据总览。", ObserveClient.ENDPOINT_DATA),
        Triple("uma_scenario", "读当前剧本信息。", ObserveClient.ENDPOINT_SCENARIO),
        Triple("uma_events", "读事件列表。", ObserveClient.ENDPOINT_EVENTS),
        Triple("uma_event_recommend", "读事件推荐。", ObserveClient.ENDPOINT_EVENT_RECOMMEND),
        Triple("uma_action_latest", "读最新一次行动。", ObserveClient.ENDPOINT_ACTION_LATEST),
        Triple("uma_training_result", "读最近训练结果。", ObserveClient.ENDPOINT_TRAINING_RESULT),
        Triple("uma_log", "读育成日志。", ObserveClient.ENDPOINT_LOG),
        Triple("uma_log_turn", "读回合日志。", ObserveClient.ENDPOINT_LOG_TURN),
        Triple("uma_ramen", "读拉面对局总览。", ObserveClient.ENDPOINT_RAMEN),
        Triple("uma_rameninfo", "读拉面信息。", ObserveClient.ENDPOINT_RAMENINFO),
        Triple("uma_ramengains", "读拉面增益。", ObserveClient.ENDPOINT_RAMENGAINS),
        Triple("uma_ramen_participants", "读拉面参与者。", ObserveClient.ENDPOINT_RAMEN_PARTICIPANTS),
        Triple("uma_ramen_planner_state", "读拉面规划器状态（决策 AI 直接相关）。", ObserveClient.ENDPOINT_RAMEN_PLANNER_STATE),
        Triple("uma_seed_history", "读种子历史。", ObserveClient.ENDPOINT_SEED_HISTORY),
        Triple("uma_seed_stats", "读种子统计。", ObserveClient.ENDPOINT_SEED_STATS),
        Triple("uma_inherit_compat", "读继承兼容性。", ObserveClient.ENDPOINT_INHERIT_COMPAT),
        Triple("uma_carddb", "读卡牌数据库（可能很大）。", ObserveClient.ENDPOINT_CARDDB),
        Triple("uma_skilldata", "读技能数据（可能很大）。", ObserveClient.ENDPOINT_SKILLDATA),
        Triple("uma_mdb", "读 MDB 数据库概览（可能很大）。", ObserveClient.ENDPOINT_MDB),
        Triple("uma_mdb_schema", "读 MDB 表结构。", ObserveClient.ENDPOINT_MDB_SCHEMA),
        Triple("uma_singletons", "读单例列表。", ObserveClient.ENDPOINT_SINGLETONS),
        Triple("uma_tables", "读表列表。", ObserveClient.ENDPOINT_TABLES),
        Triple("uma_gauge", "读量表一。", ObserveClient.ENDPOINT_GAUGE),
        Triple("uma_gauge2", "读量表二。", ObserveClient.ENDPOINT_GAUGE2),
        Triple("uma_training_partners", "读训练伙伴。", ObserveClient.ENDPOINT_TRAINING_PARTNERS),
        Triple("uma_training_seed", "读训练种子。", ObserveClient.ENDPOINT_TRAINING_SEED),
        Triple("uma_turn_probe", "读回合探针。", ObserveClient.ENDPOINT_TURN_PROBE),
        Triple("uma_breeders", "读育成师数据。", ObserveClient.ENDPOINT_BREEDERS),
    )

    /** 无参工具的空 schema：显式写"无需参数"，模型就不会硬塞。 */
    private const val EMPTY_SCHEMA = """{"type":"object","properties":{},"required":[]}"""

    fun register(
        registry: ToolRegistry,
        clientProvider: () -> ObserveClient?,
        state: ObserveState,
    ) {
        NO_ARG.forEach { (name, desc, path) ->
            registry.registerGated(
                ToolSpec(
                    name = name,
                    description = "$desc 本工具无需参数。",
                    parametersJson = EMPTY_SCHEMA,
                ),
                ToolHandler { call(clientProvider(), state, path) },
            ) { clientProvider() != null }
        }
        // ---- 带参工具（schema 写死必填项与含义；报错带用法示例） ----
        registry.registerGated(
            ToolSpec(
                name = "uma_event_observations",
                description = "读已完成事件观测流水（增量拉：先空参拿全量，之后传上次最大的 after_id 只拿新的）。无需参数也能调。",
                parametersJson = """{"type":"object","properties":{"after_id":{"type":"integer","description":"从哪个事件 id 之后开始取，0 或不传=从头"}},"required":[]}""",
            ),
            ToolHandler { args ->
                val after = longArg(args, "after_id") ?: 0L
                call(clientProvider(), state, "${ObserveClient.ENDPOINT_EVENT_OBSERVATIONS}?after_id=${after.coerceAtLeast(0L)}")
            },
        ) { clientProvider() != null }
        registry.registerGated(
            ToolSpec(
                name = "uma_search_classes",
                description = "按关键词搜 IL2CPP 类名。keyword 必填。",
                parametersJson = """{"type":"object","properties":{"keyword":{"type":"string","description":"类名关键词，如 UmaClass"}},"required":["keyword"]}""",
            ),
            ToolHandler { args ->
                val kw = textArg(args, "keyword") ?: throw IllegalArgumentException("keyword 必填。正确用法：{\"keyword\":\"UmaClass\"}")
                call(clientProvider(), state, "/classes/search/${ObserveClient.safeSegment(kw, "keyword")}")
            },
        ) { clientProvider() != null }
        registry.registerGated(
            ToolSpec(
                name = "uma_get_fields",
                description = "读一个 IL2CPP 类的字段列表。class_name 必填。",
                parametersJson = """{"type":"object","properties":{"class_name":{"type":"string","description":"完整类名"}},"required":["class_name"]}""",
            ),
            ToolHandler { args ->
                val cn = textArg(args, "class_name") ?: throw IllegalArgumentException("class_name 必填。正确用法：{\"class_name\":\"UmaClass\"}")
                call(clientProvider(), state, "/fields/${ObserveClient.safeSegment(cn, "class_name")}")
            },
        ) { clientProvider() != null }
        registry.registerGated(
            ToolSpec(
                name = "uma_get_methods",
                description = "读一个 IL2CPP 类的方法列表。class_name 必填。",
                parametersJson = """{"type":"object","properties":{"class_name":{"type":"string","description":"完整类名"}},"required":["class_name"]}""",
            ),
            ToolHandler { args ->
                val cn = textArg(args, "class_name") ?: throw IllegalArgumentException("class_name 必填。正确用法：{\"class_name\":\"UmaClass\"}")
                call(clientProvider(), state, "/methods/${ObserveClient.safeSegment(cn, "class_name")}")
            },
        ) { clientProvider() != null }
        registry.registerGated(
            ToolSpec(
                name = "uma_find_method",
                description = "按名字搜方法（跨类）。method 必填。",
                parametersJson = """{"type":"object","properties":{"method":{"type":"string","description":"方法名"}},"required":["method"]}""",
            ),
            ToolHandler { args ->
                val m = textArg(args, "method") ?: throw IllegalArgumentException("method 必填。正确用法：{\"method\":\"getHfState\"}")
                call(clientProvider(), state, "/find_method/${ObserveClient.safeSegment(m, "method")}")
            },
        ) { clientProvider() != null }
        registry.registerGated(
            ToolSpec(
                name = "uma_mdb_search",
                description = "在 MDB 数据库里搜表。q 必填（表名或关键词）。",
                parametersJson = """{"type":"object","properties":{"q":{"type":"string","description":"搜索词"}},"required":["q"]}""",
            ),
            ToolHandler { args ->
                val q = textArg(args, "q") ?: throw IllegalArgumentException("q 必填。正确用法：{\"q\":\"card_name\"}")
                call(clientProvider(), state, "${ObserveClient.ENDPOINT_MDB_SEARCH}?q=${ObserveClient.safeSegment(q, "q")}")
            },
        ) { clientProvider() != null }
        registry.registerGated(
            ToolSpec(
                name = "uma_read_endpoint",
                description = "通用只读口：读任意 hlpatch 路径的原文（端点不在工具清单里时用它，需 path 必填）。只许 GET 只读路径。",
                parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"以 / 开头的路径，如 /debug/hookdiag"},"max_kib":{"type":"integer","description":"响应上限 KiB，默认 2048"}},"required":["path"]}""",
            ),
            ToolHandler { args ->
                val path = textArg(args, "path") ?: throw IllegalArgumentException("path 必填。正确用法：{\"path\":\"/debug/hookdiag\"}")
                val maxKib = (longArg(args, "max_kib") ?: 2048L).coerceIn(1L, 16384L)
                call(clientProvider(), state, ObserveClient.validateReadPath(path), (maxKib * 1024).toInt())
            },
        ) { clientProvider() != null }
    }

    // ---------- 执行 ----------

    private fun call(
        client: ObserveClient?,
        state: ObserveState,
        path: String,
        maxBytes: Int = ObserveClient.DEFAULT_MAX_BYTES,
    ): String {
        if (client == null) throw IllegalStateException("观测桥未配置（地址为空）——先到「观测」页填地址并探测")
        if (!state.tryBeginCall()) throw IllegalStateException(state.lastNote.ifBlank { "观测桥现在不让调——单并发限制或冷却中，稍等再试" })
        try {
            val outcome = client.get(path, maxBytes)
            return if (outcome.ok) {
                state.settleCall(true, "uma 调用成功 ${outcome.path}")
                "[${outcome.path}] 读取成功 · HTTP ${outcome.httpStatus} · ${outcome.elapsedMs}ms · ${outcome.bytes}B\n${outcome.body}"
            } else {
                val explain = when (outcome.kind) {
                    ObserveClient.Kind.TOO_LARGE ->
                        "响应超过上限，已截断——用 uma_read_endpoint 调大 max_kib（最多 16384），或换分页/子路径"
                    ObserveClient.Kind.IO ->
                        "连不上观测桥——游戏没开、hlpatch 插件没挂上、或地址不对。先到「观测」页点「探测」确认（原话：${outcome.error}）"
                    else -> outcome.httpStatus?.let { ObserveClient.httpExplain(it) } ?: (outcome.error ?: "无响应")
                }
                state.settleCall(false, "uma 调用失败 ${outcome.path}: $explain")
                throw IllegalStateException("[${outcome.path} 读取失败] $explain")
            }
        } catch (e: Exception) {
            state.settleCall(false, "uma 调用异常 ${e.message}")
            throw IllegalStateException("[${path} 读取失败] ${e.message ?: "观测桥请求异常"}")
        } finally {
            state.endBusy()
        }
    }

    // ---------- 参数小工具 ----------

    private fun parseArgs(argumentsJson: String): Map<String, JsonPrimitive> = runCatching {
        Json.parseToJsonElement(argumentsJson.ifBlank { "{}" })
    }.getOrElse { return emptyMap() }
        .let { el -> el as? kotlinx.serialization.json.JsonObject }
        ?.mapValues { (_, v) -> v as? JsonPrimitive ?: JsonPrimitive("") }
        ?: emptyMap()

    private fun textArg(args: String, key: String): String? =
        parseArgs(args)[key]?.contentOrNull?.trim().takeUnless { it.isNullOrEmpty() }

    private fun longArg(args: String, key: String): Long? =
        parseArgs(args)[key]?.content?.trim()?.toLongOrNull()
}
