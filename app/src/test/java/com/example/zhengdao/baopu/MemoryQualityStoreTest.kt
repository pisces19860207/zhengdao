// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第八十九批 §3 的落盘层判据——重复合并 / 空与超长拒绝 / 衰减只降权不删 /
// 检索排序 / 冲突标记 / 老库 migration。纯判据层在 [MemoryQualityTest]，
// 源码门禁在 [MemoryQualityGateTest]，本文件只管「文件到底变成什么样」。
package com.example.zhengdao.baopu

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 记忆质量环的**落盘层**用例（第八十九批）。
 *
 * 时钟口径：[NOW] 取一个「离出单当天不足 90 天」的常量（2026-09-22 前后），于是
 * `store.write()` 内部按真表算出来的入库分必定是满分 100——衰减只在我明确喂进 `now`
 * 的那些用例里发生（要钉边界就得给常量时刻，不能让真表决定第几档）。
 */
class MemoryQualityStoreTest {

    @Rule
    @JvmField
    val tmp: TemporaryFolder = TemporaryFolder()

    private companion object {
        const val NOW = 1_790_000_000_000L
        const val DAY = MemoryQuality.DAY_MILLIS
    }

    private lateinit var globalRoot: File
    private lateinit var projectRoot: File
    private lateinit var store: MemoryStore

    private fun setup() {
        globalRoot = tmp.newFolder("app-全局")
        projectRoot = tmp.newFolder("ws")
        store = MemoryStore(globalRoot, projectRoot)
    }

    private fun entry(
        id: String,
        title: String = "称呼偏好",
        body: String = "用户希望被称呼为老师",
        tags: List<String> = listOf("称呼"),
        scope: MemoryScope = MemoryScope.PROJECT,
        createdAt: Long = NOW,
        updatedAt: Long = NOW,
        useCount: Int = 0,
        qualityScore: Int = MemoryQuality.DEFAULT_SCORE,
    ) = MemoryEntry(
        id = id,
        scope = scope,
        kind = MemoryKind.PREFERENCE,
        title = title,
        body = body,
        tags = tags,
        sourceSession = "s7",
        sourceFile = "资料库/原始/设定.md",
        status = MemoryStatus.CONFIRMED,
        createdAt = createdAt,
        updatedAt = updatedAt,
        useCount = useCount,
        qualityScore = qualityScore,
    )

    /** 不经闸门直接铺文件（造重复、造老库形态要用——闸门会把重复拒掉，铺不出来）。 */
    private fun putOnDisk(e: MemoryEntry): File {
        val dir = store.projectDir()
        dir.mkdirs()
        val f = File(dir, e.id + ".md")
        f.writeText(EntryCodec.render(e), Charsets.UTF_8)
        return f
    }

    private fun fileText(id: String): String = File(store.projectDir(), "$id.md").readText(Charsets.UTF_8)

    private fun fileBytes(id: String): ByteArray = File(store.projectDir(), "$id.md").readBytes()

    private fun snapshot(): Map<String, ByteArray> =
        store.projectDir().listFiles()?.associate { it.name to it.readBytes() } ?: emptyMap()

    // ── 1) 质量四行进得了文件（.md 侧形态） ────────────────────────────────

    @Test
    fun `落盘_质量四行进文件且原样读得回来`() {
        setup()
        val r = store.write(entry(id = "1-a"))
        assertTrue("合法记忆应当落盘：${r.reason}", r.ok)
        assertNotNull("闸门放行那条要交给调用方", r.entry)
        val text = fileText("1-a")
        for (key in listOf("quality_score:", "quality_flags:", "merged_from:", "scored_at:")) {
            assertTrue("$key 这一行必须写进文件（文件是真相，人能读）：\n$text", text.contains(key))
        }
        assertTrue("入库时按真表算 ⇒ 离今天不足 90 天，必定满分：\n$text", text.contains("quality_score: 100"))
        val back = store.loadAll().first()
        assertEquals("落盘的那条与读回来的那条逐字段一致", r.entry, back)
        assertTrue("scored_at 要留时刻（审计用）", back.scoredAt > 0L)
    }

    // ── 2) 写入校验：空／超长／明显重复不入库 ───────────────────────────────

    @Test
    fun `写入校验_空正文与超长都不入库`() {
        setup()
        val blank = store.write(entry(id = "e1", body = "   "))
        assertFalse("空正文不许入库（§1 第 1 条）", blank.ok)
        assertTrue(blank.reason.contains("不合格"))
        val tooLong = store.write(entry(id = "e2", body = "x".repeat(600)))
        assertFalse("超长不许入库", tooLong.ok)
        assertTrue(tooLong.reason.contains("超"))
        assertTrue("两条都没落盘", store.loadAll().isEmpty())
        assertFalse(File(store.projectDir(), "e1.md").exists())
        assertFalse(File(store.projectDir(), "e2.md").exists())
    }

