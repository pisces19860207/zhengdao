// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆质量纯判据单测（第八十九批 · 进化地基 ⑥，派工单 §3 点名：衰减只降权不删 /
 * 重复合并 / 冲突标记 / 检索排序）。
 *
 * 全部打在 [MemoryQuality] 这层纯函数上——落盘行为另有 [MemoryQualityStoreTest]，
 * 源码门禁另有 [MemoryQualityGateTest]。
 */
class MemoryQualityTest {

    private companion object {
        /** 任意固定时刻（判据只看差值，掺真实时钟就没法钉边界）。 */
        const val NOW = 1_730_000_000_000L
        const val DAY = MemoryQuality.DAY_MILLIS
    }

    private fun entry(
        id: String = "a",
        title: String = "称呼偏好",
        body: String = "用户希望被称呼为老师",
        scope: MemoryScope = MemoryScope.PROJECT,
        kind: MemoryKind = MemoryKind.PREFERENCE,
        tags: List<String> = listOf("称呼"),
        sourceSession: String = "s1",
        status: MemoryStatus = MemoryStatus.CONFIRMED,
        createdAt: Long = NOW,
        updatedAt: Long = NOW,
        useCount: Int = 0,
        lastUsedAt: Long = 0L,
        qualityScore: Int = MemoryQuality.DEFAULT_SCORE,
        qualityFlags: List<String> = emptyList(),
        mergedFrom: List<String> = emptyList(),
        scoredAt: Long = 0L,
    ) = MemoryEntry(
        id, scope, kind, title, body, tags, sourceSession, "资料库/原始/设定.md", status,
        createdAt, updatedAt, useCount, lastUsedAt, qualityScore, qualityFlags, mergedFrom, scoredAt,
    )

    /** 空闲 [days] 天的一条（创建与更新都在那一天，之后再没动静）。 */
    private fun aged(days: Long, id: String = "a", useCount: Int = 0) = entry(
        id = id,
        createdAt = NOW - days * DAY,
        updatedAt = NOW - days * DAY,
        useCount = useCount,
    )

    // ── 自然键（「同 key」到底指什么） ────────────────────────────────────

    @Test
    fun `自然键_标题手写差异算同键`() {
        val k = MemoryQuality.naturalKey(entry(title = "称呼偏好"))
        assertEquals("project/preference/称呼偏好", k)
        assertEquals("同一条记忆的两种写法应当同键", k, MemoryQuality.naturalKey(entry(title = "  称呼偏好！ ")))
        assertTrue(MemoryQuality.sameKey(entry(title = "称呼偏好"), entry(title = "称呼偏好?")))
    }

    @Test
    fun `自然键_跨scope或跨kind就是两码事`() {
        val a = entry(id = "a")
        assertNotEquals(MemoryQuality.naturalKey(a), MemoryQuality.naturalKey(entry(id = "b", scope = MemoryScope.GLOBAL)))
        assertNotEquals(MemoryQuality.naturalKey(a), MemoryQuality.naturalKey(entry(id = "b", kind = MemoryKind.FACT)))
        assertFalse(MemoryQuality.sameKey(a, entry(id = "b", scope = MemoryScope.GLOBAL)))
        assertFalse(MemoryQuality.sameKey(a, entry(id = "b", kind = MemoryKind.FACT)))
    }

    @Test
    fun `自然键_标题不同就不许互相判冲突`() {
        assertFalse(
            "两条不同标题的记忆是两条账，判成冲突就是把噪声当事故",
            MemoryQuality.verdict(entry(id = "a", title = "称呼偏好"), entry(id = "b", title = "积分规则"))
                is MemoryQuality.Conflict,
        )
    }

    // ── 衰减打分 ──────────────────────────────────────────────────────────

