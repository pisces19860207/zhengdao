// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百零九批 · 「零产出」语义修正的门禁。派工单 409 的判据逐条落在这里：
// ①影子跑了零差异 ⇒ 「已运行（零差异）」，不再报零产出；
// ②影子没跑 ⇒ 「未取样」（按需未触发是裁定内的常态，不算零产出）；
// ③「该合并/撤掉」只在「该跑没跑」时出现，零差异之后永远不出现；
// ④暗卫抽检零假 PASS ⇒ 「已抽检（0 假 PASS，正常）」；超期未抽检 ⇒ 未取样；
// ⑤运行记录的「耗时」格落得上、读得回（影子／暗卫／金吾卫三本账各一条）。
//
// 还有一句铁律钉在文案上：体检单不许出现「健康」，零假 PASS 显「正常」——
// 「正常」只许在真抽过且零命中时出现（没跑的未取样不许写成正常）。
package com.example.zhengdao.baopu.inspect

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthSamplingTest {

    // ── 判据①：跑了零差异 ＝ 在干活，不是零产出 ─────────────────────────────

    @Test
    fun `判据①_影子跑了零差异_判在干活_不报零产出`() {
        val v = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 2, lastRunMs = STAMP, problems = 0)),
        )
        assertEquals(OrganState.ACTIVE, v.state)
        assertNull(v.cause)
        assertEquals(Disposition.NONE, v.disposition)
        assertTrue("要显出「已运行（零差异）」：${v.reason}", v.reason.contains("已运行（零差异）"))
        assertTrue("要带上次运行时间：${v.reason}", v.reason.contains("上次"))
        assertFalse("不许报零产出：${v.reason}", v.reason.contains("零产出"))
    }

    @Test
    fun `判据①_影子跑了有差异_差异数照实报_依然在干活`() {
        val v = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 3, lastRunMs = STAMP, problems = 2)),
        )
        assertEquals(OrganState.ACTIVE, v.state)
        assertTrue(v.reason.contains("差异 2 处"))
        // 发现差异是影子的产出（它拦到了），不是"零产出"——两个维度不许混。
        assertFalse(v.reason.contains("零产出"))
    }

    @Test
    fun `判据①_跑了零差异但另有跑不出结论的_两条都照实说`() {
        val v = HealthCheck.organ(
            shadowProbe(
                SampleFacts(kind = SampleKind.SHADOW, runs = 2, lastRunMs = STAMP, problems = 0, unverifiable = 1),
            ),
        )
        assertEquals(OrganState.ACTIVE, v.state)
        assertTrue(v.reason.contains("零差异"))
        assertTrue(v.reason.contains("1 条跑不出结论"))
    }

    // ── 判据②：没跑 ＝ 未取样（不是零产出）────────────────────────────────

    @Test
    fun `判据②_影子没跑且没人要它跑_判未取样_不算零产出`() {
        val v = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 0, lastRunMs = STAMP), byDesign = "按需触发"),
        )
        assertNull("没跑不是第五态，state 留空：${v.state}", v.state)
        assertEquals(Disposition.BY_DESIGN, v.disposition)
        assertTrue(v.reason.contains("未取样"))
        assertFalse("不许报零产出：${v.reason}", v.reason.contains("零产出"))
        assertFalse("不许出「该合并/撤掉」：${v.reason}", v.reason.contains("合并"))
    }

    @Test
    fun `判据②_影子没跑_上次运行时间要显出来`() {
        val v = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 0, lastRunMs = STAMP), byDesign = "按需触发"),
        )
        assertTrue(v.reason.contains("上次"))
        val never = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 0), byDesign = "按需触发"),
        )
        assertTrue("从没跑过要照实说：${never.reason}", never.reason.contains("从没跑过"))
    }

    @Test
    fun `判据②_全窗都是跑不出结论_也算没跑_照实点名`() {
        val v = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 0, lastRunMs = STAMP, unverifiable = 3)),
        )
        assertNull(v.state)
        assertTrue(v.reason.contains("3 次都跑不出结论"))
    }

    // ── 判据③：「该合并/撤掉」只在「该跑没跑」时出现 ─────────────────────────

    @Test
    fun `判据③_该跑没跑_判缺陷并点名`() {
        val v = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 0, lastRunMs = STAMP, shouldHaveRun = true)),
        )
        assertEquals(OrganState.WIRED_NO_OUTPUT, v.state)
        assertEquals(NoOutputCause.DISPATCHED_NO_EFFECT, v.cause)
        assertEquals(Disposition.DEFECT, v.disposition)
        assertTrue(v.reason.contains("该跑没跑"))
    }

    @Test
    fun `判据③_连续两次该跑没跑才到该合并撤掉`() {
        val first = HealthCheck.organ(
            shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 0, shouldHaveRun = true)),
        )
        assertEquals(OrganState.WIRED_NO_OUTPUT, first.state)
        val second = HealthCheck.organ(
            shadowProbe(
                SampleFacts(kind = SampleKind.SHADOW, runs = 0, shouldHaveRun = true),
                previous = OrganState.WIRED_NO_OUTPUT,
            ),
        )
        assertEquals(OrganState.UNUSED_OR_DEAD, second.state)
        assertEquals(Disposition.DEFECT, second.disposition)
    }

    @Test
    fun `判据③_零差异之后永远不出「该合并撤掉」`() {
        // 病根回归：403 报的正是"影子零产出两趟 ⇒ 被判该撤掉"。如今只要它跑过（哪怕零差异），
        // 上一趟的态就不再是 WIRED_NO_OUTPUT，那条提示永远到不了它头上。
        listOf(
            SampleFacts(kind = SampleKind.SHADOW, runs = 2, lastRunMs = STAMP, problems = 0),
            SampleFacts(kind = SampleKind.SHADOW, runs = 0, lastRunMs = STAMP),
        ).forEach { facts ->
            val v = HealthCheck.organ(
                shadowProbe(facts, previous = OrganState.WIRED_NO_OUTPUT),
            )
            assertFalse(
                "跑了零差异/按需没触发不许判该撤掉：${facts} ⇒ ${v.state}",
                v.state == OrganState.UNUSED_OR_DEAD,
            )
        }
    }

    // ── 判据④：暗卫/金吾卫——0 假 PASS 显「正常」；超期 ⇒ 未取样 ────────────

    @Test
    fun `判据④_暗卫抽检零假PASS_显正常_不报零产出`() {
        val v = HealthCheck.organ(
            sampleProbe("不良人", SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0)),
        )
        assertEquals(OrganState.ACTIVE, v.state)
        assertTrue("要显「0 假 PASS」：${v.reason}", v.reason.contains("假 PASS 0"))
        assertTrue("零假 PASS 要显「正常」：${v.reason}", v.reason.contains("正常"))
        assertFalse("不许报零产出：${v.reason}", v.reason.contains("零产出"))
    }

    @Test
    fun `判据④_暗卫抽到假PASS_数字照实报`() {
        val v = HealthCheck.organ(
            sampleProbe("不良人", SampleFacts(kind = SampleKind.SAMPLER, runs = 2, lastRunMs = STAMP, problems = 3)),
        )
        assertEquals(OrganState.ACTIVE, v.state)
        assertTrue(v.reason.contains("假 PASS 3"))
        assertFalse("抽到了问题反而没有「正常」二字：${v.reason}", v.reason.contains("正常"))
    }

    @Test
    fun `判据④_暗卫超期未抽检_判未取样并带天数`() {
        val v = HealthCheck.organ(
            sampleProbe(
                "不良人",
                SampleFacts(kind = SampleKind.SAMPLER, runs = 0, lastRunMs = STAMP, overdue = true, overdueDays = 35),
            ),
        )
        assertNull(v.state)
        assertTrue(v.reason.contains("未取样"))
        assertTrue(v.reason.contains("超期 35 天"))
        assertFalse(v.reason.contains("零产出"))
    }

    @Test
    fun `判据④_暗卫从没抽检_未取样照实说`() {
        val v = HealthCheck.organ(
            sampleProbe("不良人", SampleFacts(kind = SampleKind.SAMPLER, runs = 0)),
        )
        assertNull(v.state)
        assertTrue(v.reason.contains("未取样"))
        assertTrue(v.reason.contains("从没跑过"))
    }

    @Test
    fun `判据④_金吾卫同款_已巡零假PASS显正常`() {
        val v = HealthCheck.organ(
            sampleProbe("金吾卫", SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0)),
        )
        assertEquals(OrganState.ACTIVE, v.state)
        assertTrue(v.reason.contains("已抽检 1 轮（假 PASS 0，正常）"))
    }

    // ── 抽检读数块（体检单上的两个维度）────────────────────────────────────

    @Test
    fun `体检单有抽检读数块_一行一件带上次时间与假PASS数`() {
        val sheet = HealthCheck.sheet(
            0L,
            listOf(
                HealthCheck.organ(shadowProbe(SampleFacts(kind = SampleKind.SHADOW, runs = 1, lastRunMs = STAMP))),
            ),
            emptyList(),
            HealthCheck.signals(SignalInput()),
            emptyList(),
            sampling = listOf("影子：已运行（零差异）·上次 11-03 14:20"),
        )
        assertTrue("块头要点名两个维度", sheet.lines.any { it.contains("抽检读数") && it.contains("上次抽检时间") })
        assertTrue(sheet.lines.any { it.contains("影子：已运行（零差异）·上次 11-03 14:20") })
    }

    @Test
    fun `没有抽检件就不出抽检读数块`() {
        val sheet = HealthCheck.sheet(
            0L,
            listOf(HealthCheck.organ(plainProbe())),
            emptyList(),
            HealthCheck.signals(SignalInput()),
            emptyList(),
        )
        assertFalse(sheet.lines.any { it.contains("抽检读数") })
    }

    @Test
    fun `samplingLine_真抽过零命中才许说正常`() {
        val ran = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 1, lastRunMs = STAMP, problems = 0),
        )
        assertTrue(ran.contains("正常"))
        val notRun = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 0, overdue = true, overdueDays = 9),
        )
        assertFalse("没跑的未取样不许写成正常：$notRun", notRun.contains("正常"))
        assertTrue(notRun.contains("未取样"))
    }

    // ── 运行记录的「耗时」格：落得上、读得回（三本账）──────────────────────

    @Test
    fun `影子运行记录带耗时_落账读回一致`() {
        val entry = ShadowEntry(
            id = "sh:t1",
            atMs = STAMP,
            sessionId = 7L,
            step = 3,
            riskWire = ShadowLedger.RISK_NONE,
            reversible = true,
            actions = listOf("write_file:a/b.md"),
            claimed = ImpactShape(),
            observed = ImpactShape(),
            items = emptyList(),
            verdict = ShadowVerdict.CONSISTENT,
            modelLine = "",
            elapsedMs = 1234L,
        )
        assertEquals(1234L, ShadowLedger.parseLine(entry.toJson().toString())!!.elapsedMs)
    }

    @Test
    fun `影子旧口径行没有耗时格_读回0_不冒充量过`() {
        // 409 之前的存量行形状：没有 ms 格；读回 0（追加式账本不回头改行）。
        val raw =
            """{"id":"sh:old","at":1,"session":-1,"step":0,"risk":"none","reversible":true,"actions":[],"claimed":{},"observed":{},"items":[],"verdict":"consistent","model_line":""}"""
        assertEquals(0L, ShadowLedger.parseLine(raw)!!.elapsedMs)
    }

    @Test
    fun `暗卫运行记录带耗时_落账读回一致`() {
        val round = NightRound(
            id = "night:1",
            atMs = STAMP,
            bucket = 1L,
            inspected = 5,
            hits = 0,
            noData = 0,
            emergency = false,
            elapsedMs = 88L,
        )
        assertEquals(88L, NocturnalLedger.parse(NocturnalLedger.toJson(round).toString())!!.elapsedMs)
    }

    @Test
    fun `金吾卫报告带假PASS数与耗时_落账读回一致`() {
        val digest = PatrolDigest(
            id = "guard:1",
            atMs = STAMP,
            conclusion = listOf("巡 6 项：命中 0，查不了 0"),
            counters = listOf("命中证据：记录 0／轨迹 0／其它 0"),
            issues = emptyList(),
            hits = 0,
            elapsedMs = 456L,
        )
        val back = GuardLedger.parse(GuardLedger.toJson(digest).toString())!!
        assertEquals(0, back.hits)
        assertEquals(456L, back.elapsedMs)
    }

    // ── 铁律：体检单不许出现「健康」────────────────────────────────────────

    @Test
    fun `抽检读数不出现健康二字`() {
        val line = HealthCheck.samplingLine(
            "不良人",
            SampleFacts(kind = SampleKind.SAMPLER, runs = 3, lastRunMs = STAMP, problems = 0),
        )
        assertFalse("「正常」是裁定的话术，「健康」不许回潮：$line", line.contains("健康"))
    }

    // ── 工厂 ───────────────────────────────────────────────────────────────

    private fun shadowProbe(
        facts: SampleFacts,
        previous: OrganState? = null,
        byDesign: String? = null,
    ) = probe("影子", facts, previous, byDesign)

    private fun sampleProbe(
        name: String,
        facts: SampleFacts,
        previous: OrganState? = null,
    ) = probe(name, facts, previous)

    private fun probe(
        name: String,
        sampling: SampleFacts?,
        previous: OrganState? = null,
        byDesign: String? = null,
    ) = OrganProbe(
        name = name,
        entry = "X.y",
        declaredIn = "app/src/main/java/x/X.kt",
        callSites = 1,
        sink = "账本.jsonl",
        outputs = null,
        previous = previous,
        byDesign = byDesign,
        evidence = listOf("X.kt:1 证据"),
        sampling = sampling,
    )

    /** 抽检件之外的老路不受影响（同一把判据按 sampling 是否在场分道）。 */
    private fun plainProbe() = OrganProbe(
        name = "试件",
        entry = "X.y",
        declaredIn = "app/src/main/java/x/X.kt",
        callSites = 1,
        sink = "账本.jsonl",
        outputs = 4,
        lastOutputMs = STAMP,
        evidence = listOf("X.kt:1 证据"),
    )

    private companion object {
        const val STAMP = 1_790_000_000_000L
    }
}
