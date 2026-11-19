// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.memoryprune

import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryKind
import com.example.zhengdao.baopu.MemoryQuality
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.subconscious.IdleSnapshot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第七百五十二批 · 记忆修剪**纯判据**的用例（判据①②③⑤⑥的行为面；
 * 时间/灭屏/忙碌全注入——本类零时钟零睡眠）。
 *
 * 每条判据都带**变异负例**（判据本身能被绕过它就是假的）。
 */
class PruneRulesTest {

    private companion object {
        /** 「今天」：固定时刻注入（不读钟）；2026-11-19 深夜当夜间窗口用。 */
        const val NOW = 1_770_000_000_000L

        const val DAY = MemoryQuality.DAY_MILLIS
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    /** 一条已确认条目（来源必带——落盘铁律；kind/scope/时刻/用量按用例给）。 */
    private fun entry(
        id: String,
        body: String,
        kind: MemoryKind = MemoryKind.FACT,
        scope: MemoryScope = MemoryScope.PROJECT,
        createdAt: Long = NOW - 10 * DAY,
        useCount: Int = 0,
        status: MemoryStatus = MemoryStatus.CONFIRMED,
        sourceSession: String = "s1",
    ) = MemoryEntry(
        id = id,
        scope = scope,
        kind = kind,
        title = "条目$id",
        body = body,
        sourceSession = sourceSession,
        status = status,
        createdAt = createdAt,
        updatedAt = createdAt,
        useCount = useCount,
        asOf = if (kind == MemoryKind.FACT || kind == MemoryKind.TERM) createdAt else 0L,
    )

    /** 夜间空闲快照（窗口必开）。 */
    private fun idleNight(now: Long = NOW) = IdleSnapshot(nowMs = now, hourOfDay = 2, screenOn = false, engineBusy = false)

    /** 两条正文相同（语义近＝1.0）的 FACT，createdAt 可调。 */
    private fun dupPair(idA: String, idB: String, atA: Long, atB: Long) = listOf(
        entry(idA, "缓存刷新的时机在会话开始时执行一遍", createdAt = atA),
        entry(idB, "缓存刷新的时机在会话开始时执行一遍", createdAt = atB),
    )

    // ── 判据⑤：只在空闲窗口跑（沿用 676/678 同一把尺） ──────────────────────

    @Test
    fun 判据5_前台忙碌时不开窗() {
        val busy = IdleSnapshot(NOW, hourOfDay = 2, screenOn = false, engineBusy = true)
        val round = runPruneRound(listOf(entry("a", "内容")), busy, null, "r1")
        assertFalse("负例：前台忙碌时修剪必须不跑", round.ran)
        assertTrue("0 产物是结构保证", round.merges.isEmpty() && round.retires.isEmpty())
    }

    @Test
    fun 判据5_负例_忙碌时即使夜间灭屏也不跑() {
        // 把忙碌单独抽出来钉死：夜间＋灭屏都满足也拦（678 同款负例）。
        val busy = IdleSnapshot(NOW, hourOfDay = 23, screenOn = false, engineBusy = true)
        assertFalse(runPruneRound(emptyList(), busy, null, "r").ran)
    }

    @Test
    fun 判据5_非夜间且未灭屏不开窗() {
        val day = IdleSnapshot(NOW, hourOfDay = 14, screenOn = true, engineBusy = false)
        assertFalse(runPruneRound(emptyList(), day, null, "r").ran)
    }

    @Test
    fun 判据5_灭屏即开窗() {
        val off = IdleSnapshot(NOW, hourOfDay = 14, screenOn = false, engineBusy = false)
        assertTrue(runPruneRound(emptyList(), off, null, "r").ran)
    }

    // ── 天闸 ────────────────────────────────────────────────────────────────

    @Test
    fun 天闸_同一天第二轮不跑() {
        val round1 = runPruneRound(emptyList(), idleNight(), null, "r1")
        assertTrue(round1.ran)
        val round2 = runPruneRound(emptyList(), idleNight(), round1.dayKey, "r2")
        assertFalse("一天 ≤1 次（676/677/678 同一把日历尺）", round2.ran)
    }

    // ── 判据①：合并保指针（丢一条来源 ⇒ 红） ─────────────────────────────────

    @Test
    fun 判据1_语义近时间近的两条合并_指针全集保留() {
        val entries = dupPair("a", "b", NOW - 3 * DAY, NOW - 2 * DAY)
        val round = runPruneRound(entries, idleNight(), null, "r1")
        assertEquals("一对重复 ⇒ 一组合并", 1, round.merges.size)
        val plan = round.merges.first()
        assertEquals("指针全集 = 两条 id（含主体）", setOf("a", "b"), plan.survivor.mergedFrom.toSet())
        assertEquals("被吸收一条，原 id 不变", listOf("b"), plan.absorbed.map { it.id })
        assertEquals("判据①负例的靶子：合并后每条 id 都能从主体追到", plan.allIds, plan.survivor.mergedFrom.sorted())
    }

    @Test
    fun 判据1_负例_丢了来源的合并计划构造不出来() {
        val survivor = entry("a", "正文").copy(mergedFrom = listOf("a"))
        val absorbed = entry("b", "正文")
        val boom = runCatching {
            MergePlan(roundId = "r", survivor = survivor, absorbed = listOf(absorbed), atMs = NOW)
        }
        assertTrue("构造期 require：丢指针的合并计划活不成（判据①先红在构造期）", boom.isFailure)
    }

    @Test
    fun 判据1_负例_语义近但时间差得远不并() {
        val entries = dupPair("a", "b", NOW - 60 * DAY, NOW - 2 * DAY)
        val round = runPruneRound(entries, idleNight(), null, "r1")
        assertTrue("同主题的两个阶段不并（时间近是必要条件）", round.merges.isEmpty())
        assertTrue("被考虑过但放下 ⇒ 进跳过账", round.skipped >= 2)
        assertTrue(round.skipDetail.contains("时间差得远"))
    }

    @Test
    fun 判据1_负例_时间近但语义不近不并() {
        val entries = listOf(
            entry("a", "缓存刷新在会话开始时执行", createdAt = NOW - 2 * DAY),
            entry("b", "导出报表的格式遵循用户模板的列宽设定", createdAt = NOW - 2 * DAY),
        )
        assertTrue(runPruneRound(entries, idleNight(), null, "r").merges.isEmpty())
    }

    @Test
    fun 合并视图与质量环同一房型_正文取原件原文_不做摘要压缩() {
        val same = "缓存刷新的时机在会话开始时执行一遍并写运行日志"
        val older = entry("a", same, createdAt = NOW - 3 * DAY, useCount = 2)
        val newer = entry("b", same, createdAt = NOW - 2 * DAY)
        val round = runPruneRound(listOf(older, newer), idleNight(), null, "r")
        val plan = round.merges.first()
        assertEquals(
            "正文是原件原文之一（同文平手取更新晚的那条），不是新造的摘要",
            newer.body,
            plan.survivor.body,
        )
        assertEquals("用量账聚合（MemoryQuality.merged 同一房型）", older.useCount + newer.useCount, plan.survivor.useCount)
        assertEquals("时间账聚合：创建取最早", older.createdAt, plan.survivor.createdAt)
        assertTrue("标记带 MERGED（质量环同一字面）", plan.survivor.qualityFlags.contains(MemoryQuality.FLAG_MERGED))
    }

    @Test
    fun 被吸收条目只标状态_正文来源一字不改() {
        val entries = dupPair("a", "b", NOW - 3 * DAY, NOW - 2 * DAY)
        val absorbedBefore = entries[1]
        val plan = runPruneRound(entries, idleNight(), null, "r").merges.first()
        val after = plan.absorbed.first()
        assertEquals("正文不动", absorbedBefore.body, after.body)
        assertEquals("来源不动", absorbedBefore.sourceSession, after.sourceSession)
        assertEquals("id 不变（文件原地）", absorbedBefore.id, after.id)
        assertEquals("只有状态与标记两位变了", MemoryStatus.RETIRED, after.status)
        assertTrue(after.qualityFlags.contains(MemoryQuality.FLAG_MERGED))
    }

    // ── 判据③：反向清单永不自动退役（白名单闸） ──────────────────────────────

    @Test
    fun 判据3_负例_坑表条目超期未引用也不退役() {
        val lesson = entry("l1", "部署脚本里的坑：先停服务再换证书", kind = MemoryKind.LESSON, createdAt = NOW - 120 * DAY)
        val round = runPruneRound(listOf(lesson), idleNight(), null, "r1")
        assertTrue("LESSON（坑表）不在自动清理白名单——超期也不退役", round.retires.isEmpty())
        assertEquals("被考虑到了 ⇒ 进跳过账（反向清单）", 1, round.skipped)
        assertTrue(round.skipDetail.contains("反向清单"))
    }

    @Test
    fun 判据3_负例_规矩条目永不进合并或退役产物() {
        val rule1 = entry("r1", "工作区命名一律用客户代号", kind = MemoryKind.RULE, createdAt = NOW - 3 * DAY)
        val rule2 = entry("r2", "工作区命名一律用客户代号", kind = MemoryKind.RULE, createdAt = NOW - 2 * DAY)
        val round = runPruneRound(listOf(rule1, rule2), idleNight(), null, "r1")
        assertTrue("规矩只能人改——重复的规矩也不自动合并", round.merges.isEmpty())
        assertTrue(round.retires.isEmpty())
        assertEquals(2, round.skipped)
    }

    @Test
    fun 判据3_偏好画像同样不在白名单() {
        val entries = listOf(
            entry("p1", "称呼统一用老铁", kind = MemoryKind.PREFERENCE, createdAt = NOW - 120 * DAY),
            entry("p2", "用户画像是夜猫子", kind = MemoryKind.PROFILE, createdAt = NOW - 120 * DAY),
        )
        val round = runPruneRound(entries, idleNight(), null, "r1")
        assertTrue("机器不许替人淡忘（451 口径）", round.retires.isEmpty() && round.merges.isEmpty())
    }

    // ── 判据②：过期退役（超期＋未被引用 ⇒ 标 retired，不物理删） ─────────────

    @Test
    fun 判据2_超期且未被引用的事实标退役() {
        val old = entry("f1", "某次核对的取值口径记录", createdAt = NOW - 100 * DAY)
        val round = runPruneRound(listOf(old), idleNight(), null, "r1")
        assertEquals(1, round.retires.size)
        assertEquals(MemoryStatus.RETIRED, round.retires.first().entry.status)
        assertTrue(round.retires.first().entry.qualityFlags.contains(PruneLimits.FLAG_RETIRED))
        assertEquals("id 不变＝文件原地（不物理删的结构面）", "f1", round.retires.first().id)
    }

    @Test
    fun 判据2_负例_曾被引用过的不退役() {
        val used = entry("f2", "某次核对的取值口径记录", createdAt = NOW - 100 * DAY, useCount = 1)
        val round = runPruneRound(listOf(used), idleNight(), null, "r1")
        assertTrue(round.retires.isEmpty())
        assertTrue(round.skipDetail.contains("曾被引用"))
    }

    @Test
    fun 判据2_负例_未满周期的不退役() {
        val young = entry("f3", "某次核对的取值口径记录", createdAt = NOW - 30 * DAY)
        val round = runPruneRound(listOf(young), idleNight(), null, "r1")
        assertTrue(round.retires.isEmpty() && round.skipped == 0)
    }

    @Test
    fun 判据2_退役周期可配() {
        val mid = entry("f4", "某次核对的取值口径记录", createdAt = NOW - 40 * DAY)
        val strict = runPruneRound(listOf(mid), idleNight(), null, "r", retireAfterDays = 30L)
        assertEquals("喂 30 天 ⇒ 40 天闲置就到线", 1, strict.retires.size)
        val loose = runPruneRound(listOf(mid), idleNight(), null, "r", retireAfterDays = 120L)
        assertTrue("喂 120 天 ⇒ 同一条不退役", loose.retires.isEmpty())
    }

    @Test
    fun 判据2_负例_已退役的不进产物() {
        val retired = entry("f5", "某次核对的取值口径记录", createdAt = NOW - 100 * DAY, status = MemoryStatus.RETIRED)
        val round = runPruneRound(listOf(retired), idleNight(), null, "r1")
        assertTrue("只考虑 CONFIRMED——retired 不再动（幂等）", round.retires.isEmpty() && round.skipped == 0)
    }

    @Test
    fun 术语与事实同待遇_候选与待复核不进池子() {
        val term = entry("t1", "某个内部术语的口径", kind = MemoryKind.TERM, createdAt = NOW - 100 * DAY)
        val pending = entry("p9", "某次核对的取值口径记录", createdAt = NOW - 100 * DAY, status = MemoryStatus.PENDING)
        val round = runPruneRound(listOf(term, pending), idleNight(), null, "r1")
        assertEquals("术语在白名单内（事实/术语两类）", 1, round.retires.size)
        assertEquals("PENDING 不是有效态——不进池子也不进跳过账", "t1", round.retires.first().id)
    }

    @Test
    fun 合并条目本轮不再退役() {
        val entries = dupPair("a", "b", NOW - 100 * DAY, NOW - 99 * DAY)
        val round = runPruneRound(entries, idleNight(), null, "r1")
        assertEquals(1, round.merges.size)
        assertTrue("同一件事一轮只做一样：合并条目不重复退役", round.retires.isEmpty())
    }

    // ── 判据⑥：跳过 K 的口径与读数 ──────────────────────────────────────────

    @Test
    fun 判据6_读数一句带齐_合并退役跳过() {
        val entries = dupPair("a", "b", NOW - 3 * DAY, NOW - 2 * DAY) +
            entry("f9", "某次核对的取值口径记录", createdAt = NOW - 100 * DAY)
        val round = runPruneRound(entries, idleNight(), null, "r1")
        assertTrue(round.note.contains("合并 1"))
        assertTrue(round.note.contains("退役 1"))
        assertTrue(round.note.contains("跳过"))
    }

    @Test
    fun 判据6_退役限额溢出进跳过账() {
        val entries = (1..(PruneLimits.RETIRES_MAX + 3)).map { i ->
            entry("f$i", "第${i}条超期事实的口径记录$i", createdAt = NOW - 100 * DAY)
        }
        val round = runPruneRound(entries, idleNight(), null, "r1")
        assertEquals("一轮最多退役 ${PruneLimits.RETIRES_MAX} 条", PruneLimits.RETIRES_MAX, round.retires.size)
        assertTrue("溢出 3 条进跳过账（下轮继续，账上看得见）", round.skipDetail.contains("限额已满"))
    }

    @Test
    fun 健康条目不进跳过账() {
        val entries = listOf(
            entry("ok1", "最近才记的一条事实", createdAt = NOW - 5 * DAY),
            entry("ok2", "另一条最近的事实记录", createdAt = NOW - 4 * DAY),
        )
        val round = runPruneRound(entries, idleNight(), null, "r1")
        assertEquals("没被考虑过的不进 K（K 不是全库条数）", 0, round.skipped)
        assertEquals("", round.skipDetail)
    }

    // ── 确定性 ───────────────────────────────────────────────────────────────

    @Test
    fun 同输入同输出_两轮逐字段相同() {
        val entries = dupPair("a", "b", NOW - 3 * DAY, NOW - 2 * DAY) +
            entry("f1", "某次核对的取值口径记录", createdAt = NOW - 100 * DAY) +
            entry("l1", "部署脚本里的坑：先停服务再换证书", kind = MemoryKind.LESSON, createdAt = NOW - 120 * DAY)
        val r1 = runPruneRound(entries, idleNight(), null, "r1")
        val r2 = runPruneRound(entries.shuffled(), idleNight(), null, "r1")
        assertEquals("输入顺序打乱 ⇒ 结果逐字段相同（显式稳定排序）", r1, r2)
        assertNotEquals("前提自证：确实造出了动作", 0, r1.merges.size + r1.retires.size)
    }
}
