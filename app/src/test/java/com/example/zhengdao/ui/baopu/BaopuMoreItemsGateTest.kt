// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单 2026-10-19《UI 第二批 · 热区与静态截断》§2＋§4。
// 口径只有一句：**截断必须可见**——同屏少显示几条可以，"少显示了还不说"不行。
//
// 两层证据：
//   1. 纯函数（真行为）：计数 [moreCount]、折叠切片 [visibleItems]、技能提案的
//      标题/摘要切分 [skillProposalTitle] / [skillProposalSummary]、计划条目
//      该不该给展开入口 [planItemNeedsExpand]；
//   2. 源码门禁（Compose 渲染在 JVM 跑不起来，只能钉写法）：三处卡片必须真的在用上面
//      这些函数，而且不许留着「无计数的 take(3)」和「裸 maxLines = 1」。
// 展开/收起文案只用既有的 `common_expand_arrow` / `common_collapse_arrow`，
// 计数只用新加的**一个**键 `baopu_more_items`（中英同步）——M9/M10 钉死，不许第二套。
//
// 门禁源码统一先剥注释再压平空白（`flat`）：断言钉的是"怎么写的"，不是"怎么缩进的"。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaProposal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 第二批 B 段·静态截断（派工单 §2 三行＋§4 判据 1、2）。 */
class BaopuMoreItemsGateTest {

    private fun repoFile(rel: String): File =
        File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText()
        .replace("\r\n", "\n")
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    private fun flat(s: String): String = s.replace(Regex("\\s+"), " ")

    private fun memory(): String = flat(code("src/main/java/com/example/zhengdao/ui/baopu/BaopuMemoryCard.kt"))

