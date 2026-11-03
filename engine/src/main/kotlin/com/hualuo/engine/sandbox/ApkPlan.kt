package com.hualuo.engine.sandbox

/**
 * 安装计划：把「用户要装什么」解析成「实际要装哪几个 + 哪些已满足」。
 *
 * 修复 D2（toInstall 为空立刻返回成功）：计划为空不算成功——调用方必须拿
 * [Plan.alreadySatisfied] 去 `apk info` 复验「确实装着且版本一致」，复验不过照走安装。
 *
 * 修复 D3（exitCode!=0 也算成功）：安装成功判据由 [SandboxManager] 统一定为
 * 「退出码为 0 且复验入库」，本类只负责把计划算清楚。
 *
 * 依赖递归对环免疫：visited 集合记录递归路径，成环包进 [Plan.cycles] 报告，不死循环。
 */
class ApkPlan {

    data class Plan(
        val requested: List<String>,
        /** 需要真实下载安装的包（已按依赖拓扑出现，被依赖者在前）。 */
        val toInstall: List<AlpineRepoIndex.RepoPkg>,
        /** 计划阶段判定已满足的包名（版本与索引一致）；仍需复验，见上。 */
        val alreadySatisfied: List<String>,
        /** 解析时遇到的环（用于报告，不阻塞）。 */
        val cycles: List<String>,
        /** 索引里查不到的名字（报告用）。 */
        val unknown: List<String>,
    )

    fun plan(
        index: AlpineRepoIndex,
        requested: List<String>,
        installedVersions: Map<String, String>,
    ): Plan {
        val toInstall = ArrayList<AlpineRepoIndex.RepoPkg>()
        val satisfied = ArrayList<String>()
        val cycles = ArrayList<String>()
        val unknown = ArrayList<String>()
        val seen = HashSet<String>()
        val planned = HashSet<String>()

        fun visit(name: String, chain: Set<String>) {
            if (name in chain) {
                cycles.add(name)
                return
            }
            if (name in seen) return
            seen.add(name)
            val pkg = index.resolve(name)
            if (pkg == null) {
                unknown.add(name)
                return
            }
            val installed = installedVersions[pkg.name]
            if (installed != null && installed == pkg.version && name == pkg.name) {
                // 只有「主名精确命中且版本一致」才记已满足；别名命中（so:xxx 之类）照走依赖展开。
                satisfied.add(pkg.name)
                // 父包满足不代表依赖都满足（apk add app 会补齐缺的依赖），所以继续展开：
                for (dep in pkg.depends) visit(dep, chain + pkg.name)
                return
            }
            if (pkg.name in planned) return
            planned.add(pkg.name)
            for (dep in pkg.depends) visit(dep, chain + pkg.name)
            toInstall.add(pkg)
        }

        for (req in requested) visit(req.trim(), emptySet())
        return Plan(requested, toInstall, satisfied, cycles, unknown)
    }
}