    @Test
    fun `写入校验_同键近似重复不入库并点名撞了哪条`() {
        setup()
        putOnDisk(entry(id = "1-a"))
        val r = store.write(entry(id = "2-a", body = "用户希望被称呼为老师吗"))
        assertFalse("同键＋正文近似 ⇒ 不重复入库（§1 第 1 条「明显重复」）：${r.reason}", r.ok)
        assertTrue("要说清是同键：${r.reason}", r.reason.contains("同键"))
        assertTrue("要点名撞了哪条（给人去看原件）：${r.reason}", r.reason.contains("1-a"))
        assertEquals("库里仍是一条", 1, store.loadAll().size)
        assertFalse("被拒的那条不许留下半个文件", File(store.projectDir(), "2-a.md").exists())
    }

    @Test
    fun `写入校验_不同键但正文近似也拒_沿用第八批那把尺`() {
        setup()
        putOnDisk(entry(id = "1-a"))
        val r = store.write(entry(id = "2-b", title = "另一件别的事", body = "用户希望被称呼为老师吗"))
        assertFalse(r.ok)
        assertTrue("正文近似这条判据不能因为换了标题就绕过：${r.reason}", r.reason.contains("重复"))
        assertEquals(1, store.loadAll().size)
    }

    // ── 3) 冲突标记：同键不同值 ⇒ 两边都留、不覆盖 ─────────────────────────

    @Test
    fun `冲突标记_同键不同值放行且既有那条一个字节没动`() {
        setup()
        val first = store.write(entry(id = "1-a", title = "积分口径", body = "每发一条扣十积分"))
        assertTrue(first.ok)
        val before = fileBytes("1-a") // 写完第一条就取字节快照，第二条写完再比

        val second = store.write(entry(id = "2-b", title = "积分口径", body = "每发一条扣二十积分"))
        assertTrue("同键不同值不许拒（拒了＝替用户判哪条对）：${second.reason}", second.ok)
        assertNotNull(second.entry)
        assertTrue("要明说不覆盖：${second.reason}", second.reason.contains("不覆盖"))
        assertTrue("新条目自己带冲突标记：${second.entry!!.qualityFlags}",
            second.entry!!.qualityFlags.contains(MemoryQuality.FLAG_CONFLICT))
        assertEquals("闸门要留账", MemoryQuality.ACT_CONFLICT, second.audits.first().action)

        assertArrayEquals("既有那条文件字节级不变（不覆盖用户记忆＝红线）", before, fileBytes("1-a"))
        assertFalse("读时才算的标记不许回写进别人文件", fileText("1-a").contains("CONFLICT"))
        assertEquals("两条并排都在", 2, store.loadAll().size)

        val p = store.qualityPass(NOW)
        assertEquals("视图里还是两条（不选边）", 2, p.merged.size)
        assertTrue("两侧都带标记：${p.merged.map { it.qualityFlags }}",
            p.merged.all { it.qualityFlags.contains(MemoryQuality.FLAG_CONFLICT) })
        assertEquals(listOf(listOf("1-a", "2-b")), p.conflicts)
        assertTrue("冲突要留审计账：${p.audits}", p.audits.any { it.action == MemoryQuality.ACT_CONFLICT })
    }

    // ── 4) 合并：读时塌一条，原件一条不动 ─────────────────────────────────

    @Test
    fun `合并_读时塌成一条_两条原件仍在盘上`() {
        setup()
        putOnDisk(entry(id = "1-a", createdAt = NOW - 30 * DAY, updatedAt = NOW - 30 * DAY))
        putOnDisk(entry(id = "2-b", body = "用户希望被称呼为老师吗", tags = listOf("用户"), createdAt = NOW - 10 * DAY))
        val before = snapshot()

        val p = store.qualityPass(NOW)
        assertEquals("原样清单不塌（质量层没有删除权）", 2, p.scored.size)
        assertEquals("合并只发生在视图", 1, p.merged.size)
        val m = p.merged.first()
        assertEquals("主体取最早立账那条", "1-a", m.id)
        assertEquals(listOf("1-a", "2-b"), m.mergedFrom)
        assertEquals("视图只带合并标记", listOf(MemoryQuality.FLAG_MERGED), m.qualityFlags)
        assertTrue("合并要留审计账：${p.audits}", p.audits.any { it.action == MemoryQuality.ACT_MERGE })

        assertEquals(2, store.loadAll().size)
        assertTrue(File(store.projectDir(), "1-a.md").isFile)
        assertTrue(File(store.projectDir(), "2-b.md").isFile)
        assertEquals("读时合并零写入：文件一个都没变", before.keys, snapshot().keys)
        before.forEach { (name, raw) -> assertArrayEquals("字节不变：$name", raw, snapshot().getValue(name)) }
    }

