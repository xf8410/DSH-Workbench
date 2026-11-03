package com.hualuo.engine.io

import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** 一次搬运的结果：搬了多少字节 + 内容的 sha256（用来验"传过去的和原来是不是同一份"，不是加密）。 */
data class CopyReport(val bytes: Long, val sha256Hex: String)

/** 一次搬 64 KiB。这个值别乱改：太小会被 IO 次数拖死，太大就是拿内存换不到什么。 */
const val DEFAULT_BUFFER_BYTES = 64 * 1024

/**
 * 流式复制：一次只读一小块、写一小块，**永远不把整个文件读进内存**。
 *
 * 旧 Agora 传大文件闪退的根因就是把整个文件（或者整个 base64 字符串）一次性装进内存，
 * 几个 G 的文件必然撑爆。这里从第一天就不给这种写法留位置。
 *
 * @param totalBytes 总大小；不知道就传 -1，进度回调里也会带着 -1，让界面画"不确定进度条"。
 * @param onProgress 每搬一块回调一次（已搬字节，总字节）。
 *                   刷界面请自己限流（比如 150 毫秒一次），别把主线程刷爆——旧版就栽在这儿。
 */
fun streamingCopy(
    input: InputStream,
    output: OutputStream,
    totalBytes: Long = -1L,
    bufferSize: Int = DEFAULT_BUFFER_BYTES,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): CopyReport {
    require(bufferSize > 0) { "缓冲区大小必须大于 0，当前=$bufferSize" }
    val digest = MessageDigest.getInstance("SHA-256")
    val buffer = ByteArray(bufferSize)
    var done = 0L
    while (true) {
        val read = input.read(buffer)
        if (read < 0) break
        digest.update(buffer, 0, read)
        output.write(buffer, 0, read)
        done += read
        onProgress(done, totalBytes)
    }
    output.flush()
    return CopyReport(bytes = done, sha256Hex = digest.digest().toHexLower())
}

/** 把字符串转成 UTF-8 字节后流式复制（给"内容不是文件、是一段文本"的场合用）。 */
fun streamingCopyOfText(
    text: String,
    output: OutputStream,
    onProgress: (done: Long, total: Long) -> Unit = { _, _ -> },
): CopyReport = text.byteInputStream(Charsets.UTF_8).use {
    streamingCopy(it, output, totalBytes = -1L, onProgress = onProgress)
}

internal fun ByteArray.toHexLower(): String {
    val out = StringBuilder(size * 2)
    for (b in this) {
        out.append(HEX[(b.toInt() shr 4) and 0x0F]).append(HEX[b.toInt() and 0x0F])
    }
    return out.toString()
}

private const val HEX = "0123456789abcdef"
