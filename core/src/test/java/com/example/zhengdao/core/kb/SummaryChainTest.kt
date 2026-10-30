// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * 301 摘要链接线的**行为**判据（JVM 可跑，不碰设备）。
 *
 * 钉的是用户定的五条里的 ①~④：
 * - ① 索引重建后摘要落盘（账本 ＋ 清单的 `## 文件摘要` 一节）
 * - ② 改一个文件 ⇒ **只该文件**重算，其余沿用
 * - ③ 超限按 `lastHitAt` 淘汰（条数与字节两条线都钉）。
 *   ⚠️ **口径**：判据原文叫"最久未命中"，但生产侧还没有检索消费者 ⇒ `lastHitAt ≡ genAt`
 *   ⇒ **现状淘汰的是「最久未生成」**，接检索面板后恢复真 LRU。两条测试分别钉这两件事，别混着读。
 * - ④ 检索能用上摘要，且**原有文件行一条不少**（检索面没被摘要挤掉）
 *
 * ⑤（全程不调模型）是静态门禁，在 `SummaryChainGateTest`。
 *
 * 为什么这些能在 JVM 上跑：`refresh` 只认**文件**（`rows` ＋ 三个路径），不认 `Context`；
 * `writeSummaries` 的落盘那一半已抽成 `KnowledgeBase.writeSummariesTo`。这正是 301 抽那两刀的理由。
 */
class SummaryChainTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var raw: File
    private lateinit var idx: File
    private lateinit var store: File

    @Before
    fun setUp() {
        raw = tmp.newFolder("原始")
        val done = tmp.newFolder("产物")
        idx = File(done, KnowledgeBase.F_INDEX)
        store = File(done, KnowledgeBaseSummaries.FILE_NAME)
    }

    // ── 夹具 ────────────────────────────────────────────────────────────────

    private fun put(rel: String, text: String) {
        val f = File(raw, rel)
        f.parentFile?.mkdirs()
        f.writeText(text, Charsets.UTF_8)
    }

    private fun rows(): List<Pair<String, Long>> = KnowledgeBase.listRawIn(raw)

    /** 模拟 `rebuildIndex` 的"清单真的写了"：用**生产模板**渲染，不自己拼一份假清单。 */
    private fun writeIndex() {
        idx.writeText(KnowledgeBase.indexTextFor(rows()), Charsets.UTF_8)
    }

    private fun refresh(
        force: Boolean = false,
        now: Long = System.currentTimeMillis(),
        version: String = KnowledgeBaseSummaries.SUMMARIZER_VERSION,
        maxCount: Int = KnowledgeBaseSummaries.MAX_ENTRIES,
        maxBytes: Long = KnowledgeBaseSummaries.MAX_BYTES,
    ) = KnowledgeBaseSummaries.refresh(
        rows = rows(),
        rawDir = raw,
        indexFile = idx,
        storeFile = store,
        force = force,
        now = now,
        version = version,
        maxCount = maxCount,
        maxBytes = maxBytes,
    )

    private fun entries() = KnowledgeBaseSummaries.decode(store.readText(Charsets.UTF_8))!!.entries

    private fun entryOf(rel: String) = entries().first { it.rel == rel }

    // ── 判据 ①：索引重建后摘要落盘 ───────────────────────────────────────────

    @Test
    fun `索引重建后摘要落盘_账本与清单两处都在`() {
        put("梧桐公寓租赁合同.txt", "梧桐公寓租赁合同\n甲方：张三，乙方：李四。租金每月 4200 元，押一付三。")
        put("会议纪要.md", "# 周会纪要\n今天讨论了排期与预算，决定下周一上线。")
        writeIndex()

        val rep = refresh(now = 1_000L)

        assertTrue("清单在 ⇒ 该跑", rep.ran)
        assertEquals("两个文件都要重算", 2, rep.recomputed)
        assertTrue("清单该被写入", rep.wroteIndex)
        assertTrue("账本要落盘", store.isFile)
        assertEquals(2, rep.entries)

        val body = idx.readText(Charsets.UTF_8)
        assertTrue("清单里要有摘要节", body.contains("## 文件摘要"))
        assertTrue("摘要要按行列出（agent 才读得到）", body.contains("- **梧桐公寓租赁合同.txt** — "))
        assertTrue(body.contains("- **会议纪要.md** — "))
        assertTrue(
            "摘要节要插在「给 AI 的提示」之前（结构不许被写乱）",
            body.indexOf("## 文件摘要") < body.indexOf("## 给 AI 的提示（重要）"),
        )
        assertTrue("原有文件行一条不许少", body.contains("- `会议纪要.md` — "))
    }

    @Test
    fun `子目录里的文件也出摘要_键与清单里的路径逐字一致`() {
        put("合同/租房.txt", "租房合同正文：押金与租期。")
        writeIndex()
        refresh(now = 1L)
        val rel = rows().first().first
        assertTrue("摘要行的键必须与清单里的相对路径一致（否则 agent 对不上文件）", idx.readText(Charsets.UTF_8).contains("- **$rel** — "))
    }

    @Test
    fun `清单还没生成时什么都不做_也不凭空造账本`() {
        put("a.txt", "随便写点。")
        val rep = refresh()
        assertFalse("前置守则：清单不存在就不落摘要", rep.ran)
        assertEquals("清单还没生成", rep.why)
        assertFalse("不许凭空造账本", store.exists())
        assertFalse("更不许凭空造清单", idx.exists())
    }

    // ── 判据 ②：改一个文件 ⇒ 只该文件重算 ───────────────────────────────────

    @Test
    fun `改一个文件_只有该文件重算_其余沿用原条目`() {
        put("a.txt", "甲文件：讲的是房租与押金。")
        put("b.txt", "乙文件：讲的是排期与上线。")
        put("c.txt", "丙文件：讲的是预算与采购。")
        writeIndex()
        val first = refresh(now = 1_000L)
        assertEquals(3, first.recomputed)

        put("b.txt", "乙文件（第二版）：完全换了内容，现在讲的是巡检与告警。")
        writeIndex()
        val second = refresh(now = 2_000L)

        assertEquals("只有 b 该重算", 1, second.recomputed)
        assertEquals("另外两个必须沿用", 2, second.fresh)
        assertEquals("a 的生成时间不许被顺手改掉", 1_000L, entryOf("a.txt").genAt)
        assertEquals("c 的生成时间不许被顺手改掉", 1_000L, entryOf("c.txt").genAt)
        assertEquals("b 必须重算", 2_000L, entryOf("b.txt").genAt)
        assertTrue("b 的新摘要要反映新内容", entryOf("b.txt").summary.contains("巡检"))
    }

    @Test
    fun `内容没变就一次都不重算`() {
        put("x.txt", "内容不变。")
        writeIndex()
        refresh(now = 10L)
        writeIndex()
        val again = refresh(now = 20L)
        assertEquals("内容没变就一次都不许重算", 0, again.recomputed)
        assertEquals(1, again.fresh)
    }

    // ── 判据 ③：过期判据（内容 / 版本 / 无条目）─────────────────────────────

    @Test
    fun `过期摘要不许当新用_检索直接当没有`() {
        put("a.txt", "第一版：讲的是房租与押金。")
        writeIndex()
        refresh(now = 1L)
        val old = entryOf("a.txt").summary

        // 内容变了，但**账本还没刷新**（模拟"重建没跑到摘要那一步"）
        put("a.txt", "第二版：讲的是预算与采购，跟房租无关。")

        assertNull("过期条目不许被检索到", KnowledgeBaseSummaries.lookup(store, raw, "a.txt", now = 2L))
        assertTrue(
            "过期条目的摘要文字也不许被关键词命中",
            KnowledgeBaseSummaries.search(store, raw, "押金", now = 2L).isEmpty(),
        )

        writeIndex()
        val rep = refresh(now = 3L)
        assertEquals("过期 ⇒ 必须重算", 1, rep.recomputed)
        val fresh = KnowledgeBaseSummaries.lookup(store, raw, "a.txt", now = 4L)!!
        assertNotEquals("不许把旧摘要原样当新用", old, fresh)
        assertTrue("新摘要得说新内容", fresh.contains("预算") || fresh.contains("采购"))
    }

    @Test
    fun `摘要器版本变了_全部重算`() {
        put("a.txt", "甲。")
        put("b.txt", "乙。")
        writeIndex()
        refresh(now = 1L)
        val rep = refresh(now = 2L, version = "rule-2")
        assertEquals("版本一换，老措辞全作废", 2, rep.recomputed)
        assertEquals(0, rep.fresh)
        assertEquals("rule-2", entryOf("a.txt").version)
    }

    @Test
    fun `源文件删了_条目跟着丢_不留孤儿摘要`() {
        put("keep.txt", "留着。")
        put("gone.txt", "要删。")
        writeIndex()
        refresh(now = 1L)
        assertEquals(2, entries().size)

        File(raw, "gone.txt").delete()
        writeIndex()
        val rep = refresh(now = 2L)

        assertEquals(1, rep.dropped)
        assertEquals(listOf("keep.txt"), entries().map { it.rel })
        assertFalse(
            "孤儿摘要不许留在清单里（会误导 agent 去找一个不存在的文件）",
            idx.readText(Charsets.UTF_8).contains("gone.txt**"),
        )
    }

    // ── 判据 ③′：超限淘汰（条数 / 字节两条线）──────────────────────────────

    @Test
    fun `超限按 lastHitAt 淘汰_有真命中时命中过的留下`() {
        // 本条验的是"检索面板接上之后"的形态：用 lookup 造出**真命中**，让 lastHitAt ≠ genAt。
        // 生产现状（无检索消费者 ⇒ lastHitAt ≡ genAt ⇒ 最久未生成先走）由下一条钉。
        for (i in 1..5) put("f$i.txt", "第 $i 份资料。讲的是主题$i。")
        writeIndex()
        refresh(now = 100L)   // 5 条，lastHitAt 全是 100

        // 命中 f2 与 f4 ⇒ 它们的 lastHitAt 变成 200 / 300
        KnowledgeBaseSummaries.lookup(store, raw, "f2.txt", now = 200L)
        KnowledgeBaseSummaries.lookup(store, raw, "f4.txt", now = 300L)

        val rep = refresh(now = 400L, maxCount = 3)

        assertEquals("5 条压到 3 条 ⇒ 淘汰 2", 2, rep.evicted)
        assertEquals(3, rep.entries)
        assertEquals(
            "被淘汰的必须是 lastHitAt 最小的 f1/f3（同分按 rel 定序），命中过的 f2/f4 留下",
            listOf("f2.txt", "f4.txt", "f5.txt"),
            entries().map { it.rel },
        )
    }

    @Test
    fun `没有命中时_淘汰的是最久未生成`() {
        // 生产现状：lastHitAt 恒等于 genAt ⇒ 淘汰顺序实际由 genAt 决定 = "最久未生成"。
        // 这条测试存在的意义就是**别让人把上一条读成现状**。
        val a = KnowledgeBaseSummaries.Entry("a", "甲", "h", genAt = 10L, version = "rule-1", lastHitAt = 10L)
        val b = KnowledgeBaseSummaries.Entry("b", "乙", "h", genAt = 20L, version = "rule-1", lastHitAt = 20L)
        val c = KnowledgeBaseSummaries.Entry("c", "丙", "h", genAt = 30L, version = "rule-1", lastHitAt = 30L)

        val pruned = KnowledgeBaseSummaries.prune(listOf(a, b, c), maxCount = 2, maxBytes = Long.MAX_VALUE)

        assertEquals(1, pruned.evicted)
        assertEquals("最久未生成（genAt 最小）的 a 先走", listOf("b", "c"), pruned.entries.map { it.rel })
    }

    @Test
    fun `prune_字节超限也淘汰_且结果确定`() {
        val e1 = KnowledgeBaseSummaries.Entry("a", "甲", "h", 1L, "rule-1", 10L)
        val e2 = KnowledgeBaseSummaries.Entry("b", "乙", "h", 1L, "rule-1", 20L)
        val e3 = KnowledgeBaseSummaries.Entry("c", "丙", "h", 1L, "rule-1", 30L)
        val two = KnowledgeBaseSummaries
            .encode(KnowledgeBaseSummaries.Store(listOf(e2, e3)))
            .toByteArray(Charsets.UTF_8).size.toLong()

        val pruned = KnowledgeBaseSummaries.prune(listOf(e1, e2, e3), maxCount = 100, maxBytes = two)

        assertEquals(1, pruned.evicted)
        assertEquals("lastHitAt 最小的 a 先走（此处三条 lastHitAt 就是 genAt）", listOf("b", "c"), pruned.entries.map { it.rel })
        assertTrue("账出来的字节必须真的不超上限", pruned.bytes <= two)
        assertEquals(
            "同一批输入必须淘汰同一批人",
            pruned.entries.map { it.rel },
            KnowledgeBaseSummaries.prune(listOf(e1, e2, e3), maxCount = 100, maxBytes = two)
                .entries.map { it.rel },
        )
    }

    @Test
    fun `prune_不改调用方传进来的列表`() {
        val list = ArrayList(
            listOf(
                KnowledgeBaseSummaries.Entry("a", "甲", "h", 1L, "rule-1", 1L),
                KnowledgeBaseSummaries.Entry("b", "乙", "h", 1L, "rule-1", 2L),
            ),
        )
        KnowledgeBaseSummaries.prune(list, maxCount = 1, maxBytes = Long.MAX_VALUE)
        assertEquals("纯函数不许有副作用", 2, list.size)
    }

    // ── 判据 ④：检索能用上摘要，命中率不劣化 ─────────────────────────────────

    @Test
    fun `检索能命中摘要_原有文件行一条不少`() {
        put("年度预算.txt", "本文件是 2026 年度采购预算表，按季度拆分。")
        writeIndex()
        refresh(now = 1L)

        val body = idx.readText(Charsets.UTF_8)
        assertTrue("路径检索面不许被摘要挤掉", body.contains("- `年度预算.txt` — "))
        assertTrue("摘要行也得在", body.contains("- **年度预算.txt** — "))

        // 只出现在**摘要正文**里、文件名里没有的词，也能查到 ⇒ 摘要是加了一条检索通道
        val hits = KnowledgeBaseSummaries.search(store, raw, "采购", now = 2L)
        assertEquals(1, hits.size)
        assertEquals("年度预算.txt", hits[0].first)

        // 按文件名查（原有检索口径）照样通
        assertEquals(1, KnowledgeBaseSummaries.search(store, raw, "预算", now = 3L).size)
    }

    @Test
    fun `检索命中会刷新最后命中时间`() {
        put("a.txt", "讲的是押金与房租。")
        writeIndex()
        refresh(now = 1L)
        assertEquals(1L, entryOf("a.txt").lastHitAt)

        KnowledgeBaseSummaries.search(store, raw, "押金", now = 42L)

        assertEquals("命中一次就要记一次", 42L, entryOf("a.txt").lastHitAt)
    }

    @Test
    fun `空查询不返回全部_也不写盘`() {
        put("a.txt", "随便。")
        writeIndex()
        refresh(now = 1L)
        val before = store.readText(Charsets.UTF_8)
        assertTrue(KnowledgeBaseSummaries.search(store, raw, "   ", now = 9L).isEmpty())
        assertEquals("没命中就不该动账本", before, store.readText(Charsets.UTF_8))
    }

    // ── 强制全量（手动按钮那条路）───────────────────────────────────────────

    @Test
    fun `force 全量重算_自动流程不传它`() {
        put("a.txt", "甲。")
        put("b.txt", "乙。")
        writeIndex()
        refresh(now = 1L)
        val rep = refresh(force = true, now = 2L)
        assertEquals(2, rep.recomputed)
        assertEquals(0, rep.fresh)
        assertEquals(2L, entryOf("a.txt").genAt)
    }

    // ── 规则摘要本身（纯字符串，不调模型）───────────────────────────────────

    @Test
    fun `规则摘要_类型标题首句关键词都在_且不超字数`() {
        val head = "梧桐公寓租赁合同\n甲方：张三，乙方：李四。租金每月 4200 元，押一付三。\n"
        val s = KnowledgeBaseSummaries.summarize(
            "合同/梧桐公寓租赁合同.txt",
            head,
            head.toByteArray(Charsets.UTF_8).size.toLong(),
        )
        assertTrue("类型要从扩展名看出来", s.startsWith("纯文本「梧桐公寓租赁合同」"))
        assertTrue("首句要带上", s.contains("甲方：张三，乙方：李四。"))
        assertTrue("关键词那一栏在", s.contains("· 关键词："))
        assertTrue("长度不许超上限", s.length <= KnowledgeBaseSummaries.MAX_SUMMARY_CHARS)
    }

    @Test
    fun `读不出正文时只按文件名定位_不编内容`() {
        val s = KnowledgeBaseSummaries.summarize("扫描件.pdf", null, 1024L)
        assertTrue(s.contains("PDF"))
        assertTrue(s.contains("扫描件"))
        assertTrue("要老实说没读出正文", s.contains("没读出正文"))
    }

    @Test
    fun `kindOf_认常见扩展名_不认识的说文件`() {
        assertEquals("纯文本", KnowledgeBaseSummaries.kindOf("a.txt"))
        assertEquals("Markdown 笔记", KnowledgeBaseSummaries.kindOf("笔记.MD"))
        assertEquals("Word 文档", KnowledgeBaseSummaries.kindOf("合同.docx"))
        assertEquals("PDF", KnowledgeBaseSummaries.kindOf("扫描.pdf"))
        assertEquals("文件", KnowledgeBaseSummaries.kindOf("没扩展名"))
    }

    @Test
    fun `headText_二进制与空文件都判读不出`() {
        val bin = File(raw, "bin.dat")
        bin.writeBytes(byteArrayOf(0, 1, 2, 3, 0, 9))
        assertNull("含 NUL ⇒ 二进制 ⇒ 读不出", KnowledgeBaseSummaries.headText(bin))

        val empty = File(raw, "empty.txt")
        empty.writeText("")
        assertNull("空文件 ⇒ 读不出", KnowledgeBaseSummaries.headText(empty))

        val txt = File(raw, "ok.txt")
        txt.writeText("这是一段正常的中文正文。")
        assertTrue(KnowledgeBaseSummaries.headText(txt)!!.contains("正常的中文正文"))
    }

    @Test
    fun `sha256_对得上已知值`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            KnowledgeBaseSummaries.sha256("abc".toByteArray(Charsets.UTF_8)),
        )
    }

    @Test
    fun `编解码往返一致_读不懂的账本回 null`() {
        val one = KnowledgeBaseSummaries.Store(
            listOf(KnowledgeBaseSummaries.Entry("a\"b\nc", "含引号与换行", "h", 1L, "rule-1", 2L)),
        )
        val back = KnowledgeBaseSummaries.decode(KnowledgeBaseSummaries.encode(one))!!
        assertEquals(one.entries, back.entries)

        assertNull("不是 JSON", KnowledgeBaseSummaries.decode("不是账本"))
        assertNull("空", KnowledgeBaseSummaries.decode(""))
        assertNull(
            "schema 不认识就整本丢掉（宁可全量重算，也不半信半疑）",
            KnowledgeBaseSummaries.decode("""{"schema":99,"version":"x","entries":[]}"""),
        )
    }

    @Test
    fun `账本字节记账与真实文件大小一致`() {
        put("a.txt", "甲。")
        writeIndex()
        val rep = refresh(now = 1L)
        assertEquals(
            "记账字节必须等于真实落盘大小（否则 20MB 上限就是摆设）",
            store.length(),
            rep.bytes,
        )
    }
}