    @Test
    fun `衰减_当天满分_满90天掉一档_满180天两档`() {
        assertEquals(100, MemoryQuality.scoreOf(entry(), NOW))
        assertEquals("差一天不到档，不许提前扣分", 100, MemoryQuality.scoreOf(aged(89), NOW))
        assertEquals("第 1 档", 50, MemoryQuality.scoreOf(aged(90), NOW))
        assertEquals("第 2 档", 25, MemoryQuality.scoreOf(aged(180), NOW))
        assertEquals("第 3 档", 12, MemoryQuality.scoreOf(aged(270), NOW))
        assertEquals(1, MemoryQuality.decayStep(aged(90), NOW))
        assertEquals(2, MemoryQuality.decayStep(aged(200), NOW))
    }

    @Test
    fun `衰减_地板是1分而不是0分`() {
        val ancient = aged(90L * 100)
        val s = MemoryQuality.scoreOf(ancient, NOW)
        assertEquals("档位封顶到第 7 档", 7, MemoryQuality.decayStep(ancient, NOW))
        assertEquals(MemoryQuality.MIN_SCORE, s)
        assertTrue("0 分等于召不到，那就是不删之删（红线）：$s", s > 0)
    }

    @Test
    fun `衰减_时钟倒走不凭空变出满分`() {
        // now 比活动时间还早（改系统时间/时钟漂移）⇒ 空闲按 0 天算，但分数只能来自判据本身
        assertEquals(0L, MemoryQuality.idleDays(entry(createdAt = NOW + DAY), NOW))
        assertEquals(100, MemoryQuality.scoreOf(entry(createdAt = NOW + DAY), NOW))
    }

    @Test
    fun `衰减_创建改写最后使用三者取最晚`() {
        val usedRecently = aged(400, useCount = 1).copy(lastUsedAt = NOW)
        assertEquals("被用上过就是被用上过：按最后使用算", 100, MemoryQuality.scoreOf(usedRecently, NOW))
        val editedRecently = aged(400).copy(updatedAt = NOW - 90 * DAY)
        assertEquals(50, MemoryQuality.scoreOf(editedRecently, NOW))
        assertEquals(NOW, MemoryQuality.activityAt(usedRecently))
    }

    @Test
    fun `衰减_使用次数能补分但补不满也封了顶`() {
        assertEquals("50 + 3×2", 56, MemoryQuality.scoreOf(aged(90, useCount = 3), NOW))
        assertEquals("加分 20 封顶", 70, MemoryQuality.scoreOf(aged(90, useCount = 50), NOW))
        assertEquals("满分不会补成 120", 100, MemoryQuality.scoreOf(entry(useCount = 50), NOW))
        assertEquals("加分上限是个私有数，用例钉住字面值 20", 20, MemoryQuality.useBonus(entry(useCount = 50)))
    }

    @Test
    fun `标记_低于40分打STALE_以上不打`() {
        assertTrue(MemoryQuality.timeFlags(25).contains(MemoryQuality.FLAG_STALE))
        assertTrue(MemoryQuality.timeFlags(39).contains(MemoryQuality.FLAG_STALE))
        assertTrue("50 分还在及格线上，不许标过期", MemoryQuality.timeFlags(50).isEmpty())
        assertTrue(MemoryQuality.marked(aged(180), NOW).qualityFlags.contains(MemoryQuality.FLAG_STALE))
    }

    @Test
    fun `标记_打分不许抹掉已经打上的冲突标记`() {
        // 负例口径：把 marked() 改成「flags 只留时间标记」⇒ 冲突看不见 ⇒ 就是替用户选了边
        val conflicted = aged(200).copy(qualityFlags = listOf(MemoryQuality.FLAG_CONFLICT))
        val out = MemoryQuality.marked(conflicted, NOW)
        assertTrue("过期降权不能顺手抹掉冲突标记：${out.qualityFlags}", out.qualityFlags.contains(MemoryQuality.FLAG_CONFLICT))
        assertTrue(out.qualityFlags.contains(MemoryQuality.FLAG_STALE))
    }

