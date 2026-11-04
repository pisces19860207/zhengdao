// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.TimeFormats
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 记忆分层纯判据单测（第四百三十八批）：三类映射／生命周期四态／事实 asOf 收录判据／
 * 90 天过期／偏好一句话覆盖／仅供参考渲染。
 *
 * 全部打在 [MemoryTiers] 纯函数上——落盘闸另有 [MemoryStoreTest]，注入行为另有
 * [ContextAssemblerTest]，三态红线另有 [MemoryTriStateGateTest]。
 */
class MemoryTiersTest {

    private companion object {
        /** 任意固定时刻（判据只看差值，掺真实时钟就没法钉边界）。 */
        const val NOW = 1_730_000_000_000L
        const val DAY = MemoryQuality.DAY_MILLIS
        const val TTL = MemoryTiers.FACT_TTL_MILLIS
    }

    private fun e(
        id: String = "a",
        kind: MemoryKind = MemoryKind.FACT,
        title: String = "模型X支持长文",
        body: String = "模型X于核实日支持长文",
        status: MemoryStatus = MemoryStatus.CONFIRMED,
        asOf: Long = NOW,
        createdAt: Long = NOW,
        updatedAt: Long = NOW,
    ) = MemoryEntry(
        id = id,
        scope = MemoryScope.PROJECT,
        kind = kind,
        title = title,
        body = body,
        sourceSession = "s1",
        sourceFile = "资料库/原始/设定.md",
        status = status,
        asOf = asOf,
        createdAt = createdAt,
        updatedAt = updatedAt,
    )

    /** 同键不同正文的偏好对（塌覆盖的用例料）。 */
    private fun pref(id: String, body: String, createdAt: Long = NOW) = e(
        id = id, kind = MemoryKind.PREFERENCE, title = "称呼偏好", body = body,
        asOf = createdAt, createdAt = createdAt, updatedAt = createdAt,
    )

    // ── 三类映射 ──────────────────────────────────────────────────────────

    @Test
    fun `三类映射_六个kind落三类_不留第四类`() {
        assertEquals(MemoryTier.PREFERENCE, MemoryTiers.tierOf(MemoryKind.PREFERENCE))
        assertEquals(MemoryTier.PREFERENCE, MemoryTiers.tierOf(MemoryKind.PROFILE))
        assertEquals(MemoryTier.RULE, MemoryTiers.tierOf(MemoryKind.RULE))
        assertEquals(MemoryTier.RULE, MemoryTiers.tierOf(MemoryKind.LESSON))
        assertEquals(MemoryTier.FACT, MemoryTiers.tierOf(MemoryKind.FACT))
        assertEquals(MemoryTier.FACT, MemoryTiers.tierOf(MemoryKind.TERM))
    }

    @Test
    fun `三类标签与生命周期标签`() {
        assertEquals("偏好", MemoryTiers.tierLabel(MemoryTier.PREFERENCE))
        assertEquals("规矩", MemoryTiers.tierLabel(MemoryTier.RULE))
        assertEquals("事实", MemoryTiers.tierLabel(MemoryTier.FACT))
        assertEquals("候选", MemoryTiers.lifecycleLabel(MemoryLifecycle.CANDIDATE))
        assertEquals("复核", MemoryTiers.lifecycleLabel(MemoryLifecycle.REVIEW))
        assertEquals("有效", MemoryTiers.lifecycleLabel(MemoryLifecycle.ACTIVE))
        assertTrue("过期的标签要说清去向，别让读者以为是删了：",
            MemoryTiers.lifecycleLabel(MemoryLifecycle.EXPIRED).contains("仅供参考"))
    }

    // ── 事实的 90 天保质期（判据：满 90 天整才过期） ──────────────────────

    @Test
    fun `过期线_事实按asOf起算_满90天整才过期`() {
        val fact = e(asOf = NOW)
        assertFalse("第 89 天还活着", MemoryTiers.isExpired(fact, NOW + 89 * DAY))
        assertTrue("满 90 天整就过期（≥ 判据）", MemoryTiers.isExpired(fact, NOW + TTL))
        assertEquals(NOW + TTL, MemoryTiers.expiresAt(fact))
    }

    @Test
    fun `过期线_老条目没有asOf按createdAt兜底`() {
        val old = e(asOf = 0L, createdAt = NOW)
        assertEquals("asOf 缺失不追溯拒收，过期线照算：", NOW + TTL, MemoryTiers.expiresAt(old))
        assertTrue(MemoryTiers.isExpired(old, NOW + TTL))
    }

    @Test
    fun `过期线_偏好与规矩不过期`() {
        for (kind in listOf(MemoryKind.PREFERENCE, MemoryKind.PROFILE, MemoryKind.RULE, MemoryKind.LESSON)) {
            val old = e(kind = kind, asOf = NOW - 365 * DAY, createdAt = NOW - 365 * DAY)
            assertEquals("kind=$kind 没有过期线", 0L, MemoryTiers.expiresAt(old))
            assertFalse("kind=$kind 一年后也不过期", MemoryTiers.isExpired(old, NOW))
        }
    }

