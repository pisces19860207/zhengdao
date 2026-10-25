// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第一百九十批 · 题材与写法卡的**纯判据**测试（触发／查重／换批／写法／状态机）。
 *
 * 接线红线（确认前零落盘、卡不挡输入、落盘走 179）在
 * `ui/baopu/BaopuTopicFlowGateTest.kt`；这里只测 `baopu/TopicSuggest.kt`。
 */
class TopicSuggestTest {

    // ── 触发词 ─────────────────────────────────────────────────────────

    @Test
    fun `触发-派工单三句原话都算`() {
        assertTrue(TopicSuggest.isTopicRequest("微头条发完了"))
        assertTrue(TopicSuggest.isTopicRequest("下一篇写什么"))
        assertTrue(TopicSuggest.isTopicRequest("给我几个题材"))
    }

    @Test
    fun `触发-变体也算`() {
        assertTrue(TopicSuggest.isTopicRequest("微头条都发完了，下一篇写啥"))
        assertTrue(TopicSuggest.isTopicRequest("换个题材"))
        assertTrue(TopicSuggest.isTopicRequest("再 来 一 批"))
    }

    @Test
    fun `触发-普通聊天不误触`() {
        assertFalse(TopicSuggest.isTopicRequest("今天天气不错"))
        assertFalse(TopicSuggest.isTopicRequest("微头条怎么写才有人看"))
        assertFalse(TopicSuggest.isTopicRequest("这个题材不错的话可以多写两篇"))
        assertFalse(TopicSuggest.isTopicRequest("发文件给同事"))
    }

    // ── 已发指纹解析 ────────────────────────────────────────────────────

    @Test
    fun `slug-拍板版与v1两种文件名都认`() {
        assertEquals("楼下便利店老板娘", TopicSuggest.slugFromFileName("20261023_微头条_1_楼下便利店老板娘.md"))
        assertEquals("中年失业第三个月", TopicSuggest.slugFromFileName("微头条-20261022-0830-中年失业第三个月.md"))
        assertNull(TopicSuggest.slugFromFileName("随手记.md"))
    }

    @Test
    fun `台账行-勾选两种都解析`() {
        val ledger = """
            # 产物台账
            ## 2026-10-23 批次（10:00 落盘）
            - ☐ 20261023_微头条_1_楼下便利店老板娘.md（612 字）
            - ✔ 20261024_微头条_1_中年失业第三个月.md（655 字）
        """.trimIndent()
        val refs = TopicSuggest.publishedFromLedger(ledger)
        assertEquals(2, refs.size)
        assertEquals("楼下便利店老板娘", refs[0].slug)
        assertTrue(refs[0].source.startsWith("台账 第"))
    }

    // ── 查重（撞了哪条要留证据） ─────────────────────────────────────────

    @Test
    fun `查重-撞核心场景不许出_证据里写清撞了哪条`() {
        val published = listOf(
            TopicPublishedRef("产物/20261023_微头条_1_楼下便利店老板娘.md", "楼下便利店老板娘", "楼下便利店老板娘"),
        )
        val batch = TopicSuggest.nextBatch(published, emptySet(), offset = 0, count = 32)
        // 全池扫一遍：便利店那条不该出现在任何候选里
        assertTrue(batch.candidates.none { it.seed.id == "t06" })
        val clash = batch.clashes.firstOrNull { it.seedId == "t06" }
        assertNotNull("撞车必须留证据（撞了哪条）", clash)
        assertEquals("scene", clash!!.rule)
        assertTrue(clash.refSource.contains("楼下便利店老板娘"))
    }

    @Test
    fun `查重-标题结构雷同也拦`() {
        // 与 t02「同学聚会散场后，我把群设了免打扰」同骨架（同字数同标点位）、
        // 但不含场景词「同学聚会」的已发标题 ⇒ 走 skeleton 规则
        val twin = "战友追悼落幕后，他将队拉了禁言令"
        assertEquals(TopicSuggest.skeleton(twin), TopicSuggest.skeleton("同学聚会散场后，我把群设了免打扰"))
        val published = listOf(TopicPublishedRef("产物/20261023_微头条_2_$twin.md", twin, twin))
        val clash = TopicSuggest.clashOf(TopicSuggest.POOL.first { it.id == "t02" }, published)
        assertNotNull(clash)
        assertEquals("skeleton", clash!!.rule)
    }

    @Test
    fun `查重-骨架规则本身-数字位要参与`() {
        // 数字折成 #：同长度同数字位才算结构雷同
        assertEquals(
            TopicSuggest.skeleton("45岁被裁后我明白了三件事"),
            TopicSuggest.skeleton("30岁离婚后我悟透了两件事"),
        )
        // 少了数字位（##）⇒ 不算雷同
        assertFalse(
            TopicSuggest.skeleton("45岁被裁后我明白了三件事").equals(TopicSuggest.skeleton("被裁以后我明白了三件事")),
        )
        // 字数不同 ⇒ 不算雷同
        assertFalse(
            TopicSuggest.skeleton("45岁被裁后我明白了三件事").equals(TopicSuggest.skeleton("30岁离婚后我悟透了两个道理")),
        )
    }

