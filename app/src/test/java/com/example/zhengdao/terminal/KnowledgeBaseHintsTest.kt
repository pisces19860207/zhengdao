package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * FIX-F（E-083）：把「提问 → 候选词 → 本地命中 → 注入块」这条链里**不需要 Context**
 * 的部分钉死。
 *
 * 为什么值得钉：
 *  - **候选词是这条链的命门**：中文没分词，全靠 n-gram 猜窗口。猜不到就整条功能等于不存在
 *    （用户看到的只是"什么也没发生"）。
 *  - **块必须是一行**：终端里 `\n` 就是回车 —— 一旦块里混进换行，用户的一句提问会被
 *    提交成好几条。这条得有人守着。
 *  - **命中 0 处不许注入**：不然每次闲聊都会被贴上一条"未找到"的废话。
 */
class KnowledgeBaseHintsTest {

    private val far = System.currentTimeMillis() + 10_000

    // ── 候选词 ───────────────────────────────────────────────────────────────

    @Test
    fun `中文问句剔掉口水词后能切出有用的二字窗口`() {
        // 用户真会这么问：「帮我找母猪那段素材」——整句去 grep 是找不到的，
        // 必须退到「母猪」这种二字窗口才命中。
        val c = KnowledgeBaseHints.candidates("帮我找母猪那段素材")
        assertTrue("应该切出「母猪」，实际=$c", c.contains("母猪"))
    }

    @Test
    fun `英文单词优先且被小写化`() {
        val c = KnowledgeBaseHints.candidates("看看 README 里面写了什么")
        assertEquals("readme", c.firstOrNull())
    }

    @Test
    fun `整句都是口水词时没有候选词`() {
        assertTrue(KnowledgeBaseHints.candidates("帮我看看这个是什么").isEmpty())
        assertTrue(KnowledgeBaseHints.candidates("").isEmpty())
    }

    @Test
    fun `候选词数量有上限`() {
        val c = KnowledgeBaseHints.candidates("冰天雪地里的雪妖出现在哪一本小说里面", max = 3)
        assertEquals(3, c.size)
    }

    @Test
    fun `长问句不会被三字窗口吃光名额而失去二字窗口`() {
        // 探针实测后改的：早先"所有段落先出完 3 字窗口、再出 2 字窗口"的写法，
        // 会在长问句上把候选名额占满，导致 2 字窗口一个都进不了列表 ⇒ 整句搜不到。
        val c = KnowledgeBaseHints.candidates("冰天雪地里的雪妖出现在哪一本小说里面")
        assertTrue("必须给 2 字窗口留位置，实际=$c", c.any { it.length == 2 })
    }

    @Test
    fun `剔口水词不会把两段词粘成一个假词`() {
        // 「母猪」+「发情」被"的"隔开 —— 剔掉"的"时若直接删字符，会得到"母猪发情"这个
        // 库里根本没有的词，等于白搜。必须换成空格保留边界。
        val c = KnowledgeBaseHints.candidates("母猪的发情")
        assertTrue("不该出现拼接出来的假词「母猪发情」，实际=$c", !c.contains("母猪发情"))
        assertTrue("应该分别拿到「母猪」和「发情」，实际=$c", c.contains("母猪") && c.contains("发情"))
    }

    // ── 扫描 ─────────────────────────────────────────────────────────────────

    @Test
    fun `扫描给出文件名与行号`() {
        val sources = listOf(
            "a.txt" to "第一行\n母猪的静立反射\n母猪≠母狗",
            "b.txt" to "这里没有那两个字",
        )
        val r = KnowledgeBaseHints.scan(sources, listOf("母猪"), far)
        assertEquals(listOf("母猪"), r.keywords)
        assertEquals(2, r.hits.size)
        assertEquals("a.txt", r.hits[0].rel)
        assertEquals(2, r.hits[0].line)
        assertEquals("母猪的静立反射", r.hits[0].text)
    }

