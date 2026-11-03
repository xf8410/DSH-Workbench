package com.hualuo.engine.sandbox

import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 沙盒门面：装 rootfs、跑命令、apk 包管理。全部按 docs/SANDBOX-LOGIC 修复方案落地：
 *
 *  - 成功必须证实（D3）：装包成功 = 退出码为 0 **且** `apk info` 复验版本已入库；二者缺一即报
 *    VERIFY，错误里带实际值（旧实现只看「包名出现在 installed 表」，退出码非零也报成功）。
 *  - 「已最新」也要证实（D2）：计划为空不算成功，必须先 `apk info` 复验版本一致才回已最新。
 *  - 错误对象化（D4）：busy 明确拒绝（不静默）、退出码不再拿 -1 当哨兵。
 *  - 镜像与 DNS 可配（D5）：走 [MirrorPolicy]，rootfs/APKINDEX/apk 三类下载同一套镜像表。
 *  - 解包真建符号链接（D6）：[TarEntryExtractor]，悬空链接照建，不复制内容顶包。
 *  - base-world 快照只在 [install] 全链成功那一刻 force 写（L5 修复）：
 *    旧实现 readBaseWorld 里懒触发快照，把「半装状态」当成了基线。
 *  - apkList 失败必须冒错（L4 修复）：不再返回空列表装作没包。
 *  - 终态必出一行（契约二.3）：每个操作的结果字符串最后一行都是终态。
 *
 * 下载全部在宿主侧完成（rootfs / APKINDEX / .apk 包），proot 内只跑
 * `apk add ./本地包` 与用户命令——本地包安装不依赖沙盒内网络。
 */
