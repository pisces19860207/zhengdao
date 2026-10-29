// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第二百七十七批 §3（L33 最小版：不弹卡时落一条"为什么判为不必打扰"
// 进追认队列）＋ §判据④（30 天后仍可读）＋ §负例（自决单缺理由）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.Severity
import com.example.zhengdao.baopu.inspect.Source
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SelfRulingLedgerTest {

    private lateinit var root: File

    /** 固定时刻（算"日"只用 24h 整数倍跨日，避免时区边界）。 */
    private val now = 1_700_000_000_000L

    private val dayMs = 24L * 3600 * 1000

    @Before
    fun setUp() {
        root = Files.createTempDirectory("self-ruling").toFile()
        SelfRulingLedger.resetForTest()
        DecisionLedger.install(root)
    }

    @After
    fun tearDown() {
        DecisionLedger.uninstallForTest()
        SelfRulingLedger.resetForTest()
    }

    private fun opinion() = Opinion(
        source = Source.CLASSIFIER,
        severity = Severity.MAJOR,
        gist = "未分类",
        action = ActionCode.REVIEW,
        lawRef = LawRef.L7,
        evidence = listOf("risk:unclassified_tool"),
    )

    private fun note(reason: String = "无法可依且不致命：记档不打扰", at: Long = now) =
        SelfRulingLedger.note(
            opinion = opinion(),
            opinionId = "op:$at:1:1",
            round = "process",
            tool = "write_file",
            why = "no_law",
            reason = reason,
            now = at,
        )

    @Test
    fun `判据_不打扰要留理由_落在追认队列里`() {
        val row = note()
        assertNotNull("合规的自决单必须落下来", row)
        assertEquals("no_law", row!!.why)
        assertEquals("无法可依且不致命：记档不打扰", row.reason)
        assertEquals("op:$now:1:1", row.opinionId)
        val rows = SelfRulingLedger.replay()
        assertEquals(1, rows.size)
        assertEquals(row.id, rows[0].id)
    }

    @Test
    fun `负例_自决单缺理由不许落账`() {
        assertNull("空理由必须不落", note(reason = ""))
        assertNull("空白理由必须不落", note(reason = "   \n "))
        assertTrue("缺理由的那些一条都不该进账", SelfRulingLedger.replay().isEmpty())
    }

    @Test
    fun `判据_重启之后追认队列仍可读`() {
        note()
        // 模拟重启：内存清空，但盘上那份还在（root 没动）
        SelfRulingLedger.resetForTest()
        assertEquals(1, SelfRulingLedger.replay().size)
    }

    @Test
    fun `判据_三十天后仍可读_回放不设时间窗`() {
        note(at = now)
        SelfRulingLedger.resetForTest()
        // 40 天后读：账本没有"过期"这个概念（判据④）
        val rows = SelfRulingLedger.replay()
        assertEquals(1, rows.size)
        assertTrue(rows[0].atMs < now + 40 * dayMs)
        // 也确认 id 里没被改写成"今天"
        assertTrue(rows[0].id.startsWith("sr:$now:"))
    }

    @Test
    fun `判据_与意见判定账同目录但不同文件`() {
        note()
        val file = SelfRulingLedger.fileIn(root)
        assertTrue("追认队列必须落在装配的那棵目录", file.isFile)
        assertEquals(root, file.parentFile)
        assertEquals("追认队列.jsonl", file.name)
    }

    @Test
    fun `判据_坏行跳过_缺理由的老行不许被当成有效自决单`() {
        note()
        // 手工塞一行缺 reason 的账（模拟历史坏数据）
        SelfRulingLedger.fileIn(root).appendText("{\"id\":\"sr:bad\",\"at\":$now}\n", Charsets.UTF_8)
        val rows = SelfRulingLedger.replay()
        assertEquals(1, rows.size)
        assertFalse(rows.any { it.id == "sr:bad" })
    }

    @Test
    fun `判据_日志那行带理由_验收靠grep这一句`() {
        val row = note()!!
        val line = SelfRulingLedger.logLine(row)
        assertTrue(line.contains("no_law"))
        assertTrue(line.contains("记档不打扰"))
    }
}
