// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百零六批 · 弹卡闸接线（`MediatorWiring.consultAsk`／`orderAdvice`）的行为测试。
//
// 判据口径（派工单 406）：**造场景 ⇒ 行为改变**——走真的 onUserTurn 换档链路，
// 同一张问卡在暂停／预算用尽／复位下的弹压必须翻面；计数是一回合（换轮归零）、
// 会话不跨（换会话归零）、让步跟着档位走。
package com.example.zhengdao.baopu

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class MediatorWiringDecideTest {

    @Before
    fun setUp() {
        MediatorWiring.resetForTest()
    }

    @After
    fun tearDown() {
        MediatorWiring.resetForTest()
    }

    private fun turn(text: String, sessionId: Long = 1L) =
        MediatorWiring.onUserTurn(text, modelText = "", sessionId = sessionId, now = 0L)

    @Test
    fun `造场景_同一回合金松档_第三张问卡被压前两张照常`() {
        turn("帮我查一下天气")
        assertTrue(MediatorWiring.consultAsk().allowed)
        assertTrue(MediatorWiring.consultAsk().allowed)
        val third = MediatorWiring.consultAsk()
        assertFalse("默认耐心阈值 2：本回合第 3 张压下（少问）", third.allowed)
        assertTrue(third.reason.contains("耐心阈值"))
    }

    @Test
    fun `造场景_换了回合预算回满_不是一会话一张额度`() {
        turn("帮我查一下天气")
        MediatorWiring.consultAsk()
        MediatorWiring.consultAsk()
        assertFalse(MediatorWiring.consultAsk().allowed)
        turn("那把结果整理成表") // 新回合 ⇒ 计数归零
        assertTrue("新回合新预算", MediatorWiring.consultAsk().allowed)
    }

    @Test
    fun `造场景_用户说不想弄了_同一回合从照常弹翻成压`() {
        turn("帮我查一下天气")
        assertTrue(MediatorWiring.consultAsk().allowed)
        turn("不想弄了") // 暂停
        val advice = MediatorWiring.consultAsk()
        assertFalse(advice.allowed)
        assertTrue(advice.reason.contains("暂停"))
    }

    @Test
    fun `造让步场景_归责回合只许一张_松口回合回满两张`() {
        turn("你到底行不行") // 归责 ⇒ STRAINED：耐心阈值收一档（2→1）
        assertTrue("STRAINED 的一张还问得出", MediatorWiring.consultAsk().allowed)
        assertFalse("第二张就到线（阈值得收）", MediatorWiring.consultAsk().allowed)
        turn("没事了") // 人给台阶 ⇒ 复位回 CALM（让步：档位回原位）
        assertTrue("预算回满：第 1 张照常", MediatorWiring.consultAsk().allowed)
        assertTrue("预算回满：第 2 张也回得来（STRAINED 时它已被压）", MediatorWiring.consultAsk().allowed)
    }

    @Test
    fun `必问档恒放行且不占预算`() {
        turn("帮我查一下天气")
        MediatorWiring.consultAsk()
        MediatorWiring.consultAsk()
        val must = MediatorWiring.consultAsk(mustAsk = true)
        assertTrue(must.allowed)
        // 必问没吃预算：换一个回合再问普通卡，还是老结局（而非多出一张的余量）。
        turn("继续")
        assertTrue(MediatorWiring.consultAsk().allowed)
        assertTrue(MediatorWiring.consultAsk().allowed)
        assertFalse(MediatorWiring.consultAsk().allowed)
    }

    @Test
    fun `换会话计数归零_不跨会话记账`() {
        turn("帮我查一下天气", sessionId = 1L)
        MediatorWiring.consultAsk()
        MediatorWiring.consultAsk()
        assertFalse(MediatorWiring.consultAsk().allowed)
        turn("帮我查一下天气", sessionId = 2L)
        assertTrue("新会话新预算（会话不跨）", MediatorWiring.consultAsk().allowed)
    }

    @Test
    fun `负例_没进过用户输入的引擎直跑_调解器不参与不计数`() {
        // resetForTest 后（＝没看过任何会话）：问多少张都照常放行，行为与接线前一致。
        assertTrue(MediatorWiring.consultAsk().allowed)
        assertTrue(MediatorWiring.consultAsk().allowed)
        assertTrue(MediatorWiring.consultAsk().allowed)
        assertTrue(MediatorWiring.consultAsk().allowed)
    }

    @Test
    fun `造场景_排序建议_档位升起来才变_CALM照旧`() {
        turn("帮我查一下天气")
        val calm = MediatorWiring.orderAdvice(listOf(2, 0, 1))
        assertFalse(calm.changed)
        turn("这都不行") // 短促否定 ⇒ CAREFUL
        val careful = MediatorWiring.orderAdvice(listOf(2, 0, 1))
        assertTrue(careful.changed)
        assertEquals(listOf(1, 2, 0), careful.order)
    }
}
