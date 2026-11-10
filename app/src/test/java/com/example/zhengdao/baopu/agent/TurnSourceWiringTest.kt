// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十四批 · 「当前轮次消息号」信箱的直测（纯 JVM，零 Android）。
//
// 判据（574 派工单）：
//   ① 模型传了来源号 ⇒ 用模型的（trim 后原样），不覆盖；
//   ② 没传 ⇒ 自动补本轮消息号（引擎落库的真值）；
//   ③ 没有本轮（定时/后台）⇒ 空串（落盘口落 null，不写占位号）；
//   ④ 信箱每轮覆盖、收工摘空——不许回退上一轮的旧值（宁空勿猜）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class TurnSourceWiringTest {

    @Before
    fun reset() {
        TurnSourceWiring.resetForTest()
    }

    @After
    fun clean() {
        TurnSourceWiring.resetForTest()
    }

    @Test
    fun `传了就用模型的_空白等于没传`() {
        TurnSourceWiring.installTurn(42L)
        assertEquals(
            "显式来源号原样保留（不覆盖）",
            "缺口-544",
            TurnSourceWiring.resolveFrom(JSONObject().put("sourceMsgId", " 缺口-544 "), "sourceMsgId"),
        )
        assertEquals(
            "纯空白不算传了：照样走自动补",
            "42",
            TurnSourceWiring.resolveFrom(JSONObject().put("sourceMsgId", "   "), "sourceMsgId"),
        )
    }

    @Test
    fun `没传_自动补本轮消息号`() {
        TurnSourceWiring.installTurn(7L)
        assertEquals("7", TurnSourceWiring.resolveFrom(JSONObject(), "sourceId"))
    }

    @Test
    fun `没有本轮_空串_宁空勿猜`() {
        assertEquals(
            "信箱空 ⇒ 空串（落盘口落 null，不写占位号）",
            "",
            TurnSourceWiring.resolveFrom(JSONObject(), "sourceMsgId"),
        )
        // 收工摘空：上一轮的号不许漏给下一轮
        TurnSourceWiring.installTurn(9L)
        TurnSourceWiring.clearTurn()
        assertEquals("", TurnSourceWiring.resolveFrom(JSONObject(), "sourceId"))
    }

    @Test
    fun `每轮覆盖_出处跟着当前轮`() {
        TurnSourceWiring.installTurn(1L)
        assertEquals("1", TurnSourceWiring.currentTurnMessageId().toString())
        TurnSourceWiring.installTurn(2L)
        assertEquals("信箱每轮覆盖", "2", TurnSourceWiring.currentTurnMessageId().toString())
    }
}
