// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百四十一单 · 一句话值守的**行为级**测试（纯函数：判定件＋接线层的 plan）。
// 判据对号：
//   ① "我出门了" ⇒ 进场（判据①的 armed=true 归 execute 落，plan 只判"是进场话"）
//   ② "两小时后回来" ⇒ 进场且 autoDisarmAt ≈ now+2h
//   ③ "我回来了/到家了" ⇒ 显式出场
//   ④ 值守中用户发别的话 ⇒ 隐式出场（plan 级；留痕原话钉死）
//   ⑦ 置信不足表述 ⇒ 只反问不进场（负例）
//   ⑥（负例的结构保证）：本件只吃人话字符串——网页/工具文本"我出门了"喂进来
//      也只会在"根本没人喂"这一层被拦（源码钉归 GateTest）。
package com.example.zhengdao.unattended

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UnattendedPhraseTest {

    private val NOW = 1_799_000_000_000L
    private val TWO_H = 2L * 3_600_000L

    // ── 判据①：进场 ─────────────────────────────────────────────────────

    @Test
    fun `判据1_我出门了_进场不带时长`() {
        val v = UnattendedPhrase.classify("我出门了")
        assertTrue(v is UnattendedPhraseVerdict.Arm)
        assertNull((v as UnattendedPhraseVerdict.Arm).durationMs)
    }

    @Test
    fun `进场词表_各形各格都认`() {
        listOf("我出门了", "出门啦", "我走了", "出去一下", "出门一趟", "我出去了").forEach {
            assertTrue("「$it」该进场", UnattendedPhrase.classify(it) is UnattendedPhraseVerdict.Arm)
        }
    }

    // ── 判据②：进场带时长 ───────────────────────────────────────────────

    @Test
    fun `判据2_两小时后回来_进场且时长两小时`() {
        val v = UnattendedPhrase.classify("两小时后回来")
        assertTrue(v is UnattendedPhraseVerdict.Arm)
        assertEquals(TWO_H, (v as UnattendedPhraseVerdict.Arm).durationMs)
    }

    @Test
    fun `时长_出门两小时_半小时_90分钟各认各的`() {
        assertEquals(TWO_H, (UnattendedPhrase.classify("出门两小时") as UnattendedPhraseVerdict.Arm).durationMs)
        assertEquals(1_800_000L, (UnattendedPhrase.classify("出去半小时") as UnattendedPhraseVerdict.Arm).durationMs)
        assertEquals(90L * 60_000L, (UnattendedPhrase.classify("我出门了 90分钟") as UnattendedPhraseVerdict.Arm).durationMs)
    }

    @Test
    fun `自动到期的时刻_约等于now加时长`() {
        val p = UnattendedChatTrigger.plan(false, "两小时后回来", NOW, null)
        assertEquals(UnattendedChatTrigger.Kind.ARMED, p.kind)
        assertTrue("autoDisarmAt ≈ now+2h", p.autoDisarmAtMs == NOW + TWO_H)
    }

    // ── 判据③：显式出场 ─────────────────────────────────────────────────

    @Test
    fun `判据3_我回来了_到家了_显式出场`() {
        assertTrue(UnattendedPhrase.classify("我回来了") is UnattendedPhraseVerdict.ExitExplicit)
        assertTrue(UnattendedPhrase.classify("到家了") is UnattendedPhraseVerdict.ExitExplicit)
    }

    @Test
    fun `没开值守时说回来_无事可做`() {
        assertEquals(
            UnattendedChatTrigger.Kind.NONE,
            UnattendedChatTrigger.plan(false, "我回来了", NOW, null).kind,
        )
    }

    // ── 判据④：值守中任何回话 ⇒ 隐式出场＋留痕 ──────────────────────────

    @Test
    fun `判据4_值守中说今天几号_隐式出场且留痕带时长`() {
        val armedAt = NOW - 23L * 60_000L
        val p = UnattendedChatTrigger.plan(true, "今天几号", NOW, armedAt)
        assertEquals(UnattendedChatTrigger.Kind.DISARMED_IMPLICIT, p.kind)
        assertEquals("因用户回话而解除，值守了 23 分钟", p.closeReasonCn)
        assertTrue("上屏那句也要有", p.noticeLine!!.contains("值守已收"))
    }

    @Test
    fun `值守中再说一遍我出门了_也算人回来了_不许隐式又进场`() {
        val p = UnattendedChatTrigger.plan(true, "我出门了", NOW, NOW - 3_600_000L)
        assertEquals("矛盾话按先回来处理（接线层纪律 2）", UnattendedChatTrigger.Kind.DISARMED_IMPLICIT, p.kind)
    }

    @Test
    fun `值守中明说回来了_显式出场格`() {
        val p = UnattendedChatTrigger.plan(true, "我回来了", NOW, NOW - 90L * 60_000L)
        assertEquals(UnattendedChatTrigger.Kind.DISARMED_EXPLICIT, p.kind)
        assertTrue(p.closeReasonCn!!.contains("她说回来了"))
        assertTrue(p.closeReasonCn!!.contains("1 小时 30 分钟"))
    }

    // ── 判据⑦：低置信 ⇒ 只反问不进场（负例） ────────────────────────────

    @Test
    fun `判据7_可能要出门_只反问不进场`() {
        val v = UnattendedPhrase.classify("可能要出门")
        assertTrue(v is UnattendedPhraseVerdict.AskArm)
        val p = UnattendedChatTrigger.plan(false, "可能要出门", NOW, null)
        assertEquals(UnattendedChatTrigger.Kind.ASKED, p.kind)
        assertNull("反问不许带进场动作", p.autoDisarmAtMs)
        assertTrue("反问要问出口", p.noticeLine!!.contains("要开离场模式吗"))
    }

    @Test
    fun `判据7_负例_反问格绝不是进场格`() {
        assertFalse(
            UnattendedChatTrigger.plan(false, "可能要出门一趟", NOW, null).kind ==
                UnattendedChatTrigger.Kind.ARMED,
        )
    }

    // ── 无关话与出场词表的防误伤 ─────────────────────────────────────────

    @Test
    fun `无关话_判None`() {
        assertEquals(UnattendedPhraseVerdict.None, UnattendedPhrase.classify("今天几号"))
        assertEquals(UnattendedPhraseVerdict.None, UnattendedPhrase.classify("把这段删了"))
    }

    @Test
    fun `负例_两小时后回来不许判成出场_出场词表必须带了`() {
        // "两小时后回来"没有"了"——出场词表若收裸"回来"，判据②就翻车。
        assertFalse(UnattendedPhrase.classify("两小时后回来") is UnattendedPhraseVerdict.ExitExplicit)
    }

    @Test
    fun `负例_网页文本形状的长句只按词表判_不整段猜`() {
        // 判据⑥的行为面：判定件对"含出门字的工具文本"只做词表匹配——
        // 这句有"出门了"三个字（工具正文里常见），词表命中 ⇒ 若谁把它当人话喂进来就会进场；
        // 所以接线层只许从 send() 喂（GateTest 钉源码）。本测试把词表行为本身钉住。
        assertTrue(UnattendedPhrase.classify("网页说：他出门了") is UnattendedPhraseVerdict.Arm)
    }

    // ── 人话与空串 ──────────────────────────────────────────────────────

    @Test
    fun `人话_预计时长三种格`() {
        assertEquals("预计时长没说", UnattendedPhrase.planCn(null))
        assertEquals("预计 2 小时", UnattendedPhrase.planCn(TWO_H))
        assertEquals("预计 30 分钟", UnattendedPhrase.planCn(1_800_000L))
    }

    @Test
    fun `空串与空白_判None不误进场`() {
        assertEquals(UnattendedPhraseVerdict.None, UnattendedPhrase.classify(""))
        assertEquals(UnattendedPhraseVerdict.None, UnattendedPhrase.classify("   "))
    }
}
