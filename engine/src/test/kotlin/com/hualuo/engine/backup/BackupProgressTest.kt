package com.hualuo.engine.backup

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

/** 进度回调的契约：导出报（已写, 总数）逐份推进；导入报已收份数。不碰既有 BackupArchiveTest。 */
class BackupProgressTest {

    private fun sources(ids: List<String>) = ids.map { id ->
        BackupSessionSource(id) { "line-$id".toByteArray().inputStream() }
    }

    @Test
    fun writeReportsOneTickPerSessionWithTotal() {
        val out = ByteArrayOutputStream()
        val ticks = ArrayList<Pair<Int, Int>>()
        writeBackup(out, "k=v", sources(listOf("a", "b", "c")), "0.6.0-test") { done, total ->
            ticks.add(done to total)
        }
        assertEquals(listOf(1 to 3, 2 to 3, 3 to 3), ticks)
    }

    @Test
    fun readReportsHandledSessionsInOrder() {
        val out = ByteArrayOutputStream()
        writeBackup(out, "k=v", sources(listOf("a", "b")), "0.6.0-test")
        val handled = ArrayList<Int>()
        // 具名参数调用：onProgress 排在 onSession 前是钉死的顺序（保老调用尾随 lambda 兼容）
        readBackup(
            ByteArrayInputStream(out.toByteArray()),
            onProgress = { handled.add(it) },
            onSession = { _, _ -> Unit },
        )
        assertEquals(listOf(1, 2), handled)
    }
}
