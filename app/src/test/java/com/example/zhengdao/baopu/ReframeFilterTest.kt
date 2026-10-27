// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十八批 · 双向转译 `ReframeFilter` 的判据层。
//
// 六条对照**逐字相等**（派工单 §3.5 与用户 2026-10-26 的原话）；
// 爱称白名单**原样透传**，但攻击短语优先——「小笨蛋 + 废物」不能拿白名单当挡箭牌；
// 改写只去人格评价，事实（哪一步、失败在哪）必须留下来，且不许洗白、不许加油打气。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReframeFilterTest {

    // ── 1. 派工单六条对照（逐字） ───────────────────────────────────────

    @Test
    fun `转译一_你怎么这么笨`() {
        assertEquals(
            "用户判定该步做法不对（已第2次），请换方案",
            ReframeFilter.reframe("你怎么这么笨", Side.USER),
        )
    }

    @Test
    fun `转译二_我不想弄了`() {
        assertEquals(
            "用户暂停意愿：存档、下次继续，不要继续重试",
            ReframeFilter.reframe("我不想弄了", Side.USER),
        )
    }

    @Test
    fun `转译三_这都不行`() {
        assertEquals(
            "方案被判不可行，要求换方案",
            ReframeFilter.reframe("这都不行？", Side.USER),
        )
    }

    @Test
    fun `转译四_你随便吧`() {
        assertEquals(
            "用户放弃指定做法，由你选一个并说明理由（仍要确认）",
            ReframeFilter.reframe("你随便吧", Side.USER),
        )
    }

    @Test
    fun `转译五_别动原文件_字面保留`() {
        assertEquals(
            "约束：不得修改原文件",
            ReframeFilter.reframe("别动原文件！", Side.USER),
        )
    }

    @Test
    fun `转译六_模型我太笨了`() {
        assertEquals(
            "该步失败：原因 未说明",
            ReframeFilter.reframe("我太笨了", Side.MODEL),
        )
    }

    // ── 2. 模型侧：原因要保住 ───────────────────────────────────────────

    @Test
    fun `模型侧_自贬带原因时原因原样留下`() {
        assertEquals(
            "该步失败：原因 点「查询」没找到元素",
            ReframeFilter.reframe("我太笨了，点「查询」没找到元素", Side.MODEL),
        )
    }

    @Test
    fun `模型侧_一句里两次自贬只回一条失败句`() {
        assertEquals(
            "该步失败：原因 未说明",
            ReframeFilter.reframe("我太笨了，我什么都做不好", Side.MODEL),
        )
        assertEquals(2, ReframeFilter.selfDeprecationCount("我太笨了，我什么都做不好"))
    }

    @Test
    fun `模型侧_没有自贬就原样不动`() {
        val text = "第 3 步点「查询」超时，换用直接输入。"
        assertEquals(text, ReframeFilter.reframe(text, Side.MODEL))
    }

    // ── 3. 爱称白名单：原样透传 ────────────────────────────────────────

    @Test
    fun `爱称_八个白名单词逐字透传`() {
        ReframeFilter.PASS.forEach { word ->
            val text = "$word，这一步又错啦～"
            assertEquals(text, ReframeFilter.reframe(text, Side.USER))
            assertFalse("爱称不许算责备：$word", ReframeFilter.isBlame(text))
            assertFalse("爱称不许算短促否定：$word", ReframeFilter.isShortNegation(text))
        }
    }

    @Test
    fun `爱称_句末昵称形态也透传`() {
        assertEquals("笨蛋~", ReframeFilter.reframe("笨蛋~", Side.USER))
        assertTrue(ReframeFilter.isLoveName("笨蛋~"))
        assertFalse(ReframeFilter.isBlame("笨蛋~"))
    }

    @Test
    fun `爱称_不抬情绪也不动emoji预算`() {
        val s = HarmonyMediator.observe(
            HarmonyMediator.State(),
            "小笨蛋，这一步又错啦～",
            "",
            HarmonyMediator.Outcome.None,
        )
        assertEquals(HarmonyMediator.Mood.CALM, s.mood)
        assertEquals(0, s.emojiBudget)
        assertEquals(0, s.taskFailures)
        assertEquals(0, s.consecutiveNegations)
    }

    @Test
    fun `爱称_混着攻击短语时按攻击处理`() {
        val text = "你个废物，小笨蛋"
        assertTrue(ReframeFilter.isAttack(text))
        assertTrue(ReframeFilter.isBlame(text))
        val out = ReframeFilter.reframe(text, Side.USER)
        assertTrue(out.contains("用户判定该步做法不对"))
        assertFalse(out.contains("小笨蛋"))
    }

    @Test
    fun `爱称_白名单不能当挡箭牌_攻击表逐条优先`() {
        ReframeFilter.ATTACKS.forEach { attack ->
            val text = "$attack，小笨蛋"
            assertTrue("攻击词未命中：$attack", ReframeFilter.isAttack(text))
            assertFalse(
                "白名单挡了攻击：$attack",
                ReframeFilter.reframe(text, Side.USER) == text,
            )
        }
    }

    // ── 4. 保事实、不洗白、不加鼓励 ─────────────────────────────────────

    @Test
    fun `保事实_改写后仍能看见哪一步和失败在哪`() {
        val out = ReframeFilter.reframe("你怎么这么笨，第3步点「查询」没找到元素", Side.USER)
        assertTrue(out.contains("已第2次"))
        assertTrue("事实丢了：$out", out.contains("第3步"))
        assertTrue("失败点丢了：$out", out.contains("没找到"))
        assertTrue("事实段丢失：$out", out.contains("事实："))
    }

    @Test
    fun `不洗白_否定不许被改写成肯定`() {
        val out = ReframeFilter.reframe("你怎么这么笨", Side.USER)
        assertTrue(out.contains("不对"))
        listOf("差不多对", "其实可以", "没问题", "已经对了").forEach {
            assertFalse("洗白：$it", out.contains(it))
        }
    }

    @Test
    fun `不加鼓励_改写结果里没有反事实表扬`() {
        val outs = listOf(
            ReframeFilter.reframe("你怎么这么笨", Side.USER),
            ReframeFilter.reframe("你个废物", Side.USER),
            ReframeFilter.reframe("我太笨了", Side.MODEL),
        )
        listOf("你做得很好", "做得好", "棒", "加油", "别灰心").forEach { praise ->
            outs.forEach { out -> assertFalse("反事实鼓励：$praise / $out", out.contains(praise)) }
        }
    }

    @Test
    fun `原样_普通请求一字不改`() {
        val text = "帮我把文件复制到资料库"
        assertEquals(text, ReframeFilter.reframe(text, Side.USER))
        assertFalse(ReframeFilter.needsReframe(text, Side.USER))
    }

    @Test
    fun `约束_夹在骂人句里也要留下来`() {
        val out = ReframeFilter.reframe("你个蠢货，别动原文件", Side.USER)
        assertTrue(out.contains("用户判定该步做法不对"))
        assertTrue("约束丢了：$out", out.contains("约束：不得修改原文件"))
    }

    @Test
    fun `改写次数_脏句才算一次改写`() {
        assertTrue(ReframeFilter.needsReframe("你怎么这么笨", Side.USER))
        assertTrue(ReframeFilter.needsReframe("我太笨了", Side.MODEL))
        assertFalse(ReframeFilter.needsReframe("帮我把文件复制到资料库", Side.USER))
        val s = HarmonyMediator.observe(
            HarmonyMediator.State(),
            "你怎么这么笨",
            "我太笨了",
            HarmonyMediator.Outcome.None,
        )
        assertEquals(1, s.reframes)
    }

    // ── 5. 信号判据 ────────────────────────────────────────────────────

    @Test
    fun `信号_人给台阶`() {
        assertTrue(ReframeFilter.isReset("没事了"))
        assertTrue(ReframeFilter.isReset("继续吧"))
        assertFalse(ReframeFilter.isReset("我不想弄了"))
    }

    @Test
    fun `信号_暂停`() {
        assertTrue(ReframeFilter.isPause("我不想弄了"))
        assertTrue(ReframeFilter.isPause("不弄了"))
        assertFalse(ReframeFilter.isPause("继续吧"))
    }

    @Test
    fun `信号_责备与短促否定`() {
        assertTrue(ReframeFilter.isBlame("说了几遍了"))
        assertTrue(ReframeFilter.isBlame("蠢货"))
        assertFalse(ReframeFilter.isBlame("不对"))
        assertTrue(ReframeFilter.isShortNegation("又错"))
        assertTrue(ReframeFilter.isShortNegation("算了"))
        assertFalse(ReframeFilter.isShortNegation("小笨蛋，又错啦～"))
    }

    @Test
    fun `信号_放弃指定做法与约束`() {
        assertTrue(ReframeFilter.isHandoff("你随便吧"))
        assertTrue(ReframeFilter.isHandoff("你看着办"))
        assertTrue(ReframeFilter.isConstraint("不要改原文件"))
        assertFalse(ReframeFilter.isConstraint("帮我把文件复制到资料库"))
    }
}
