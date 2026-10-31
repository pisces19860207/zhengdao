// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十九批 · 灾异证据源（AnomalyFeed）的行为门禁——工单口径逐条：
// 只发事实事件、不判决；三类闭集；每件事必须带出处（账行 id 或 文件:行），无出处不发；
// 连 3 次 FAIL 含不可逆才发；账 append-only、不设时间窗。
//
// ## 正例
// 三类各发一条（账行 id 与 文件:行 两种出处形状都收）；事件只有事实列（JSON 七键，
// 没有「判决」列）；账落盘 append-only、内存清了盘还在。
//
// ## 负例（造违规即红，红在生产采集器上）
// 无出处（空白／无冒号／无数字锚）⇒ 不发不落账；事实空 ⇒ 不发；
// 连 3 次 FAIL 全可逆 ⇒ 不发；无出处的 FAIL 不计数；三连发过即归零。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.law.LawIo
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AnomalyFeedTest {

    private val t0 = 1_800_000_000_000L
    private lateinit var root: java.io.File

    @Before
    fun setUp() {
        AnomalyFeed.resetForTest()
        root = Files.createTempDirectory("zd339-root").toFile()
        DecisionLedger.install(root)
    }

    @After
    fun tearDown() {
        AnomalyFeed.resetForTest()
        DecisionLedger.uninstallForTest()
        root.deleteRecursively()
    }

    // ── 正例：三类各发一条，两种出处形状都收，账落盘 ─────────────────────────────

    @Test
    fun `三类各发一条_两种出处形状都收_事件只有事实列`() {
        // 账行 id 形状（op:/em: 都行）
        val mismatch = AnomalyFeed.report(
            AnomalyFactKind.LEDGER_MISMATCH,
            provenance = "op:1800000000000:42",
            gist = "账上说已删除 7 个文件，实地 7 个都在",
            nowMs = t0,
            evidence = listOf("ledger:deleted=7", "fact:found=7"),
        )
        assertNotNull("账行 id 形状的出处收得下", mismatch)
        assertEquals(AnomalyFactKind.LEDGER_MISMATCH, mismatch!!.kind)
        assertEquals("op:1800000000000:42", mismatch.provenance)
        // 文件:行 形状
        val overScope = AnomalyFeed.report(
            AnomalyFactKind.OVER_SCOPE,
            provenance = "agent/RunEngine.kt:1453",
            gist = "授权范围外的目录出现了写入",
            nowMs = t0 + 1,
        )
        assertNotNull("文件:行 形状的出处收得下", overScope)
        assertEquals(AnomalyFactKind.OVER_SCOPE, overScope!!.kind)
        // 事件只有事实列：JSON 七键，没有「判决」列（不判决的形状面）
        val keys = mismatch.toJson().keys().asSequence().toSet()
        assertEquals(
            "事实事件的列必须是这七样（没有判决列）",
            setOf("v", "id", "at", "kind", "src", "gist", "ev"),
            keys,
        )
        // 账落盘：回放查得到（盘是权威）
        assertEquals(2, AnomalyFeed.replay().size)
        AnomalyFeed.resetForTest() // 内存清空：盘是权威
        assertEquals("内存清了盘还在（append-only 跨内存）", 2, AnomalyFeed.replay().size)
    }

    // ── 负例：无出处不发 ─────────────────────────────────────────────────────────

    @Test
    fun `无出处不发_空白_无冒号_无数字锚都拒_且不落账`() {
        listOf(
            "   ",                      // 空白
            "感觉很糟",                  // 连冒号都没有
            "感觉:很糟",                 // 有冒号没数字锚
            "RunEngine.kt:",            // 冒号后面没东西
            ":123",                     // 有锚没主体
        ).forEach { src ->
            assertNull(
                "无出处必须不发（出处=「$src」）",
                AnomalyFeed.report(AnomalyFactKind.LEDGER_MISMATCH, provenance = src, gist = "x", nowMs = t0),
            )
        }
        assertNull(
            "事实为空同样不发",
            AnomalyFeed.report(AnomalyFactKind.OVER_SCOPE, provenance = "op:1:1", gist = "  ", nowMs = t0),
        )
        assertTrue("拒收的事件不许偷偷进账", AnomalyFeed.replay().isEmpty())
    }

    // ── 连 3 次 FAIL：含不可逆才发、成功打断、无出处不计数、发过归零 ─────────────

    @Test
    fun `连3FAIL_全可逆不发_含不可逆第三次发_发过归零_成功打断`() {
        // 两连 FAIL（可逆）＋一笔成功 ⇒ 打断，之后再来两连也不发
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:1:1", nowMs = t0)
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:1:2", nowMs = t0 + 1)
        AnomalyFeed.observeExecutionOk()
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:1:3", nowMs = t0 + 2)
        assertNull("2 连 + 打断后 2 连：都不发", AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:1:4", nowMs = t0 + 3))
        assertTrue(AnomalyFeed.replay().isEmpty())
        // 三连 FAIL 全是可逆 ⇒ 第 3 次也不发（口径：含不可逆才发）
        AnomalyFeed.observeExecutionOk()
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:2:1", nowMs = t0 + 10)
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:2:2", nowMs = t0 + 11)
        assertNull("三连全可逆不发", AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:2:3", nowMs = t0 + 12))
        // 三连、第二笔不可逆 ⇒ 第 3 次到线就发，出处＝最近一次失败的锚
        AnomalyFeed.observeExecutionOk()
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:3:1", nowMs = t0 + 20)
        AnomalyFeed.observeExecutionFail(irreversible = true, provenance = "op:3:2", nowMs = t0 + 21)
        val streak = AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:3:3", nowMs = t0 + 22)
        assertNotNull("连 3 次且含不可逆 ⇒ 发", streak)
        assertEquals(AnomalyFactKind.FAIL_STREAK, streak!!.kind)
        assertEquals("出处＝最近一次失败的锚", "op:3:3", streak.provenance)
        assertTrue("机器键盖 streak:3", streak.evidence.contains("streak:3"))
        assertTrue("机器键盖 irreversible:true", streak.evidence.contains("irreversible:true"))
        // 发过归零：再来两连含不可逆也不发（新一轮要从 1 数起）
        AnomalyFeed.observeExecutionFail(irreversible = true, provenance = "op:4:1", nowMs = t0 + 30)
        assertNull("发过即归零，2 连不发", AnomalyFeed.observeExecutionFail(irreversible = true, provenance = "op:4:2", nowMs = t0 + 31))
        // 无出处的 FAIL 不计数：两连 + 一笔无出处 + 第三笔有效 ⇒ 照样到线（没被没出处的打断或续命）
        AnomalyFeed.observeExecutionOk()
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:5:1", nowMs = t0 + 40)
        AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "op:5:2", nowMs = t0 + 41)
        assertNull("无出处的 FAIL 连计数都不进", AnomalyFeed.observeExecutionFail(irreversible = false, provenance = "   ", nowMs = t0 + 42))
        val after = AnomalyFeed.observeExecutionFail(irreversible = true, provenance = "op:5:3", nowMs = t0 + 43)
        assertNotNull("有效第三笔照常到线（无出处那笔没续也没断）", after)
    }

    // ── 源级：只发事实、不判决（AnomalyFeed 不许引用判定层） ─────────────────────

    @Test
    fun `源级_证据源不许引用判定层_出处锚判据可独立执行`() {
        // 防空转：扫描器必须真的看见了这份生产源
        LawIo.assertNotBlind(LawIo.MAIN_ROOT, minFiles = 100, minLines = 10_000)
        val feed = LawIo.stripComments(
            LawIo.read("src/main/java/com/example/zhengdao/baopu/inspect/AnomalyFeed.kt"),
        )
        assertFalse(
            "证据源不许引用 AnomalyGate（只发事实、不判决——判决是判定层的辖地）",
            feed.contains("AnomalyGate"),
        )
        // 出处锚判据可独立执行（工单口径的两形状收、四形状拒）
        assertNotNull(AnomalyFeed.provenanceAnchor("em:1700000000000:7"))
        assertNotNull(AnomalyFeed.provenanceAnchor("baopu/law/AnomalyGate.kt:133"))
        assertNull(AnomalyFeed.provenanceAnchor("据说"))
        assertNull(AnomalyFeed.provenanceAnchor("op:1:"))
        // 回放不设时间窗：老时刻的事件照样在
        val old = AnomalyFeed.report(
            AnomalyFactKind.LEDGER_MISMATCH,
            provenance = "op:9:9",
            gist = "很早以前的账实不符",
            nowMs = 1_000L,
        )
        assertNotNull(old)
        assertTrue("30 天后回放仍在（不设时间窗）", AnomalyFeed.replay().any { it.id == old!!.id })
    }
}