    private fun skill(): String = flat(code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSkillCard.kt"))

    private fun plan(): String = flat(code("src/main/java/com/example/zhengdao/ui/baopu/BaopuPlanCard.kt"))

    private fun proposed(text: String) = PersonaProposal(
        layer = PersonaLayer.C_PROJECT,
        current = "旧索引",
        proposed = text,
        reason = "会话封卷提炼",
    )

    // ── 纯函数：计数与切片 ──────────────────────────────────────────────────

    @Test
    fun `M1_moreCount三条口径_0_恰好_超出`() {
        assertEquals("超出：5 条只显示 3 条 ⇒ 还有 2 条", 2, moreCount(total = 5, shown = 3))
        assertEquals("恰好：3 条只显示 3 条 ⇒ 没有折叠", 0, moreCount(total = 3, shown = 3))
        assertEquals("不足：2 条只显示 2 条 ⇒ 不许出负数", 0, moreCount(total = 2, shown = 3))
        assertEquals("空表 ⇒ 0", 0, moreCount(total = 0, shown = 3))
    }

    @Test
    fun `M2_visibleItems折叠态给前3条_展开态给全量`() {
        val five = (1..5).toList()
        assertEquals(listOf(1, 2, 3), visibleItems(five, expanded = false, limit = 3))
        assertEquals(five, visibleItems(five, expanded = true, limit = 3))
        val three = (1..3).toList()
        assertEquals(three, visibleItems(three, expanded = false, limit = 3))
        assertEquals("只有 3 条时展开态不多不少", three, visibleItems(three, expanded = true, limit = 3))
        assertEquals(emptyList<Int>(), visibleItems(emptyList<Int>(), expanded = false, limit = 3))
    }

    // ── 门禁 1：记忆候选卡两处 take(3) 都要配计数入口 ─────────────────────────

    @Test
    fun `M3_记忆卡两处不许再无计数截断_且两块各自独立展开`() {
        val src = memory()
        assertFalse("「无计数的 take(3)」是 §2 点名的病，一处都不许留", src.contains(".take(3)"))
        // 只数**调用点**：定义行是 `fun <T> visibleItems(`（带泛型，"fun " 不在紧邻位置），
        // 两个 lookbehind 各挡一种房型。
        assertEquals(
            "两张卡（候选／待过目）都要走折叠切片",
            2,
            Regex("(?<!fun <T> )(?<!fun )visibleItems\\(").findAll(src).count(),
        )
        assertEquals("两张卡都要有自己的「还有 N 条」", 2, Regex("(?<!fun )moreCount\\(").findAll(src).count())
        assertEquals(
            "两张卡各自一份展开状态（同屏两卡不能共用一个开关）",
            2,
            Regex("var \\w+Expanded by remember").findAll(src).count(),
        )
        assertTrue("计数文案只用那一个键", src.contains("R.plurals.baopu_more_items") || src.contains("R.string.baopu_more_items"))
        assertTrue("展开箭头复用既有键", src.contains("R.string.common_expand_arrow"))
        assertTrue("收起箭头复用既有键", src.contains("R.string.common_collapse_arrow"))
        assertEquals("同屏上限只能是 3（改这个数字要另派单）", 3, CARD_VISIBLE_LIMIT)
    }

    // ── 技能提案卡：标题＋摘要＋长内容展开＋列表折叠 ──────────────────────────

    @Test
    fun `M4_技能提案标题走索引解析器_解析不出来就退回整行不猜`() {
        val line = "【技能】写小说续写：用户要求续写章节时按此流程，先 read_file `技能/写小说续写/SKILL.md` 按它做。"
        val p = proposed("旧的一行\n$line")
        assertEquals("技能名来自数据层解析器，UI 不自己切字符串", "写小说续写", skillProposalTitle(p))
        assertEquals("摘要＝场景那句（read_file 尾巴由数据层剥掉）", "用户要求续写章节时按此流程", skillProposalSummary(p))
        assertTrue("正文除这一行还有内容 ⇒ 必须给展开入口", skillProposalHasMore(p))
        assertFalse("只有一行 ⇒ 没有可展开的东西，不许摆个假入口", skillProposalHasMore(proposed(line)))
        assertEquals(MemoryScope.PROJECT, personaScopeOf(PersonaLayer.C_PROJECT))
        assertEquals(MemoryScope.GLOBAL, personaScopeOf(PersonaLayer.B_GLOBAL))
        // 显示层不露 `**`（上游 4a8ca5b2 那条口径在这儿同样成立；存盘文本一个字不动）。
        val star = proposed("旧\n【技能】**改稿**：把稿子按三段改，先 read_file `技能/改稿/SKILL.md` 按它做。")
        assertEquals("标题去掉星号", "改稿", skillProposalTitle(star))
        assertEquals("摘要去掉星号", "把稿子按三段改", skillProposalSummary(star))
    }

    @Test
    fun `M5_技能提案解析不出索引行_标题是整行_摘要走理由`() {
        val p = proposed("这段人设改写不是索引行")
        assertEquals("这段人设改写不是索引行", skillProposalTitle(p))
        assertEquals("会话封卷提炼", skillProposalSummary(p))
        assertFalse(skillProposalHasMore(p))
    }

    @Test
    fun `M6_技能提案卡不再无上限平铺_也不再只给最后一行`() {
        val card = skill().substringAfter("fun BaopuSkillProposalCard(").substringBefore("@Composable")
        assertTrue("列表要折叠（超过 3 条给「还有 N 条」）", card.contains("visibleItems(proposals, expanded ="))
        assertTrue("折叠计数走同一个 moreCount", card.contains("moreCount(total = proposals.size"))
        assertTrue("每条给标题（技能名）", card.contains("skillProposalTitle(p)"))
        assertTrue("每条给可读摘要", card.contains("skillProposalSummary(p)"))
        assertTrue("长内容展开入口按 skillProposalHasMore 决定", card.contains("skillProposalHasMore(p)"))
        assertFalse("无上限平铺（proposals.forEach）是 §2 点名的病", card.contains("proposals.forEach"))
        assertTrue("展开/收起只用既有那一套键", card.contains("R.string.common_expand_arrow"))
        assertFalse("不许新造第二套展开文案", card.contains("R.string.skill_proposal_expand"))
    }

    // ── 计划卡条目：至少两行＋按条目记的展开 ─────────────────────────────────

    @Test
    fun `M7_计划条目收起态两行_长条目给展开入口`() {
        assertFalse("60 字以内算两行装得下 ⇒ 不摆入口", planItemNeedsExpand("短的一条"))
        assertTrue("超过阈值必须摆展开入口", planItemNeedsExpand("一".repeat(PLAN_ITEM_COLLAPSED_CHARS + 1)))
        assertFalse("阈值本身算装得下（边界不许含糊）", planItemNeedsExpand("一".repeat(PLAN_ITEM_COLLAPSED_CHARS)))
        assertEquals("收起态两行", 2, PLAN_ITEM_COLLAPSED_MAX_LINES)
    }

    @Test
    fun `M8_计划卡条目不再裸maxLines1_展开状态按条目记`() {
        val src = plan()
        assertFalse("条目 Text 不许再只 maxLines = 1（§2 点名的病）", src.contains("maxLines = 1,"))
        assertTrue("收起态仍是两行（不是 1 行省略）", src.contains("else PLAN_ITEM_COLLAPSED_MAX_LINES"))
        assertTrue("该不该摆入口走纯函数", src.contains("planItemNeedsExpand(item.text)"))
        assertTrue("展开状态按条目记（一个条目一个键）", src.contains("expandedItemIndexes"))
        assertTrue("点开看全量", src.contains("if (index in expandedItemIndexes)"))
        assertTrue("展开/收起复用既有键", src.contains("R.string.common_expand_arrow"))
        assertTrue("done/doing/todo 的判据没被这批改", src.contains("PlanRules.STATUS_DONE"))
        assertTrue("符号映射仍走 planStatusSymbol", src.contains("planStatusSymbol(item.status)"))
    }

    // ── strings：一个键、中英同步、不造第二套 ───────────────────────────────

    @Test
    fun `M9_baopu_more_items中英都有且只有一条`() {
        val zh = repoFile("src/main/res/values/strings.xml").readText()
        val en = repoFile("src/main/res/values-en/strings.xml").readText()
        val key = "name=\"baopu_more_items\""
        assertEquals("中文只能有一条", 1, Regex(key).findAll(zh).count())
        assertEquals("英文只能有一条", 1, Regex(key).findAll(en).count())
        assertTrue("中文口径要写「还有 N 条」（N 是占位符）", zh.contains("还有 %1\$d 条"))
        assertTrue("英文口径 \"%1\$d more\"", en.contains("%1\$d more"))
        assertEquals(
            "两个文件的键集合必须对称（本批只加这一个键）",
            Regex("name=\"([A-Za-z0-9_]+)\"").findAll(zh).map { it.groupValues[1] }.toSet(),
            Regex("name=\"([A-Za-z0-9_]+)\"").findAll(en).map { it.groupValues[1] }.toSet(),
        )
    }

    @Test
    fun `M10_三处卡片的展开文案都只用既有两键`() {
        val src = memory() + skill() + plan()
        val keys = Regex("R\\.string\\.(\\w+)").findAll(src).map { it.groupValues[1] }.toSet()
        val expandish = keys.filter { it.contains("expand") || it.contains("collapse") }.toSet()
        assertEquals(
            "展开/收起文案只许 common_expand_arrow / common_collapse_arrow，实际：$expandish",
            setOf("common_expand_arrow", "common_collapse_arrow"),
            expandish,
        )
    }
}
