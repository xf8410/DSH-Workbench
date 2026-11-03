package com.hualuo.engine.toolcalls

/**
 * 工具执行器：吃模型给的参数 JSON 原文，吐一段**给模型看的结果文本**。
 *
 * 纪律：
 *  - 结果别产巨型文本（喂回上下文前还有一道 [ToolResultTrimmer.trim] 兜底，但执行器自己先省着点）；
 *  - 抛异常可以（比如网络炸了）：注册表会把异常翻成一段「失败在这」的结果文本回填给模型——
 *    工具失败也是给模型的证据，不许静默吞成空气。
 */
fun interface ToolHandler {
    fun execute(argumentsJson: String): String
}

/** 一次工具执行的收场：ok=false 时 text 是给模型看的失败原因（照样喂回，不算链路错）。 */
data class ToolOutcome(val ok: Boolean, val text: String)

/**
 * 工具注册表：名字到「清单形状 + 执行器」的映射。
 *
 * 为什么要有这张表：模型能调的工具必须**先注册才有了**。清单（[specs]）原样进请求的
 * tools 数组；执行侧（[execute]）只认注册过的名字——没注册的名字回一句指名道姓的
 * 「不存在 + 现在有哪些」，让模型自己改口，而不是把异常抛穿把整轮生成弄死。
 *
 * 家常规矩：
 *  - 工具名走 OpenAI 的字符规（字母数字下划线横杠，1 到 64 位）：注册时就把关，
 *    拼错的名字在开发期炸出来，不许等模型调用时才 400；
 *  - 重名注册 = 后一份覆盖前一份（测试与覆盖场景方便），不留两份影子；
 *  - 线程模型：注册发生在启动期（单线程），执行发生在生成线程——本类不加锁，
 *    注册完之后不再改（改也是整表替换，不留半新半旧）。
 */
class ToolRegistry {

    private val entries = LinkedHashMap<String, Entry>()

    /** 一份注册：清单形状与执行器捆在一起，拆开放会有人只注册一半。 */
    data class Entry(val spec: ToolSpec, val handler: ToolHandler, val visibleIf: (() -> Boolean)? = null)

    /** 注册一个工具。名字不合规直接抛（这是我们自己代码的错，开发期就该炸，不许等模型调用）。 */
    fun register(spec: ToolSpec, handler: ToolHandler) {
        require(spec.name.matches(NAME_RULE)) {
            "工具名「${spec.name}」不合规：只许字母、数字、下划线、横杠，1 到 64 位（OpenAI 的字符规）"
        }
        entries[spec.name] = Entry(spec, handler, null)
    }

    /**
     * 注册一个带**运行时可见性**的工具（M4 第五刀）：[visibleIf] 每轮请求现问——
     * 关掉的工具从清单里**消失**（对齐旧 Agora definitions(ctx) 按开关返空的做法），
     * 不是「看得见但点不动」；翻回开立刻回来，不用重启。
     *
     * 单独一个函数名而不是第三个默认参数：**尾随 lambda 会绑到最后一个参数**——
     * register(spec) { ... } 的老写法会把执行器绑到 visibleIf 上冒充 Boolean，
     * CI 编译段抓到过（ChatRuntime 的 clock 同款坑），不赌。
     * 调用侧兜底：清单里没有却被叫到（开关在回合中途翻掉），按不可用回话，不执行。
     */
    fun registerGated(spec: ToolSpec, handler: ToolHandler, visibleIf: () -> Boolean) {
        require(spec.name.matches(NAME_RULE)) {
            "工具名「${spec.name}」不合规：只许字母、数字、下划线、横杠，1 到 64 位（OpenAI 的字符规）"
        }
        entries[spec.name] = Entry(spec, handler, visibleIf)
    }

    /** 清单（进请求 tools 数组的形状）；空注册表给空清单：空清单不发 tools 字段，行为与老版一字不差。 */
    fun specs(): List<ToolSpec> =
        entries.values.filter { it.visibleIf == null || it.visibleIf() }.map { it.spec }

    fun isEmpty(): Boolean = entries.isEmpty()

    fun size(): Int = entries.size

    /**
     * 执行一次调用：没注册的名字、执行抛异常，都折成 [ToolOutcome.ok]=false 的文本
     * （照样回填给模型，让它自己改口或报错），绝不把异常抛穿到生成循环。
     * 可见性为假的工具按「此刻不可用」回话——开关中途翻掉不该放行迟到的调用。
     */
    fun execute(name: String, argumentsJson: String): ToolOutcome {
        val entry = entries[name]
            ?: return ToolOutcome(false, "工具「$name」不存在。现在能用的工具：" + availableNames())
        if (entry.visibleIf != null && !entry.visibleIf()) {
            return ToolOutcome(false, "工具「$name」此刻不可用（对应开关刚被关掉）。")
        }
        val result = runCatching { entry.handler.execute(argumentsJson) }
        return result.fold(
            onSuccess = { ToolOutcome(true, it) },
            onFailure = { ToolOutcome(false, "工具「$name」执行失败：${it.message ?: "内部出错"}") },
        )
    }

    private fun availableNames(): String =
        if (entries.isEmpty()) "（一个都还没接）" else entries.keys.joinToString("、")

    private companion object {
        /** OpenAI 的工具名字符规：字母、数字、下划线、横杠，1 到 64 位。 */
        val NAME_RULE = Regex("[A-Za-z0-9_-]{1,64}")
    }
}
