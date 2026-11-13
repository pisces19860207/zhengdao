// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百一十九批（569 缺陷批 · 619）· 验卡豁免的四条判据（纯 JVM，零时钟、零 Android）。
//
// 派工单原文：
//   ① 给"验卡/自检"一类**显式验证动作**留一条豁免：它在免打扰期内仍然出卡
//      （或改为"只弹一次、标自检"）；
//   ② **不许把免打扰整体放宽**（那不是修，是撤）。
//   判据：① 免打扰期内做一次验卡 ⇒ 确认卡出现（夹具）；② 普通打扰仍被压（负例）；
//         ③ 豁免只对显式验证动作生效（断言：其他 AskKind 不受影响）。
//
// 本文件逐条钉，另加两条本批自己加的边界（离场锁屏不算、静默结束重新计）：
// 那两条不是派工单的字面，是"豁免不许变成后门"的机器依据。
package com.example.zhengdao.baopu.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class QuietVerifyExemptTest {

    private val t0 = 1_700_000_000_000L
    private val oneMinute = QuietLimits.MINUTE_MS

    /** 569 现场：凌晨 2 点，**没有免打扰授权**（那晚账上写的是「凭=夜间时段」）。 */
    private fun nightAt2() = SessionShape(hourOfDay = 2)

    private fun day() = SessionShape(hourOfDay = 14)

    private fun grant(atMs: Long = t0) = QuietMode.grant("session:619", QuietLimits.MAX_MINUTES, atMs)

    /** 普通提问（没有自检标）——判据②的负例就是它。 */
    private fun plain(q: String) = AskUserRequest(
        question = q,
        options = listOf(AskUserOption("keep", "都留着"), AskUserOption("drop", "删掉")),
    )

    /** 用户亲口说「验卡」时引擎弹的那张卡（唯一构造口在 [QuietVerify]）。 */
    private fun selfCheckCard() = QuietVerify.request(VerifyTrigger.VERIFY_CARD)

    // ── 判据①：免打扰期内做一次验卡 ⇒ 卡出现 ─────────────────────────────

    @Test
    fun `判据①_夜里无授权_验卡照样出卡_账行标自检并写凭据`() {
        val gate = QuietCardGate()
        val out = gate.onAsk(null, nightAt2(), t0, selfCheckCard())
        assertEquals("验卡在夜间静默期内必须出卡（569 的病灶就在这一格）", QuietCardVerdict.ASK, out.verdict)
        assertTrue("账要认得出这是豁免：${out.line}", out.line.contains("自检豁免"))
        assertTrue("账要写清是凭什么静默：${out.line}", out.line.contains("夜间时段"))
        assertEquals("豁免放行的那张不进延后队列", 0, gate.size)
        assertNull("它没有被延后，所以没有 deferred", out.deferred)
    }

    @Test
    fun `判据①_免打扰授权期内验卡_也出卡`() {
        val gate = QuietCardGate()
        val out = gate.onAsk(grant(), day(), t0 + oneMinute, selfCheckCard())
        assertEquals(QuietCardVerdict.ASK, out.verdict)
        assertTrue("凭据要写到授权上：${out.line}", out.line.contains("免打扰授权中"))
    }

    // ── 判据②：普通打扰仍被压（负例） ──────────────────────────────────

    @Test
    fun `判据②_同一段静默里普通提问仍被压_验卡只放它自己那一张`() {
        val gate = QuietCardGate()
        val plainOut = gate.onAsk(grant(), nightAt2(), t0, plain("要不要合并这两段？"))
        assertEquals("普通打扰照旧压（放宽了一个字就不是修）", QuietCardVerdict.DEFER, plainOut.verdict)
        assertTrue(plainOut.line.contains("因免打扰延后"))
        assertEquals(1, gate.size)
        // 紧接着的那次验卡放行 ⇒ 说明豁免是**逐卡**判的，不是把这一段静默整体关掉
        assertEquals(QuietCardVerdict.ASK, gate.onAsk(grant(), nightAt2(), t0, selfCheckCard()).verdict)
        assertEquals("压着的那一件还在队里，一件没少", 1, gate.size)
    }

    @Test
    fun `判据②_验卡之后普通提问还是被压_顺序不影响`() {
        val gate = QuietCardGate()
        assertEquals(QuietCardVerdict.ASK, gate.onAsk(grant(), nightAt2(), t0, selfCheckCard()).verdict)
        val after = gate.onAsk(grant(), nightAt2(), t0 + oneMinute, plain("要不要合并这两段？"))
        assertEquals(QuietCardVerdict.DEFER, after.verdict)
    }

    // ── 判据③：豁免只对显式验证动作生效 ────────────────────────────────

    @Test
    fun `判据③_其他AskKind一张都不放`() {
        AskKind.entries.forEach { kind ->
            val gate = QuietCardGate()
            val out = gate.onAsk(
                grant(),
                nightAt2(),
                t0,
                plain("${kind.name} 这一张要不要？").copy(kind = kind),
            )
            assertEquals("$kind 在免打扰期内仍须被压（豁免不认事由分类）", QuietCardVerdict.DEFER, out.verdict)
        }
    }

    @Test
    fun `判据③_同一个QUESTION_带自检标才放_去掉标就压`() {
        val withFlag = selfCheckCard()
        val withoutFlag = withFlag.copy(selfCheck = false)
        assertEquals(
            "带标 ⇒ 放（显式验证动作）",
            QuietCardVerdict.ASK,
            QuietCardGate().onAsk(grant(), nightAt2(), t0, withFlag).verdict,
        )
        assertEquals(
            "同一个 kind、只把标去掉 ⇒ 照压（证明豁免认的是标，不是卡类）",
            QuietCardVerdict.DEFER,
            QuietCardGate().onAsk(grant(), nightAt2(), t0, withoutFlag).verdict,
        )
    }

    // ── 派工单①括号：只弹一次 ──────────────────────────────────────────

    @Test
    fun `只弹一次_同一段静默里第二张起照旧压`() {
        val gate = QuietCardGate()
        assertEquals(QuietCardVerdict.ASK, gate.onAsk(grant(), nightAt2(), t0, selfCheckCard()).verdict)
        val again = gate.onAsk(grant(), nightAt2(), t0 + oneMinute, selfCheckCard())
        assertEquals("豁免是通行证，不是常开的门", QuietCardVerdict.DEFER, again.verdict)
        assertEquals("不放的那张不丢：照样进队等静默结束", 1, gate.size)
    }

    @Test
    fun `只弹一次_静默结束后重新计_下一段静默还能放一张`() {
        val gate = QuietCardGate()
        assertEquals(QuietCardVerdict.ASK, gate.onAsk(grant(), nightAt2(), t0, selfCheckCard()).verdict)
        // 白天、不忙 ⇒ 静默不在：这一张照常弹，同时把豁免的账清零
        assertEquals(QuietCardVerdict.ASK, gate.onAsk(null, day(), t0 + oneMinute, plain("白天问一句")).verdict)
        assertEquals(
            "下一段静默（夜里）又能放一张——不然验一次卡就把这台机器废到重启",
            QuietCardVerdict.ASK,
            gate.onAsk(null, nightAt2(), t0 + 2 * oneMinute, selfCheckCard()).verdict,
        )
    }

    // ── 本批自加的两条边界 ─────────────────────────────────────────────

    @Test
    fun `离场锁屏时自检卡也不放_人不在弹了也没人点`() {
        val gate = QuietCardGate()
        val out = gate.onAsk(null, day(), t0, selfCheckCard(), awayLocked = true)
        assertEquals("锁屏那一路仍然全压（这条不是免打扰，是人在不在）", QuietCardVerdict.DEFER, out.verdict)
        assertTrue("账上要看得见是锁屏压的：${out.line}", out.line.contains("离场值守·锁屏"))
    }

    @Test
    fun `触发词是闭集_认不出就不弹`() {
        assertEquals(VerifyTrigger.VERIFY_CARD, QuietVerify.triggerOf("验卡"))
        assertEquals(VerifyTrigger.VERIFY_ONE, QuietVerify.triggerOf("帮我验一张卡看看"))
        assertEquals(VerifyTrigger.SELF_CHECK_CARD, QuietVerify.triggerOf("自检一张卡"))
        assertNull(
            "普通句子不许被当成验卡（机器不能自己给自己批一次豁免）",
            QuietVerify.triggerOf("把这份稿子删了"),
        )
        assertNull(QuietVerify.triggerOf(""))
    }

    @Test
    fun `自检卡是一张能点的真卡_事由仍是普通提问`() {
        val req = selfCheckCard()
        assertEquals("不许为它新加 AskKind 档位（加档位要动 ui 层渲染）", AskKind.QUESTION, req.kind)
        assertTrue("豁免凭据就是这个标", req.selfCheck)
        assertTrue(
            "选项数要在工具的口径里：${req.options.size}",
            req.options.size in AskUserTool.MIN_OPTIONS..AskUserTool.MAX_OPTIONS,
        )
        assertTrue("subject 带自检锚：${req.subject}", req.subject.startsWith(SELF_CHECK_SUBJECT_PREFIX))
        assertTrue("题面要有一句人话", req.question.isNotBlank())
        assertFalse(
            "问题组形状会让它走多问卡——验的是那张选项卡本身",
            AskGroupRules.isGroupCard(req),
        )
    }

    @Test
    fun `验卡那一行账_谁要的凭什么都写清`() {
        val line = QuietVerify.armLine(VerifyTrigger.VERIFY_CARD, 7L, grant(), nightAt2(), t0 + oneMinute)
        assertTrue(line.contains("验卡"))
        assertTrue("要能追到哪件事：$line", line.contains("session:7"))
        assertTrue("要写清此刻是什么态：$line", line.contains("免打扰中"))
        assertTrue("静默凭据要写出来：$line", line.contains("免打扰授权中＋夜间时段"))
        assertFalse("账是一行（换行就是第二条账）", line.contains('\n'))
    }
}
