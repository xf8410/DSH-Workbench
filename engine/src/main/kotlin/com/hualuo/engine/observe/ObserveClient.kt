package com.hualuo.engine.observe

import com.hualuo.engine.api.WireRequest
import com.hualuo.engine.api.WireTransport
import java.io.IOException
import java.net.URLEncoder

/**
 * hlpatch SO 观测桥客户端（560 清单 361-400 域的第一块引擎件）。
 *
 * 对端是跑在设备本机的 hlpatch SO 插件（默认 127.0.0.1:18765），只读 HTTP：
 * 全部 GET、返回 JSON 文本。客户端只做三件事：把路径管干净、把响应字节管干净、
 * 把事实（状态码/耗时/字节数）报出来——**不改写、不截断内容、不脱敏**，原文照收。
 *
 * 端点全集（对齐旧仓 UmaToolProvider 的映射，逐条对过）：
 *   /health /status /summary
 *   /api/event/choices  /api/event/observations?after_id=N
 *   /debug/hookdiag  /debug/event_reward_targets  /debug/ramen_transition
 *   /classes/search/<kw>  /fields/<class>  /methods/<class>  /find_method/<method>
 *
 * 红线（560 清单 400）：**禁止普通流程默认全量类扫描**——所以本客户端不提供
 * "list 全部类"的便捷方法；通用口 [get] 可以读任意路径，但只有用户/模型显式
 * 点名某条路径才会走到（显式点名 = 用户决定，不归普通流程）。
 */
