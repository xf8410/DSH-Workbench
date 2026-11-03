package com.hualuo.engine.transfer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 准入测试的立场就一句话：什么文件都得能传（用户原话），
 * 所以这里既测"奇怪名字全部收"，也测"报告不许少报"，还测"将来谁想加类型白名单会红"。
 *
 * 带表情的文件名照样要测，但源码里不写裸表情（家规，闸门 NoEmojiInSourceTest）：
 * 样本用码位拼出来（见 GRINNING_NAME），运行时仍是真表情文件名。
 */
class FileAdmissionTest {

    private fun candidate(
        path: String,
        size: Long = 10L,
        readable: Boolean = true,
        regular: Boolean = true,
        inside: Boolean = true,
        symlink: Boolean = false,
        name: String = path.substringAfterLast('/'),
    ) = FileCandidate(
        path = path,
        name = name,
        sizeBytes = size,
        readable = readable,
        isRegularFile = regular,
        insideGrant = inside,
        isSymbolicLink = symlink,
    )

    private val plain = FileAdmission()

    @Test
    fun fileAdmissionRejectsOnlyHardFacts() {
        // 拒绝理由是个封闭小集合，且一个都不能带类型/格式味道。
        // 想新增 UnknownType、BadExtension、MimeNotAllowed 这类理由，这条会先拦住你。
        assertEquals(
            listOf("NotReadable", "OutsideGrant", "NotAFile", "TooLarge", "Unnameable"),
            RejectReason.entries.map { it.name },
        )
        val typeFlavoured = listOf("type", "ext", "mime", "format", "suffix", "kind", "allow", "white", "black")
        for (reason in RejectReason.entries) {
            val lower = reason.name.lowercase()
            assertTrue("拒绝理由不许是类型判断：${reason.name}", typeFlavoured.none { it in lower })
        }
    }

    @Test
    fun everyWeirdNameIsAdmitted() {
        val names = listOf(
            "noext", "archive.tar.gz", "photo.JPG", "脚本.sh", "a b c.txt",
            "中文文件名（最终版）.docx", "con", "nul.txt", "file.", "-rf", "..weird",
            ".hidden", GRINNING_NAME, "data.par2", "x".repeat(200), "!@#\$%^&()[]{}'=,;",
            "global-metadata.dat", "lib.so", "boot.img", "录像.MP4", "无扩展名",
        )
        for (name in names) {
            val verdict = plain.admit(candidate("/sdcard/$name", name = name))
            assertTrue("这种名字必须收：$name（$verdict）", verdict is Admission.Accepted)
        }
    }

    @Test
    fun zeroByteAndHugeFilesAreBothFine() {
        assertTrue(
            "零字节文件必须收（旧仓丢过空文件）",
            plain.admit(candidate("/sdcard/empty", size = FileCandidate.ZERO_BYTES)) is Admission.Accepted,
        )
        val twoGb = 2L * 1024 * 1024 * 1024
        assertTrue(
            "2GB 不是拒绝理由（不设上限时）",
            plain.admit(candidate("/sdcard/big.zip", size = twoGb)) is Admission.Accepted,
        )
    }

    @Test
    fun unknownSizeIsNeverTreatedAsTooLarge() {
        val strict = FileAdmission(maxFileBytes = 100L)

        val verdict = strict.admit(candidate("/sdcard/streaming", size = FileCandidate.SIZE_UNKNOWN))

        assertTrue("大小还不知道就判超限是猜，不许：$verdict", verdict is Admission.Accepted)
    }

    @Test
    fun explicitPerFileLimitIsHonoredAndSaidLoudly() {
        val strict = FileAdmission(maxFileBytes = 100L)

        val verdict = strict.admit(candidate("/sdcard/over.txt", size = 101L)) as Admission.Rejected

        assertEquals(RejectReason.TooLarge, verdict.reason)
        assertTrue("得带上限与实际值：${verdict.detail}", verdict.detail.contains("100") && verdict.detail.contains("101"))
        assertTrue(verdict.path.endsWith("over.txt"))
    }

    @Test
    fun unreadableAndOutsideGrantComeBackWithPaths() {
        val unreadable = plain.admit(candidate("/sdcard/no-perm.bin", readable = false)) as Admission.Rejected
        assertEquals(RejectReason.NotReadable, unreadable.reason)
        assertTrue(unreadable.detail.contains("/sdcard/no-perm.bin"))

        val outside = plain.admit(candidate("/data/data/other/app.bin", inside = false)) as Admission.Rejected
        assertEquals(RejectReason.OutsideGrant, outside.reason)
        assertTrue("得说清是越界：${outside.detail}", outside.detail.contains("授权"))
    }