    @Test
    fun `过期线_时间线都拿不到就不判过期`() {
        val timeless = e(asOf = 0L, createdAt = 0L)
        assertEquals("没有时间线就不发明时间线", 0L, MemoryTiers.expiresAt(timeless))
        assertFalse(MemoryTiers.isExpired(timeless, NOW))
    }

    @Test
    fun `过期_调用方没给时刻恒不过期`() {
        val fact = e(asOf = NOW - 365 * DAY)
        assertFalse("now=0 是旧口径（组装器不传时刻＝行为不变）", MemoryTiers.isExpired(fact, 0L))
    }

    // ── 收录判据（事实无 source＋asOf 不收） ──────────────────────────────

    @Test
    fun `收录_事实没有asOf不收_理由说清`() {
        val g = MemoryTiers.acceptance(e(asOf = 0L))
        assertTrue(g is MemoryTiers.Acceptance.Bad)
        val reason = (g as MemoryTiers.Acceptance.Bad).reason
        assertTrue(reason.contains("asOf"))
        assertTrue("理由要说这是哪条判据：$reason", reason.contains("438"))
    }

    @Test
    fun `收录_事实带asOf放行_原样`() {
        val fact = e(asOf = NOW)
        val g = MemoryTiers.acceptance(fact)
        assertTrue(g is MemoryTiers.Acceptance.Ok)
        assertEquals(fact, (g as MemoryTiers.Acceptance.Ok).entry)
    }

    @Test
    fun `收录_非事实类没有asOf照收`() {
        // TERM 属事实类（要 asOf），不在这张名单里——三类映射用例已钉。
        for (kind in listOf(MemoryKind.PREFERENCE, MemoryKind.RULE, MemoryKind.LESSON, MemoryKind.PROFILE)) {
            val g = MemoryTiers.acceptance(e(kind = kind, asOf = 0L))
            assertTrue("kind=$kind 没有 asOf 也照收：$g", g is MemoryTiers.Acceptance.Ok)
        }
        val term = MemoryTiers.acceptance(e(kind = MemoryKind.TERM, asOf = 0L))
        assertTrue("术语是事实类：没有 asOf 同样不收：$term", term is MemoryTiers.Acceptance.Bad)
    }

    // ── 生命周期四态（读时判、零写入） ────────────────────────────────────

    @Test
    fun `生命周期_四态对法`() {
        assertEquals(MemoryLifecycle.CANDIDATE, MemoryTiers.lifecycleOf(e(status = MemoryStatus.CANDIDATE), NOW))
        assertEquals(MemoryLifecycle.REVIEW, MemoryTiers.lifecycleOf(e(status = MemoryStatus.PENDING), NOW))
        assertEquals(MemoryLifecycle.ACTIVE, MemoryTiers.lifecycleOf(e(status = MemoryStatus.CONFIRMED), NOW))
        assertEquals(
            "确认过的事实过了保质期 ⇒ 过期（读时判，不落盘改状态）",
            MemoryLifecycle.EXPIRED,
            MemoryTiers.lifecycleOf(e(asOf = NOW - TTL), NOW),
        )
        assertEquals("被拒不进四态（null＝当没有这条）",
            null, MemoryTiers.lifecycleOf(e(status = MemoryStatus.REJECTED), NOW))
    }

    @Test
    fun `生命周期_偏好再老也是有效`() {
        val old = e(kind = MemoryKind.PREFERENCE, asOf = NOW - 365 * DAY, createdAt = NOW - 365 * DAY)
        assertEquals(MemoryLifecycle.ACTIVE, MemoryTiers.lifecycleOf(old, NOW))
    }

    // ── 注入资格（候选不可用于判断；有效可作提示） ────────────────────────

    @Test
    fun `注入_只有有效能进_候选复核被拒不进`() {
        assertTrue(MemoryTiers.injectable(e(status = MemoryStatus.CONFIRMED)))
        for (s in listOf(MemoryStatus.CANDIDATE, MemoryStatus.PENDING, MemoryStatus.REJECTED)) {
            assertFalse("status=$s 不可用于判断（生命周期判据）", MemoryTiers.injectable(e(status = s)))
        }
        assertTrue("过期事实仍可进（仅供参考形态，标注而不隐藏）", MemoryTiers.injectable(e(asOf = NOW - TTL)))
    }

    // ── 偏好的一句话覆盖 ──────────────────────────────────────────────────

    @Test
    fun `覆盖_只对偏好成立`() {
        assertTrue(MemoryTiers.overridable(MemoryTier.PREFERENCE))
        for (t in listOf(MemoryTier.RULE, MemoryTier.FACT)) {
            assertFalse("tier=$t 不吃一句话覆盖", MemoryTiers.overridable(t))
        }
    }

