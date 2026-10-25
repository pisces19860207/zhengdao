// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第二百零九批 · 文本卡折叠：**纯判据**（阈值/摘要）＋ **记忆对象** ＋ **五条负例**门禁。
 *
 * 图纸：`_zd-private/docs/派工单-2026-10-25-第二百零九批-文本卡折叠.md` §1/§2。
 *
 * 为什么负例走源码级断言：本模块没有 Compose UI 测试基建（无 Robolectric），
 * 与 `ContainerTierGuardTest` / `BaopuLongformGateTest` 同惯例——判据取文本、断言可执行；
 * 能抽成纯函数的（阈值、摘要、按 id 记忆）一律走**真跑**，源码门禁只钉「接线没走样」。
 */
class BaopuLongformCollapseTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    /** 去注释版（块注释＋行注释）——查「代码里有没有某个 token」时用，防 KDoc 误伤。 */
    private fun code(rel: String): String = src(rel)
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)^[ \\t]*//.*$"), "")

    /** 取某个标记之后第一段花括号正文（朴素配对，够用；调用方保证标记唯一）。 */
    private fun block(text: String, marker: String): String {
        val start = text.indexOf(marker)
        assertTrue("源码里找不到 $marker", start >= 0)
        val open = text.indexOf('{', start)
        assertTrue("$marker 后面没有 {", open >= 0)
        var depth = 0
        var i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("$marker 的花括号不配对")
    }

    /**
     * 取「容器调用 + 尾随 lambda」的正文：先**括号**配平参数表（跳过参数里的
     * lambda，如 `onHeaderClick = { … }`），再取尾随 lambda 的花括号正文。
     */
    private fun callBlock(text: String, marker: String): String {
        val start = text.indexOf(marker)
        assertTrue("源码里找不到 $marker", start >= 0)
        val openParen = text.indexOf('(', start)
        assertTrue("$marker 后面没有 (", openParen >= 0)
        var depth = 0
        var i = openParen
        while (i < text.length) {
            when (text[i]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) break
                }
            }
            i++
        }
        assertTrue("$marker 的参数表括号不配对", depth == 0)
        val open = text.indexOf('{', i)
        assertTrue("$marker 的参数表之后没有尾随 lambda", open >= 0)
        depth = 0
        i = open
        while (i < text.length) {
            when (text[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("$marker 的尾随 lambda 花括号不配对")
    }

    // ── 默认折叠阈值（>800 字 或 >20 行） ─────────────────────────────────

    @Test
    fun `阈值-a 超过800字才默认折叠_等于800不折`() {
        assertTrue(BaopuLongformPolicy.shouldDefaultCollapse("字".repeat(801)))
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse("字".repeat(800)))
    }

    @Test
    fun `阈值-b 超过20非空行才默认折叠_等于20不折`() {
        val twenty = (1..20).joinToString("\n") { "第${it}行短句" } // 共 120 字左右，够短
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse(twenty))
        assertTrue(BaopuLongformPolicy.shouldDefaultCollapse("$twenty\n第21行短句"))
    }

    @Test
    fun `阈值-c 空白行不算行_它只是排版`() {
        val text = (1..10).joinToString("\n\n\n") { "第${it}行" }
        assertEquals(10, BaopuLongformPolicy.lineCount(text))
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse(text))
    }

    @Test
    fun `阈值-负例 短文本一律不默认折叠`() {
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse(""))
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse("   \n  \n"))
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse("好的，已记下。"))
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse("字".repeat(799)))
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse((1..20).joinToString("\n") { "短句$it" }))
    }

    // ── 首行摘要（折叠态那一行） ───────────────────────────────────────────

    @Test
    fun `摘要-a 首行是标题行_摘要取下一行`() {
        val md = "# 秋日随想\n\n清晨的雾还没散，巷口的豆浆摊已经排起了队。\n\n结尾就到这里。"
        assertEquals(
            "清晨的雾还没散，巷口的豆浆摊已经排起了队。",
            BaopuLongformPolicy.firstLineSummary(md),
        )
    }

    @Test
    fun `摘要-b 全文无标题行_首行当了卡头标题_摘要避开它`() {
        val md = "今天聊点别的。\n\n第一段正文这才开始，前面那行当了卡头标题。"
        assertEquals(
            "第一段正文这才开始，前面那行当了卡头标题。",
            BaopuLongformPolicy.firstLineSummary(md),
        )
    }

    @Test
    fun `摘要-c 标题在中途_首行是前言_摘要取首行`() {
        val md = "前言一句。\n\n# 正标题\n\n正文第一行。"
        assertEquals("前言一句。", BaopuLongformPolicy.firstLineSummary(md))
    }

    @Test
    fun `摘要-d 剥 markdown 标记只留正文`() {
        val md = "# 标题\n\n- **加粗**开头的一句话\n\n后面。"
        assertEquals("加粗开头的一句话", BaopuLongformPolicy.firstLineSummary(md))
    }

    @Test
    fun `摘要-e 超长截断加省略号_且永远只有一行`() {
        val md = "# 标题\n\n${"字".repeat(100)}\n\n尾巴。"
        val s = BaopuLongformPolicy.firstLineSummary(md)
        assertTrue("超长必须截断加省略号", s.endsWith("…"))
        assertEquals(BaopuLongformPolicy.SUMMARY_MAX + 1, s.length)
        assertFalse("摘要不许含换行（≤1 行）", s.contains("\n"))
    }

    @Test
    fun `摘要-f 空文本与单行文本不炸`() {
        assertEquals("", BaopuLongformPolicy.firstLineSummary(""))
        assertEquals("", BaopuLongformPolicy.firstLineSummary("   \n  \n"))
        assertEquals("只此一行。", BaopuLongformPolicy.firstLineSummary("只此一行。"))
        assertEquals("只有标题", BaopuLongformPolicy.firstLineSummary("# 只有标题"))
    }

    @Test
    fun `摘要-g 多行文本只取一行`() {
        val md = "# 标题\n\n第一行。\n第二行。\n第三行。"
        assertEquals("第一行。", BaopuLongformPolicy.firstLineSummary(md))
    }

    // ── 按消息 id 的记忆（重组不弹回、跨会话不记） ─────────────────────────

    @Test
    fun `记忆-a 按消息id存取_条与条互不串`() {
        BaopuCollapseMemory.clear()
        BaopuCollapseMemory.put("42", true)
        BaopuCollapseMemory.put("43", false)
        assertEquals(true, BaopuCollapseMemory.get("42"))
        assertEquals(false, BaopuCollapseMemory.get("43"))
    }

    @Test
    fun `记忆-b 没记过_返回null（调用方回落默认判据）`() {
        BaopuCollapseMemory.clear()
        assertNull(BaopuCollapseMemory.get("never-seen"))
    }

    @Test
    fun `记忆-c 同一id后写覆盖先写`() {
        BaopuCollapseMemory.clear()
        BaopuCollapseMemory.put("7", true)
        BaopuCollapseMemory.put("7", false)
        assertEquals(false, BaopuCollapseMemory.get("7"))
    }

    @Test
    fun `记忆-d forget只忘指定那一条`() {
        BaopuCollapseMemory.clear()
        BaopuCollapseMemory.put("a", true)
        BaopuCollapseMemory.put("b", true)
        BaopuCollapseMemory.forget("a")
        assertNull(BaopuCollapseMemory.get("a"))
        assertEquals(true, BaopuCollapseMemory.get("b"))
    }

    @Test
    fun `记忆-e 跨会话不记_清空即全新开局`() {
        BaopuCollapseMemory.clear()
        BaopuCollapseMemory.put("42", true)
        assertEquals(1, BaopuCollapseMemory.size())
        // 进程重启＝对象连 Map 一起蒸发；clear() 是这条语义的可执行代理
        BaopuCollapseMemory.clear()
        assertNull(BaopuCollapseMemory.get("42"))
        assertEquals(0, BaopuCollapseMemory.size())
    }

    // ── 五条负例（派工单 §2） ─────────────────────────────────────────────

    @Test
    fun `负例1-点卡头没反应_卡头必须可点且真的翻状态`() {
        val card = src(CARD_REL)
        val container = src(CONTAINER_REL)
        // ① 卡头接了点击——不是死箭头
        assertTrue(
            "长文卡必须把 toggleCollapse 接到卡头 onHeaderClick 上",
            card.contains("onHeaderClick = { toggleCollapse() }"),
        )
        // ② 点击必须落到按 id 的记忆里（只翻本地 state 的话，行被滚出屏就弹回）
        val toggle = block(code(CARD_REL), "fun toggleCollapse()")
        assertTrue("点卡头必须写 BaopuCollapseMemory", toggle.contains("BaopuCollapseMemory.put"))
        // ③ 容器侧：卡头热区 ≥48dp 且 clickable 接在 onHeaderClick 上
        val body = block(container, "fun BaopuActionContainer(")
        assertTrue(
            "卡头热区必须 ≥48dp（BAO_ACTION_HEADER_TOUCH_DP）",
            body.contains("heightIn(min = BAO_ACTION_HEADER_TOUCH_DP.dp)"),
        )
        assertTrue(
            "卡头必须可点（clickable 接 onHeaderClick）",
            body.contains(".clickable(onClickLabel = onHeaderClickLabel, onClick = onHeaderClick)"),
        )
        assertTrue("48 是硬数", container.contains("const val BAO_ACTION_HEADER_TOUCH_DP = 48"))
        // ④ 48dp 热区只许长在 onHeaderClick != null 条件里——其余九张卡的卡头不长高
        assertTrue(
            "热区必须条件在 onHeaderClick != null 上（不许给所有行动卡卡头长高）",
            body.contains("if (onHeaderClick != null)"),
        )
    }

    @Test
    fun `负例2-折叠后操作按钮消失_操作条必须在折叠分支之外`() {
        val card = code(CARD_REL) // 去注释：防 KDoc/行注释里的同名单词误伤
        val call = callBlock(card, "BaopuActionContainer(")
        // 操作条整卡内容里只许一处，且不在折叠分支里
        assertTrue("操作条必须一直在（actionRow）", call.contains("actionRow()"))
        assertEquals(
            "容器内容里 actionRow() 只许出现一次",
            1,
            Regex("actionRow\\(\\)").findAll(call).count(),
        )
        val folded = block(card, "if (collapsed) {")
        assertFalse("折叠分支里不许出现操作条（它该在分支外，两态都显示）", folded.contains("actionRow"))
        assertFalse(
            "折叠分支里不许画正文/视图切换（那才是要省掉的部分）",
            folded.contains("toggleRow") || folded.contains("body("),
        )
        // 折叠态该有的：首行摘要
        assertTrue("折叠态必须显示首行摘要", folded.contains("firstLineSummary"))
    }

    @Test
    fun `负例3-滚动回来折叠态丢失_状态必须活在组合外`() {
        val card = src(CARD_REL)
        // 初始值必须读按 id 的记忆（remember 只活在组合里，行被滚出屏就没了）
        assertTrue("折叠态初始值必须读 BaopuCollapseMemory", card.contains("BaopuCollapseMemory.get(it)"))
        assertTrue("必须按消息 id 记（remember 的 key 是 foldKey）", card.contains("remember(foldKey)"))
        assertTrue("foldKey 必须来自 messageId", card.contains("messageId?.takeIf { it.isNotBlank() }"))
        // 行为侧由「记忆-a~e」证明：对象活在组合外，put 过就翻不回来
    }

    @Test
    fun `负例4-短文本强制折叠_默认判据是纯函数且短文本为假`() {
        val card = src(CARD_REL)
        assertTrue(
            "没记过时必须回落 shouldDefaultCollapse（不许一刀切全折）",
            card.contains("shouldDefaultCollapse(markdown)"),
        )
        // 纯判据侧（真跑）：短文本一律 false——边界值钉死（800/20 是「超过」不是「达到」）
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse("字".repeat(800)))
        assertFalse(BaopuLongformPolicy.shouldDefaultCollapse("好的。"))
    }

    @Test
    fun `负例5-折叠触发重落盘_折叠路径零IO`() {
        // ① 折叠动作本身：只翻状态＋写内存，不许碰落盘/台账/文件
        val toggle = block(code(CARD_REL), "fun toggleCollapse()")
        listOf("save", "Store", "File", "ledger", "Ledger", "writeText", "OutputStream").forEach { token ->
            assertFalse("toggleCollapse 里不许出现 $token（折叠不许触发重落盘）", toggle.contains(token))
        }
        // ② 记忆对象本身：只有一张内存 Map（注释已剥，防 KDoc 误伤）
        val memory = code(MEMORY_REL)
        listOf("File(", "SharedPreferences", "Context", "OutputStream", "writeText", "openStream").forEach { token ->
            assertFalse("BaopuCollapseMemory 里不许出现 $token（进程级内存，零 IO）", memory.contains(token))
        }
        // ③ 落盘在全卡仍只有 save() 一个出口（179 红线不动）
        val save = block(code(CARD_REL), "fun save(ext: String)")
        assertTrue("save() 必须仍走 BaopuLongformStore.save", save.contains("BaopuLongformStore.save"))
        assertFalse(
            "save() 之外不许再碰 BaopuLongformStore",
            code(CARD_REL).replace(save, "").contains("BaopuLongformStore."),
        )
    }

    @Test
    fun `接线-手动文本卡也按消息id记_派生id不撞自动卡`() {
        val list = src(LIST_REL)
        assertTrue(
            "手动「文本卡」入口也要带 messageId（派生 id，与自动卡互不干扰）",
            list.contains("messageId = \"\${msg.id}-manual\""),
        )
    }

    private companion object {
        const val CARD_REL = "src/main/java/com/example/zhengdao/ui/baopu/BaopuLongformCard.kt"
        const val CONTAINER_REL = "src/main/java/com/example/zhengdao/ui/baopu/BaopuContainers.kt"
        const val MEMORY_REL = "src/main/java/com/example/zhengdao/ui/baopu/BaopuCollapseMemory.kt"
        const val LIST_REL = "src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt"
    }
}