    @Test
    fun `标记_只写账位不碰内容与来源`() {
        val before = entry(id = "a", body = "用户希望被称呼为老师", sourceSession = "s7")
        val after = MemoryQuality.marked(before, NOW - 200 * DAY)
        assertEquals("正文一个字都不许动", before.body, after.body)
        assertEquals(before.title, after.title)
        assertEquals(before.id, after.id)
        assertEquals(before.sourceSession, after.sourceSession)
        assertEquals(NOW - 200 * DAY, after.scoredAt)
    }

    // ── 同键判据：重复 vs 冲突 ────────────────────────────────────────────

    @Test
    fun `判据_同键近似算重复_差远算冲突_不同键不相干`() {
        val a = entry(id = "a", body = "用户希望被称呼为老师")
        val dup = entry(id = "b", body = "用户希望被称呼为老师吗") // 多一个字：9/10 = 0.9 ⇒ 重复
        val conflict = entry(id = "c", body = "平台积分规则是每发一条扣十积分")
        assertTrue(MemoryQuality.verdict(a, dup) is MemoryQuality.DuplicateBody)
        assertTrue(MemoryQuality.verdict(a, conflict) is MemoryQuality.Conflict)
        val otherKey = entry(id = "d", title = "积分口径", body = "平台积分规则是每发一条扣十积分")
        assertEquals(MemoryQuality.DifferentKey, MemoryQuality.verdict(a, otherKey))
    }

    @Test
    fun `判据_阈值就用第八批那把尺不另造`() {
        val sim = MemoryRules.bodySimilarity("用户希望被称呼为老师", "用户希望被称呼为老师")
        assertEquals(1.0, sim, 1e-9)
        // 相似度落在 0.8（阈值 0.9 以下）⇒ 同键也只能算冲突，不许偷偷当重复吃掉
        val below = MemoryRules.bodySimilarity("aa bb cc dd ee", "aa bb cc dd ff")
        assertTrue("构造用例的前提：$below < 0.9", below < MemoryRules.DUPLICATE_SIMILARITY)
        val v = MemoryQuality.verdict(
            entry(id = "a", title = "口径", body = "aa bb cc dd ee"),
            entry(id = "b", title = "口径", body = "aa bb cc dd ff"),
        )
        assertTrue(v is MemoryQuality.Conflict)
    }

    // ── 合并 ──────────────────────────────────────────────────────────────

    private val dupA = entry(
        id = "1-称呼偏好", body = "用户希望被称呼为老师", tags = listOf("称呼"),
        sourceSession = "s1", createdAt = NOW - 30 * DAY, updatedAt = NOW - 30 * DAY,
    )
    private val dupB = entry(
        id = "2-称呼偏好", body = "用户希望被称呼为老师吗", tags = listOf("用户"),
        sourceSession = "s2", createdAt = NOW - 10 * DAY, updatedAt = NOW - 5 * DAY, useCount = 4,
    )

    @Test
    fun `合并_保留全部来源id与标签并集与正文信息量多的那条`() {
        val m = MemoryQuality.merged(listOf(dupB, dupA), NOW)
        assertEquals("主条目取最早立账那条", "1-称呼偏好", m.id)
        assertEquals("组内 id 全留（含主条目自己）——这就是「不丢内容」的凭据", listOf("1-称呼偏好", "2-称呼偏好"), m.mergedFrom)
        assertEquals(listOf("称呼", "用户"), m.tags)
        assertEquals("正文取词多的那条", dupB.body, m.body)
        assertTrue(m.qualityFlags.contains(MemoryQuality.FLAG_MERGED))
        assertEquals("s1", m.sourceSession) // 来源跟着主条目，另一条顺着 mergedFrom 回原文
    }

    @Test
    fun `合并_时间与用量并账`() {
        val m = MemoryQuality.merged(listOf(dupA, dupB), NOW)
        assertEquals("创建取最早", NOW - 30 * DAY, m.createdAt)
        assertEquals("更新取最晚", NOW - 5 * DAY, m.updatedAt)
        assertEquals("使用数求和", 4, m.useCount)
        assertEquals("最后使用取最晚", 0L, m.lastUsedAt)
        assertEquals("分数取组内最高（合并后不许比任何原件更难看）", MemoryQuality.DEFAULT_SCORE, m.qualityScore)
    }

