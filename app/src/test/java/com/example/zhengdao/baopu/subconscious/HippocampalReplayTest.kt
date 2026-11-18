// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十八批 · 海马重放纯判据层的单测（JVM 直测，时间/灭屏/忙碌全注入）。
// 判据对照（派工单 §二）：
//   ① 每条带 L0 指针（缺 ⇒ 丢弃；负例：无指针条目出现在结果里 ⇒ 红——结构拒绝）
//   ② 只在空闲窗口跑（负例：前台忙碌时跑 ⇒ 红；夜间边界复用 SessionShape 22/7）
//   ③ 不自动合并（合并只出建议；产物文本是机械拼装）＋天闸 ≤1 次/天（时间注入）
package com.example.zhengdao.baopu.subconscious

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class HippocampalReplayTest {

    // ── 夹具（时间注入；不读钟） ──────────────────────────────────────────────

    /** 任意一个固定时刻（2026-11-17 23:30 UTC 的毫秒值——本测试只用它算天桶，不解释钟点）。 */
    private val nowMs: Long = 1_792_321_800_000L

    private fun idle(
        hourOfDay: Int? = 23,
        screenOn: Boolean? = true,
        engineBusy: Boolean = false,
        now: Long = nowMs,
    ) = IdleSnapshot(nowMs = now, hourOfDay = hourOfDay, screenOn = screenOn, engineBusy = engineBusy)

    private fun candidate(
        memoryId: String,
        title: String,
        body: String = "正文内容",
        kind: String = "LESSON",
        kindLabel: String = "教训",
        scope: String = "PROJECT",
        scopeLabel: String = "项目",
        sourceSession: String = "",
        l0: String? = null,
        quote: String = "",
        updatedAt: Long = nowMs - 3_600_000L,
    ) = ReplayCandidate(
        memoryId = memoryId,
        kind = kind,
        kindLabel = kindLabel,
        scope = scope,
        scopeLabel = scopeLabel,
        title = title,
        body = body,
        sourceSession = sourceSession,
        updatedAtMs = updatedAt,
        l0Value = l0,
        quote = quote,
    )

    private fun key(sessionId: Long, seq: Long, hint: String) = HippocampusKey(sessionId, seq, hint)

    // ── 判据②：空闲窗口（先忙后暗） ──────────────────────────────────────────

    @Test
    fun 判据2_负例_前台忙碌时不开窗() {
        // 夜间＋灭屏条件都满足，但任务在跑 ⇒ 不开（「前台忙碌时跑 ⇒ 红」拦在这）。
        assertEquals(IdleVerdict.BUSY, idleVerdict(idle(engineBusy = true, screenOn = false)))
    }

    @Test
    fun 判据2_夜间且无任务_窗口开() {
        assertEquals(IdleVerdict.OPEN, idleVerdict(idle(hourOfDay = 23)))
        assertEquals(IdleVerdict.OPEN, idleVerdict(idle(hourOfDay = 3)))
    }

    @Test
    fun 判据2_白天灭屏且无任务_窗口开() {
        assertEquals(IdleVerdict.OPEN, idleVerdict(idle(hourOfDay = 14, screenOn = false)))
    }

    @Test
    fun 判据2_白天亮屏_不开() {
        assertEquals(IdleVerdict.NOT_DARK, idleVerdict(idle(hourOfDay = 14, screenOn = true)))
    }

    @Test
    fun 判据2_读不到的格子不据此放行() {
        // 钟点没报＋屏幕读不到 ⇒ 不算夜间也不算灭屏 ⇒ 不开（SessionShape 同款纪律）。
        assertEquals(IdleVerdict.NOT_DARK, idleVerdict(idle(hourOfDay = null, screenOn = null)))
        // 屏幕读不到但夜间成立 ⇒ 夜间那一格独立撑开窗口。
        assertEquals(IdleVerdict.OPEN, idleVerdict(idle(hourOfDay = 23, screenOn = null)))
    }

    @Test
    fun 判据2_夜间边界与SessionShape同一把尺() {
        // 22 点起、7 点前是夜间（QuietLimits 的边界经 SessionShape.atNight 复用，不重造）。
        assertEquals(IdleVerdict.OPEN, idleVerdict(idle(hourOfDay = 22)))
        assertEquals(IdleVerdict.NOT_DARK, idleVerdict(idle(hourOfDay = 7, screenOn = true)))
    }

    @Test
    fun 判据2_负例_忙碌时跑一轮_必须零产物() {
        val round = runHippocampalReplay(
            candidates = listOf(candidate("m1", "标题", l0 = "session:1#seq:1")),
            idle = idle(engineBusy = true, screenOn = false),
            lastRunDayKey = null,
            roundId = "r1",
        )
        assertEquals(false, round.ran)
        assertEquals(0, round.rows.size)
        assertTrue(round.note.contains("任务在跑"))
    }

    // ── 天闸 ≤1 次/天 ────────────────────────────────────────────────────────

    @Test
    fun 天闸_同一天第二趟什么都不做() {
        val day = com.example.zhengdao.baopu.inspect.PatrolScheduler.dayBucket(nowMs)
        val round = runHippocampalReplay(
            candidates = listOf(candidate("m1", "标题", l0 = "session:1#seq:1")),
            idle = idle(),
            lastRunDayKey = day,
            roundId = "r2",
        )
        assertEquals(false, round.ran)
        assertTrue(round.note.contains("一天 ≤1 次"))
    }

    @Test
    fun 天闸_跨天后放行() {
        val yesterday = com.example.zhengdao.baopu.inspect.PatrolScheduler.dayBucket(nowMs) - 1
        val round = runHippocampalReplay(
            candidates = listOf(candidate("m1", "标题", l0 = "session:1#seq:1")),
            idle = idle(),
            lastRunDayKey = yesterday,
            roundId = "r3",
        )
        assertEquals(true, round.ran)
    }

    @Test
    fun 天闸_窗口没开不耗当日额度_延后不写状态() {
        // 白天亮屏跑一轮 ⇒ 没跑；这轮之后 lastRunDayKey 仍是 null（装配层只按 ran 写状态）。
        val round = runHippocampalReplay(
            candidates = listOf(candidate("m1", "标题", l0 = "session:1#seq:1")),
            idle = idle(hourOfDay = 14, screenOn = true),
            lastRunDayKey = null,
            roundId = "r4",
        )
        assertEquals(false, round.ran)
        assertTrue(round.note.contains("延后"))
    }

    // ── 判据①：缺 L0 指针即丢（缺锚定/解析不到 ⇒ 丢弃；无指针产物构造不出来） ──

    @Test
    fun 判据1_没锚上情景键的候选不进产物() {
        val round = runHippocampalReplay(
            candidates = listOf(candidate("m1", "标题", l0 = null)),
            idle = idle(),
            lastRunDayKey = null,
            roundId = "r5",
        )
        assertEquals(true, round.ran)
        assertEquals(0, round.entries.size)
        assertEquals(0, round.rows.size)
        assertTrue(round.note.contains("锚上 0"))
    }

    @Test
    fun 判据1_负例_无指针条目出现在结果里_结构拒绝() {
        // L0Pointer 构造期拒空——「无指针条目」根本构造不出来（结构保证，不是运行时检查）。
        try {
            L0Pointer("")
            fail("无指针的 L0Pointer 不许构造成功")
        } catch (expected: IllegalArgumentException) {
            assertTrue(expected.message!!.contains("缺即丢"))
        }
    }

    @Test
    fun 判据1_锚上的候选条目必带指针() {
        val round = runHippocampalReplay(
            candidates = listOf(candidate("m1", "标题", l0 = "session:7#seq:3", quote = "原话一句")),
            idle = idle(),
            lastRunDayKey = null,
            roundId = "r6",
        )
        val entry = round.entries.single()
        assertEquals("session:7#seq:3", entry.l0.value)
        assertEquals("原话一句", entry.quote)
        val row = round.rows.single()
        assertEquals(entry.l0.value, row.l0.value)
    }

    // ── 锚定（海马索引：线索 → 键） ──────────────────────────────────────────

    @Test
    fun 锚定_会话直配最硬() {
        val k1 = key(9, 1, "毫不相干的线索")
        val k2 = key(3, 2, "也不相干")
        val anchored = replayAnchorOf(candidate("m1", "标题", sourceSession = "3"), listOf(k1, k2))
        assertEquals(k2, anchored)
    }

    @Test
    fun 锚定_词面重合达标才算锚上() {
        val k = key(1, 1, "解析器缓存的缺陷修复")
        // 词面重合 0 ⇒ 锚不上。
        assertNull(replayAnchorOf(candidate("m1", "计划表导出", "完全无关的正文"), listOf(k)))
        // 重合 ≥2 ⇒ 锚上。
        val anchored = replayAnchorOf(candidate("m1", "解析器缓存", body = "缓存失效的缺陷要修"), listOf(k))
        assertEquals(k, anchored)
    }

    @Test
    fun 锚定_键空表_一律锚不上() {
        assertNull(replayAnchorOf(candidate("m1", "标题", sourceSession = "3"), emptyList()))
    }

    @Test
    fun 锚定_同输入同输出() {
        val keys = listOf(key(1, 5, "解析器缓存"), key(1, 2, "解析器缓存"))
        val c = candidate("m1", "解析器缓存的问题")
        assertEquals(replayAnchorOf(c, keys), replayAnchorOf(c, keys))
    }

    // ── 合并建议（485 口径：结构化过滤＋中文 BM25；只出建议） ────────────────

    @Test
    fun 合并_同类型同池高相似_出建议() {
        val a = candidate("m1", "构建缓存的清理方法", body = "构建缓存要在收工后清理再退出", l0 = "session:1#seq:1")
        val b = candidate("m2", "构建缓存清理", body = "退出前要清理构建缓存再收工", l0 = "session:1#seq:2")
        val round = runHippocampalReplay(listOf(a, b), idle(), null, "r7")
        assertEquals(1, round.merges.size)
        val merge = round.merges.single()
        assertEquals("session:1#seq:1", merge.l0a.value)
        assertEquals("session:1#seq:2", merge.l0b.value)
        assertTrue(merge.similarity > 0.0)
        // 建议行两条证据指针都在。
        val row = round.rows.first { it.kind == ReplayRowKind.MERGE }
        assertEquals(merge.l0a.value, row.l0.value)
        assertEquals(merge.l0b.value, row.l0Second!!.value)
    }

    @Test
    fun 合并_不同类型不出建议_结构化过滤在前() {
        val a = candidate("m1", "构建缓存的清理方法", kind = "LESSON", kindLabel = "教训", l0 = "session:1#seq:1")
        val b = candidate("m2", "构建缓存清理", kind = "FACT", kindLabel = "事实", l0 = "session:1#seq:2")
        val round = runHippocampalReplay(listOf(a, b), idle(), null, "r8")
        assertEquals(0, round.merges.size)
    }

    @Test
    fun 合并_完全无关的正文不出建议() {
        val a = candidate("m1", "构建缓存的清理方法", body = "构建缓存收工后清理", l0 = "session:1#seq:1")
        val b = candidate("m2", "天气与穿衣", body = "雨天出门记得带伞", l0 = "session:1#seq:2")
        val round = runHippocampalReplay(listOf(a, b), idle(), null, "r9")
        assertEquals(0, round.merges.size)
    }

    @Test
    fun 合并_一边缺指针就不出建议() {
        val a = candidate("m1", "构建缓存的清理方法", body = "构建缓存收工后清理", l0 = "session:1#seq:1")
        val b = candidate("m2", "构建缓存清理", body = "退出前清理构建缓存", l0 = null)
        val round = runHippocampalReplay(listOf(a, b), idle(), null, "r10")
        assertEquals(0, round.merges.size)
    }

    @Test
    fun 合并_相似度确定性_同输入同值() {
        val a = "构建缓存要在收工后清理再退出"
        val b = "退出前要清理构建缓存再收工"
        val s1 = ReplayBm25.similarity(a, b)
        val s2 = ReplayBm25.similarity(a, b)
        assertEquals(s1, s2, 0.0)
    }

    @Test
    fun 合并_同文相似度为一_无关趋零() {
        val a = "构建缓存要在收工后清理再退出"
        assertTrue(ReplayBm25.similarity(a, a) == 1.0)
        assertEquals(0.0, ReplayBm25.similarity(a, "完全不相干的另一段文本"), 0.0)
    }

    // ── 产物结构与确定性 ──────────────────────────────────────────────────────

    @Test
    fun 产物_行数与条目建议数一致() {
        val a = candidate("m1", "标题一", l0 = "session:1#seq:1")
        val round = runHippocampalReplay(listOf(a), idle(), null, "r11")
        assertEquals(round.entries.size + round.merges.size, round.rows.size)
    }

    @Test
    fun 产物_经验条目是机械拼装不生成() {
        val round = runHippocampalReplay(
            listOf(candidate("m1", "缓存清理", body = "收工清缓存", kindLabel = "教训", l0 = "session:1#seq:1", quote = "原话")),
            idle(),
            null,
            "r12",
        )
        val text = round.entries.single().text
        assertTrue(text.contains("缓存清理"))
        assertTrue(text.contains("教训"))
        assertTrue(text.contains("收工清缓存"))
        assertTrue(text.contains("原话"))
        assertTrue(text.contains("点开回原消息"))
    }

    @Test
    fun 产物_条目上限与建议上限() {
        val many = (1..10).map { i ->
            candidate(
                "m$i",
                "构建缓存条目$i",
                body = "构建缓存$i 要在收工后清理退出",
                l0 = "session:1#seq:$i",
            )
        }
        val round = runHippocampalReplay(many, idle(), null, "r13")
        assertEquals(ReplayLimits.ENTRIES_MAX, round.entries.size)
        assertTrue(round.merges.size <= ReplayLimits.MERGES_MAX)
        assertEquals(round.entries.size + round.merges.size, round.rows.size)
    }

    @Test
    fun 产物_同输入两轮逐字相等_确定性() {
        val candidates = listOf(
            candidate("m1", "构建缓存清理", body = "构建缓存收工后清理退出", l0 = "session:1#seq:1"),
            candidate("m2", "构建缓存清理法", body = "退出前清理构建缓存收工", l0 = "session:1#seq:2"),
        )
        val r1 = runHippocampalReplay(candidates, idle(), null, "rA")
        val r2 = runHippocampalReplay(candidates, idle(), null, "rA")
        assertEquals(r1.rows, r2.rows)
        assertEquals(r1.note, r2.note)
    }

    @Test
    fun 产物_候选空表_真跑但零产物() {
        val round = runHippocampalReplay(emptyList(), idle(), null, "r14")
        assertEquals(true, round.ran)
        assertEquals(0, round.rows.size)
    }

    // ── 694 键适配（约定形状；合入前装配层喂，合入后整缝交接） ────────────────

    @Test
    fun 键适配_账行带消息号_定位成键() {
        val items = listOf(
            ledgerItem("L1", "解析器缓存修复", "101"),
            ledgerItem("L2", "没带消息号的行", ""),
        )
        val keys = com.example.zhengdao.baopu.subconscious.keysOfLedger(items) { id ->
            if (id == "101") 7L to 3L else null
        }
        assertEquals(1, keys.size)
        assertEquals(key(7, 3, "解析器缓存修复"), keys.single())
    }

    @Test
    fun 键适配_定位不到的行跳过() {
        val items = listOf(ledgerItem("L1", "标题", "404"))
        val keys = com.example.zhengdao.baopu.subconscious.keysOfLedger(items) { null }
        assertEquals(0, keys.size)
    }

    @Test
    fun 键形状_与694约定一致_只出键不取正文() {
        val k = key(7, 3, "线索短语")
        assertEquals("session:7#seq:3", k.pointer)
        // 键里没有消息正文——判据⑤（694）「只出键不取内容」的形状边界。
        assertNotNull(k.hint)
        assertEquals(listOf(7L, 3L, "线索短语"), listOf(k.sessionId, k.seq, k.hint))
    }

    /** 账行夹具（[com.example.zhengdao.baopu.ledger.LedgerItem] 的最小投影）。 */
    private fun ledgerItem(id: String, title: String, sourceMsgId: String) =
        com.example.zhengdao.baopu.ledger.LedgerItem(
            id = id,
            title = title,
            sourceMsgId = sourceMsgId,
            createdAtMs = nowMs,
            status = com.example.zhengdao.baopu.ledger.LedgerState.FLYING,
        )
}
