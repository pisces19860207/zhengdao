// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十一批 §2/§4：压缩前后关键标签、保留率正常/低/空输入、触发原因。
// 标签样例取自 `eval/cases.json` 每例的可核对字符串（不依赖该文件内容变化）。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CompressionQualityTest {

    /** eval/cases.json 里各例的可核对字符串（final_contains / file_contains / 路径）。 */
    private val evalLabels = listOf(
        "第二行内容",
        "第三行内容",
        "三国演义",
        "notes/摘要.md",
        "桃花",
        "已修正",
        "第一行",
        "第四行",
        "资料库/整理/新名.txt",
        "资料库/整理/旧名.txt",
        "index.json",
        "items",
        "王大锤",
        "不许动.txt",
    )

    @Test
    fun `抽取标签_事实决定待办三类`() {
        val labels = CompressionQuality.extractLabels(
            """
            资料库/整理/三国演义.txt 存在
            决定：不要改文件，其它行一个字都别动
            把第二行改成「已修正」，写进 b.md
            """.trimIndent(),
        )
        assertEquals(3, labels.size)
        assertEquals(LabelKind.FACT, labels[0].kind)
        assertEquals(LabelKind.DECISION, labels[1].kind)
        assertEquals(LabelKind.TODO, labels[2].kind)
    }

    @Test
    fun `正常保留_标签都在摘要里_不告警`() {
        val source = listOf(
            "事实：第二行内容和第三行内容在 a.md 里",
            "决定：不要改文件",
            "待办：把桃花写进 notes/摘要.md",
        )
        val summary = "事实：第二行内容、第三行内容、a.md\n决定：不要改文件\n" +
            "待办：桃花 notes/摘要.md"
        val m = CompressionQuality.measure(
            sourceTexts = source,
            summary = summary,
            triggerReason = "测试",
            summaryTokens = 200,
            sourceTokens = 800,
        )
        assertEquals(3, m.labelsTotal)
        assertEquals(3, m.labelsKept)
        assertEquals(1.0, m.retention, 0.0)
        assertFalse(m.lowRetention)
        assertTrue(m.summaryLine().contains("保留率 1.00"))
    }

    @Test
    fun `低保留_只标记告警_不抛异常`() {
        val source = listOf(
            "资料库/整理/三国演义.txt 里有三国演义",
            "决定：不要改文件，保持第一行和第四行。",
            "写进 notes/摘要.md，里面要有桃花。",
        )
        val m = CompressionQuality.measure(
            sourceTexts = source,
            summary = "目标：处理完了\n已定的决定：无\n未决：无",
            triggerReason = "测试",
            summaryTokens = 30,
            sourceTokens = 300,
        )
        assertTrue("应当识别出低保留：${m.retention}", m.lowRetention)
        assertTrue(m.retention < CompressionQuality.LOW_RETENTION_THRESHOLD)
        assertTrue(m.labelsKept < m.labelsTotal)
    }

    @Test
    fun `空输入_无标签_保留率记1且不告警`() {
        val m = CompressionQuality.measure(
            sourceTexts = emptyList(),
            summary = "",
            triggerReason = "测试",
            summaryTokens = 0,
            sourceTokens = 0,
        )
        assertEquals(0, m.labelsTotal)
        assertEquals(0, m.labelsKept)
        assertEquals("没有可测标签时记 1.0（报告靠 labelsTotal 区分）", 1.0, m.retention, 0.0)
        assertFalse(m.lowRetention)
        assertEquals(0.0, m.compressionRatio, 0.0)
    }

    @Test
    fun `标签覆盖阈值_一半词面重合算保住`() {
        val label = KeyLabel(LabelKind.FACT, "第二行内容")
        assertTrue("行内/内容 两个 bigram 命中 = 0.5", CompressionQuality.isRetained(label, "第三行内容"))
        assertFalse(CompressionQuality.isRetained(label, "第一行"))
        assertFalse(CompressionQuality.isRetained(label, ""))
    }

    @Test
    fun `eval用例的核对字符串能被度量识别`() {
        val source = evalLabels
        val m = CompressionQuality.measure(
            sourceTexts = source,
            summary = evalLabels.joinToString(" "),
            triggerReason = "测试",
            summaryTokens = 100,
            sourceTokens = 200,
        )
        assertEquals(evalLabels.size, m.labelsTotal)
        assertEquals("原文里已有的核对串，摘要原样保留 ⇒ 全保住", evalLabels.size, m.labelsKept)
        assertEquals(1.0, m.retention, 0.0)
    }

    @Test
    fun `token比与触发原因可复核`() {
        val m = CompressionQuality.measure(
            sourceTexts = listOf("一个足够长的句子用来抽标签"),
            summary = "摘要",
            triggerReason = "测试",
            summaryTokens = 250,
            sourceTokens = 1_000,
        )
        assertEquals(0.25, m.compressionRatio, 0.0)
        assertEquals(1_000, m.sourceTokens)
        assertEquals(250, m.summaryTokens)
        assertTrue(m.summaryLine().contains("tokens 1000→250"))
    }

    @Test
    fun `触发原因_超线写超过触发线_未超写调用方要求`() {
        val over = CompressionQuality.triggerReason(800_000, Providers.DEEPSEEK)
        val under = CompressionQuality.triggerReason(100, Providers.DEEPSEEK)
        assertTrue(over, over.startsWith("超过触发线"))
        assertTrue(over.contains("734003"))
        assertTrue(under, under.startsWith("调用方要求"))
    }

    @Test
    fun `抽取上限_最多MAX_LABELS条`() {
        val text = (1..(CompressionQuality.MAX_LABELS + 20)).joinToString("\n") { "事实行 $it 号" }
        assertEquals(CompressionQuality.MAX_LABELS, CompressionQuality.extractLabels(text).size)
    }
}