    @Test
    fun `合并_主条目与读数时刻无关`() {
        val v1 = MemoryQuality.merged(listOf(dupA, dupB), NOW)
        val v2 = MemoryQuality.merged(listOf(dupB, dupA), NOW + 999 * DAY)
        assertEquals("换 now 换输入顺序都不许改主体 id（否则注入前缀跟着抖）", v1.id, v2.id)
        assertEquals(v1.mergedFrom, v2.mergedFrom)
        assertEquals(v1.body, v2.body)
    }

    @Test
    fun `合并_少于两条不许调`() {
        try {
            MemoryQuality.merged(listOf(entry()), NOW)
            assertTrue("单条不该走合并（会白贴一个 MERGED 标记），必须抛", false)
        } catch (e: IllegalArgumentException) {
            assertTrue(e.message!!.contains("至少"))
        }
    }

    // ── 一次质量扫描 ──────────────────────────────────────────────────────

    private val conflictA = entry(id = "1-积分口径", title = "积分口径", body = "每发一条扣十积分")
    private val conflictB = entry(id = "2-积分口径", title = "积分口径", body = "每发一条扣二十积分")

    @Test
    fun `扫描_条数一条不少`() {
        val input = listOf(entry(id = "a"), entry(id = "b", title = "积分口径"), conflictA, conflictB, dupA, dupB)
        val p = MemoryQuality.pass(input, NOW)
        assertEquals("质量层没有删除权：scored 必须与输入同条数", input.size, p.scored.size)
        assertEquals("原样清单排序后也不许少一条", input.size, p.rankedList.size)
        assertEquals(
            "输入里的每条 id 都要在 scored 里出现（合并只发生在视图，不发生在原样清单）",
            input.map { it.id }.toSet(), p.scored.map { it.id }.toSet(),
        )
    }

    @Test
    fun `扫描_重复合并成一条视图_冲突两侧都留着`() {
        val p = MemoryQuality.pass(listOf(dupA, dupB, conflictA, conflictB), NOW)
        val mergedKeys = p.merged.groupBy { MemoryQuality.naturalKey(it) }
        val dupView = mergedKeys.getValue(MemoryQuality.naturalKey(dupA))
        assertEquals("同键重复塌成一条视图", 1, dupView.size)
        assertEquals(listOf(dupA.id, dupB.id), dupView.first().mergedFrom)
        val conflictView = mergedKeys.getValue(MemoryQuality.naturalKey(conflictA))
        assertEquals("冲突不选边：视图里必须还是两条", 2, conflictView.size)
        assertTrue("两侧都要带 CONFLICT：${conflictView.map { it.qualityFlags }}",
            conflictView.all { it.qualityFlags.contains(MemoryQuality.FLAG_CONFLICT) })
        assertEquals(listOf(listOf(conflictA.id, conflictB.id).sorted()), p.conflicts)
    }

    @Test
    fun `扫描_单条的键不贴MERGED标记也不写mergedFrom`() {
        val p = MemoryQuality.pass(listOf(entry(id = "only")), NOW)
        assertEquals(1, p.merged.size)
        val only = p.merged.first()
        assertEquals("only", only.id)
        assertTrue("没发生合并就别留合并账：${only.qualityFlags}", only.qualityFlags.isEmpty())
        assertTrue(only.mergedFrom.isEmpty())
    }

    @Test
    fun `扫描_确定性_打乱输入两次逐字段一致`() {
        val input = listOf(entry(id = "a"), dupA, dupB, conflictA, conflictB, aged(200, id = "old"))
        val p1 = MemoryQuality.pass(input, NOW)
        val p2 = MemoryQuality.pass(input.reversed(), NOW)
        assertEquals(p1.merged, p2.merged)
        assertEquals(p1.rankedList, p2.rankedList)
        assertEquals(p1.conflicts, p2.conflicts)
        assertEquals(p1.audits, p2.audits)
    }

