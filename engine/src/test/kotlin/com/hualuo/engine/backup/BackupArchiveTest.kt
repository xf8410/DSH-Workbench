package com.hualuo.engine.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 备份包的账：写读往返一字不差、身份对不上就拒收、认不出的条目列名照报、
 * 危险 id 写不进包也进不了本地、回调只读一半引擎自己排干。
 */
class BackupArchiveTest {

    /**
     * 返回「读包回执 + 回调收到的会话内容」两个账本。
     * （首版把 seen 关在 helper 里、测试体却引用它——CI 编译段抓过，run 35097676942。）
     */
    private fun roundTrip(
        settingsText: String,
        sessions: List<BackupSessionSource>,
        appVersion: String = "0.4.0 (4)",
    ): Pair<BackupReadResult, Map<String, String>> {
        val bytes = ByteArrayOutputStream()
        writeBackup(bytes, settingsText, sessions, appVersion)
        val seen = LinkedHashMap<String, String>()
        val result = readBackup(ByteArrayInputStream(bytes.toByteArray())) { id, stream ->
            seen[id] = stream.readBytes().toString(Charsets.UTF_8)
        }
        return result to seen
    }

    /** 造一个陌生 zip：没 manifest、条目名字五花八门，甚至带路径意外的会话名。 */
    private fun foreignZip(): ByteArray {
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry("something-else.txt"))
            zip.write("hello".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("sessions/..%2Fevil.jsonl"))
            zip.write("nope".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        return out.toByteArray()
    }

    @Test
    fun roundTripKeepsEverythingVerbatim() {
        val (result, seen) = roundTrip(
            settingsText = "# ui\nprovider.name=测试端\nprovider.base_url=https://gw.example.com",
            sessions = listOf(
                BackupSessionSource("s-1") { "user: 你好\nassistant: 好".byteInputStream() },
                BackupSessionSource("s-2.b_9") { "half line".byteInputStream() },
            ),
        )

        assertEquals(BACKUP_FORMAT, result.manifest?.format)
        assertEquals(BACKUP_VERSION, result.manifest?.version)
        assertEquals("0.4.0 (4)", result.manifest?.appVersion)
        assertEquals(2, result.manifest?.sessionCount)
        assertEquals("设置原文一字不差", "# ui\nprovider.name=测试端\nprovider.base_url=https://gw.example.com", result.settingsText)
        assertEquals(2, result.sessionsHandled)
        assertEquals("user: 你好\nassistant: 好", seen["s-1"])
        assertEquals("half line", seen["s-2.b_9"])
        assertTrue(result.skippedEntries.isEmpty())
    }

    @Test
    fun emptyBackupStillCarriesIdentity() {
        val (result, seen) = roundTrip(settingsText = "", sessions = emptyList())
        assertEquals(BACKUP_FORMAT, result.manifest?.format)
        assertEquals("", result.settingsText)
        assertEquals(0, result.sessionsHandled)
        assertTrue(seen.isEmpty())
    }

    @Test
    fun foreignZipIsNotOursAndGetsListed() {
        val result = readBackup(ByteArrayInputStream(foreignZip())) { _, _ ->
            fail("陌生包不该把任何条目当会话回调进来")
        }
        assertNull("没有 manifest 就不是本家的包", result.manifest)
        assertNull("settings 也没有", result.settingsText)
        assertEquals(0, result.sessionsHandled)
        assertTrue("认不出的条目要列出名字", result.skippedEntries.contains("something-else.txt"))
    }

    @Test
    fun dangerousSessionIdsAreRefusedOnWrite() {
        val bytes = ByteArrayOutputStream()
        try {
            writeBackup(
                bytes,
                "a=b",
                listOf(BackupSessionSource("../evil") { "x".byteInputStream() }),
                "v",
            )
            fail("路径意外的 id 不许进包")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("不安全"))
        }
    }

    @Test
    fun unsafeSessionEntryIsSkippedOnRead() {
        // 会话条目名带斜杠（a/b.jsonl）——读包时不许落成文件
        val out = ByteArrayOutputStream()
        java.util.zip.ZipOutputStream(out).use { zip ->
            zip.putNextEntry(java.util.zip.ZipEntry(MANIFEST_ENTRY))
            zip.write(
                ("{\"format\":\"$BACKUP_FORMAT\",\"version\":$BACKUP_VERSION,\"createdAt\":\"t\"," +
                    "\"sessionCount\":1,\"appVersion\":\"v\"}").toByteArray(Charsets.UTF_8),
            )
            zip.closeEntry()
            zip.putNextEntry(java.util.zip.ZipEntry("sessions/a/b.jsonl"))
            zip.write("nope".toByteArray(Charsets.UTF_8))
            zip.closeEntry()
        }
        val result = readBackup(ByteArrayInputStream(out.toByteArray())) { _, _ ->
            fail("不安全的 id 不该回调进来")
        }
        assertEquals(BACKUP_FORMAT, result.manifest?.format)
        assertEquals(0, result.sessionsHandled)
        assertTrue(result.skippedEntries.isNotEmpty())
    }

    @Test
    fun truncatedCallbackStillAdvances() {
        // 回调故意只读 16 字节：引擎要自己排干剩余字节，后面的条目照样读得到
        val bytes = ByteArrayOutputStream()
        val big = "x".repeat(200_000)
        writeBackup(
            bytes,
            "k=v",
            listOf(BackupSessionSource("big") { big.byteInputStream() }),
            "v",
        )
        val seen = LinkedHashMap<String, Int>()
        val result = readBackup(ByteArrayInputStream(bytes.toByteArray())) { id, stream ->
            seen[id] = stream.readNBytes(16).toString(Charsets.UTF_8).length
        }
        assertEquals(1, result.sessionsHandled)
        assertEquals(16, seen["big"])
        assertEquals("排干之后 settings 照样完整读到", "k=v", result.settingsText)
    }
}
