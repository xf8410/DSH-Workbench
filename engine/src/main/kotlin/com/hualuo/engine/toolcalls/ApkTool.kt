package com.hualuo.engine.toolcalls

import com.hualuo.engine.apk.ApkInspector
import com.hualuo.engine.apk.ApkSignature
import com.hualuo.engine.apk.AxmlManifest
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

/**
 * APK 体检工具（P1 补）：一个安装包「是谁签的、包名叫什么、版本多少、装不装得上」。
 *
 * 为什么现在补：这几轮每次碰到「装不上 / 覆盖不了 / 是不是同一个 App」，
 * 都只能靠 adb_shell 去 dumpsys，绕一大圈。engine 里其实早就有
 * [ApkInspector]（条目与 ABI）、[ApkSignature]（v2/v3 证书指纹）、
 * [AxmlManifest]（包名与版本）三件，只是没接到工具面上。
 *
 * 输出的是**核对账**：
 *  - 包名 + versionName/versionCode（判断是不是同一个 App）；
 *  - v2/v3 证书 subject 与 SHA-256（判断覆盖安装会不会被系统拒）；
 *  - 大小、SHA-256、ABI、条目数（判断是不是同一份文件）。
 */
object ApkTool {

    fun register(registry: ToolRegistry) {
        registry.register(
            ToolSpec(
                name = "apk_inspect",
                description = "体检一个本机 APK：包名、版本、签名证书指纹（v2/v3）、大小、ABI、条目数。" +
                    "装包前用它核对「是不是同一个 App、覆盖安装会不会被拒」",
                parametersJson = """{"type":"object","properties":{"path":{"type":"string","description":"APK 的绝对路径，如 /sdcard/Download/x.apk"},"include_entries":{"type":"boolean","description":"true=列出包内条目（默认只给前 40 个）"},"max_entries":{"type":"integer","description":"列条目时最多几条，默认 40，范围 1-500"}},"required":["path"]}""",
            ),
        ) { argumentsJson ->
            val args = argsOf(argumentsJson)
            val path = reqStr(args, "path", "APK 的绝对路径")
            val file = File(path)
            if (!file.isFile) fail("找不到这个文件：$path")
            if (file.length() <= 0) fail("这是个空文件（0 字节）：$path")
            val includeEntries = (args["include_entries"] as? JsonPrimitive)?.contentOrNull == "true"
            val maxEntries = (optInt(args, "max_entries") ?: 40).coerceIn(1, 500)
            format(file, includeEntries, maxEntries)
        }
    }

    private fun format(file: File, includeEntries: Boolean, maxEntries: Int): String {
        val report = runCatching { ApkInspector.inspect(file) }.getOrNull()
            ?: fail("这个文件读不成 APK（可能不是 zip，或读不动）")
        val manifest = runCatching { AxmlManifest.parseFromApk(file) }.getOrNull()
        val signature = runCatching { ApkSignature.inspect(file) }.getOrNull()

        val sb = StringBuilder("APK：${file.name}")
        sb.append("\n- 大小：").append(report.sizeBytes).append(" 字节（").append(mb(report.sizeBytes)).append("）")
        sb.append("\n- SHA-256：").append(report.sha256Hex)
        sb.append("\n- 魔数：").append(report.magicNote)
        if (manifest != null) {
            sb.append("\n- 包名：").append(manifest.packageName ?: "读不出")
            sb.append("\n- 版本：").append(manifest.versionName ?: "?").append("（versionCode ").append(manifest.versionCode ?: "?")
            if (manifest.compileSdkVersion != null) sb.append("，compileSdk ").append(manifest.compileSdkVersion)
            sb.append("）")
        } else {
            sb.append("\n- 包名/版本：manifest 解不开（可能是加密或非标准包）")
        }
        if (signature != null) {
            val error = signature.error
            if (error != null) {
                sb.append("\n- 签名：").append(error)
            } else if (signature.schemes.isEmpty()) {
                sb.append("\n- 签名：没有 v2/v3 签名块（可能只有 v1，或未签名）")
            } else {
                sb.append("\n- 签名：")
                signature.schemes.forEach { scheme ->
                    sb.append("\n  * ").append(scheme.scheme).append("：").append(scheme.certSubject)
                    sb.append(" / SHA-256 ").append(scheme.certSha256Hex)
                    if (scheme.certCount > 1) sb.append("（证书链 ").append(scheme.certCount).append(" 张）")
                }
                if (signature.schemes.map { it.certSha256Hex }.distinct().size == 1) {
                    sb.append("\n  * 这份指纹就是判断「能不能覆盖安装」的依据：与已装版本不同就必须先卸载")
                }
            }
        }
        sb.append("\n- ABI：").append(if (report.abis.isEmpty()) "无 native 库" else report.abis.joinToString("、"))
        sb.append("\n- 条目：").append(report.entries.size).append(" 条，解压后 ").append(mb(report.totalUncompressedBytes))
        if (report.duplicateGroups.isNotEmpty()) {
            sb.append("\n- 重复文件组：").append(report.duplicateGroups.size).append(" 组")
        }
        if (includeEntries) {
            sb.append("\n- 条目清单（前 ").append(maxEntries).append(" 条）：")
            report.entries.take(maxEntries).forEach { entry ->
                sb.append("\n  ").append(entry.name).append("  ").append(entry.sizeBytes).append("B")
            }
            if (report.entries.size > maxEntries) {
                sb.append("\n  ...（共 ").append(report.entries.size).append(" 条，只列了前 ").append(maxEntries).append(" 条）")
            }
        }
        return sb.toString()
    }

    private fun mb(bytes: Long): String = String.format("%.1fMB", bytes / (1024.0 * 1024))

    private fun argsOf(argumentsJson: String): JsonObject =
        runCatching { json.parseToJsonElement(argumentsJson) }.getOrNull() as? JsonObject
            ?: JsonObject(emptyMap())

    private fun optStr(args: JsonObject, key: String): String? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }

    private fun reqStr(args: JsonObject, key: String, hint: String): String =
        optStr(args, key) ?: fail("缺参数 $key（$hint）")

    private fun optInt(args: JsonObject, key: String): Int? =
        (args[key] as? JsonPrimitive)?.contentOrNull?.trim()?.toIntOrNull()

    private fun fail(message: String): Nothing = throw IllegalArgumentException(message)

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }
}