    @Test
    fun `扫描_审计有账可查但不含记忆正文`() {
        val secret = "只可意会的正文甲"
        val p = MemoryQuality.pass(listOf(dupA, dupB, conflictA, conflictB, aged(200, id = "old")), NOW)
        val actions = p.audits.map { it.action }.toSet()
        assertTrue("合并要留账：${p.audits}", actions.contains(MemoryQuality.ACT_MERGE))
        assertTrue("冲突要留账：${p.audits}", actions.contains(MemoryQuality.ACT_CONFLICT))
        assertTrue("降权要留账：${p.audits}", actions.contains(MemoryQuality.ACT_STALE))
        val text = p.audits.joinToString("|") { "${it.action}#${it.target}#${it.detail}" }
        assertFalse("审计要能贴日志：不许带记忆正文取值", text.contains(secret))
        assertFalse(text.contains(conflictA.body))
    }

    @Test
    fun `扫描_过期只降权不删除_也不改正文`() {
        val old = aged(200, id = "old")
        val p = MemoryQuality.pass(listOf(entry(id = "fresh"), old), NOW)
        assertEquals(2, p.scored.size)
        assertEquals("过期排后面，但两条都在", listOf("fresh", "old"), p.rankedList.map { it.id })
        val o = p.scored.first { it.id == "old" }
        assertEquals(25, o.qualityScore)
        assertEquals(old.body, o.body)
        assertTrue(o.qualityFlags.contains(MemoryQuality.FLAG_STALE))
    }

    @Test
    fun `可逆_unmarked之后回到没算过分的原样`() {
        val original = entry(id = "a", qualityScore = 7, qualityFlags = listOf(MemoryQuality.FLAG_STALE), mergedFrom = listOf("x"), scoredAt = 123L)
        val cleared = MemoryQuality.unmarked(original)
        assertEquals(MemoryQuality.DEFAULT_SCORE, cleared.qualityScore)
        assertTrue(cleared.qualityFlags.isEmpty())
        assertTrue(cleared.mergedFrom.isEmpty())
        assertEquals(0L, cleared.scoredAt)
        assertEquals("清账只清账位，正文与来源原样", original.body, cleared.body)
        assertEquals(original.sourceSession, cleared.sourceSession)
    }

    // ── 排序 ──────────────────────────────────────────────────────────────

    @Test
    fun `排序_分数降序_同分按创建_再同按id`() {
        val low = entry(id = "low", qualityScore = 10, createdAt = 1L)
        val hiOld = entry(id = "hi-old", qualityScore = 90, createdAt = 1L)
        val hiNewer = entry(id = "hi-new", qualityScore = 90, createdAt = 2L)
        val hiSameTimeB = entry(id = "b", qualityScore = 90, createdAt = 2L)
        val order = MemoryQuality.ranked(listOf(low, hiNewer, hiOld, hiSameTimeB)).map { it.id }
        assertEquals(listOf("hi-old", "b", "hi-new", "low"), order)
    }

    @Test
    fun `排序_全平时逐位等于第八批的旧序`() {
        // 兼容证据：老库默认满分 ⇒ 新排序必须与 (createdAt, id) 一模一样，升级当天前缀不动
        val legacy = listOf(
            entry(id = "b", createdAt = 2L, qualityScore = MemoryQuality.DEFAULT_SCORE),
            entry(id = "a", createdAt = 3L, qualityScore = MemoryQuality.DEFAULT_SCORE),
            entry(id = "c", createdAt = 1L, qualityScore = MemoryQuality.DEFAULT_SCORE),
        )
        val old = legacy.sortedWith(compareBy({ it.createdAt }, { it.id }))
        assertEquals(old.map { it.id }, MemoryQuality.ranked(legacy).map { it.id })
    }

