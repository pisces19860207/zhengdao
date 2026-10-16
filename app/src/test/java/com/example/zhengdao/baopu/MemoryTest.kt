// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Memory 纯判据单测（刀 0b · 2026-10-16）。
 *
 * 覆盖派工单 §4.2 点名：候选记忆校验与去重、标签规范化、召回裁剪不超上界。
 */
class MemoryTest {

    private fun entry(
        id: String = "1",
        scope: MemoryScope = MemoryScope.PROJECT,
        kind: MemoryKind = MemoryKind.PREFERENCE,
        title: String = "称呼偏好",
        body: String = "用户希望被称呼为老师",
        tags: List<String> = listOf("称呼"),
        sourceSession: String = "s1",
        sourceFile: String = "资料库/原始/设定.md",
        status: MemoryStatus = MemoryStatus.CANDIDATE,
        updatedAt: Long = 100L,
    ) = MemoryEntry(id, scope, kind, title, body, tags, sourceSession, sourceFile, status, 1L, updatedAt)

    // ── 校验 ──────────────────────────────────────────────────────────────

    @Test
    fun `校验_合格候选原样通过并规范化`() {
        val v = MemoryRules.validate(entry(title = "  称呼偏好  ", body = "  用户希望被称呼为老师  "))
        assertTrue(v is MemoryRules.Validation.Ok)
        val ok = v as MemoryRules.Validation.Ok
        assertEquals("称呼偏好", ok.normalized.title)
        assertEquals("用户希望被称呼为老师", ok.normalized.body)
    }

    @Test
    fun `校验_缺来源必须拒`() {
        val v = MemoryRules.validate(entry(sourceSession = ""))
        assertTrue("缺来源会话必须拒：$v", v is MemoryRules.Validation.Bad)
        assertTrue((v as MemoryRules.Validation.Bad).reason.contains("来源"))
    }

    @Test
    fun `校验_空标题空正文与超限都拒`() {
        assertTrue(MemoryRules.validate(entry(title = "  ")) is MemoryRules.Validation.Bad)
        assertTrue(MemoryRules.validate(entry(body = "")) is MemoryRules.Validation.Bad)
        assertTrue(MemoryRules.validate(entry(body = "x".repeat(501))) is MemoryRules.Validation.Bad)
        assertTrue(MemoryRules.validate(entry(title = "x".repeat(81))) is MemoryRules.Validation.Bad)
    }

    @Test
    fun `校验_标签超个数必须拒`() {
        val many = (1..9).map { "标签$it" }
        val v = MemoryRules.validate(entry(tags = many))
        assertTrue(v is MemoryRules.Validation.Bad)
        assertTrue((v as MemoryRules.Validation.Bad).reason.contains("标签"))
    }

    // ── 标签规范化 ────────────────────────────────────────────────────────

    @Test
    fun `标签规范化_大小写空白去重与超长丢弃`() {
        val out = MemoryRules.normalizeTags(listOf("  称呼 ", "称呼", "CALL", "call", "x".repeat(21), ""))
        assertEquals(listOf("称呼", "call"), out) // 去重（保序）＋ 小写 ＋ 丢超长 ＋ 丢空
    }

    @Test
    fun `标签规范化_不暗截个数_超限交由validate拒`() {
        // 规范化只清洗不截数：20 个标签原样留下，超不超由 validate 判（拒绝要让人知道）
        val out = MemoryRules.normalizeTags((1..20).map { "t$it" })
        assertEquals(20, out.size)
        assertTrue(MemoryRules.validate(entry(tags = out)) is MemoryRules.Validation.Bad)
    }

    // ── 去重 ──────────────────────────────────────────────────────────────

    @Test
    fun `去重_同scope同kind正文近似判重`() {
        val a = entry(body = "用户希望被称呼为老师，不喜欢被叫老板")
        val b = entry(id = "2", body = "用户希望被称呼为老师，不喜欢被叫老板")
        assertTrue(MemoryRules.isDuplicate(a, b))
    }

