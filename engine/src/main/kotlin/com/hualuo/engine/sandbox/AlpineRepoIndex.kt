package com.hualuo.engine.sandbox

/**
 * Alpine APKINDEX 解析：主包名表 + Provides 别名表。
 *
 * 修复 D1（口径不一致）：旧实现顶层包名只查主表、依赖解析却走别名表兜底——
 * 同一份索引两套标准，`python` 这类别名/虚拟名在顶层直接判死。
 * 从这里开始 [resolve] 先主表后别名表，顶层与依赖**同一套查法**。
 *
 * APKINDEX 文本格式（段间空行）：
 * ```
 * C:Q1xxxxx
 * P:musl
 * V:1.2.5-r0
 * A:aarch64
 * o:musl
 * D:so:libc.musl-aarch64.so.1
 * p:so:libc.musl-aarch64.so.1=1
 * ```
 */
class AlpineRepoIndex private constructor(
    private val byName: Map<String, RepoPkg>,
    private val aliasToName: Map<String, String>,
    val pkgCount: Int,
) {

    data class RepoPkg(
        val name: String,
        val version: String,
        val depends: List<String>,
        val provides: List<String>,
    )

    /**
     * 查一个包：先主包名表，查不到再走 Provides 别名表（so:xxx / 虚拟名 / =版本）。
     * 顶层输入与依赖递归都走这一个函数，不再各查各的。
     */
    fun resolve(requested: String): RepoPkg? {
        byName[requested]?.let { return it }
        // Provides 里可能带 "=版本" 或 "#序号" 修饰，建表时已剥掉；这里纯名字对纯名字。
        val real = aliasToName[requested] ?: return null
        return byName[real]
    }

    companion object {
        /** 从 APKINDEX 全文解析。坏行跳过不崩；重复包名后写的覆盖先写的（与 apk 索引刷新语义一致）。 */
        fun parse(text: String): AlpineRepoIndex {
            val byName = LinkedHashMap<String, RepoPkg>()
            val alias = LinkedHashMap<String, String>()
            var curName: String? = null
            var curVersion = ""
            var curDepends = ArrayList<String>()
            var curProvides = ArrayList<String>()

            fun flush() {
                val n = curName ?: return
                val pkg = RepoPkg(n, curVersion, curDepends.toList(), curProvides.toList())
                byName[n] = pkg
                for (p in pkg.provides) {
                    val bare = p.substringBefore('=').substringBefore('#').trim()
                    if (bare.isNotEmpty()) alias.putIfAbsent(bare, n) // 先建表者赢（索引排前 = 官方主提供者）
                }
                curName = null
                curVersion = ""
                curDepends = ArrayList()
                curProvides = ArrayList()
            }

            for (raw in text.lineSequence()) {
                val line = raw.trimEnd('\n', '\r')
                if (line.isEmpty()) {
                    flush()
                    continue
                }
                if (line.length < 2 || line[1] != ':') continue // 不认识的行跳过，不让整条索引报废
                val key = line[0]
                val value = line.substring(2)
                when (key) {
                    'P' -> curName = value
                    'V' -> curVersion = value
                    'D' -> curDepends.addAll(value.split(' ').map { it.trim() }.filter { it.isNotEmpty() })
                    'p' -> curProvides.addAll(value.split(' ').map { it.trim() }.filter { it.isNotEmpty() })
                }
            }
            flush()
            return AlpineRepoIndex(byName, alias, byName.size)
        }
    }
}