    @Test
    fun `合并_不丢内容_正文取信息量多的那条_标签取并集`() {
        setup()
        putOnDisk(entry(id = "1-a"))
        putOnDisk(entry(id = "2-b", body = "用户希望被称呼为老师吗", tags = listOf("用户")))
        val m = store.qualityPass(NOW).merged.first()
        assertEquals("用户希望被称呼为老师吗", m.body)
        assertEquals("标签并集（主条目在前）", listOf("称呼", "用户"), m.tags)
        assertEquals("来源 id 全留", 2, m.mergedFrom.size)
    }

    // ── 5) 衰减：只降权，不删文件、不减条数 ───────────────────────────────

    @Test
    fun `衰减_过期只降权_文件与条数一根毛都不少`() {
        setup()
        putOnDisk(entry(id = "old", title = "旧的一条", createdAt = NOW - 200 * DAY, updatedAt = NOW - 200 * DAY))
        putOnDisk(entry(id = "new", title = "新的一条"))
        val bytes = snapshot()

        val p = store.qualityPass(NOW)
        val o = p.scored.first { it.id == "old" }
        assertEquals("200 天 ⇒ 第 2 档 ⇒ 25 分", 25, o.qualityScore)
        assertTrue("低于 40 分打过期标记", o.qualityFlags.contains(MemoryQuality.FLAG_STALE))
        assertEquals("标记不是删除：正文照原样", "用户希望被称呼为老师", o.body)
        assertEquals("条数一条不少", 2, p.scored.size)
        assertEquals("视图也不许因过期少一条", 2, p.merged.size)
        assertEquals(listOf("new", "old"), p.rankedList.map { it.id })
        assertTrue("降权要留账：${p.audits}", p.audits.any { it.action == MemoryQuality.ACT_STALE })
        bytes.forEach { (name, raw) -> assertArrayEquals("读路径零写入，字节不变：$name", raw, snapshot().getValue(name)) }
        assertEquals(2, store.reindex().entries.size)
        // loadAll 排的是**文件里存的分数**（老账存的是默认满分）⇒ 序与第八批旧序一致；
        // 现算的衰减序在 qualityPass/ranked 里。两份口径各管各的，本用例把差异钉住。
        assertEquals(listOf("old", "new"), store.loadAll().map { it.id })
    }

    @Test
    fun `衰减_分数地板是1_过期到底也还在库里`() {
        setup()
        putOnDisk(entry(id = "ancient", title = "极旧的一条",
            createdAt = NOW - 900 * DAY, updatedAt = NOW - 900 * DAY))
        val p = store.qualityPass(NOW)
        val a = p.scored.first()
        assertEquals("再旧也只降到地板分，不降到 0（0 分＝召不到＝不删之删）", MemoryQuality.MIN_SCORE, a.qualityScore)
        assertEquals(1, p.merged.size)
        assertTrue(File(store.projectDir(), "ancient.md").isFile)
        assertEquals("地板分照样进得了召回（0 分才被滤空）", listOf("ancient"), store.recall("老师", 10_000).picked.map { it.id })
    }

    // ── 6) 老库兼容：没有质量行的文件 ─────────────────────────────────────

    private fun legacyText(id: String, title: String, body: String, createdAt: Long): String =
        "---\n" +
            "id: $id\n" +
            "scope: project\n" +
            "kind: preference\n" +
            "title: $title\n" +
            "tags: 老\n" +
            "status: confirmed\n" +
            "source_session: 7\n" +
            "source_file: 资料库/原始/设定.md\n" +
            "created_at: $createdAt\n" +
            "updated_at: $createdAt\n" +
            "use_count: 0\n" +
            "last_used_at: 0\n" +
            "---\n\n" + body + "\n"