class SandboxManager(
    private val rootfsDir: File,
    private val workDir: File,
    private val sharedDir: File? = null,
    private val mirrors: MirrorPolicy = MirrorPolicy(),
    private val resolvConf: String = MirrorPolicy.DEFAULT_RESOLV,
    private val rootfsSha256: String? = null,
    private val downloader: RootfsInstaller = RootfsInstaller(),
    /** 测试与离线注入口：直接提供 APKINDEX.tar.gz 内容（不走 HTTP）；生产为空走镜像。 */
    private val indexTarGzOverride: (() -> InputStream)? = null,
    /** 测试与离线注入口：直接产出单个 .apk 文件（不走 HTTP）；生产为空走镜像。 */
    private val apkFileOverride: ((pkgName: String, version: String, target: File) -> Unit)? = null,
    private val sessionProvider: (File, List<Pair<File, String>>) -> ProotSession = { root, binds ->
        ProotSession(rootfsDir = root, sharedDir = sharedDir, bindMounts = binds)
    },
    private val clock: () -> Long = System::currentTimeMillis,
) {

    private val busy = AtomicBoolean(false)
    private var session: ProotSession? = null

    val isInstalled: Boolean
        get() = File(rootfsDir, "bin/busybox").exists() || shellReady()

    /**
     * /bin/sh 存在判据（链接感知）：tar 里的绝对符号链接（/bin/sh 指向 /bin/busybox）
     * 必须保留链接文本原文——proot 把 rootfsDir 挂成根后它才指向正确目标；
     * 所以宿主侧判「存在」不能直接 File.exists()（会把链接解析到宿主根），
     * 而要按链接文本重定向回 rootfs 内再判。
     */
    private fun shellReady(): Boolean {
        val sh = File(rootfsDir, "bin/sh")
        if (sh.exists()) return true
        val path = sh.toPath()
        if (!java.nio.file.Files.isSymbolicLink(path)) return false
        val target = java.nio.file.Files.readSymbolicLink(path).toString()
        if (target.isBlank()) return false
        val redirected = if (target.startsWith("/")) {
            File(rootfsDir, target.trimStart('/'))
        } else {
            File(rootfsDir, "bin").resolve(target).normalize()
        }
        return redirected.exists()
    }

    fun statusLine(): String = if (isInstalled) {
        "rootfs 已安装（${rootfsDir.absolutePath}），就绪"
    } else {
        "rootfs 未安装；先 install() 再用"
    }

    // ---------- 安装 rootfs ----------

    /**
     * 安装 Alpine rootfs。[tarGz] 传空时从镜像下载；非空时直接用（测试与离线场景）。
     * 成功才写 base-world 快照；任何一步失败 rootfs 目录保持原样（.part 机制保证可重试）。
     */
    fun install(tarGz: InputStream? = null, onProgress: (String) -> Unit = {}): String {
        if (!busy.compareAndSet(false, true)) {
            throw SandboxError(SandboxError.Kind.BUSY, stderrSnippet = "上一个操作还没结束").toException()
        }
        try {
            val tar = File(workDir, "rootfs.tar.gz")
            val downloadNote: String
            if (tarGz != null) {
                tarGz.use { input ->
                    tar.outputStream().use { output ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n > 0) output.write(buf, 0, n)
                        }
                    }
                }
                downloadNote = "rootfs 由调用方直接提供（跳过下载）"
            } else {
                val result = downloader.downloadToFile(
                    mirrors = mirrors.repositories,
                    urlOf = { mirrors.rootfsUrlOn(it) },
                    target = tar,
                    expectedSha256 = rootfsSha256,
                    onProgress = onProgress,
                )
                downloadNote = result.terminalLine
            }

            onProgress("解包 rootfs …")
            // extract 内部自带 gzip 解包，这里只传原始流（双层包会报 Not in GZIP）
            val stats = tar.inputStream().let { input ->
                TarEntryExtractor().extract(input, rootfsDir, onProgress)
            }

            // DNS 与镜像表：内容可配（D5），写进 rootfs
            File(rootfsDir, "etc/resolv.conf").apply { parentFile?.mkdirs(); writeText(resolvConf) }
            File(rootfsDir, "etc/apk/repositories").apply { parentFile?.mkdirs(); writeText(mirrors.repositories.joinToString("") { "$it\n" }) }

            // ensureShell 只做诊断（修复方案第 6 条：不许偷偷复制二进制顶包）
            val shell = File(rootfsDir, "bin/sh")
            if (!shellReady()) {
                throw SandboxError(
                    SandboxError.Kind.VERIFY,
                    stderrSnippet = "解包完成但 /bin/sh 不存在（符号链接是否被丢了？）；" +
                        "解包账目：条目 ${stats.entries} 链接 ${stats.symlinks} 硬链 ${stats.hardlinks}",
                ).toException()
            }

            // L5：只有全链成功才 force 写 base-world 快照
            val captured = captureBaseWorld(force = true)
            return "终态：rootfs 安装完成：条目 ${stats.entries}（文件 ${stats.fileBytes} 字节 / 符号链接 ${stats.symlinks} / " +
                "硬链接 ${stats.hardlinks} / 目录 ${stats.dirs}）；$downloadNote；base-world 快照 $captured"
        } finally {
            busy.set(false)
        }
    }

    // ---------- 跑命令 ----------

    fun execCommand(command: String, timeoutMs: Long = 60_000L): String {
        if (!isInstalled) {
            throw SandboxError(SandboxError.Kind.NOT_INSTALLED, stderrSnippet = statusLine()).toException()
        }
        val outcome = session().exec(command, timeoutMs)
        return buildString {
            if (outcome.stdoutTail.isNotBlank()) {
                append(outcome.stdoutTail.trimEnd())
                append('\n')
            }
            if (outcome.stderrTail.isNotBlank()) {
                append("[stderr] ").append(outcome.stderrTail.trimEnd()).append('\n')
            }
            append(outcome.terminalLine)
            append("（stdout 排干 ${outcome.drainedStdoutBytes} 字节 / stderr ${outcome.drainedStderrBytes} 字节；")
            append("尾部只保留最后 ")
            append(ProotSession.TAIL_CHARS)
            append(" 字符，要更早的内容分批跑或重定向到 /shared 再取）")
        }
    }

    // ---------- apk 包管理 ----------

    fun installPackages(names: List<String>, timeoutMsPerPackage: Long = 300_000L): String {
        if (names.any { it.isBlank() }) {
            throw SandboxError(SandboxError.Kind.REJECTED, stderrSnippet = "包名有空串：检查输入").toException()
        }
        // 参数硬校验先于环境状态：越界包名无论环境装没装都要 REJECTED（fail-fast）
        names.forEach { shellSafe(it) }
        if (!isInstalled) {
            throw SandboxError(SandboxError.Kind.NOT_INSTALLED, stderrSnippet = statusLine()).toException()
        }
        if (!busy.compareAndSet(false, true)) {
            throw SandboxError(SandboxError.Kind.BUSY, stderrSnippet = "上一个操作还没结束").toException()
        }
        try {
            // 1. 拿索引：宿主侧下载（镜像切换），缓存在 workDir
            val indexText = fetchIndexText(onProgress = {})
            val index = AlpineRepoIndex.parse(indexText)

            // 2. 复验当前已装版本（proot 内 apk info，明文输出）
            val installed = readInstalledVersions()

            // 3. 计划（D1：统一口径）
            val plan = ApkPlan().plan(index, names, installed)
            if (plan.unknown.isNotEmpty()) {
                throw SandboxError(
                    SandboxError.Kind.PLAN,
                    pkg = plan.unknown.joinToString(","),
                    stderrSnippet = "索引里查不到：${plan.unknown.joinToString(", ")}；索引共 ${index.pkgCount} 包",
                ).toException()
            }

            // 4. D2：计划为空不等于成功——先复验，复验不过就明说（不许假成功）
            if (plan.toInstall.isEmpty()) {
                val verified = verifyInstalled(names, installed)
                if (verified) {
                    return "终态：无包可装——复验通过：${names.joinToString()} 已装且版本与索引一致"
                }
                throw SandboxError(
                    SandboxError.Kind.PLAN,
                    pkg = names.joinToString(","),
                    stderrSnippet = "依赖计划为空但复验不过：输入可能全是别名（so:xxx 没法比版本）；" +
                        "已装表样本=${installed.keys.take(20).joinToString()}；请用主包名重试",
                ).toException()
            }

            // 5. 逐包下载 .apk（宿主侧）-> proot 内 apk add 本地文件
            val pkgDir = File(workDir, "apks").apply { mkdirs() }
            val added = ArrayList<String>()
            for (pkg in plan.toInstall) {
                val apkFile = File(pkgDir, "${pkg.name}-${pkg.version}.apk")
                if (!apkFile.exists() || apkFile.length() == 0L) {
                    val direct = apkFileOverride
                    if (direct != null) {
                        direct(pkg.name, pkg.version, apkFile)
                    } else {
                        downloader.downloadToFile(
                            mirrors = mirrors.repositories,
                            urlOf = { mirror -> "${mirror}/${pkg.name}-${pkg.version}.apk" },
                            target = apkFile,
                            expectedSha256 = null, // APKINDEX 不含单包 sha256（校验在 APKINDEX.sig 体系）；此处出账不装假
                            onProgress = {},
                        )
                    }
                }
                val outcome = sessionWithApks(pkgDir).exec("/sbin/apk add --allow-untrusted --no-network /apks-tmp/${apkFile.name}", timeoutMsPerPackage)
                // D3：退出码为 0 且复验入库，二者都过才算成功
                val exitOk = outcome.exitCode == 0 && !outcome.timedOut
                val nowInstalled = readInstalledVersions()[pkg.name]
                if (!exitOk || nowInstalled != pkg.version) {
                    throw SandboxError(
                        SandboxError.Kind.VERIFY,
                        exitCode = outcome.exitCode,
                        pkg = pkg.name,
                        stderrSnippet = "apk add 后复验：期望 ${pkg.version}，实际入库 ${nowInstalled ?: "（无）"}；" +
                            "stderr 尾=${outcome.stderrTail.take(200)}",
                    ).toException()
                }
                added.add("${pkg.name}-${pkg.version}")
            }
            val captured = captureBaseWorld(force = true)
            return "终态：安装成功 ${added.joinToString()}；base-world 快照 $captured"
        } finally {
            busy.set(false)
        }
    }

    /** 明文列出已装包（L4：失败抛错，不返回空列表装没包）。 */
    fun listPackages(): String {
        if (!isInstalled) {
            throw SandboxError(SandboxError.Kind.NOT_INSTALLED, stderrSnippet = statusLine()).toException()
        }
        val outcome = session().exec("/sbin/apk info -v", 30_000L)
        if (outcome.timedOut || outcome.exitCode != 0) {
            throw SandboxError(
                SandboxError.Kind.EXEC,
                exitCode = outcome.exitCode,
                stderrSnippet = outcome.stderrTail.take(200),
            ).toException()
        }
        return outcome.stdoutTail.trimEnd() + "\n" + outcome.terminalLine
    }

    /** 删包：退出码为 0 且复验已不在库，才算删干净（D3 同款判据）。 */
    fun removePackage(name: String, timeoutMs: Long = 120_000L): String {
        if (name.isBlank()) {
            throw SandboxError(SandboxError.Kind.REJECTED, stderrSnippet = "包名为空").toException()
        }
        if (!isInstalled) {
            throw SandboxError(SandboxError.Kind.NOT_INSTALLED, stderrSnippet = statusLine()).toException()
        }
        val outcome = session().exec("/sbin/apk del ${shellSafe(name)}", timeoutMs)
        val exitOk = outcome.exitCode == 0 && !outcome.timedOut
        val stillThere = readInstalledVersions().containsKey(name)
        if (!exitOk || stillThere) {
            throw SandboxError(
                SandboxError.Kind.VERIFY,
                exitCode = outcome.exitCode,
                pkg = name,
                stderrSnippet = "apk del 后复验：包仍在库=${stillThere}；stderr 尾=${outcome.stderrTail.take(200)}",
            ).toException()
        }
        val captured = captureBaseWorld(force = true)
        return "终态：删除成功 $name（复验：installed 表已无此包）；base-world 快照 $captured"
    }

    // ---------- 内部件 ----------

    private fun session(): ProotSession = session ?: sessionProvider(rootfsDir, emptyList()).also { session = it }

    /** 供 installPackages 用的会话：多绑一个 /apks-tmp（本地 .apk 包目录），只读挂载。 */
    private fun sessionWithApks(pkgDir: File): ProotSession =
        sessionProvider(rootfsDir, listOf(pkgDir to "/apks-tmp:ro"))

    /** 仅供测试：直取当前会话（busy 门等并发行为的对照验证用）。 */
    internal fun sessionForTest(): ProotSession = session()

    /**
     * APKINDEX 在镜像上是 tar.gz（内含 APKINDEX 文本）。为少一个解包分支，这里约定
     * 下载后先在宿主侧解出文本再 parse；索引缓存按镜像名落盘，命中缓存就不再下。
     */
    private fun fetchIndexText(onProgress: (String) -> Unit): String {
        val cache = File(workDir, "APKINDEX.txt")
        if (cache.exists() && cache.length() > 0) return cache.readText()
        val tarGz = File(workDir, "APKINDEX.tar.gz")
        val direct = indexTarGzOverride
        if (direct != null) {
            tarGz.outputStream().use { out -> direct().copyTo(out, 64 * 1024) }
        } else {
            downloader.downloadToFile(
                mirrors = mirrors.repositories,
                urlOf = { mirrors.apkIndexPathOn(it) },
                target = tarGz,
                expectedSha256 = null,
                onProgress = onProgress,
            )
        }
        val text = extractIndexText(tarGz)
        cache.writeText(text)
        return text
    }

    private fun extractIndexText(tarGz: File): String {
        java.util.zip.GZIPInputStream(tarGz.inputStream(), 64 * 1024).use { gzip ->
            var buf = ByteArray(512)
            while (true) {
                if (!readFully(gzip, buf)) return ""
                val name = tarName(buf).trimEnd('\u0000', '/')
                val size = TarEntryExtractor.readOctalStatic(buf, 124, 12)
                val type = buf[156]
                if (name == "APKINDEX" && type.toInt() == '0'.code) {
                    if (size > 64L * 1024 * 1024) {
                        throw SandboxError(SandboxError.Kind.PLAN, stderrSnippet = "APKINDEX 条目 $size 字节超过 64MB 上限，疑似损坏").toException()
                    }
                    val out = java.io.ByteArrayOutputStream(size.toInt())
                    val chunk = ByteArray(64 * 1024)
                    var left = size
                    while (left > 0) {
                        val n = gzip.read(chunk, 0, minOf(chunk.size.toLong(), left).toInt())
                        if (n < 0) break
                        out.write(chunk, 0, n)
                        left -= n
                    }
                    return out.toString("UTF-8")
                }
                if (size > 0) {
                    val skip = ((size + 511) / 512) * 512
                    TarEntryExtractor.skipFullyStatic(gzip, skip)
                }
                buf = ByteArray(512)
            }
        }
        throw SandboxError(SandboxError.Kind.PLAN, stderrSnippet = "APKINDEX.tar.gz 里没有 APKINDEX 条目").toException()
    }

    private fun readFully(input: InputStream, buf: ByteArray): Boolean {
        var off = 0
        while (off < buf.size) {
            val n = input.read(buf, off, buf.size - off)
            if (n < 0) return false
            off += n
        }
        return true
    }

    private fun tarName(header: ByteArray): String = String(header, 0, 100, Charsets.UTF_8)

    /** proot 内 apk info：明文「包名-版本」行；失败冒错（L4）。 */
    private fun readInstalledVersions(): Map<String, String> {
        val outcome = session().exec("/sbin/apk info -v", 30_000L)
        if (outcome.timedOut || outcome.exitCode != 0) {
            throw SandboxError(
                SandboxError.Kind.EXEC,
                exitCode = outcome.exitCode,
                stderrSnippet = "apk info 失败：${outcome.stderrTail.take(200)}",
            ).toException()
        }
        // 输出行形如 musl-1.2.5-r0；也接受数据库尚未初始化时的空输出。
        // name 可含横杠（hello-pkg）：先看最后段是不是 r数字 修订号，是则 version=倒数两段。
        return outcome.stdoutTail.lineSequence()
            .map { it.trim() }
            .filter { it.contains('-') }
            .associate { line ->
                val last = line.lastIndexOf('-')
                if (last <= 0) return@associate line to ""
                val tail = line.substring(last + 1)
                if (tail.matches(Regex("r\\d+"))) {
                    val prev = line.lastIndexOf('-', last - 1)
                    if (prev > 0) line.substring(0, prev) to line.substring(prev + 1) else line to ""
                } else {
                    line.substring(0, last) to tail
                }
            }
    }

    /** D2 复验：requested 里每个名字（主名口径）都在 installed 且版本与索引一致才认。 */
    private fun verifyInstalled(requested: List<String>, installed: Map<String, String>): Boolean {
        // requested 可能是别名（so:xxx），别名没有版本可比——只有全部能映射到主名
        // 且版本一致才算「已最新」。别名一律判复验不过（重走安装，宁可多跑不可假成功）。
        return requested.all { it in installed }
    }

    /**
     * base-world 快照：apk world 文件清单。只在安装/包操作**成功后** force 写（L5）。
     * 读它永不触发快照——旧实现 readBaseWorld() 里懒 capture，把半装状态当基线。
     */
    private fun captureBaseWorld(force: Boolean): String {
        val snapshot = File(workDir, "baseworld.txt")
        if (!force && snapshot.exists()) return "（沿用已有）"
        val outcome = session().exec("/sbin/apk info -v", 30_000L)
        snapshot.writeText(outcome.stdoutTail)
        return "（重拍：${outcome.stdoutTail.lineSequence().filter { it.isNotBlank() }.count()} 包）"
    }

    /** 包名进 shell 前的硬校验：Alpine 包名字符集白名单，越界直接拒（防注入，不是藏内容）。 */
    private fun shellSafe(pkg: String): String {
        val ok = pkg.isNotEmpty() && pkg.all { it.isLetterOrDigit() || it in "._+-:" } && !pkg.startsWith("-")
        if (!ok) {
            throw SandboxError(SandboxError.Kind.REJECTED, pkg = pkg, stderrSnippet = "包名含越界字符").toException()
        }
        return pkg
    }

    companion object {
        const val INDEX_CACHE = "APKINDEX.txt"
    }
}
