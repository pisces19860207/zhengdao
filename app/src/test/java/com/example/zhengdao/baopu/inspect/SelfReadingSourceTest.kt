// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十一批 · 自决账三读数的**取数口**测试（`inspect/HealthSources`）。
//
// 判据（派工单 §③「三读数进体检，分母全是外部事实」）：
// 1. 三行都在（自决率／误自决率／打扰比），数字来自账里的**行数**；
// 2. 一笔都没有 ⇒ 写「无数据」，**不写 0%**（0% 会被读成「一次都没有」）；
// 3. 读不懂的行单独报出来，不许静默丢；
// 4. 这一层只读：读完目录里不许多出任何文件。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.policy.Authorization
import com.example.zhengdao.baopu.policy.Rollback
import com.example.zhengdao.baopu.policy.SelfDecideLog
import com.example.zhengdao.baopu.policy.SelfRequest
import com.example.zhengdao.baopu.policy.SelfRule
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelfReadingSourceTest {

    private val dir = Files.createTempDirectory("self331").toFile()

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    private fun slipRuling(i: Int) = SelfRule.judge(
        SelfRequest(
            actionId = "a-$i",
            action = "改文案$i",
            authorization = Authorization.IN_SCOPE,
            irreversible = true,
            rollback = Rollback.Ready("git checkout"),
            destructive = true,
        ),
    )

    private fun askRuling(i: Int) = SelfRule.judge(
        SelfRequest(actionId = "q-$i", action = "删库$i", authorization = Authorization.IN_SCOPE, irreversible = true),
    )

    private fun writeLog(slips: Int, asks: Int) {
        var log = SelfDecideLog.empty()
        var at = 1_000L
        repeat(slips) { i ->
            log = log.record(slipRuling(i), at)
            at += 1
        }
        repeat(asks) { i ->
            log = log.record(askRuling(i), at)
            at += 1
        }
        java.io.File(dir, HealthSources.SELF_LOG).writeText(log.encode().joinToString("\n"), Charsets.UTF_8)
    }

    @Test
    fun `缺账时三率写无数据_不写0百分比`() {
        assertNull("文件不在时 selfLines 给空表而不是 null", HealthSources.selfLines(dir)?.takeIf { it.isNotEmpty() })
        val lines = HealthSources.selfReadings(dir, ledgerTotal = 0)
        assertEquals(3, lines.size)
        lines.forEach { assertTrue("三行都要说清没有数：$it", it.contains("无数据")) }
        lines.forEach { assertFalse("不许印 0%：$it", it.contains("%")) }
    }

    @Test
    fun `有账时三行都在_数字来自账里的行数`() {
        writeLog(slips = 8, asks = 2)
        val lines = HealthSources.selfReadings(dir, ledgerTotal = 10)
        assertEquals(3, lines.size)
        assertTrue("自决率 8/10：$lines", lines[0].contains("80%") && lines[0].contains("分母 10"))
        assertTrue("误自决率分母是自决单数：$lines", lines[1].contains("分母 8"))
        assertTrue("打扰比 2/10：$lines", lines[2].contains("20%"))
    }

    @Test
    fun `分母现从208账数_不用调用方递`() {
        writeLog(slips = 8, asks = 2)
        java.io.File(dir, DecisionLedger.FILE_NAME).writeText(
            (1..20).joinToString("\n") { "{\"v\":1,\"id\":\"x$it\"}" },
            Charsets.UTF_8,
        )
        val lines = HealthSources.selfReadings(dir)
        assertTrue("8/10 自决率：$lines", lines[0].contains("80%"))
        assertTrue("8 笔自决 ≤ 208 的 20 行，不许报账实不符：$lines", lines.none { it.contains("账实不符") })
    }

    @Test
    fun `读不懂的行单独报出来_不许静默丢`() {
        writeLog(slips = 2, asks = 0)
        val file = java.io.File(dir, HealthSources.SELF_LOG)
        file.writeText(file.readText(Charsets.UTF_8) + "\n这不是一行账", Charsets.UTF_8)
        val lines = HealthSources.selfReadings(dir, ledgerTotal = 2)
        assertEquals(4, lines.size)
        assertTrue("差一行要说出来：$lines", lines.last().contains("1 行读不懂"))
    }

    @Test
    fun `只读_读完目录里不多出任何文件`() {
        writeLog(slips = 3, asks = 1)
        val before = dir.list()!!.sorted()
        HealthSources.selfReadings(dir, ledgerTotal = 4)
        HealthSources.selfLines(dir)
        assertEquals("取数层只读：多出来的东西就是写口", before, dir.list()!!.sorted())
    }
}
