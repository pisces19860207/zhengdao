// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 一页能力清单（判据①②：首启产出、≤10 行、人话、不把"不可用"说成"可以做"）。
package com.example.zhengdao.baopu.scout

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoutSheetTest {

    private fun rec(
        cap: Capability,
        state: ScoutState,
        denials: Int = 0,
        mentioned: Boolean = false,
        reason: String = "",
        note: String = "",
        at: Long = 1L,
    ) = ScoutRecord(cap, state, denials, at, "", reason, note, mentioned, "PGT-AN10/16")

    /** 最坏情况：每一种桶都占一格（以后加桶还会再撞这道闸）。 */
    private val worstCase = listOf(
        rec(Capability.STORAGE, ScoutState.GRANTED),
        rec(Capability.CLIPBOARD, ScoutState.GRANTED, note = "只在前台可用"),
        rec(Capability.NOTIFICATIONS, ScoutState.REVOKED),
        rec(Capability.HEADPHONES, ScoutState.REVOKED, mentioned = true),
        rec(Capability.CAMERA, ScoutState.DENIED),
        rec(Capability.ACCESSIBILITY, ScoutState.DENIED, denials = 2),
        rec(Capability.BATTERY_OPTIMIZATION, ScoutState.DENIED, denials = 3),
        rec(Capability.LOCATION, ScoutState.UNAVAILABLE, reason = "本应用没有申请位置权限"),
        rec(Capability.ROOT, ScoutState.UNAVAILABLE, reason = "这台手机没有 root"),
        rec(Capability.VIRTUALIZATION, ScoutState.UNAVAILABLE, reason = "没有 /dev/kvm"),
        rec(Capability.NETWORK, ScoutState.UNAVAILABLE, reason = "探测失败：SecurityException"),
    )

    @Test fun `negative two an eleven line page does not pass the guard`() {
        // 负例②「清单超 10 行」：把"超一行"的页面喂给同一把尺（`fits`），必须判红——
        // 否则上面那条"最坏情况也 ≤10 行"的绿只是恰好没撑破。
        val tooLong = ScoutSheet.Sheet("【本机能力】10-30 12:00 探测", List(10) { "凑一行" }, emptyList())
        assertFalse(tooLong.fits)
        assertEquals(11, tooLong.totalLines)
        assertTrue(ScoutSheet.compose(worstCase, 1_000L).fits)
    }

    @Test fun `worst case still fits ten lines including the headline`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        assertTrue(sheet.fits)
        assertEquals(10, ScoutLimits.MAX_SHEET_LINES)
        assertTrue("总行数超了：${sheet.totalLines}", sheet.totalLines <= 10)
        assertEquals(sheet.lines.size + 1, sheet.totalLines)
        assertTrue(sheet.headline.startsWith("【本机能力】"))
    }

    @Test fun `granted items are one plain line and do not carry notes that were not there`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        assertEquals("能直接用：存储", sheet.lines.first())
        assertTrue(sheet.lines.any { it == "能用但有限制：剪贴板（只在前台可用）" })
    }

    @Test fun `revoked is said once in plain words and then folds into the system settings line`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        val fresh = sheet.lines.first { it.contains("上次开过") }
        assertTrue(fresh, fresh.contains("通知"))
        assertFalse("提过一次的不许再念一遍", fresh.contains("耳机"))
        // 提过一次的 revoked 折进"请到系统设置里打开"，**不折**进"要用时会问你要不要开"——
        // 判据层那时已经不许主动问了，清单不能替它许一个不会发生的第二次。
        val quiet = sheet.lines.first { it.startsWith("现在没开") }
        assertTrue("耳机没折进系统设置那行：$quiet", quiet.contains("耳机"))
        val ask = sheet.lines.first { it.startsWith("要用时会问你要不要开") }
        assertFalse("清单许了判据层不给的第二次：$ask", ask.contains("耳机"))
        assertEquals(listOf(Capability.NOTIFICATIONS), sheet.revokedToMention)
    }

    @Test fun `three denials go quiet instead of asking again`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        val quiet = sheet.lines.first { it.startsWith("现在没开") }
        assertTrue(quiet, quiet.contains("后台保活"))
        assertFalse("2 次的还在可问桶里", quiet.contains("无障碍"))
        assertTrue(sheet.lines.any { it.contains("无障碍") && it.startsWith("要用时会问你要不要开") })
    }

    @Test fun `unavailable keeps its reason and never hides behind hope`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        val gone = sheet.lines.first { it.startsWith("现在做不了") }
        listOf("位置", "本应用没有申请位置权限", "root", "没有 /dev/kvm", "探测失败").forEach {
            assertTrue("缺了「$it」：$gone", gone.contains(it))
        }
        assertFalse("不许说成能做", sheet.render().contains("可以试试"))
    }

    @Test fun `sheet never scolds or quotes how many times the user said no`() {
        val text = ScoutSheet.compose(worstCase, 1_000L).render()
        assertFalse(text.contains("拒"))
        assertFalse(text.contains("又"))
        assertFalse(Regex("\\d+ 次").containsMatchIn(text))
    }

    @Test fun `nothing probed means nothing claimed`() {
        val sheet = ScoutSheet.compose(emptyList(), 1_000L)
        assertEquals(1, sheet.lines.size)
        assertTrue(sheet.lines.first().contains("这次没探到能力状态"))
        assertFalse(sheet.render().contains("能直接用"))
        assertTrue(sheet.revokedToMention.isEmpty())
    }

    @Test fun `order follows the capability enum not the input order`() {
        val shuffled = worstCase.reversed()
        assertEquals(
            ScoutSheet.compose(worstCase, 1_000L).render(),
            ScoutSheet.compose(shuffled, 1_000L).render(),
        )
    }

    @Test fun `rendered page starts with the headline and lists one item per line`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        val lines = sheet.render().split("\n")
        assertEquals(sheet.totalLines, lines.size)
        assertEquals(sheet.headline, lines.first())
        lines.drop(1).forEach { assertTrue(it, it.startsWith("- ")) }
    }

    @Test fun `model text keeps the do not pretend rule attached`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        val text = sheet.modelText()
        assertTrue(text.startsWith(sheet.render()))
        assertTrue(text.contains("别假装能做"))
    }

    @Test fun `model line summarises counts without leaking history`() {
        val line = ScoutSheet.modelLine(worstCase, 1_000L)
        assertTrue(line, line.contains("能直接用 2 项"))
        assertTrue(line, line.contains("受限 9 项"))
        assertFalse(line, line.contains("拒"))
    }

    @Test fun `headline carries the probe time`() {
        val sheet = ScoutSheet.compose(worstCase, 1_000L)
        assertTrue(sheet.headline, Regex("【本机能力】\\d{2}-\\d{2} \\d{2}:\\d{2} 探测").containsMatchIn(sheet.headline))
    }
}
