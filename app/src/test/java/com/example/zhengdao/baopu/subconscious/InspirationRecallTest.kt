// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十七批 · 534 灵感闪回判据测试：逐条对派工单判据，负例各一条、
// 全部时间注入（不读钟）；三重命中缺一不浮是本单的心脏，三个负例各钉一重。
package com.example.zhengdao.baopu.subconscious

import com.example.zhengdao.baopu.safety.DiaryCodec
import com.example.zhengdao.baopu.safety.DiaryEvent
import com.example.zhengdao.baopu.safety.DiaryKind
import com.example.zhengdao.testkit.TempSandbox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class InspirationRecallTest {

    // ── 夹具（词面按 MemoryRules.tokensOf 的 CJK 二元组设计，语义重合可手算） ──

    private val body = "海战中要先占据上风位置"
    private val tags = listOf("海战")
    private val nearContext = "这场海战要讲究上风" // 重合二元组：海战、上风 ⇒ 2
    private val farContext = "今天天气不错适合出门散步" // 重合 0
    private val onTopic = "我们来聊聊海战的打法" // 与标签/预览重合：海战 ⇒ ≥1
    private val offTopic = "聊聊晚饭吃什么" // 重合 0
    private val NOW = 1_763_200_000_000L // 固定时刻（时间注入；不读钟）
    private val DAY_MS = 86_400_000L

    private fun candidate(
        id: String = "insp-test-1",
        body: String = this.body,
        tags: List<String> = this.tags,
        createdAtMs: Long = NOW - DAY_MS,
    ): InspirationCandidate = InspirationCandidate(id, body.take(30), tags, createdAtMs, body)

    private fun signals(
        nowMs: Long = NOW,
        contextText: String = nearContext,
        lastMessageAtMs: Long = NOW - 3_600_000L,
        latestUserText: String = onTopic,
    ): RecallSignals = RecallSignals(nowMs, contextText, lastMessageAtMs, latestUserText)

    private val keywordSelector =
        InspirationSelector { _, _ -> InspirationSelection.Unavailable }

    // ── 判据①：记一条灵感 ⇒ L0 有原文（可点开＝行/卡带原文逐字） ─────────────

    @Test
    fun 判据1_记录灵感_L0有原文含时间标签() {
        TempSandbox.use("zd677-store") { dir ->
            val store = InspirationStore(File(dir, "灵感"))
            val saved = store.record(NOW, body, tags)
            assertTrue("正常记录必须成功", saved is InspirationStore.SaveResult.Saved)
            val r = (saved as InspirationStore.SaveResult.Saved).record
            assertEquals("原文逐字", body, r.body)
            assertEquals("时刻落盘", NOW, r.createdAtMs)
            assertEquals("标签落盘", tags, r.tags)
            val scan = store.scan()
            assertEquals(1, scan.records.size)
            assertEquals("读回的原文还是逐字", body, scan.records.first().body)
            assertTrue("id 形如 insp-*", scan.records.first().id.startsWith("insp-"))
            assertEquals("坏文件 0", 0, scan.broken)
        }
    }

    @Test
    fun 判据1_渲染读回往返一致() {
        val r = InspirationRecord("insp-x", NOW, listOf("写作", "灵感"), "原文一行")
        val parsed = InspirationStore.parse(InspirationStore.render(r))
        assertNotNull(parsed)
        assertEquals(r, parsed)
    }

    @Test
    fun 判据1_空原文拒收且说原因() {
        TempSandbox.use("zd677-empty") { dir ->
            val store = InspirationStore(File(dir, "灵感"))
            val bad = store.record(NOW, "   ", emptyList())
            assertTrue(bad is InspirationStore.SaveResult.Rejected)
            assertTrue((bad as InspirationStore.SaveResult.Rejected).reason.contains("空"))
        }
    }

    @Test
    fun 判据1_超长拒收_一句话直写不是塞文章() {
        TempSandbox.use("zd677-long") { dir ->
            val store = InspirationStore(File(dir, "灵感"))
            val bad = store.record(NOW, "长".repeat(InspirationStore.BODY_LIMIT + 1), emptyList())
            assertTrue(bad is InspirationStore.SaveResult.Rejected)
            assertTrue((bad as InspirationStore.SaveResult.Rejected).reason.contains("${InspirationStore.BODY_LIMIT}"))
        }
    }

    @Test
    fun 判据1_坏文件跳过但计数_不静默() {
        TempSandbox.use("zd677-broken") { dir ->
            val d = File(dir, "灵感").apply { mkdirs() }
            File(d, "insp-good.md").writeText(
                InspirationStore.render(InspirationRecord("insp-good", NOW, emptyList(), "好条目")),
            )
            File(d, "insp-bad.md").writeText("---\nid: insp-bad\ncreated_at: 不是数字\n---\n\n坏条目")
            val scan = InspirationStore(d).scan()
            assertEquals("好条目读回", 1, scan.records.size)
            assertEquals("坏文件说出来", 1, scan.broken)
        }
    }

    // ── 判据②：三重命中才浮，缺一不浮（三个负例各一条） ───────────────────────

    @Test
    fun 判据2_正例_三重全中_浮一条带原文() {
        val round = runInspirationRecall(listOf(candidate()), signals(), keywordSelector, emptyList())
        assertEquals("浮出恰好一条", 1, round.rows.size)
        val row = round.rows.first()
        assertEquals("行 id 是稳定键", "inspiration:insp-test-1", row.id)
        assertEquals("卡面原文逐字（可点开）", body, row.body)
        assertTrue("副标题带 L0 指针", row.subtitle.contains("insp-test-1"))
        assertEquals("规则版标注", "规则", round.selectorMode)
    }

    @Test
    fun 判据2_负例一_语义不近邻_不浮() {
        val s = signals(contextText = farContext) // 词面重合 0 < 2；其余两重全中
        val round = runInspirationRecall(listOf(candidate()), s, keywordSelector, emptyList())
        assertTrue("缺语义近邻 ⇒ 不浮", round.rows.isEmpty())
        assertTrue("note 说出缺哪重", round.note.contains("语义不近邻"))
    }

    @Test
    fun 判据2_负例二_近期上下文缺_不浮() {
        val s = signals(lastMessageAtMs = NOW - 31 * DAY_MS) // 31 天前 ⇒ 出 30 天窗；其余两重全中
        val round = runInspirationRecall(listOf(candidate()), s, keywordSelector, emptyList())
        assertTrue("缺近期上下文 ⇒ 不浮", round.rows.isEmpty())
        assertTrue(round.note.contains("近期上下文"))
    }

    @Test
    fun 判据2_负例三_不同任务主题_不浮() {
        val s = signals(latestUserText = offTopic) // 主题不重合；其余两重全中
        val round = runInspirationRecall(listOf(candidate()), s, keywordSelector, emptyList())
        assertTrue("缺同任务/主题 ⇒ 不浮", round.rows.isEmpty())
        assertTrue(round.note.contains("不同任务/主题"))
    }

    @Test
    fun 判据2_边界_29天在内_31天在外() {
        assertTrue(InspirationGates.recentContext(signals(lastMessageAtMs = NOW - 29 * DAY_MS)))
        assertFalse(InspirationGates.recentContext(signals(lastMessageAtMs = NOW - 31 * DAY_MS)))
        assertFalse("上下文空白也是缺", InspirationGates.recentContext(signals(contextText = " ")))
        assertFalse("没有消息时刻也是缺", InspirationGates.recentContext(signals(lastMessageAtMs = 0L)))
    }

    @Test
    fun 判据2_词面重合按二元组数钉() {
        assertEquals(2, InspirationGates.semanticBigrams(candidate(), nearContext))
        assertEquals(0, InspirationGates.semanticBigrams(candidate(), farContext))
    }

    // ── 模型只「选不造」：选中即语义近邻；候选外 id 整条丢弃；不可用退回规则 ────

    @Test
    fun 模型选中_词面不足也算语义近邻_但另外两重仍要中() {
        val s = signals(contextText = farContext, latestUserText = onTopic) // 词面 0
        val selector = InspirationSelector { _, _ -> InspirationSelection.Picked(listOf("insp-test-1")) }
        val round = runInspirationRecall(listOf(candidate()), s, selector, emptyList())
        assertEquals("模型选中 ⇒ 语义那重成立 ⇒ 三重齐 ⇒ 浮", 1, round.rows.size)
        assertEquals("模型版标注", "模型", round.selectorMode)
    }

    @Test
    fun 模型选中_另外两重缺照样不浮() {
        val s = signals(contextText = farContext, latestUserText = offTopic) // 主题不中
        val selector = InspirationSelector { _, _ -> InspirationSelection.Picked(listOf("insp-test-1")) }
        val round = runInspirationRecall(listOf(candidate()), s, selector, emptyList())
        assertTrue("选不造只管语义那重，缺一仍不浮", round.rows.isEmpty())
    }

    @Test
    fun 模型返回候选外id_整条丢弃_退回规则版() {
        val s = signals()
        val selector = InspirationSelector { _, _ -> InspirationSelection.Picked(listOf("insp-编造的")) }
        val round = runInspirationRecall(listOf(candidate()), s, selector, emptyList())
        // 候选外 id 丢弃 ⇒ picked 空 ⇒ 关键词版（本例词面够，照样浮，但模式标「规则」）
        assertEquals(1, round.rows.size)
        assertEquals("丢弃后标规则版（低置信来源）", "规则", round.selectorMode)
    }

    @Test
    fun 候选最多喂八条() {
        assertEquals(8, MAX_CANDIDATES)
    }

    // ── 判据④：每天 ≤1 次（时间注入夹具） ────────────────────────────────────

    @Test
    fun 判据4_当天已浮过_这轮不浮() {
        val floated = listOf(InspirationLedger.floatedEvent(NOW - 60_000L, "insp-old", "旧", "依据"))
        val round = runInspirationRecall(listOf(candidate()), signals(), keywordSelector, floated)
        assertTrue("同一天第二浮 ⇒ 拦", round.rows.isEmpty())
        assertTrue(round.note.contains("每天 ≤1 次"))
    }

    @Test
    fun 判据4_前天浮过_今天还能浮() {
        val floated = listOf(InspirationLedger.floatedEvent(NOW - 2 * DAY_MS, "insp-old", "旧", "依据"))
        val round = runInspirationRecall(listOf(candidate()), signals(), keywordSelector, floated)
        assertEquals("不是今天 ⇒ 闸不拦", 1, round.rows.size)
    }

    @Test
    fun 判据4_多个全中候选_一天也只浮一条() {
        val many = listOf(candidate("insp-a"), candidate("insp-b"), candidate("insp-c"))
        val round = runInspirationRecall(many, signals(), keywordSelector, emptyList())
        assertEquals("≤1 条/天", 1, round.rows.size)
    }

    // ── 忽略窗（24h 不再浮）与账行 ────────────────────────────────────────────

    @Test
    fun 忽略24h内不再浮() {
        val events = listOf(InspirationLedger.ignoredEvent(NOW - 3_600_000L, "insp-test-1"))
        val round = runInspirationRecall(listOf(candidate()), signals(), keywordSelector, events)
        assertTrue("被忽略的那条不浮", round.rows.isEmpty())
        assertTrue(round.note.contains("忽略"))
    }

    @Test
    fun 忽略过24h_恢复候选资格() {
        val events = listOf(InspirationLedger.ignoredEvent(NOW - 25 * 3_600_000L, "insp-test-1"))
        val round = runInspirationRecall(listOf(candidate()), signals(), keywordSelector, events)
        assertEquals(1, round.rows.size)
    }

    @Test
    fun 账行_浮几条记几行_读回带id() {
        TempSandbox.use("zd677-ledger") { dir ->
            val f = InspirationLedger.fileOf(dir)
            assertTrue("落点在 产物/ 下", f.path.replace('\\', '/').endsWith("产物/灵感闪回.jsonl"))
            InspirationLedger.append(f, InspirationLedger.floatedEvent(NOW, "insp-1", "标题", "依据 L0 insp-1"))
            InspirationLedger.append(f, InspirationLedger.openedEvent(NOW + 1, "insp-1"))
            InspirationLedger.append(f, InspirationLedger.ignoredEvent(NOW + 2, "insp-2"))
            val events = InspirationLedger.read(f)
            assertEquals("本账三行", 3, events.size)
            assertTrue("浮出行带 l0 id", events[0].why.contains("·浮出·insp-1"))
            assertTrue("点开行带 l0 id", events[1].why.contains("·点开·insp-1"))
            assertTrue("忽略行带 l0 id", events[2].why.contains("·忽略·insp-2"))
            assertEquals(
                "最近浮出日桶＝当天",
                com.example.zhengdao.baopu.inspect.PatrolScheduler.dayBucket(NOW),
                InspirationLedger.lastFloatedDay(events),
            )
        }
    }

    @Test
    fun 账行_坏行跳过_别人的行不吃() {
        TempSandbox.use("zd677-ledger2") { dir ->
            val f = InspirationLedger.fileOf(dir)
            f.parentFile?.mkdirs()
            f.writeText(
                "garbage line\n" +
                DiaryCodec.encode(
                    DiaryEvent(
                        DiaryKind.ACTION,
                        NOW,
                        "别的账的行",
                        "x",
                        "y",
                    ),
                ) + "\n" +
                    DiaryCodec.encode(InspirationLedger.floatedEvent(NOW, "insp-9", "t", "r")) + "\n",
            )
            val events = InspirationLedger.read(f)
            assertEquals("坏行＋别人的行都不算本账的", 1, events.size)
            assertEquals("insp-9", events.first().why.substringAfterLast("·"))
        }
    }

    @Test
    fun 最近浮出日桶_取最新一次() {
        val events = listOf(
            InspirationLedger.floatedEvent(NOW - 3 * DAY_MS, "insp-a", "t", "r"),
            InspirationLedger.floatedEvent(NOW - DAY_MS, "insp-b", "t", "r"),
        )
        assertEquals(
            "取最新的那次",
            com.example.zhengdao.baopu.inspect.PatrolScheduler.dayBucket(NOW - DAY_MS),
            InspirationLedger.lastFloatedDay(events),
        )
        assertNull("没浮过 ⇒ null", InspirationLedger.lastFloatedDay(emptyList()))
    }

    // ── 判据③：不点不进上下文（信箱机制）＋开关默认开 ─────────────────────────

    @Test
    fun 判据3_不点_信箱空_下一轮上下文零注入() {
        com.example.zhengdao.baopu.subconscious.InspirationMailbox.clear()
        assertTrue("没人点开 ⇒ drain 空 ⇒ extraNotes 与接线前逐字节一致", InspirationMailbox.drain().isEmpty())
    }

    @Test
    fun 判据3_点开_放行一次_取走即清() {
        com.example.zhengdao.baopu.subconscious.InspirationMailbox.clear()
        com.example.zhengdao.baopu.subconscious.InspirationMailbox.offer("【灵感闪回】原文")
        val drained = InspirationMailbox.drain()
        assertEquals("放行一条", 1, drained.size)
        assertTrue(drained.first().contains("原文"))
        assertTrue("取走即清，不重放", InspirationMailbox.drain().isEmpty())
    }

    @Test
    fun 开关_默认开_可关() {
        assertTrue("没存过 ⇒ 默认开", InspirationSwitch.resolve(null))
        assertFalse("存过 false ⇒ 关", InspirationSwitch.resolve(false))
        assertTrue(InspirationSwitch.resolve(true))
    }

    @Test
    fun 行_标题30字_副标题带记录日() {
        val row = InspirationRow(
            id = "inspiration:insp-x",
            atMs = NOW,
            l0Id = "insp-x",
            body = "一".repeat(40),
            tags = listOf("写作"),
            recordedAtMs = NOW - DAY_MS,
        )
        assertEquals(30, row.title.length)
        assertTrue(row.subtitle.contains("insp-x"))
        assertTrue(row.subtitle.contains("记"))
    }

    @Test
    fun 候选预览30字() {
        val longBody = "长".repeat(40)
        val cand = InspirationCandidate("i", longBody.take(30), emptyList(), NOW, longBody)
        assertEquals(30, cand.preview.length)
        assertTrue(
            "短原文不截断",
            InspirationCandidate.of(InspirationRecord("i", NOW, emptyList(), "短")).preview == "短",
        )
    }
}