    @Test
    fun `一行只记一次`() {
        // 同一行同时含两个候选词时只算一次命中，否则命中数会虚高、块也会重复列同一行。
        val sources = listOf("a.txt" to "母猪的静立反射")
        val r = KnowledgeBaseHints.scan(sources, listOf("母猪", "静立"), far)
        assertEquals(1, r.hits.size)
    }

    @Test
    fun `没命中就没有结果`() {
        val sources = listOf("a.txt" to "完全无关的内容")
        val r = KnowledgeBaseHints.scan(sources, listOf("母猪"), far)
        assertTrue(r.hits.isEmpty())
        assertTrue(r.keywords.isEmpty())
    }

    @Test
    fun `命中数有上限`() {
        val many = (1..50).joinToString("\n") { "母猪 第 $it 行" }
        val r = KnowledgeBaseHints.scan(listOf("a.txt" to many), listOf("母猪"), far, maxHits = 3)
        assertEquals(3, r.hits.size)
    }

    @Test
    fun `同一个文件最多列三处 好让别的文件也能露头`() {
        val many = (1..10).joinToString("\n") { "母猪 第 $it 行" }
        val r = KnowledgeBaseHints.scan(
            listOf("a.txt" to many, "b.txt" to "母猪 也在这里"),
            listOf("母猪"), far,
        )
        assertEquals("a.txt 3 处 + b.txt 1 处", 4, r.hits.size)
        assertEquals(3, r.hits.count { it.rel == "a.txt" })
        assertEquals(1, r.hits.count { it.rel == "b.txt" })
    }

    @Test
    fun `检索词取命中多的那个 同数时保持先后`() {
        val counts = mapOf("甲" to 1, "乙" to 5, "丙" to 5)
        assertEquals(listOf("乙", "丙"), KnowledgeBaseHints.pickKeywords(listOf("甲", "乙", "丙"), counts))
    }

    // ── 注入块 ───────────────────────────────────────────────────────────────

    private fun hits(n: Int, relLen: Int = 12) = (1..n).map {
        KnowledgeBaseHints.Hit("文".repeat(relLen) + "$it.txt", it, "第 $it 行的内容")
    }

    @Test
    fun `没有命中就不生成块`() {
        assertNull(KnowledgeBaseHints.formatBlock(listOf("母猪"), emptyList()))
        assertNull(KnowledgeBaseHints.formatBlock(emptyList(), hits(2)))
    }

    @Test
    fun `块里带文件名与行号 并声明不是本人输入`() {
        val b = KnowledgeBaseHints.formatBlock(listOf("母猪"), hits(2))!!
        assertTrue("块里应给出第一处的文件名＋行号，实际=$b", b.contains(".txt:1"))
        assertTrue("块里应给出第二处的行号，实际=$b", b.contains(".txt:2"))
        assertTrue("必须写清是自动附上的、不是用户打的字", b.contains("非本人输入"))
        assertTrue("必须告诉用户能关", b.contains("关"))
    }

    @Test
    fun `块永远是一行 —— 块里出现换行会把一次提问提交成好几条`() {
        val b = KnowledgeBaseHints.formatBlock(listOf("母猪"), hits(6))!!
        assertTrue("块里不许有换行", !b.contains('\n'))
        assertTrue("块里不许有回车", !b.contains('\r'))
    }

    @Test
    fun `块长度有上限 超了就少列几处`() {
        val b = KnowledgeBaseHints.formatBlock(listOf("母猪", "静立反射"), hits(30, relLen = 60))!!
        assertTrue("块长度应 ≤ 400，实际=${b.length}", b.length <= 400)
        assertTrue("被截掉时要说明还有多少处，实际=$b", b.contains("等 30 处"))
    }

    @Test
    fun `按字符截断而不是按字节`() {
        assertEquals("母猪…", KnowledgeBaseHints.clip("母猪的静立反射", 2))
        assertEquals("短", KnowledgeBaseHints.clip("短", 2))
    }
}