class ObserveClient(
    /** 形如 http://127.0.0.1:18765，不带尾斜杠。 */
    val baseUrl: String,
    private val transport: WireTransport,
) {

    /** 一次 GET 的事实账：状态码 + 原文（2xx）或错误体（非 2xx）或异常说明。 */
    data class GetOutcome(
        val path: String,
        val httpStatus: Int?,
        /** 2xx 时是响应原文；非 2xx 时是错误体；连接失败时为 null。 */
        val body: String?,
        val error: String?,
        val elapsedMs: Long,
        val bytes: Long,
        /** 失败类别：报错按类说人话（超限/连不上/HTTP 状态），别让上层猜字符串。 */
        val kind: Kind = Kind.HTTP,
    ) {
        val ok: Boolean get() = httpStatus != null && httpStatus in 200..299 && body != null
    }

    /** GetOutcome 的类别。 */
    enum class Kind { OK, HTTP, TOO_LARGE, IO }

    /**
     * 只读 GET 一条路径。
     *
     * 字节纪律（红线二）：响应经 transport 逐行交付，这里分块拼、超 [maxBytes]
     * 立刻断——不把大响应一口气吞进内存；超限是异常，报账不硬撑。
     * 超时按端点自适应：探活类短等，大摘要多等（对端是嵌入式 SO，忙起来慢）。
     */
    fun get(path: String, maxBytes: Int = DEFAULT_MAX_BYTES): GetOutcome {
        val clean = validateReadPath(path)
        val started = System.currentTimeMillis()
        val request = WireRequest(
            url = baseUrl + clean,
            method = "GET",
            connectTimeoutMs = CONNECT_TIMEOUT_MS,
            readTimeoutMs = readTimeoutFor(clean),
        )
        val collected = StringBuilder()
        var received = 0L
        return try {
            val response = transport.exchange(request) { line ->
                val piece = line + "\n"
                val pieceBytes = piece.toByteArray(Charsets.UTF_8).size.toLong()
                if (received + pieceBytes > maxBytes) {
                    throw ObserveTooLargeException(received, maxBytes.toLong())
                }
                collected.append(piece)
                received += pieceBytes
                true
            }
            GetOutcome(
                path = clean,
                httpStatus = response.status,
                body = if (response.status in 200..299) collected.toString() else response.errorBody,
                error = if (response.status in 200..299) null else "HTTP ${response.status}",
                kind = if (response.status in 200..299) Kind.OK else Kind.HTTP,
                elapsedMs = System.currentTimeMillis() - started,
                bytes = received,
            )
        } catch (e: ObserveTooLargeException) {
            GetOutcome(
                path = clean,
                httpStatus = null,
                body = collected.toString(),
                error = "响应超过 ${maxBytes / 1024} KiB 上限，已断（已收 ${e.bytesSoFar} 字节）",
                kind = Kind.TOO_LARGE,
                elapsedMs = System.currentTimeMillis() - started,
                bytes = received,
            )
        } catch (e: IOException) {
            GetOutcome(
                path = clean,
                httpStatus = null,
                body = null,
                error = e.message ?: "连接失败（18765 无响应或超时）",
                kind = Kind.IO,
                elapsedMs = System.currentTimeMillis() - started,
                bytes = received,
            )
        }
    }

    /** 超限异常：带上断开前已收的字节数，报账用。 */
    class ObserveTooLargeException(val bytesSoFar: Long, val limit: Long) :
        IOException("observe 响应超过 $limit 字节上限")

    /** 一次探测的收场：两端点的事实账。 */
    data class ProbeOutcome(
        val health: GetOutcome?,
        val status: GetOutcome?,
    )

    /**
     * 探测序列：先 GET /health（通且像 hlpatch）再 GET /status。
     * 判定规则（[ObserveLink] 六态）在 [ObserveLink.probe] 落地，纯 JVM 可测：
     *   health 不通：DISCONNECTED（18765 无响应/拒绝/超时）
     *   health 503/429：OVERLOADED（对端忙，进冷却）
     *   health 2xx 但空体：DEGRADED（有东西应答但说不出健康）
     *   health 2xx 非 JSON：INCOMPATIBLE（疑似不是 hlpatch 在这个端口上）
     *   health 通 status 败：DEGRADED（桥活着，状态端点不给力）
     *   两个都通            到 READY
     * 版本兼容性不硬判：/health 原文原样带回，人眼/模型自己看版本号。
     */
    fun probe(): ProbeOutcome {
        val health = get(ENDPOINT_HEALTH, maxBytes = 64 * 1024)
        if (!health.ok) return ProbeOutcome(health, null)
        val body = health.body.orEmpty().trim()
        if (body.isEmpty()) return ProbeOutcome(health, null)
        if (!looksLikeJson(body)) return ProbeOutcome(health, null)
        val status = get(ENDPOINT_STATUS, maxBytes = 256 * 1024)
        return ProbeOutcome(health, status)
    }

    companion object {
        const val ENDPOINT_HEALTH = "/health"
        const val ENDPOINT_STATUS = "/status"
        const val ENDPOINT_SUMMARY = "/summary"
        const val ENDPOINT_EVENT_CHOICES = "/api/event/choices"
        const val ENDPOINT_EVENT_OBSERVATIONS = "/api/event/observations"
        const val ENDPOINT_HOOK_DIAG = "/debug/hookdiag"
        const val ENDPOINT_EVENT_REWARD_TARGETS = "/debug/event_reward_targets"
        const val ENDPOINT_RAMEN_TRANSITION = "/debug/ramen_transition"

        // ---- 嗅探/协议观测（只读查询；toggle/clear/install 是写操作，不做工具） ----
        const val ENDPOINT_SNIFF_STATUS = "/api/sniff/status"
        const val ENDPOINT_SNIFF_METADATA = "/api/sniff/metadata"
        const val ENDPOINT_SNIFF_UNITY = "/api/sniff/unity"
        const val ENDPOINT_SNIFF_DIAG = "/api/sniff/diag"
        const val ENDPOINT_MD5LOG = "/api/md5log"

        // ---- 育成数据 ----
        const val ENDPOINT_DATA = "/data"
        const val ENDPOINT_SCENARIO = "/scenario"
        const val ENDPOINT_EVENTS = "/events"
        const val ENDPOINT_EVENT_RECOMMEND = "/event/recommend"
        const val ENDPOINT_ACTION_LATEST = "/action/latest"
        const val ENDPOINT_TRAINING_RESULT = "/training/result"
        const val ENDPOINT_LOG = "/log"
        const val ENDPOINT_LOG_TURN = "/log/turn"

        // ---- 拉面（育成 AI 主线） ----
        const val ENDPOINT_RAMEN = "/ramen"
        const val ENDPOINT_RAMENINFO = "/debug/rameninfo"
        const val ENDPOINT_RAMENGAINS = "/debug/ramengains"
        const val ENDPOINT_RAMEN_PARTICIPANTS = "/debug/ramen_participants"
        const val ENDPOINT_RAMEN_PLANNER_STATE = "/debug/ramen_planner_state"

        // ---- 种子/继承/数据库 ----
        const val ENDPOINT_SEED_HISTORY = "/seed/history"
        const val ENDPOINT_SEED_STATS = "/seed/stats"
        const val ENDPOINT_INHERIT_COMPAT = "/inherit/compat"
        const val ENDPOINT_CARDDB = "/carddb"
        const val ENDPOINT_SKILLDATA = "/skilldata"
        const val ENDPOINT_MDB = "/mdb"
        const val ENDPOINT_MDB_SCHEMA = "/mdb/schema"
        const val ENDPOINT_MDB_SEARCH = "/mdb/search"
        const val ENDPOINT_SINGLETONS = "/singletons"
        const val ENDPOINT_TABLES = "/tables"

        // ---- debug 高频 ----
        const val ENDPOINT_GAUGE = "/debug/gauge"
        const val ENDPOINT_GAUGE2 = "/debug/gauge2"
        const val ENDPOINT_TRAINING_PARTNERS = "/debug/training_partners"
        const val ENDPOINT_TRAINING_SEED = "/debug/training_seed"
        const val ENDPOINT_TURN_PROBE = "/debug/turn_probe"
        const val ENDPOINT_BREEDERS = "/debug/breeders"

        /** 单次默认字节上限：16 MiB（旧仓同档；summary 会到 2 MiB 级）。 */
        const val DEFAULT_MAX_BYTES = 16 * 1024 * 1024
        const val CONNECT_TIMEOUT_MS = 5_000

        /** 读路径校验（照旧仓 validateReadPath 逐条重写）：
         *  / 开头、长度 2-1000、不许 // 开头、不许 ://（防绝对/网络 URL）、无控制字符。 */
        fun validateReadPath(input: String): String {
            require(input.startsWith('/')) { "path 必须以 / 开头" }
            require(input.length in 2..1000) { "path 长度必须在 2-1000" }
            require(!input.startsWith("//") && !input.contains("://")) { "path 不许是绝对/网络地址" }
            require(input.none { it == '\r' || it == '\n' || it == '\u0000' }) { "path 含控制字符" }
            return input
        }

        /** 路径段安全化：URL 编码（防 / 注入到别的端点），长度 1-500。 */
        fun safeSegment(value: String, label: String): String {
            require(value.length in 1..500) { "$label 长度必须 1-500" }
            require(value.none { it == '\r' || it == '\n' || it == '\u0000' }) { "$label 含控制字符" }
            return URLEncoder.encode(value, "UTF-8")
        }

        /** 读超时按端点分档：探活快、摘要慢（嵌入式 SO 忙起来生成摘要要等）。 */
        fun readTimeoutFor(path: String): Int = when {
            path == ENDPOINT_HEALTH || path == ENDPOINT_STATUS -> 10_000
            // 大响应端点：摘要/完整协议观测/全调试数据，SO 生成要时间
            path == ENDPOINT_SUMMARY || path == ENDPOINT_SNIFF_METADATA ||
                path == ENDPOINT_MDB || path == ENDPOINT_CARDDB ||
                path == ENDPOINT_SKILLDATA || path == ENDPOINT_DATA -> 60_000
            else -> 30_000
        }

        /** 粗判 JSON：首字符 { 或 [。不引序列化器，纯字符判断（探测够用）。 */
        fun looksLikeJson(text: String): Boolean {
            val t = text.trim()
            return t.startsWith("{") || t.startsWith("[")
        }

        /**
         * 状态码的中文人话（用户纪律：报错不许甩裸 "HTTP 404"，要带名字）。
         * 数字仍附在括号里当事实，解释在前、数字在后。
         */
        fun httpExplain(code: Int): String = when (code) {
            400 -> "请求格式不对，对端拒收（400）"
            401, 403 -> "对端拒绝访问（$code）"
            404 -> "对端没这条路由（404）——hlpatch 版本不同端点会变，可用 uma_read_endpoint 显式试别的路径"
            405 -> "对端不认这个请求方式（405）"
            408 -> "对端等太久没回（408）"
            429 -> "对端在忙，请求太密（429）"
            in 500..599 -> "对端内部出错（$code）——游戏可能在忙或插件状态异常，稍后再试"
            else -> "对端返回异常状态（$code）"
        }
    }
}