    @Test
    fun `去重_正文差得远不判重`() {
        val a = entry(body = "用户希望被称呼为老师")
        val b = entry(id = "2", body = "平台的积分刺客规则是每发一条扣十积分")
        assertFalse(MemoryRules.isDuplicate(a, b))
    }

    @Test
    fun `去重_scope或kind不同不判重`() {
        val a = entry()
        assertFalse(MemoryRules.isDuplicate(a, entry(id = "2", scope = MemoryScope.GLOBAL)))
        assertFalse(MemoryRules.isDuplicate(a, entry(id = "2", kind = MemoryKind.FACT)))
    }

    @Test
    fun `去重_近似阈值边界`() {
        // 构造两组词集合，Jaccard 精确可控
        val a = entry(body = "aa bb cc dd ee") // ASCII 词集合
        val b = entry(id = "2", body = "aa bb cc dd ff") // 4/6 交集并集 = 0.667 < 0.9
        assertFalse(MemoryRules.isDuplicate(a, b))
        val c = entry(id = "3", body = "aa bb cc dd ee") // 完全相同 = 1.0
        assertTrue(MemoryRules.isDuplicate(a, c))
    }

    @Test
    fun `去重_dedupeAgainst把近似候选滤掉`() {
        val existing = listOf(entry(id = "old", body = "用户希望被称呼为老师"))
        val candidates = listOf(
            entry(id = "c1", body = "用户希望被称呼为老师"),          // 近似 ⇒ 滤
            entry(id = "c2", body = "平台的积分刺客规则每发一条扣十积分"), // 不近似 ⇒ 留
        )
        val out = MemoryRules.dedupeAgainst(candidates, existing)
        assertEquals(listOf("c2"), out.map { it.id })
    }

    @Test
    fun `相似度_空正文为零_相同为一`() {
        assertEquals(0.0, MemoryRules.bodySimilarity("", "x"), 1e-9)
        assertEquals(1.0, MemoryRules.bodySimilarity("一样的话", "一样的话"), 1e-9)
    }

    // ── 切词 ──────────────────────────────────────────────────────────────

    @Test
    fun `切词_中文按二字元_ASCII按词`() {
        val t = MemoryRules.tokensOf("称呼偏好 hello world")
        // 「称呼偏好」四字 ⇒ 三个二字元：称呼 / 呼偏 / 偏好
        assertTrue("中文二字元", t.contains("称呼") && t.contains("呼偏") && t.contains("偏好"))
        assertTrue("ASCII 整词小写", t.contains("hello") && t.contains("world"))
    }

    @Test
    fun `切词_标点与空白是分隔`() {
        val t = MemoryRules.tokensOf("a，b。c")
        assertEquals(setOf("a", "b", "c"), t)
    }

    // ── 召回裁剪 ──────────────────────────────────────────────────────────

    private fun sized(id: String, n: Int, updatedAt: Long = 1L) =
        entry(id = id, title = "t$id", body = "x".repeat(n), tags = emptyList(), updatedAt = updatedAt)

    @Test
    fun `召回_按查询词打分_标签重于标题重于正文_无关的不召`() {
        val entries = listOf(
            entry(id = "a", title = "别的", body = "别的", tags = listOf("别的")),   // 与查询无关 ⇒ 不召
            entry(id = "b", title = "称呼", body = "别的", tags = listOf("别的")),   // 标题命中(2)
            entry(id = "c", title = "别的", body = "称呼相关", tags = listOf("别的")), // 正文命中(1)
        )
        val r = MemoryRules.recall(entries, "称呼", budgetTokens = 10_000)
        assertEquals(listOf("b", "c"), r.picked.map { it.id }) // 标题命中 > 正文命中；无关的 a 不进
    }

