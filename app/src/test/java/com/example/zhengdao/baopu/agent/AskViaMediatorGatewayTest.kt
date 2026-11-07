// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零七批 · 弹卡收口的行为测试：`AskGovernance.askViaMediator` 是可省问卡的
// **唯一**左相闸——压卡必落账（who=LEFT／依据＝档位＋预算／卡类型／时间），
// 必问卡走让步格压不掉（铁律：只少问，不压必问），本轮三数守恒
// N = M + K + 可省弹出。造场景走真的 onUserTurn 换档链路（与 MediatorWiringDecideTest
// 同一配方），不是 mock 判据。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.MediatorWiring
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class AskViaMediatorGatewayTest {

    @Before
    fun setUp() {
        MediatorWiring.resetForTest()
        AskGovernance.resetForTest()
    }

    @After
    fun tearDown() {
        MediatorWiring.resetForTest()
        AskGovernance.resetForTest()
    }

    private fun turn(text: String) =
        MediatorWiring.onUserTurn(text, modelText = "", sessionId = 1L, now = 0L)

    // ── 闸的行为：压卡落账四要素（who/why/卡类型/时间）────────────────────

    @Test
    fun `暂停态普通卡被压_落账who_LEFT_依据_卡类型_时间齐备`() {
        turn("帮我查一下天气")
        turn("不想弄了") // 暂停（MediatorWiringDecideTest 同款场景）
        val advice = AskGovernance.askViaMediator(mustAsk = false, card = "ask_user", atMs = 123_456L)
        assertNull("暂停态：可省问卡被压（闸收 null＝不弹不排队）", advice)
        val row = AskGovLedger.replay().first { it.kind == AskGovLedger.KIND_SUPPRESS }
        assertEquals("who=LEFT（D 段③）", "LEFT", row.who)
        assertTrue("why＝依据一句", row.basis.contains("暂停"))
        assertTrue("why 要带预算读数", row.basis.contains("｜"))
        assertEquals("卡类型进 detail", "card=ask_user", row.detail)
        assertEquals("时间随行", 123_456L, row.atMs)
        assertEquals("tool 仍是 ask_user 口", "ask_user", row.tool)
    }

    // ── 铁律负例：必问卡压不掉 ────────────────────────────────────────────

    @Test
    fun `必问负例_暂停态必问卡照弹_压卡账一行没有`() {
        turn("帮我查一下天气")
        turn("不想弄了") // 暂停——调解器此刻压得掉一切可省卡
        val advice = AskGovernance.askViaMediator(mustAsk = true, card = "多问卡·用户点名")
        assertNotNull("铁律：必问走让步格，暂停也照弹", advice)
        assertTrue(advice!!.allowed)
        assertTrue(
            "被压的只有可省卡：必问卡不许在压卡账上留行",
            AskGovLedger.replay().none { it.kind == AskGovLedger.KIND_SUPPRESS },
        )
    }

    @Test
    fun `必问负例_耐心预算耗尽必问卡照弹_不占预算`() {
        turn("帮我查一下天气")
        assertTrue(AskGovernance.askViaMediator(false, "ask_user")!!.allowed)
        assertTrue(AskGovernance.askViaMediator(false, "ask_user")!!.allowed)
        assertNull("默认耐心阈值 2：第三张可省卡被压", AskGovernance.askViaMediator(false, "求助卡"))
        val must = AskGovernance.askViaMediator(true, "多问卡·用户点名")
        assertNotNull("预算耗尽也压不掉必问（让步格恒放行）", must)
        assertTrue(must!!.allowed)
    }

    // ── §二 可观测：本轮三数守恒 ─────────────────────────────────────────

    @Test
    fun `本轮三数守恒_N等于M加K加可省弹出`() {
        turn("帮我查一下天气")
        AskGovernance.askViaMediator(true, "多问卡·用户点名") // N=1 K=1（放行；必问不占预算）
        AskGovernance.askViaMediator(false, "ask_user") //      N=2（放行，已问 1）
        AskGovernance.askViaMediator(false, "求助卡") //        N=3（放行，已问 2＝阈值）
        assertNull(AskGovernance.askViaMediator(false, "计划偏离卡")) // N=4 M=1（超阈值被压）
        val t = AskGovernance.turnTally()
        assertEquals(4, t.attempts)
        assertEquals(1, t.suppressed)
        assertEquals(1, t.mustAsk)
        assertEquals("守恒：N = M + K + 可省弹出", t.attempts, t.suppressed + t.mustAsk + (t.popped - t.mustAsk))
        val line = AskGovernance.turnTallyLine()
        assertTrue("体检一行三数齐：$line", line.contains("本轮问卡 4"))
        assertTrue(line.contains("被压 1"))
        assertTrue(line.contains("必问 1"))
        assertTrue(line.contains("弹出 3"))
    }

    @Test
    fun `beginTurn清三数_新回合从零起算`() {
        turn("帮我查一下天气")
        AskGovernance.askViaMediator(false, "ask_user")
        AskGovernance.beginTurn()
        val t = AskGovernance.turnTally()
        assertEquals(0, t.attempts)
        assertEquals(0, t.suppressed)
        assertEquals(0, t.mustAsk)
        assertTrue(AskGovernance.turnTallyLine().contains("本轮问卡 0"))
    }
}
