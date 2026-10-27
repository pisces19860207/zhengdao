// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百四十五批 · B1：答案消息「详情 ▸」折叠的判据。
//
// 判据（派工单 B1）：
// · 首行常显，其余（解释／过程／出处）默认折叠；
// · 代码块／列表／表格**整块**要么全显、要么整块进折叠，不许从中间切；
// · 展开后与原文逐字一致（head ＋ tail 逐字等于原文，每个样例都断言一次）；
// · 默认收起（界面侧由源码门禁钉住：初始态必须是 false，点开＝再点收起）。
package com.example.zhengdao.ui.baopu

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BaopuAnswerFoldTest {

    // ── 正例：切点 ────────────────────────────────────────────────────────

    @Test
    fun `首行平文_只露首行_其余折起来`() {
        val text = "答案是 42。\n\n因为它等于 6 乘 7，推导过程略长，放在折叠里。"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals("答案是 42。", f.head)
        assertEquals("\n\n因为它等于 6 乘 7，推导过程略长，放在折叠里。", f.tail)
        assertTrue(f.foldable)
    }

    @Test
    fun `代码块在首行_整块常显_不从中间切`() {
        val text = "```bash\nnpm i\nnpm run build\n```\n\n跑完把日志给我。"
        val f = BaopuAnswerFoldPolicy.split(text)
        // 两个围栏（开、收）都必须留在首块里 ⇒ 不会被切成两半
        assertEquals(2, f.head.lines().count { it.trimStart().startsWith("```") })
        assertEquals("```bash\nnpm i\nnpm run build\n```", f.head)
        assertEquals("\n\n跑完把日志给我。", f.tail)
    }

    @Test
    fun `代码块在其余_整块进折叠`() {
        val text = "照下面这段跑：\n\n```kotlin\nval a = 1\nval b = 2\n```\n"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals("照下面这段跑：", f.head)
        // 折叠里是完整的代码块（开、收围栏都在 tail，没有被切在中间）
        assertEquals(2, f.tail.lines().count { it.trimStart().startsWith("```") })
    }

    @Test
    fun `列表整块常显_含项间空行与缩进续行`() {
        val text = "- 甲\n- 乙\n  续行也属于乙\n\n- 丙\n\n以上三条。"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals("- 甲\n- 乙\n  续行也属于乙\n\n- 丙", f.head)
        assertEquals("\n\n以上三条。", f.tail)
    }

    @Test
    fun `有序列表与顿号编号都算列表`() {
        assertEquals("1. 第一步\n2. 第二步", BaopuAnswerFoldPolicy.split("1. 第一步\n2. 第二步\n结论。").head)
        assertEquals("1、第一步\n2、第二步", BaopuAnswerFoldPolicy.split("1、第一步\n2、第二步\n结论。").head)
    }

    @Test
    fun `表格整块常显`() {
        val text = "| 项 | 值 |\n| --- | --- |\n| 甲 | 1 |\n\n表就这些。"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals("| 项 | 值 |\n| --- | --- |\n| 甲 | 1 |", f.head)
        assertEquals("\n\n表就这些。", f.tail)
    }

    @Test
    fun `引用整块常显`() {
        val text = "> 原文第一句\n> 原文第二句\n\n我的解读在下面。"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals("> 原文第一句\n> 原文第二句", f.head)
        assertEquals("\n\n我的解读在下面。", f.tail)
    }

    @Test
    fun `首个非空行才是切点_开头的空行留在首块`() {
        val text = "\n\n答案是 42。\n解释。"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals("\n\n答案是 42。", f.head)
        assertEquals("\n解释。", f.tail)
    }

    // ── 正例：逐字一致（展开不许丢行、不许改写空白） ──────────────────────

    @Test
    fun `逐字还原_拼接逐字等于原文`() {
        val samples = listOf(
            "答案是 42。\n\n解释。",
            "```bash\na\nb\n```\n\n然后。",
            "- 甲\n- 乙\n\n以上。",
            "| a | b |\n| - | - |\n\n说明。",
            "> 引\n\n解读。",
            "# 标题\n\n正文。",
            "一句话，没有换行。",
            "结尾带换行。\n",
            "\n\n\n开头三个空行。\n\n后面。",
            "第一行\n第二行\n\n第三段\n第四行",
            "无收尾围栏\n```kotlin\nval a = 1\n",
            "纯空白 \n\n \n",
        )
        for (s in samples) {
            val f = BaopuAnswerFoldPolicy.split(s)
            assertEquals("拼接必须逐字等于原文：${s.take(24)}", s, f.head + f.tail)
            assertEquals("expand 必须逐字等于原文：${s.take(24)}", s, f.expand())
        }
    }

    @Test
    fun `展开后的行数等于原文行数`() {
        val text = "答案\n\n第一段解释\n\n```kt\nval a = 1\n```\n\n出处：某文档"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals(text.lines().size, f.expand().lines().size)
        assertTrue("折叠里必须有内容", f.foldable)
    }

    @Test
    fun `CRLF 原文也能逐字还原`() {
        val text = "答案\r\n\r\n解释\r\n"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals(text, f.head + f.tail)
        assertEquals("答案", f.head)
    }

    // ── 正例：不该折叠的情形 ──────────────────────────────────────────────

    @Test
    fun `单段与空文本不折叠`() {
        assertFalse(BaopuAnswerFoldPolicy.isFoldable("就一句话。"))
        assertFalse("只有一个换行不算折叠内容", BaopuAnswerFoldPolicy.isFoldable("一句话。\n"))
        assertFalse(BaopuAnswerFoldPolicy.isFoldable(""))
        assertFalse(BaopuAnswerFoldPolicy.isFoldable("   \n\n  "))
    }

    @Test
    fun `未收尾的围栏不折叠_切开等于切一半代码`() {
        val text = "```kotlin\nval a = 1\nval b = 2\n"
        val f = BaopuAnswerFoldPolicy.split(text)
        assertEquals(text, f.head)
        assertFalse(f.foldable)
    }

    // ── 门禁：界面侧（源码级，措辞与注释都不许冒充判据） ──────────────────

    @Test
    fun `门禁_默认收起_点开再点收起`() {
        val ui = codeOnly(readUi("BaopuMessageList.kt"))
        assertTrue(
            "消息卡必须用折叠判据切，不许自己另切一份",
            Regex("""BaopuAnswerFoldPolicy\.split\(""").containsMatchIn(ui),
        )
        assertTrue(
            "初始态必须是收起（false）；改成 true 就是「默认展开」缺陷",
            Regex(
                """mutableStateOf\(BaopuCollapseMemory\.get\(memoryKey\)\?\.let \{ !it \} \?: false\)""",
            ).containsMatchIn(ui),
        )
        assertTrue(
            "点开＝再点收起：同一个按钮翻转同一个状态",
            Regex("""val next = !expanded""").containsMatchIn(ui),
        )
        assertTrue(
            "手势与审计栏同一套：TextButton ＋ 48dp 触控高度",
            Regex("""modifier = Modifier\.heightIn\(min = 48\.dp\)""").containsMatchIn(ui),
        )
    }

    @Test
    fun `门禁_折叠只接在长文卡之后_多段不抢`() {
        val ui = codeOnly(readUi("BaopuMessageList.kt"))
        assertTrue(
            "只有单段纯文本才折叠（多段／压缩点各有渲染口）",
            Regex("""textParts\.size == 1 &&\s*textParts\[0\]\.kind == "text"""").containsMatchIn(ui),
        )
        val longformAt = ui.indexOf("} else if (longform)")
        val foldAt = ui.indexOf("} else if (answerFold != null && answerFold.foldable)")
        assertTrue("长文卡分支必须还在", longformAt >= 0)
        assertTrue("折叠必须排在长文卡之后（长文进卡，不折两次）", foldAt > longformAt)
    }

    @Test
    fun `门禁_中英文案键一一对应`() {
        val zh = readRes("values/strings_baopu_answer.xml")
        val en = readRes("values-en/strings_baopu_answer.xml")
        val keys = Regex("""<string name="([^"]+)">""").findAll(zh).map { it.groupValues[1] }.toList()
        val keysEn = Regex("""<string name="([^"]+)">""").findAll(en).map { it.groupValues[1] }.toList()
        assertEquals(listOf("baopu_answer_fold_expand", "baopu_answer_fold_collapse"), keys)
        assertEquals("英文镜像必须键对键一致", keys, keysEn)
    }

    // ── 小工具 ───────────────────────────────────────────────────────────

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }

    private fun readUi(rel: String): String =
        File(repoRoot(), "app/src/main/java/com/example/zhengdao/ui/baopu/$rel").readText()

    private fun readRes(rel: String): String =
        File(repoRoot(), "app/src/main/res/$rel").readText()

    private fun codeOnly(src: String): String =
        src.replace("\r\n", "\n").split("\n").joinToString("\n") { it.substringBefore("//") }
            .replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
}