    @Test
    fun `老库_没有质量四行照样读回来_默认满分且序不变`() {
        setup()
        val dir = store.projectDir().apply { mkdirs() }
        // 倒着铺，让文件系统遍历顺序与期望序相反（排序键必须自己把序拉正）
        File(dir, "200-c.md").writeText(legacyText("200-c", "老账三", "老库时代的第三条正文", 200L), Charsets.UTF_8)
        File(dir, "200-b.md").writeText(legacyText("200-b", "老账二", "老库时代的第二条正文", 200L), Charsets.UTF_8)
        File(dir, "100-a.md").writeText(legacyText("100-a", "老账一", "老库时代的第一条正文", 100L), Charsets.UTF_8)

        val loaded = store.loadAll()
        assertEquals(3, loaded.size)
        loaded.forEach { e ->
            assertEquals("老数据默认分 = 满分（§1 迁移条）：${e.id}", MemoryQuality.DEFAULT_SCORE, e.qualityScore)
            assertTrue("${e.id} 不该凭空带标记", e.qualityFlags.isEmpty())
            assertTrue("${e.id} 不该凭空带合并账", e.mergedFrom.isEmpty())
            assertEquals("${e.id} 没算过质量账", 0L, e.scoredAt)
            assertTrue("正文一个字不丢：${e.body}", e.body.startsWith("老库时代的"))
        }
        val legacyOrder = loaded.sortedWith(compareBy({ it.createdAt }, { it.id })).map { it.id }
        assertEquals("全平分时读取序与第八批旧序逐位相同（注入前缀不动）", legacyOrder, loaded.map { it.id })
        assertEquals(listOf("100-a", "200-b", "200-c"), loaded.map { it.id })
        assertTrue("reindex 也不许报坏文件", store.reindex().brokenFiles.isEmpty())
    }

    @Test
    fun `老库_坏掉的质量行落默认分不装懂`() {
        setup()
        val dir = store.projectDir().apply { mkdirs() }
        for ((i, raw) in listOf("abc", "-5", "999").withIndex()) {
            val text = EntryCodec.render(entry(id = "b$i", title = "坏值$i")).replace(
                "quality_score: 100", "quality_score: $raw",
            )
            File(dir, "b$i.md").writeText(text, Charsets.UTF_8)
        }
        val loaded = store.loadAll()
        assertEquals(3, loaded.size)
        loaded.forEach { e ->
            assertEquals("读不回来的分数一律落默认分（宁可重算也不拿坏值降权）：${e.id}",
                MemoryQuality.DEFAULT_SCORE, e.qualityScore)
        }
    }

    // ── 7) 检索排序：接口兼容 ─────────────────────────────────────────────

    @Test
    fun `召回_签名与返回类型没变_同键重复只注入一条`() {
        setup()
        putOnDisk(entry(id = "1-a", createdAt = NOW - 30 * DAY, updatedAt = NOW - 30 * DAY))
        putOnDisk(entry(id = "2-b", body = "用户希望被称呼为老师吗", tags = listOf("用户"), createdAt = NOW - 10 * DAY))
        val r = store.recall("称呼", 10_000)
        assertEquals("视图里塌成一条", listOf("1-a"), r.picked.map { it.id })
        assertFalse("预算给足就不许报截断", r.truncated)
        assertEquals("盘上仍是两条", 2, store.loadAll().size)
        // 默认参数（预算）与第八批一致：不传第二个参数也照样能召
        assertEquals(listOf("1-a"), store.recall("称呼").picked.map { it.id })
    }

    @Test
    fun `召回_过期那条排后面但仍然召得到`() {
        setup()
        putOnDisk(entry(id = "old", title = "称呼老账", createdAt = NOW - 200 * DAY, updatedAt = NOW - 200 * DAY))
        putOnDisk(entry(id = "new", title = "称呼新账"))
        val p = store.qualityPass(NOW)
        assertEquals(listOf("new", "old"), p.rankedList.map { it.id })
        assertEquals("两条都还在（降权不是剔除）", 2, p.merged.size)
        assertTrue(p.merged.any { it.id == "old" })
        assertEquals("旧条的分数低于新条", true,
            p.scored.first { it.id == "old" }.qualityScore < p.scored.first { it.id == "new" }.qualityScore)
    }

    @Test
    fun `排序_loadAll按存分排_与reindex同序`() {
        setup()
        putOnDisk(entry(id = "1-a", title = "甲事", qualityScore = 10))
        putOnDisk(entry(id = "2-b", title = "乙事"))
        assertEquals("存的分数低的排后面", listOf("2-b", "1-a"), store.loadAll().map { it.id })
        assertEquals("两个读法同源同序", store.loadAll(), store.reindex().entries)
        assertEquals(2, store.qualityPass(NOW).rankedList.size)
    }
}
