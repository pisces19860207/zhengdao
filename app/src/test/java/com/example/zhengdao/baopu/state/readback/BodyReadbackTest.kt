// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百二十二批 · 读回契约判据：
//   ① 三行各 ≤90 字、总 ≤200 token（真跑 TokenEstimator，不手估）
//   ② 同一角色连续 5 轮不变量逐字不变（纯函数 + 封印两条路都测）
//   ③ 不变量只有人批才变（改版即旧批作废；非用户批不出封印）
//   ④ 缺失留位不编（腹型/骨架/臀围 + 状态四项留「未接入」）
//   ⑤ 词档来自 669 词表、真值来自 BodyState（换数即换行，带负例）
package com.example.zhengdao.baopu.state.readback

import com.example.zhengdao.baopu.TokenEstimator
import com.example.zhengdao.baopu.body.AnatomyZone
import com.example.zhengdao.baopu.body.ZoneRelations
import com.example.zhengdao.baopu.state.form.BodyState
import com.example.zhengdao.baopu.state.form.FormFixtures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyReadbackTest {

    private fun state(): BodyState = FormFixtures.state()
    private fun zones() = FormFixtures.anatomy

    // ── ① 字数 / token 预算 ──────────────────────────────────────────────

    @Test
    fun `判据一_三行各不超90字`() {
        val lines = BodyReadback.render(state(), zones()).lines
        assertTrue("不变量行 ${lines.invariant.length} 字", lines.invariant.length <= ReadbackLines.MAX_LINE_CHARS)
        assertTrue("状态行 ${lines.status.length} 字", lines.status.length <= ReadbackLines.MAX_LINE_CHARS)
        assertTrue("词档行 ${lines.vocab.length} 字", lines.vocab.length <= ReadbackLines.MAX_LINE_CHARS)
    }

    @Test
    fun `判据一_三行合计不超200token_真跑估算器`() {
        val lines = BodyReadback.render(state(), zones()).lines
        val tokens = TokenEstimator.estimate(lines.asText())
        assertTrue("实测 $tokens token", tokens <= ReadbackLines.MAX_TOTAL_TOKENS)
    }

    @Test
    fun `判据一_注入接缝只吐三行纯文本`() {
        val r = BodyReadback.render(state(), zones())
        assertEquals(3, r.lines.asContextNotes().size)
        assertEquals(listOf(r.lines.invariant, r.lines.status, r.lines.vocab), r.lines.asContextNotes())
    }

    // ── ② 连续 5 轮逐字不变 ─────────────────────────────────────────────

    @Test
    fun `判据二_同一角色连续5轮不变量逐字不变`() {
        val s = state()
        val z = zones()
        val inv = (1..5).map { BodyReadback.render(s, z).lines.invariant }
        assertEquals(1, inv.toSet().size)
        // 负例：换个身体，不变量必须变——否则说明渲染器跟身体脱钩（恒真假绿）
        val changed = FormFixtures.state(FormFixtures.spec(heightCm = 180.0, bodyFatPct = 25.0))
        assertNotEquals(inv.first(), BodyReadback.render(changed, z).lines.invariant)
    }

    // ── ③ 只有人批才变 ──────────────────────────────────────────────────

    @Test
    fun `判据三_有效批准时不变量逐字取批准行`() {
        val s = state()
        val z = zones()
        val fresh = BodyReadback.render(s, z)
        val seal = InvariantSeal.approve(fresh.invariantBasis, fresh.lines.invariant, InvariantSeal.WHO_USER, 1L)!!
        val line = InvariantReadout.effectiveLine(seal, fresh.invariantBasis, fresh.lines.invariant)
        assertEquals(fresh.lines.invariant, line)
        assertTrue(InvariantReadout.isEffective(seal, fresh.invariantBasis, fresh.lines.invariant))
    }

    @Test
    fun `判据三_改版即旧批作废_不拿失效批准顶数`() {
        val s = state()
        val z = zones()
        val fresh = BodyReadback.render(s, z)
        val seal = InvariantSeal.approve(fresh.invariantBasis, fresh.lines.invariant, InvariantSeal.WHO_USER, 1L)!!

        val changed = FormFixtures.state(FormFixtures.spec(heightCm = 181.0))
        val changedRender = BodyReadback.render(changed, z)
        assertNotEquals(fresh.invariantBasis, changedRender.invariantBasis) // 摘要真的变了
        assertFalse(seal.validFor(changedRender.invariantBasis, changedRender.lines.invariant))

        // 旧批失效 ⇒ 回落当轮真读数，而不是继续吐已批准的那行
        val effective = InvariantReadout.effectiveLine(seal, changedRender.invariantBasis, changedRender.lines.invariant)
        assertEquals(changedRender.lines.invariant, effective)
        assertFalse(InvariantReadout.isEffective(seal, changedRender.invariantBasis, changedRender.lines.invariant))
    }

    @Test
    fun `判据三_批一个字就换digest_时间不进摘要`() {
        val basis = "b|1|2"
        val line = "[身体] 176cm"
        val a = InvariantSeal.digestOf(basis, line)
        assertEquals(a, InvariantSeal.digestOf(basis, line))          // 同内容同摘要
        assertNotEquals(a, InvariantSeal.digestOf(basis, line + "x")) // 改一个字即换
        assertNotEquals(a, InvariantSeal.digestOf(basis + "|", line)) // 摘要变也算改版
        // 批准时间不同不影响摘要内容本身（digestOf 不吃 atMs）
        val s1 = InvariantSeal.approve(basis, line, InvariantSeal.WHO_USER, 1L)!!
        val s2 = InvariantSeal.approve(basis, line, InvariantSeal.WHO_USER, 999L)!!
        assertEquals(s1.digest, s2.digest)
    }

    // ── ④ 缺失留位不编 ──────────────────────────────────────────────────

    @Test
    fun `判据四_不变量行缺口径留位不编数`() {
        val inv = BodyReadback.invariantLine(state())
        assertTrue("须带留位标记", inv.contains(ReadbackLines.UNWIRED))
        assertTrue("腹型/骨架/臀围一并留位", inv.contains("腹型/骨架/臀围:${ReadbackLines.UNWIRED}"))
        // 负例：660 无骨架口径，若有人编出"中骨架/大骨架"字样即红
        assertFalse(inv.contains("中骨架"))
        assertFalse(inv.contains("大骨架"))
    }

    @Test
    fun `判据四_状态行四项全留位不编数`() {
        val status = BodyReadback.statusLine()
        assertTrue(status.startsWith(BodyReadback.STATUS_TAG))
        for (key in listOf("turgor", "出汗", "心率档", "疲劳")) {
            assertTrue("$key 须留位：$status", status.contains("$key:${ReadbackLines.UNWIRED}"))
        }
        assertTrue("须说明 658 未合", status.contains("658未合"))
    }

    // ── ⑤ 真值来源（带负例）─────────────────────────────────────────────

    @Test
    fun `判据五_不变量真值随身体改动`() {
        val base = BodyReadback.invariantLine(state())
        assertTrue("含身高真值", base.contains("170cm"))
        assertTrue("含体脂真值", base.contains("体脂18%"))
        assertTrue("含肌肉档真值", base.contains("肌肉档3"))

        val taller = BodyReadback.invariantLine(
            FormFixtures.state(FormFixtures.spec(heightCm = 176.0, bodyFatPct = 25.0)),
        )
        assertTrue("身高须换成 176", taller.contains("176cm"))
        assertTrue("体脂须换成 25%", taller.contains("体脂25%"))
        assertNotEquals(base, taller)
    }

    @Test
    fun `判据五_词档行13区正名全来自词表`() {
        val z = zones()
        val vocab = BodyReadback.vocabLine(z)
        assertEquals(13, z.size)
        assertTrue(vocab.startsWith(BodyReadback.VOCAB_TAG))
        z.forEach { assertTrue("缺区名 ${it.name}", vocab.contains(it.name)) }
    }

    @Test
    fun `判据五_词档行随传入词表变_不硬编码`() {
        val custom = listOf(
            AnatomyZone("x1", "测试甲", "a", listOf("甲"), listOf("obs"), listOf("err"), ZoneRelations(emptyList(), "x")),
            AnatomyZone("x2", "测试乙", "b", listOf("乙"), listOf("obs"), listOf("err"), ZoneRelations(emptyList(), "x")),
        )
        val vocab = BodyReadback.vocabLine(custom)
        assertTrue(vocab.contains("测试甲/测试乙"))
        assertFalse(vocab.contains("根部")) // 负例：真词表才有的区名，不应出现在自定义词表行里
    }

    @Test
    fun `判据五_纯函数_同输入两次逐字节一致`() {
        val s = state()
        val z = zones()
        assertEquals(BodyReadback.render(s, z).lines, BodyReadback.render(s, z).lines)
        assertEquals(BodyReadback.invariantBasis(s), BodyReadback.invariantBasis(s))
    }

    // ── 人批入口门禁 ────────────────────────────────────────────────────

    @Test
    fun `人批_非用户直接拒_不产封印`() {
        val f = BodyReadback.render(state(), zones())
        assertNull(InvariantSeal.approve(f.invariantBasis, f.lines.invariant, "模型", 1L))
        assertNull(InvariantSeal.approve(f.invariantBasis, f.lines.invariant, "", 1L))
        assertNull(InvariantSeal.approve(f.invariantBasis, f.lines.invariant, "系统", 1L))
    }

    @Test
    fun `封印缺失时取现算真读数_不冒充已批准`() {
        val f = BodyReadback.render(state(), zones())
        assertEquals(f.lines.invariant, InvariantReadout.effectiveLine(null, f.invariantBasis, f.lines.invariant))
        assertFalse(InvariantReadout.isEffective(null, f.invariantBasis, f.lines.invariant))
    }
}
