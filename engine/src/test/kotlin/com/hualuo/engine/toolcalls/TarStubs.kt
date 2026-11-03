package com.hualuo.engine.toolcalls

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream

/** tar 条目桩（SandboxManagerTest 的 rootfs 注入用）。 */
internal data class StubEntry(val name: String, val content: String = "", val type: Char = '0', val linkName: String = "") {
    fun headerAndBody(): ByteArray {
        val header = ByteArray(512)
        val nameBytes = name.toByteArray()
        System.arraycopy(nameBytes, 0, header, 0, minOf(nameBytes.size, 100))
        header[156] = type.code.toByte()
        val body = content.toByteArray()
        val sizeOctal = body.size.toString(8).padStart(11, '0') + "\u0000"
        System.arraycopy(sizeOctal.toByteArray(), 0, header, 124, 12)
        val linkBytes = linkName.toByteArray()
        System.arraycopy(linkBytes, 0, header, 157, minOf(linkBytes.size, 100))
        for (i in 148..155) header[i] = ' '.code.toByte()
        var sum = 0L
        for (b in header) sum += b.toLong() and 0xFF
        val chk = (java.lang.Long.toOctalString(sum) + " ").padStart(6, '0').toByteArray()
        System.arraycopy(chk, 0, header, 148, 6)
        val out = ByteArrayOutputStream()
        out.write(header)
        out.write(body)
        val pad = (512 - body.size % 512) % 512
        if (pad > 0) out.write(ByteArray(pad))
        return out.toByteArray()
    }
}

/** tar.gz 组装桩。 */
internal object TarStubs {
    fun ofEntries(vararg entries: StubEntry): ByteArray {
        val raw = ByteArrayOutputStream()
        for (e in entries) raw.write(e.headerAndBody())
        raw.write(ByteArray(1024))
        val gz = ByteArrayOutputStream()
        GZIPOutputStream(gz).use { it.write(raw.toByteArray()) }
        return gz.toByteArray()
    }
}
