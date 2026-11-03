package com.hualuo.engine.lsp

import com.hualuo.engine.io.sanitizeForLog

/**
 * 一个语言包的定义：按需下载与安装所需的全部事实（LSP 刀·切片②骨架）。
 *
 * 为什么 URL 与 SHA-256 必须同框：「先下下来、再随便找个渠道核对哈希」是把核对留给运气——
 * 损坏或恶意的包在下载那一刻已经落在磁盘上了。把期望哈希钉在定义里，
 * 安装器在下载之后、动任何磁盘之前先验（PackInstaller 的 verify 段）。
 *
 * 上游清单（这些定义由谁生产、怎么更新）是后面一刀的事；本切片只认「调用方给的定义」。
 *
 * 字段纪律（构造时硬验，违规抛 IllegalArgumentException，不带病进流程）：
 *  - id / version：ASCII 小写词符（a-z 0-9 点 下划线 减号），长 1 到 64——它们直接进路径；
 *  - serverBinary：包内相对路径（真实发行版多是 bin/ 这种嵌套结构）。若干 ASCII 词符段用
 *    正斜杠分隔，每段字母数字开头；不许双点、空段、反斜杠、绝对路径与含控制字符。
 *    解包完成后点名验的就是它（PackExtractor.verifyEntry）；
 *  - sha256Hex：64 位小写十六进制，大写视为不匹配（宁严勿宽）；
 *  - downloadUrl：只认 http 与 https，且不许含控制字符；
 *  - 任何字段都不许含控制字符（换行能伪造日志行）；报错消息里的原样值控制字符可视化后原样拼入
 *    （控制字符换成点、超长截断），不把字符本体原样印出去。
 *
 * 契约修订（相对第一版）：serverBinary 从「纯文件名」放宽为「干净的包内相对路径」。
 * 理由：真实语言服务发行版（kotlin/rust/go 等）几乎都把可执行文件放在 bin/ 子目录，
 * 纯文件名会逼包生产方拍平目录、破坏服务自己的相对引用。防穿越的强度不降反升：
 * 改成逐段验，双点、空段、绝对路径、反斜杠全部硬拒（测试逐条钉死）。
 */
data class LanguagePack(
    val id: String,
    val displayName: String,
    val version: String,
    val serverBinary: String,
    val sha256Hex: String,
    val downloadUrl: String,
) {
    init {
        require(isSlug(id)) { "语言包 id 必须是 ASCII 小写词符（长 1 到 64），现在是：" + sanitizeForLog(id) }
        require(isSlug(version)) { "版本号必须是 ASCII 小写词符，现在是：" + sanitizeForLog(version) }
        require(isPackPath(serverBinary)) {
            "服务入口必须是包内相对路径（ASCII 段用正斜杠分隔，不许双点、空段、反斜杠、绝对路径），现在是：" +
                sanitizeForLog(serverBinary)
        }
        require(isSha256(sha256Hex)) { "sha256 必须是 64 位小写十六进制（实际长度 ${sha256Hex.length}）" }
        require(isHttpOrHttps(downloadUrl)) { "下载地址只认 http 与 https（现在是：" + sanitizeForLog(downloadUrl) + "）" }
        require(downloadUrl.none { it.hasControl() }) {
            "下载地址不许含控制字符（换行能伪造日志行），现在是：" + sanitizeForLog(downloadUrl)
        }
        require(displayName.isNotEmpty() && displayName.none { it.hasControl() }) { "显示名不能为空、也不许含控制字符" }
    }
}

/** 控制字符判定：U+0000 到 U+001F 与 U+007F。任何字段里出现都硬拒。 */
internal fun Char.hasControl(): Boolean = code < 0x20 || code == 0x7F

/** ASCII 小写词符：首字符是字母或数字，其后允许点、下划线、减号，总长 1 到 64。 */
internal fun isSlug(value: String): Boolean = SLUG.matches(value)

/**
 * 包内相对路径：若干 ASCII 词符段用正斜杠分隔。
 * 总长 1 到 256；不许绝对路径（正斜杠开头）与反斜杠（Windows 解包工具会把它当分隔符）；
 * 每段不许为空、不许含双点（所以点段、双点段、a..b 这类段都过不了）、不许控制字符。
 */
internal fun isPackPath(value: String): Boolean {
    if (value.isEmpty() || value.length > 256) return false
    if (value.startsWith("/") || value.contains('\\')) return false
    val parts = value.split('/')
    return parts.all { it.isNotEmpty() && SEGMENT.matches(it) && !it.contains("..") }
}

/** 64 位小写十六进制。大写、短一位、多一位都算不匹配。 */
internal fun isSha256(value: String): Boolean =
    value.length == 64 && value.all { it in '0'..'9' || it in 'a'..'f' }

internal fun isHttpOrHttps(value: String): Boolean =
    value.startsWith("https://") || value.startsWith("http://")

private val SLUG = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")

/** 段规则与文件名同源：首字符字母数字，其后允许点、下划线、减号，总长不超过 128。 */
private val SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")