    @Test
    fun namesThatCannotGoIntoAnArchiveAreRejected() {
        // 尾名为空或只有点号：从路径就能推出来
        for (path in listOf("/sdcard/", "/sdcard/.", "/sdcard/..")) {
            val verdict = plain.admit(candidate(path))
            assertTrue("放不进产物的名字该拒：$path", verdict is Admission.Rejected)
            assertEquals(RejectReason.Unnameable, (verdict as Admission.Rejected).reason)
        }

        // 名字里带斜杠或 NUL：只能直接构造（从路径尾名推不出来）。
        // 真实来源是 SAF 文档名与 zip 条目名，它们可以带斜杠，这种名字进清单会毁掉整个产物。
        for (badName in listOf("a/b", "a\u0000b", "", ".", "..")) {
            val verdict = plain.admit(candidate("/sdcard/x", name = badName))
            assertTrue("这种名字进不了产物清单：[$badName]", verdict is Admission.Rejected)
            assertEquals(RejectReason.Unnameable, (verdict as Admission.Rejected).reason)
        }
    }

    @Test
    fun collectReportsEveryCandidateAndNeverLies() {
        val candidates = listOf(
            candidate("/sdcard/a.log"),
            candidate("/sdcard/b.apk"),
            candidate("/sdcard/c"),
            candidate("/sdcard/somedir", regular = false),
            candidate("/sdcard/locked", readable = false),
        )

        val report = plain.collect(candidates)

        assertEquals(5, report.candidateCount)
        assertEquals(3, report.admitted.size)
        assertEquals(2, report.skipped.size)
        val summary = report.summary()
        assertTrue("汇总要写「入选 3」：$summary", summary.contains("入选 3"))
        assertTrue("汇总要写「跳过 2」：$summary", summary.contains("跳过 2"))
        assertTrue("被跳过的要能点名：$summary", summary.contains("somedir") && summary.contains("locked"))
    }

    @Test
    fun directoriesAreNotSilentlyVanishing() {
        val report = plain.collect(listOf(candidate("/sdcard/dir", regular = false)))

        assertEquals(0, report.admitted.size)
        assertEquals(1, report.skipped.size)
        assertEquals(RejectReason.NotAFile, report.skipped.first().reason)
        assertTrue("有跳过项时总数只能算估算", report.hasEstimate)
    }

    @Test
    fun batchLimitDropsOnlyWhatDoesNotFitAndNamesIt() {
        val budgeted = FileAdmission(maxTotalBytes = 150L)
        val report = budgeted.collect(
            listOf(
                candidate("/sdcard/one", size = 100L),
                candidate("/sdcard/two", size = 60L),
                candidate("/sdcard/three", size = 10L),
            ),
        )

        assertEquals("装不下的跳掉，后面装得下的照样收", listOf("one", "three"), report.admitted.map { it.name })
        assertEquals(1, report.skipped.size)
        assertEquals("two", report.skipped.first().tailName())
        assertTrue("得说是总量上限：${report.skipped.first().detail}", report.skipped.first().detail.contains("总量上限"))
    }

    @Test
    fun unknownSizesNeverTripTheBatchLimitButMarkEstimate() {
        val budgeted = FileAdmission(maxTotalBytes = 50L)
        val report = budgeted.collect(
            listOf(
                candidate("/sdcard/s1", size = FileCandidate.SIZE_UNKNOWN),
                candidate("/sdcard/s2", size = FileCandidate.SIZE_UNKNOWN),
                candidate("/sdcard/s3", size = FileCandidate.SIZE_UNKNOWN),
            ),
        )

        assertEquals(3, report.admitted.size)
        assertEquals(3, report.unknownSizeCount)
        assertTrue(report.hasEstimate)
        assertEquals(0L, report.knownBytes)
        assertTrue("汇总要提示进度只能估算：${report.summary()}", report.summary().contains("估算"))
    }

    @Test
    fun symlinksAreAdmittedAndCounted() {
        val report = plain.collect(
            listOf(
                candidate("/sdcard/link1", symlink = true),
                candidate("/sdcard/normal"),
            ),
        )

        assertEquals(2, report.admitted.size)
        assertEquals(1, report.symlinkCount)
        assertTrue("得让用户知道顺着链接读了别处：${report.summary()}", report.summary().contains("符号链接"))
    }

    @Test
    fun limitsMustBeSaneNumbers() {
        try {
            FileAdmission(maxFileBytes = 0L)
            throw AssertionError("0 当上限本该抛")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("正数"))
        }
        try {
            FileAdmission(maxTotalBytes = -5L)
            throw AssertionError("负数当上限本该抛")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("正数"))
        }
    }

    @Test
    fun emptyBatchIsNotAnError() {
        val report = plain.collect(emptyList())

        assertEquals(0, report.candidateCount)
        assertEquals(0L, report.knownBytes)
        assertFalse(report.hasEstimate)
    }

    /** 取路径尾名做断言用，省得每处都 substring。 */
    private fun Admission.Rejected.tailName(): String = path.substringAfterLast('/')

    companion object {
        /** 表情文件名的样本：按码位拼，源码保持纯 ASCII（家规：禁裸表情）。 */
        private val GRINNING_NAME: String = "emoji" + String(Character.toChars(0x1F600)) + ".png"
    }
}
