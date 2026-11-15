// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.browser

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 会话级页面记忆的**纯判据**（651）：上限 20、有序、重复不叠加、只记 URL 与标题。
 *
 * 为什么这些能在 JVM 上直测：`PageMemoryPolicy` 零 Android 依赖（房型照
 * `BrowserSnapshotPolicyTest` / `LocalPreviewPolicyTest`）。落盘那一半由
 * [PageMemoryGateTest]（源码与 DDL 逐列比对）与真机取证（报告 §五）两路兜住。
 */
class PageMemoryPolicyTest {

    private fun page(n: Int) = PageMemoryPolicy.Page("https://example.com/p$n", "第 $n 页")

    // ── 判据：每会话最近 20 页（有序） ────────────────────────────────────────

    @Test
    fun `上限是 20_第 21 页进来挤掉最老的`() {
        var kept = emptyList<PageMemoryPolicy.Page>()
        for (i in 1..21) kept = PageMemoryPolicy.merge(kept, page(i))
        assertEquals("保留条数必须正好 20", 20, kept.size)
        assertEquals("最老的那一页（p1）必须被挤出去", "https://example.com/p2", kept.first().url)
        assertEquals("最新的一页在末尾（oldest → newest）", "https://example.com/p21", kept.last().url)
        // 顺序＝访问先后，逐条钉（不是"大概有序"）
        assertEquals(
            "顺序必须是 p2..p21",
            (2..21).map { "https://example.com/p$it" },
            kept.map { it.url },
        )
    }

    @Test
    fun `重复访问不叠加_重看的那页移到末尾`() {
        var kept = emptyList<PageMemoryPolicy.Page>()
        for (i in 1..3) kept = PageMemoryPolicy.merge(kept, page(i))
        val again = PageMemoryPolicy.merge(kept, page(1))
        assertEquals("重复访问不许新增一条", 3, again.size)
        assertEquals(
            "重看的那页必须落到末尾（不是留在原位）",
            listOf("https://example.com/p2", "https://example.com/p3", "https://example.com/p1"),
            again.map { it.url },
        )
    }

    @Test
    fun `同一页反复看 20 次也只占一条`() {
        var kept = emptyList<PageMemoryPolicy.Page>()
        repeat(20) { kept = PageMemoryPolicy.merge(kept, page(7)) }
        assertEquals(1, kept.size)
    }

    @Test
    fun `展示序是新在上_存储序不动`() {
        var kept = emptyList<PageMemoryPolicy.Page>()
        for (i in 1..3) kept = PageMemoryPolicy.merge(kept, page(i))
        val shown = PageMemoryPolicy.recentFirst(kept)
        assertEquals("界面第一行＝最新看的那页", "https://example.com/p3", shown.first().url)
        assertEquals("反转不改原列表（存储序仍是 oldest → newest）", "https://example.com/p1", kept.first().url)
    }

    // ── 判据：只记 URL 与标题 ────────────────────────────────────────────────

    @Test
    fun `Page 只有两个字段_结构上就没有正文位`() {
        val fields = PageMemoryPolicy.Page::class.java.declaredFields
            .filterNot { it.isSynthetic }
            // Compose 编译器会给类塞一个静态 `$stable` 位（与字段语义无关），静态的不算实例字段
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
            .sorted()
        assertEquals("Page 的字段必须正好是 title/url —— 想记正文得先改这里", listOf("title", "url"), fields)
    }

    @Test
    fun `normalize 三条不该记_空地址_about_blank_预览假地址`() {
        assertNull("空地址", PageMemoryPolicy.normalize("", "标题", isPreview = false))
        assertNull("空白地址", PageMemoryPolicy.normalize("   ", "标题", isPreview = false))
        assertNull("about:blank（关标签的清场跳转）", PageMemoryPolicy.normalize("about:blank", "", false))
        assertNull(
            "本地预览假地址（点不开，不是网页）",
            PageMemoryPolicy.normalize("local-preview://a.md", "a.md", isPreview = true),
        )
        val ok = PageMemoryPolicy.normalize(" https://example.com/x ", " 标题 ", isPreview = false)
        assertEquals("正常页要去首尾空白", "https://example.com/x", ok?.url)
        assertEquals("标题同样去空白", "标题", ok?.title)
    }

    @Test
    fun `normalize 裁长_超长标题与超长 URL 都不许原样落库`() {
        val longTitle = "标".repeat(PageMemoryPolicy.MAX_TITLE_CHARS + 50)
        val longUrl = "https://example.com/" + "a".repeat(PageMemoryPolicy.MAX_URL_CHARS + 50)
        val p = PageMemoryPolicy.normalize(longUrl, longTitle, isPreview = false)!!
        assertEquals("标题必须裁到上限", PageMemoryPolicy.MAX_TITLE_CHARS, p.title.length)
        assertEquals("URL 必须裁到上限", PageMemoryPolicy.MAX_URL_CHARS, p.url.length)
    }

    @Test
    fun `标题空就显示地址本身`() {
        val p = PageMemoryPolicy.Page("https://example.com/a", "")
        assertEquals("空标题不许留一行空白", "https://example.com/a", PageMemoryPolicy.labelOf(p))
        val titled = PageMemoryPolicy.Page("https://example.com/a", "有标题")
        assertEquals("有标题就显示标题", "有标题", PageMemoryPolicy.labelOf(titled))
    }

    // ── 字段表与 DDL 的对照（第二道在 PageMemoryGateTest 里逐列比） ─────────────

    @Test
    fun `字段表就是五列_没有正文列`() {
        assertEquals(
            listOf("id", "session_id", "url", "title", "visited_at"),
            PageMemoryPolicy.COLUMNS,
        )
        for (banned in listOf("body", "content", "text", "html", "snippet", "excerpt")) {
            assertTrue(
                "字段表里不许出现 $banned（只记 URL 与标题）",
                PageMemoryPolicy.COLUMNS.none { it.contains(banned, ignoreCase = true) },
            )
        }
    }
}
