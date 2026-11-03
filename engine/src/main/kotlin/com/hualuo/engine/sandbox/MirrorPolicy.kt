package com.hualuo.engine.sandbox

/**
 * 下载源与 DNS 策略（修复 D5：镜像与 DNS 硬编码、单点、不可换源）。
 *
 * 旧实现把镜像写死为 dl-cdn 一家、resolv.conf 写死 8.8.8.8/1.1.1.1——
 * 国内网络一抖就「环境错误」且无路可换。从这里开始：
 *  - 镜像是一张有序列表，下载失败自动切下一家（见 [next]）；
 *  - DNS 内容是设置项（默认保持官方版取值，可改）；
 *  - rootfs 与 APKINDEX 同一套镜像表（路径拼接规则见 [apkIndexPath] / [rootfsUrlOn]）。
 */
class MirrorPolicy(
    val repositories: List<String> = DEFAULT_MIRRORS,
    val resolvConf: String = DEFAULT_RESOLV,
) {

    init {
        require(repositories.isNotEmpty()) { "镜像列表不许为空：至少要留一家可下载来源" }
    }

    /** 当前应使用的镜像（失败前第一家）。 */
    fun current(): String = repositories.first()

    /**
     * 下载失败后的降级视图：把失败那家从表头挪走，下一家顶上。
     * 全部换完还不行就保持原列表（调用方报 DOWNLOAD，错误里带最后一家地址）。
     */
    fun next(failedMirror: String): MirrorPolicy {
        if (repositories.size <= 1) return this
        val rest = repositories.filter { it != failedMirror }
        if (rest.isEmpty()) return this
        return MirrorPolicy(rest + listOf(failedMirror), resolvConf)
    }

    fun rootfsUrlOn(mirror: String): String = "$mirror/releases/aarch64/$ROOTFS_FILE"

    /** 镜像串已含仓库段（…/main），直接拼文件名。 */
    fun apkIndexPathOn(mirror: String, repoSub: String = "main"): String = "$mirror/APKINDEX.tar.gz"

    companion object {
        const val ALPINE_VERSION_PATH = "v3.21"

        /** 官方在前（版本最正），清华与中科大做国内兜底；都挂了才报 DOWNLOAD。 */
        val DEFAULT_MIRRORS = listOf(
            "https://dl-cdn.alpinelinux.org/alpine/$ALPINE_VERSION_PATH/main",
            "https://mirrors.tuna.tsinghua.edu.cn/alpine/$ALPINE_VERSION_PATH/main",
            "https://mirrors.ustc.edu.cn/alpine/$ALPINE_VERSION_PATH/main",
        )

        /** 默认与官方版一致的 DNS 取值；设置页可改，不再写死在执行路径里。 */
        const val DEFAULT_RESOLV = "nameserver 8.8.8.8\nnameserver 1.1.1.1\n"

        const val ROOTFS_FILE = "alpine-minirootfs-3.21.0-aarch64.tar.gz"
        val ROOTFS_SHA256_PLACEHOLDER = ""
    }
}
