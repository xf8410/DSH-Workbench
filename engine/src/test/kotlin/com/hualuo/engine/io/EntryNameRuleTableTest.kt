package com.hualuo.engine.io

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 条目名判定对照表：一行一个名字，既打印实际判定（红的时候能当场看见真相），也逐行断言（平时的回归锁）。
 *
 * 这张表的来历（全部可在 CI 日志复核）：
 *   run 34658873187：4 条断言红，静态推理说"应该绿"，而 Gradle 短格式不打印原因；
 *   run 34690750236：摊成这张表之后真相出来了——**闸门对 20 个名字的判定全部正确**，
 *   红的是测试脚手架自己：`TemporaryFolder.newFolder("固定名")` 在同一个测试里被调第二次
 *   就抛 IllegalStateException("a folder with the path '包根目录' already exists")。
 *   所以这张表现在同时是**行为契约**（不许有人把盘符/UNC/双层编码的拦截改松）和**防再犯**。
 *
 * 与 ExtractGuardTest 的分工：那边管"每条事故各一条人话断言"，这边管"整片形状一次性对齐"。
 */
class EntryNameRuleTableTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val root: File by lazy { folder.newFolder("对照表根目录") }

    /**
     * @param keyword 期望拒绝理由里出现的关键字；写 null 表示这一行**必须放行**。
     * @param segments 放行时期望的段数（顺便钉住"多个连续斜杠不会凭空多出一层目录"）。
     */
    private data class Row(val name: String, val keyword: String?, val segments: Int = 0)

    private val rows = listOf(
        Row("../逃出去的文件.bin", "跳出目标目录"),
        Row("..\\..\\w.exe", "跳出目标目录"),
        Row("..", "跳出目标目录"),
        Row("./..", "跳出目标目录"),
        Row("a/../../b", "跳出目标目录"),
        Row("..%2f..%2fetc%2fpasswd", "跳出目标目录"),
        Row("%2e%2e%2f坏东西.bin", "跳出目标目录"),
        Row("%252e%252e%252f坏东西.bin", "跳出目标目录"),
        Row("%2e%2e%2f%252e%252e%2f.bin", "跳出目标目录"),
        Row("x".repeat(300) + "/../坏.bin", "跳出目标目录"),
        Row("/etc/hosts", "绝对路径"),
        Row("//服务器/共享/文件.bin", "网络共享"),
        Row("\\\\服务器\\共享\\文件.bin", "反斜杠开头"),
        Row("C:/Windows/x.bin", "盘符"),
        Row("C|/Windows/x.bin", "盘符"),
        Row("../伪造\n一行日志", "控制字符"),
        Row("带\t制表符.bin", "控制字符"),
        Row("换%0a行.bin", "控制字符"),
        Row("", "去空白之后是空的"),
        Row("   ", "去空白之后是空的"),
        Row("./子包//再下一层/文件.bin", null, 3),
        Row("正常/文件.bin", null, 2),
        Row("空目录条目/", null, 1),
    )

    /** 让不可见字符在日志里现出原形。先转义反斜杠，否则 `\n` 会被读成两个字符。 */
    private fun visible(text: String): String = text
        .replace("\\", "\\\\")
        .replace("\r", "\\r")
        .replace("\n", "\\n")
        .replace("\t", "\\t")

    @Test
    fun `对照表逐行核对且打印实际判定`() {
        println(
            "环境 file.encoding=" + System.getProperty("file.encoding") +
                " sun.jnu.encoding=" + System.getProperty("sun.jnu.encoding"),
        )
        println("根目录=" + root.path + " 存在=" + root.exists())
        var rejected = 0
        var accepted = 0
        rows.forEachIndexed { index, row ->
            val where = "第 ${index + 1} 行「${visible(row.name)}」"
            val ruling = ruleOutEntryName(row.name)
            val verdict = when (ruling) {
                is NameRuling.Rejected -> {
                    rejected += 1
                    "拒绝：" + ruling.reason
                }
                is NameRuling.Accepted -> {
                    accepted += 1
                    "放行：段=" + ruling.segments.joinToString("|")
                }
            }
            println(
                "%02d 名=[%s] 还原=[%s] 期望=[%s] 实际=%s".format(
                    index + 1,
                    visible(row.name),
                    visible(percentDecodeName(row.name).trim()),
                    row.keyword ?: "放行 ${row.segments} 段",
                    verdict,
                ),
            )
            if (row.keyword == null) {
                assertTrue("$where 必须放行，实际：$verdict", ruling is NameRuling.Accepted)
                val segments = (ruling as NameRuling.Accepted).segments
                assertEquals("$where 段数不对，实际：$verdict", row.segments, segments.size)
                // 放行的那一行必须真能落回根目录里面，不只是字符串层面好看
                val landed = normalizeEntryPath(root, row.name)
                assertTrue("$where 落点跑出根目录：${landed.path}", landed.path.startsWith(root.path))
            } else {
                assertTrue("$where 必须被拒，实际：$verdict", ruling is NameRuling.Rejected)
                val reason = (ruling as NameRuling.Rejected).reason
                assertTrue("$where 拒了但理由不对，实际：$reason", reason.contains(row.keyword))
            }
        }
        println("合计：拒绝 $rejected 行，放行 $accepted 行，共 ${rows.size} 行")
        assertTrue("对照表一共 ${rows.size} 行，少于 20 行说明名单被误删", rows.size >= 20)
        assertTrue(
            "表里必须既有拒绝也有放行，全是同一边就说明闸或表坏了：拒绝=$rejected 放行=$accepted",
            rejected > 0 && accepted > 0,
        )
    }
}