    @Test
    fun `查重-没撞的照常出_理由里写清查过几条`() {
        val batch = TopicSuggest.nextBatch(emptyList(), emptySet(), offset = 0)
        assertEquals(TopicSuggest.CANDIDATES, batch.candidates.size)
        assertTrue(batch.candidates.all { it.reason.contains("台账还是空的") })
        assertFalse(batch.exhausted)
    }

    // ── 换一批零重复 ────────────────────────────────────────────────────

    @Test
    fun `换一批-与上批零重复_且默认全选`() {
        val first = TopicSuggest.nextBatch(emptyList(), emptySet(), offset = 0)
        val state = TopicFlowRules.start(first, "s1", 0)
        // 默认全选＝候选全体（派工单 §0.1「默认全选给下一张卡」）
        assertEquals(first.candidates.map { it.seed.id }.toSet(), state.selected)

        val exclude = state.shownSeedIds
        val second = TopicSuggest.nextBatch(emptyList(), exclude, offset = TopicSuggest.CANDIDATES)
        val secondIds = second.candidates.map { it.seed.id }.toSet()
        assertTrue("换一批不许和上批重复", secondIds.intersect(exclude).isEmpty())
        assertEquals(TopicSuggest.CANDIDATES, second.candidates.size)
    }

    @Test
    fun `换一批-连换多轮也不重复`() {
        val shown = mutableSetOf<String>()
        var offset = 0
        repeat(6) { round ->
            val batch = TopicSuggest.nextBatch(emptyList(), shown, offset)
            batch.candidates.forEach { c ->
                assertTrue("第 ${round + 1} 轮出现重复候选 ${c.seed.id}", shown.add(c.seed.id))
            }
            offset += TopicSuggest.CANDIDATES
        }
    }

    @Test
    fun `换一批-池子见底要如实说`() {
        // 排除到只剩 2 条没出过 ⇒ 凑不满一批，exhausted＝true、候选只有 2
        val keep = listOf("t01", "t02")
        val exclude = TopicSuggest.POOL.map { it.id }.filter { it !in keep }.toSet()
        val batch = TopicSuggest.nextBatch(emptyList(), exclude, offset = 0)
        assertTrue(batch.exhausted)
        assertEquals(2, batch.candidates.size)
    }

    // ── 写法档位 ────────────────────────────────────────────────────────

    @Test
    fun `写法-默认档是上次成功那套_没有就第一套`() {
        assertEquals("s1", TopicSuggest.defaultStyle(null).id)
        assertEquals("s3", TopicSuggest.defaultStyle("s3").id)
        assertEquals("s1", TopicSuggest.defaultStyle("不存在的").id) // 容错回落
    }

    @Test
    fun `写法-指令含题目与三要素_不同题目指令不同`() {
        val s = TopicSuggest.styleOf("s2")
        val a = TopicSuggest.writeInstruction("题目甲", s)
        val b = TopicSuggest.writeInstruction("题目乙", s)
        assertTrue(a.contains("题目甲") && a.contains(s.voice) && a.contains(s.structure))
        assertFalse(a == b)
    }

    @Test
    fun `写法-预计字数按区间`() {
        assertEquals("3 篇 × 500–700 字", TopicSuggest.estimate(3, TopicSuggest.styleOf("s1")))
    }

    // ── 状态机：确认卡不许被文字绕过 ─────────────────────────────────────