    @Test
    fun `召回_无命中返回空且不截断`() {
        val r = MemoryRules.recall(listOf(entry()), "完全不相关的词", budgetTokens = 100)
        assertTrue(r.picked.isEmpty())
        assertFalse(r.truncated)
    }

    @Test
    fun `召回_完全平手按id定序_不依赖集合形态`() {
        // 追加四第 2 条：显式稳定排序。三条记忆分数/更新时间/创建时间全同 ⇒ 必须 id 升序，
        // 且换成 HashSet（迭代序任意）喂同一批内容，结果一个样。
        val entries = listOf(
            entry(id = "c", title = "称呼偏好", body = "称呼 甲"),
            entry(id = "a", title = "称呼偏好", body = "称呼 甲"),
            entry(id = "b", title = "称呼偏好", body = "称呼 甲"),
        )
        val shuffled = listOf(entries[2], entries[0], entries[1]).toHashSet().toList()
        val r1 = MemoryRules.recall(entries, "称呼", budgetTokens = 10_000)
        val r2 = MemoryRules.recall(shuffled, "称呼", budgetTokens = 10_000)
        assertEquals(listOf("a", "b", "c"), r1.picked.map { it.id })
        assertEquals(listOf("a", "b", "c"), r2.picked.map { it.id })
    }

    @Test
    fun `裁剪_超上界必须截断且明说`() {
        // 每条渲染后约 (标题 + 正文) 字符；注入器按字符长度计，预算 60
        val entries = (1..5).map { sized("m$it", n = 40) }
        val r = MemoryRules.trimToBudget(entries, budgetTokens = 60, tokensOf = { it.length })
        assertTrue("超上界必须截断", r.truncated)
        assertTrue("截断必须明说（不许静默）", r.note.contains("未注入"))
        assertTrue(r.picked.size < entries.size)
        var used = 0
        r.picked.forEach { used += MemoryRules.renderForContext(it).length }
        assertTrue("注入量不超上界（$used > 60）", used <= 60)
    }

    @Test
    fun `裁剪_不超上界不截断`() {
        val entries = listOf(sized("a", 10), sized("b", 10))
        val r = MemoryRules.trimToBudget(entries, budgetTokens = 1000, tokensOf = { it.length })
        assertFalse(r.truncated)
        assertEquals(2, r.picked.size)
        assertEquals("", r.note)
    }

    @Test
    fun `裁剪_保持传入顺序不重排`() {
        val entries = listOf(sized("z", 10), sized("a", 10), sized("m", 10))
        val r = MemoryRules.trimToBudget(entries, budgetTokens = 1000, tokensOf = { it.length })
        assertEquals(listOf("z", "a", "m"), r.picked.map { it.id })
    }

    @Test
    fun `裁剪_单条就超上界也装下但要标记`() {
        val r = MemoryRules.trimToBudget(listOf(sized("big", 500)), budgetTokens = 50, tokensOf = { it.length })
        assertEquals(1, r.picked.size)
        assertTrue(r.truncated)
    }

    // ── 标识 ──────────────────────────────────────────────────────────────

    @Test
    fun `id与slug_确定性且文件名安全`() {
        val id = MemoryRules.newId(1_729_000_000_000L, "称呼偏好 hello")
        assertEquals("1729000000000-称呼偏好-hello", id)
        assertEquals("1729000000000-称呼偏好-hello.md", id + ".md")
        assertEquals("memory", MemoryRules.slugOf("！！！"))
        assertTrue(MemoryRules.slugOf("a/b\\c").none { it == '/' || it == '\\' })
    }

    // ── 渲染 ──────────────────────────────────────────────────────────────

    @Test
    fun `上下文渲染_带类型标签与标签列表`() {
        val text = MemoryRules.renderForContext(entry(tags = listOf("称呼", "用户")))
        assertTrue(text.contains("【偏好】称呼偏好"))
        assertTrue(text.contains("（标签：称呼、用户）"))
        assertTrue(text.contains("用户希望被称呼为老师"))
    }
}
