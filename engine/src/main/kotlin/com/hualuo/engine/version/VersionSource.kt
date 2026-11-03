package com.hualuo.engine.version

import java.io.File

/** 应用的版本号。全项目只有 version.properties 一个来源，别处一律不许再写数字。 */
data class AppVersion(val versionName: String, val versionCode: Int) {
    fun display(): String = "$versionName（代号 $versionCode）"
}

/** 解析 version.properties 的文本。写错就直接报错，不许悄悄给个默认值。 */
fun parseVersionProperties(text: String): AppVersion {
    var name = ""
    var code = ""
    text.lineSequence().forEach { raw ->
        val line = raw.trim()
        if (line.isEmpty() || line.startsWith("#")) return@forEach
        val eq = line.indexOf('=')
        if (eq <= 0) return@forEach
        val key = line.substring(0, eq).trim()
        val value = line.substring(eq + 1).trim()
        when (key) {
            "versionName" -> name = value
            "versionCode" -> code = value
        }
    }
    require(name.isNotEmpty()) { "version.properties 里没写 versionName" }
    val codeInt = code.toIntOrNull()
        ?: throw IllegalArgumentException("version.properties 的 versionCode 必须是整数，当前写的是「$code」")
    require(codeInt > 0) { "versionCode 必须大于 0，当前=$codeInt" }
    return AppVersion(name, codeInt)
}

/** 从仓库根目录读版本号（构建脚本和测试都用它，避免两处各写一遍）。 */
fun readVersionFromRoot(rootDir: File): AppVersion =
    parseVersionProperties(File(rootDir, "version.properties").readText(Charsets.UTF_8))