    private fun confirmState(): TopicFlowState {
        val batch = TopicSuggest.nextBatch(emptyList(), emptySet(), offset = 0)
        var s = TopicFlowRules.start(batch, "s1", 0)
        s = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.ToStyle)!!
        s = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.UseRecommended)!!
        assertEquals(TopicFlowPhase.CONFIRM, s.phase)
        return s
    }

    @Test
    fun `状态机-确认卡在等时_任何文字都不开写`() {
        val s = confirmState()
        // 负例靶心（派工单 §2「确认卡被文字绕过 ⇒ 红」）：连按钮原文也不行
        listOf("就按这些写", "确认", "开始写吧", "写", "微头条发完了", "随便聊聊").forEach { text ->
            val after = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.UserText(text))
            assertEquals("文字「$text」不许改相位", TopicFlowPhase.CONFIRM, after!!.phase)
        }
    }

    @Test
    fun `状态机-文字不改选择不改写法`() {
        val s = confirmState()
        val after = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.UserText("全选，用长的写"))!!
        assertEquals(s.selected, after.selected)
        assertEquals(s.styleId, after.styleId)
    }

    @Test
    fun `状态机-只有确认按钮开写闸_且只认CONFIRM相位`() {
        // SUGGEST/STYLE 相位下 ConfirmWrite 无效（状态原样返回）
        val batch = TopicSuggest.nextBatch(emptyList(), emptySet(), offset = 0)
        val suggest = TopicFlowRules.start(batch, "s1", 0)
        assertEquals(suggest, TopicFlowRules.reduce(suggest, TopicFlowRules.FlowEvent.ConfirmWrite))
        assertEquals(TopicFlowPhase.SUGGEST, suggest.phase)

        // CONFIRM 相位下开写：队列＝选中的那些
        val s = confirmState()
        val writing = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.ConfirmWrite)!!
        assertEquals(TopicFlowPhase.WRITING, writing.phase)
        assertEquals(TopicSuggest.CANDIDATES, writing.writing!!.queue.size)

        // 只选 1 条 ⇒ 只写 1 条（点选只在 SUGGEST 相位生效——确认卡上没有改选入口，
        // 要改走「改题材」回建议卡）
        val batch2 = TopicSuggest.nextBatch(emptyList(), emptySet(), offset = 0)
        var st = TopicFlowRules.start(batch2, "s1", 0)
        batch2.candidates.drop(1).forEach { c ->
            st = TopicFlowRules.reduce(st, TopicFlowRules.FlowEvent.Select(c.seed.id))!!
        }
        assertEquals(setOf(batch2.candidates.first().seed.id), st.selected)
        st = TopicFlowRules.reduce(st, TopicFlowRules.FlowEvent.ToStyle)!!
        st = TopicFlowRules.reduce(st, TopicFlowRules.FlowEvent.UseRecommended)!!
        val w2 = TopicFlowRules.reduce(st, TopicFlowRules.FlowEvent.ConfirmWrite)!!
        assertEquals(1, w2.writing!!.queue.size)
    }

    @Test
    fun `状态机-逐篇记账_全部写完转DONE`() {
        val s = confirmState()
        var w = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.ConfirmWrite)!!
        val n = w.writing!!.queue.size
        repeat(n - 1) { i ->
            w = TopicFlowRules.reduce(w, TopicFlowRules.FlowEvent.ArticleDone(TopicArticleResult(w.writing!!.queue[i].seed.title, true, "path")))!!
            assertEquals(TopicFlowPhase.WRITING, w.phase)
            assertEquals(i + 1, w.writing!!.done)
        }
        w = TopicFlowRules.reduce(w, TopicFlowRules.FlowEvent.ArticleDone(TopicArticleResult(w.writing!!.queue.last().seed.title, false, "落盘失败")))!!
        assertEquals(TopicFlowPhase.DONE, w.phase)
        assertEquals(n, w.writing!!.results.size)
        assertEquals(1, w.writing!!.results.count { !it.ok }) // 失败可知哪篇没落
    }

    @Test
    fun `状态机-改一档就地切下一套_末尾绕回`() {
        val batch = TopicSuggest.nextBatch(emptyList(), emptySet(), offset = 0)
        var s = TopicFlowRules.reduce(TopicFlowRules.start(batch, "s1", 0), TopicFlowRules.FlowEvent.ToStyle)!!
        s = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.ShiftStyle)!!
        assertEquals("s2", s.styleId)
        assertEquals(TopicFlowPhase.CONFIRM, s.phase) // 改一档后直接进确认卡
        s = TopicFlowRules.reduce(TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.BackToStyle)!!, TopicFlowRules.FlowEvent.ShiftStyle)!!
        assertEquals("s3", s.styleId)
        s = TopicFlowRules.reduce(TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.BackToStyle)!!, TopicFlowRules.FlowEvent.ShiftStyle)!!
        assertEquals("s1", s.styleId) // 末尾绕回
    }

    @Test
    fun `状态机-确认卡能回头改题材改写法`() {
        val s = confirmState()
        assertEquals(
            TopicFlowPhase.SUGGEST,
            TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.BackToSuggest)!!.phase,
        )
        assertEquals(
            TopicFlowPhase.STYLE,
            TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.BackToStyle)!!.phase,
        )
    }

    @Test
    fun `状态机-写作中停下转DONE_保留已有结果`() {
        val s = confirmState()
        val w = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.ConfirmWrite)!!
        val after1 = TopicFlowRules.reduce(w, TopicFlowRules.FlowEvent.ArticleDone(TopicArticleResult("甲", true, "p")))!!
        val stopped = TopicFlowRules.reduce(after1, TopicFlowRules.FlowEvent.StopWrite)!!
        assertEquals(TopicFlowPhase.DONE, stopped.phase)
        assertEquals(1, stopped.writing!!.results.size)
    }

    @Test
    fun `状态机-换批后shown只增不减_选中重置全选`() {
        val first = TopicSuggest.nextBatch(emptyList(), emptySet(), offset = 0)
        var s = TopicFlowRules.start(first, "s1", 0)
        // 取消一条选中
        s = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.Select(first.candidates.first().seed.id))!!
        val second = TopicSuggest.nextBatch(emptyList(), s.shownSeedIds, offset = 4)
        s = TopicFlowRules.reduce(s, TopicFlowRules.FlowEvent.BatchSwapped(second, 4))!!
        assertEquals(2, s.batchNo)
        assertEquals(
            (first.candidates.map { it.seed.id } + second.candidates.map { it.seed.id }).toSet(),
            s.shownSeedIds,
        )
        assertEquals(second.candidates.map { it.seed.id }.toSet(), s.selected) // 新批默认全选
    }
}