    @Test
    fun `塌覆盖_同键偏好留最新_其余出视图_顺序保持`() {
        val old = pref("1-称呼偏好", "叫我老师", createdAt = NOW - 2 * DAY)
        val new = pref("2-称呼偏好", "改叫老板", createdAt = NOW - 1 * DAY)
        assertEquals(listOf(new.id), MemoryTiers.collapseOverrides(listOf(old, new)).map { it.id })
        assertEquals("输入顺序反过来，塌完还是留最新", listOf(new.id), MemoryTiers.collapseOverrides(listOf(new, old)).map { it.id })
        assertEquals("塌完留下的就是新句那条（旧条不在视图里）", new, MemoryTiers.collapseOverrides(listOf(old, new)).single())
    }

    @Test
    fun `塌覆盖_平手同创建取id升序`() {
        val a = pref("a-称呼偏好", "叫老师")
        val b = pref("b-称呼偏好", "叫师傅")
        assertEquals("创建时间平手 ⇒ id 升序那条算「最新」（与 primaryOf 同房型）",
            listOf(a.id), MemoryTiers.collapseOverrides(listOf(b, a)).map { it.id })
    }

    @Test
    fun `塌覆盖_不同键与规矩事实同键多条都不动`() {
        val factA = e(id = "f1", title = "模型X支持长文", body = "支持 8k")
        val factB = e(id = "f2", title = "模型X支持长文", body = "支持 128k")
        val ruleA = e(id = "r1", kind = MemoryKind.LESSON, title = "部署坑", body = "先备份")
        val ruleB = e(id = "r2", kind = MemoryKind.LESSON, title = "部署坑", body = "先停机")
        val other = pref("p1", "叫我老师")
        val input = listOf(factA, factB, ruleA, ruleB, other)
        assertEquals("事实与规矩的同键多条原样保留（冲突等人裁，不自动选边）", input, MemoryTiers.collapseOverrides(input))
    }

    @Test
    fun `塌覆盖_单条与空原样返回`() {
        val one = listOf(pref("p1", "叫我老师"))
        assertEquals(one, MemoryTiers.collapseOverrides(one))
        assertEquals(emptyList<MemoryEntry>(), MemoryTiers.collapseOverrides(emptyList()))
    }

    @Test
    fun `塌覆盖_确定性_打乱输入留下的集合一致`() {
        val input = listOf(
            pref("1-称呼偏好", "叫我老师", createdAt = NOW - 2 * DAY),
            pref("2-称呼偏好", "改叫老板", createdAt = NOW - 1 * DAY),
            e(id = "f1", title = "模型X支持长文"),
            e(id = "f2", title = "另一条事实", body = "另一件事"),
        )
        // 塌覆盖只决定「谁留下」，不决定顺序（顺序跟随输入，装配侧的 sticky/new 语义说了算）
        // ⇒ 判确定性的是留下的 id 集合，不是列表逐位。
        assertEquals(
            MemoryTiers.collapseOverrides(input).map { it.id }.toSet(),
            MemoryTiers.collapseOverrides(input.reversed()).map { it.id }.toSet(),
        )
        assertEquals(setOf("2-称呼偏好", "f1", "f2"), MemoryTiers.collapseOverrides(input).map { it.id }.toSet())
    }

    // ── 注入渲染（过期 ⇒ 仅供参考） ──────────────────────────────────────

    @Test
    fun `渲染_未过期与老口径逐字相同`() {
        val fresh = e()
        assertEquals(MemoryRules.renderForContext(fresh), MemoryTiers.renderForContext(fresh, NOW))
        assertEquals("now=0（旧口径）也逐字相同", MemoryRules.renderForContext(fresh), MemoryTiers.renderForContext(fresh, 0L))
    }

    @Test
    fun `渲染_过期降格仅供参考_带核实日期与复核指引`() {
        val expired = e(asOf = NOW - TTL)
        val rendered = MemoryTiers.renderForContext(expired, NOW)
        assertTrue("类型标签带降格记号：$rendered", rendered.contains("【事实·仅供参考】"))
        assertTrue("正文一个字不少：$rendered", rendered.contains(expired.body))
        assertTrue("说清哪天核实的：$rendered", rendered.contains(TimeFormats.dayStamp(NOW - TTL)))
        assertTrue("说清超了多少天：$rendered", rendered.contains("90 天保质期"))
        assertTrue("给出续用路径：$rendered", rendered.contains("复核"))
        assertFalse("不许把过期条目冒充现行事实：$rendered", rendered.contains("【事实】"))
    }

    @Test
    fun `渲染_规矩与偏好永不吃仅供参考`() {
        val oldRule = e(kind = MemoryKind.RULE, asOf = NOW - 365 * DAY, createdAt = NOW - 365 * DAY)
        val oldPref = e(kind = MemoryKind.PREFERENCE, asOf = NOW - 365 * DAY, createdAt = NOW - 365 * DAY)
        assertEquals(MemoryRules.renderForContext(oldRule), MemoryTiers.renderForContext(oldRule, NOW))
        assertEquals(MemoryRules.renderForContext(oldPref), MemoryTiers.renderForContext(oldPref, NOW))
    }
}