    @Test
    fun `排序_不裁剪不改变条数`() {
        val many = (1..9).map { entry(id = "m$it", qualityScore = it) }
        val out = MemoryQuality.ranked(many)
        assertEquals(many.size, out.size)
        assertEquals("m9", out.first().id)
        assertEquals("m1", out.last().id)
    }

    // ── 写入闸门 ──────────────────────────────────────────────────────────

    @Test
    fun `闸门_同键近似重复直接拒且原因说清`() {
        val g = MemoryQuality.gateWrite(dupB, listOf(dupA), NOW)
        assertTrue("同键近似应当拒：$g", g is MemoryQuality.WriteGate.Reject)
        val reason = (g as MemoryQuality.WriteGate.Reject).reason
        assertTrue(reason.contains("同键"))
        assertTrue("原因里要点名撞了哪条：$reason", reason.contains(dupA.id))
        assertEquals(MemoryQuality.ACT_REJECT, g.audit.action)
    }

    @Test
    fun `闸门_不同键但正文近似也拒_沿用第八批判据`() {
        val other = entry(id = "x", title = "另一标题", body = "用户希望被称呼为老师")
        val g = MemoryQuality.gateWrite(other, listOf(dupA), NOW)
        assertTrue("同 scope＋同 kind＋正文近似也应当拒：$g", g is MemoryQuality.WriteGate.Reject)
        assertTrue((g as MemoryQuality.WriteGate.Reject).reason.contains("重复"))
    }

    @Test
    fun `闸门_同键不同值放行但标记冲突`() {
        val g = MemoryQuality.gateWrite(conflictB, listOf(conflictA), NOW)
        assertTrue("同键不同值不许拒（拒了等于替用户判哪条对，那是自动选边）：$g", g is MemoryQuality.WriteGate.Ok)
        val ok = g as MemoryQuality.WriteGate.Ok
        assertTrue("新条目自己带 CONFLICT 标记：${ok.entry.qualityFlags}", ok.entry.qualityFlags.contains(MemoryQuality.FLAG_CONFLICT))
        assertTrue("要说清没覆盖谁：${ok.note}", ok.note.contains(conflictA.id) && ok.note.contains("不覆盖"))
        assertEquals(MemoryQuality.ACT_CONFLICT, ok.audits.first().action)
        assertEquals("闸门只判进来的那条，不顺手改别人的账", conflictB.id, ok.entry.id)
        assertEquals("进来的那条正文一个字没动", conflictB.body, ok.entry.body)
        val lone = MemoryQuality.pass(listOf(conflictA), NOW).scored.first()
        assertTrue("既有那条（单独看）不该被质量层标成冲突或过期：" + lone.qualityFlags.toString(), lone.qualityFlags.isEmpty())
        assertEquals("既有那条分数仍是入库分", conflictA.qualityScore, lone.qualityScore)
    }

    @Test
    fun `闸门_全新记忆放行并算好入库分`() {
        val fresh = entry(id = "new", title = "新的一条", body = "平台积分规则每发一条扣十积分")
        val g = MemoryQuality.gateWrite(fresh, listOf(conflictA), NOW)
        assertTrue(g is MemoryQuality.WriteGate.Ok)
        val ok = g as MemoryQuality.WriteGate.Ok
        assertEquals(100, ok.entry.qualityScore)
        assertTrue("全新记忆不该带任何标记：${ok.entry.qualityFlags}", ok.entry.qualityFlags.isEmpty())
        assertEquals("", ok.note)
        assertEquals(ok.entry.body, fresh.body)
    }

    @Test
    fun `闸门_空与超长仍由validate拒_本层不重造判据`() {
        for (bad in listOf(entry(id = "e1", body = "   "), entry(id = "e2", body = "x".repeat(501)))) {
            assertTrue("空/超长必须不合格（§1 第 1 条）", MemoryRules.validate(bad) is MemoryRules.Validation.Bad)
        }
    }
}
