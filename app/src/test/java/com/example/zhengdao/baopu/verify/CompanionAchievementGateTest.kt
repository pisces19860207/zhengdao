// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十一批 · 右相「三态达成」的**三态夹具与负例**（派工单判据②③）。
//
// 判据②：三态各一条夹具——PASS/FAIL/UNVERIFIABLE 都能出现在**学习档收口**（上一轮判了
// 学习档，这一轮的回复就是收口证据）；
// 判据③：UNVERIFIABLE 不许被当 PASS（负例）——没有达成证据 ⇒ UNVERIFIABLE，而
// UNVERIFIABLE 的行里不许出现 PASS 字样（fail-closed：判不了就说判不了）。
//
// 附带钉死：非学习档不判（工作档的达成归 302/332/378/482 那几道既有闸）、矛盾不猜、
// 依据必填。判定是纯函数，零状态、零 IO——夹具即用例。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.CompanionMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class CompanionAchievementGateTest {

    // ── 判据②：三态各一条夹具（都在学习档收口）──────────────────────────────

    @Test
    fun `判据②_PASS夹具_讲清了且按步骤做完_用户确认跟上`() {
        val a = CompanionAchievementGate.judge(CompanionMode.STUDY, "跟上了，第二步也做出来了")!!
        assertEquals(ChancellorVerdict.PASS, a.verdict)
        assertTrue("依据必须点出确认证据", a.reason.contains("确认跟上") && a.reason.contains("跟上了"))
    }

    @Test
    fun `判据②_FAIL夹具_讲了但没做_用户说没懂报错`() {
        val a = CompanionAchievementGate.judge(CompanionMode.STUDY, "没懂，第二步报错了")!!
        assertEquals(ChancellorVerdict.FAIL, a.verdict)
        assertTrue("依据必须点出没做/做错的证据", a.reason.contains("没做/做错") && a.reason.contains("没懂"))
    }

    @Test
    fun `判据②_UNVERIFIABLE夹具_没有达成证据_要问或留观`() {
        val a = CompanionAchievementGate.judge(CompanionMode.STUDY, "今天天气不错")!!
        assertEquals(ChancellorVerdict.UNVERIFIABLE, a.verdict)
        assertTrue("依据必须说清要问或留观", a.reason.contains("要问或留观"))
    }

    // ── 判据③：UNVERIFIABLE 不许被当 PASS（负例）────────────────────────────

    @Test
    fun `判据③_UNVERIFIABLE不许当PASS_无证据判不了`() {
        // 没有确认词、也没有没做证据 ⇒ 恒 UNVERIFIABLE（fail-closed），任何输入组合下
        // 无证据都不许翻成 PASS。
        listOf(
            "今天天气不错",
            "嗯",
            "好",
            "我看看",
            "",
        ).forEach { reply ->
            val a = CompanionAchievementGate.judge(CompanionMode.STUDY, reply)!!
            assertTrue(
                "无证据回复「$reply」不许判 PASS",
                a.verdict != ChancellorVerdict.PASS,
            )
            assertEquals(ChancellorVerdict.UNVERIFIABLE, a.verdict)
        }
    }

    @Test
    fun `判据③_UNVERIFIABLE那一行不许出现PASS字样`() {
        // 人读的那一行是三态的唯一出口：UNVERIFIABLE 行里出现 "PASS" 就是"当 PASS"的第一步。
        val a = CompanionAchievementGate.judge(CompanionMode.STUDY, "今天天气不错")!!
        val line = CompanionAchievementGate.line(3, 11L, a)
        assertTrue("UNVERIFIABLE 行必须带「判不了，不当通过」", line.contains("判不了，不当通过"))
        assertTrue("UNVERIFIABLE 行不许出现 PASS 字样：$line", !line.contains("PASS"))
        // PASS 行自然带 PASS、FAIL 行自然带 FAIL（对照，证明断言不是恒真）
        assertTrue(
            CompanionAchievementGate.line(3, 11L, CompanionAchievementGate.judge(CompanionMode.STUDY, "跟上了")!!).contains("PASS"),
        )
        assertTrue(
            CompanionAchievementGate.line(3, 11L, CompanionAchievementGate.judge(CompanionMode.STUDY, "没懂")!!).contains("FAIL"),
        )
    }

    // ── 判定形状：谁判/矛盾不猜/依据必填 ─────────────────────────────────────

    @Test
    fun `非学习档不判_达成判定只对学习档收口`() {
        assertNull("没判过（第一轮）⇒ 不判", CompanionAchievementGate.judge(null, "跟上了"))
        assertNull("工作档 ⇒ 不判（交付三态归既有闸）", CompanionAchievementGate.judge(CompanionMode.WORK, "跟上了"))
        assertNull("默认档（也是工作）⇒ 不判", CompanionAchievementGate.judge(CompanionMode.DEFAULT, "明白了"))
    }

    @Test
    fun `证据自相矛盾_矛盾不猜落UNVERIFIABLE`() {
        val a = CompanionAchievementGate.judge(CompanionMode.STUDY, "一开始没懂，后来明白了")!!
        assertEquals("矛盾不猜（550 explicitOf 同款裁定）", ChancellorVerdict.UNVERIFIABLE, a.verdict)
        assertTrue(a.reason.contains("矛盾"))
    }

    @Test
    fun `依据必填_没有依据的判定造不出来`() {
        assertThrows(IllegalArgumentException::class.java) {
            CompanionAchievement(ChancellorVerdict.PASS, "  ")
        }
    }
}
