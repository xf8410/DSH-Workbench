package com.hualuo.engine.toolcalls

import com.hualuo.engine.sandbox.ProotSession
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 沙盒全链测试的共享桩：把「proot 命令」透明转成宿主 /bin/sh 执行。
 *
 * installed 表（[installed]）语义 = apk info -v 的输出行（name-version）：
 *  - apk add：从 [indexText] 的 P: 行反查包名（包名可含横杠，不能按文件名瞎切），把
 *    name-version 写进表——D3 复验读到的是真入库状态，不是 mock 出来的绿；
 *  - apk del：把对应行从表里删掉。
 */
class ProotShimLauncher(
    private val installed: MutableMap<String, String>,
    private val indexText: String = "",
) : ProotSession.Launcher {

    override fun start(cmd: List<String>, workdir: File?): Process {
        val inner = if (cmd.firstOrNull() == "proot") {
            val idx = cmd.indexOf("/bin/sh")
            cmd.subList(idx, cmd.size)
        } else cmd

        val shIndex = inner.indexOf("/bin/sh")
        val script = if (shIndex >= 0 && inner.size > shIndex + 2) inner[shIndex + 2] else inner.lastOrNull() ?: ""

        val rewrote = when {
            // 真 apk info -v 是每行一个 name-version；这里必须按行输出（空格分隔会让按行解析全错）
            script.contains("apk info") -> "echo '" + installed.entries.joinToString("\n") { "${it.key}-${it.value}" } + "'"
            script.contains("apk add") -> {
                val stem = Regex("/apks-tmp/([^\\s]+)\\.apk").find(script)?.groupValues?.get(1) ?: ""
                val pkgName = knownPackages().firstOrNull { stem.startsWith("$it-") }
                if (pkgName != null) {
                    installed[pkgName] = stem.removePrefix("$pkgName-")
                    "echo added $pkgName"
                } else {
                    "echo unknown-package-in-stem:$stem; exit 1"
                }
            }
            script.contains("apk del") -> {
                val stem = script.substringAfter("apk del").trim().substringBefore(' ')
                val pkgName = knownPackages().firstOrNull { stem.startsWith("$it-") } ?: stem
                installed.remove(pkgName)
                "echo removed $pkgName"
            }
            else -> script
        }

        val shimCmd = if (shIndex >= 0) {
            inner.subList(0, shIndex + 1) + listOf("-c", rewrote) + inner.drop(shIndex + 3)
        } else {
            listOf("/bin/sh", "-c", rewrote)
        }
        return ProcessBuilder(shimCmd).apply { workdir?.let { directory(it) } }.start()
    }

    private fun knownPackages(): List<String> =
        Regex("(?m)^P:(.+)$").findAll(indexText).map { it.groupValues[1].trim() }.toList()

    companion object {
        fun destroyQuietly(p: Process) {
            p.destroyForcibly()
            p.waitFor(2, TimeUnit.SECONDS)
        }
    }
}
