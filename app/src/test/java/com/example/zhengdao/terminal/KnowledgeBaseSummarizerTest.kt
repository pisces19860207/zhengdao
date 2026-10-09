// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import com.example.zhengdao.oc.OcPart
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P2 摘要逻辑的单测。
 *
 * P2 的网络与设备部分本机测不了（见 `docs/知识库-P2设计方案.md` 第六节），
 * 但"怎么拼提示词、怎么判答完、怎么解析与回写"全是纯函数 —— 这里把它们钉死，
 * 真机上出问题时才能确定"不是这一半的错"。
 */
class KnowledgeBaseSummarizerTest {

    // ── ① 提示词 ────────────────────────────────────────────────────────────

    @Test
    fun `提示词含每个文件名与试读内容`() {
        val p = KnowledgeBaseSummarizer.buildPrompt(
            listOf("人物设定.txt" to "主角名叫张三，是个铁匠。", "大纲.md" to null)
        )
        assertTrue("应含第一个文件名", p.contains("人物设定.txt"))
        assertTrue("应含试读内容", p.contains("主角名叫张三"))
        assertTrue("应含第二个文件名", p.contains("大纲.md"))
        assertTrue("读不出内容时要给出降级提示", p.contains("读不出文本内容"))
        assertTrue("必须带不许写文件的红线", p.contains("不要写任何文件"))
    }

    @Test
    fun `提示词会截断过长的试读内容`() {
        val long = "啊".repeat(5000)
        val p = KnowledgeBaseSummarizer.buildPrompt(listOf("大文件.txt" to long), maxCharsPerFile = 100)
        assertTrue("应有截断标记", p.contains("内容已截断"))
        assertFalse("不应把整篇都塞进去", p.contains(long))
    }

    @Test
    fun `提示词对空白试读按读不出处理`() {
        val p = KnowledgeBaseSummarizer.buildPrompt(listOf("空的.txt" to "   \n  "))
        assertTrue(p.contains("读不出文本内容"))
    }

    // ── ② 答完判定（稳定性收敛）─────────────────────────────────────────────

    @Test
    fun `文本稳定且非空才算答完`() {
        assertFalse("首轮没有上一轮可比", KnowledgeBaseSummarizer.isSettled(null, "结果"))
        assertTrue("两轮一样 ⇒ 答完", KnowledgeBaseSummarizer.isSettled("结果", "结果"))
        assertFalse("还在变 ⇒ 没答完", KnowledgeBaseSummarizer.isSettled("结", "结果"))
    }

    @Test
    fun `空文本永远不算答完`() {
        assertFalse("两轮都空不能当答完", KnowledgeBaseSummarizer.isSettled("", ""))
        assertFalse("空格也不算", KnowledgeBaseSummarizer.isSettled("  ", "  "))
        assertFalse("null 不算", KnowledgeBaseSummarizer.isSettled(null, null))
    }

    @Test
    fun `只取助手正文 不混入工具与推理`() {
        val parts = listOf(
            OcPart.Reasoning("r1", "我想想"),
            OcPart.Tool("t1", toolName = "bash", state = com.example.zhengdao.oc.ToolState.Success),
            OcPart.Text("x1", "第一段"),
            OcPart.Text("x2", "第二段"),
        )
        assertEquals("第一段\n第二段", KnowledgeBaseSummarizer.assistantText(parts))
    }

    @Test
    fun `从消息响应里取最后一条助手正文`() {
        // ⚠️ 真实线格式：`{info:{id,type}, content:[…]}` —— content 与 **info 平级**，
        //    不在 info 里面（真机实测，也是 parseMessages 为什么要单独读外层 parts 的原因）。
        val json = """
            {"data":[
              {"info":{"id":"m1","type":"user"},"content":[{"type":"text","text":"用户的话"}]},
              {"info":{"id":"m2","type":"assistant"},"content":[{"type":"text","text":"助手的话"}]},
              {"info":{"id":"m3","type":"assistant"},"content":[{"type":"text","text":"最后一条"}]}
            ]}
        """.trimIndent()
        assertEquals("最后一条", KnowledgeBaseSummarizer.assistantTextFromMessages(json))
    }

    @Test
    fun `绝不把用户自己说的话当成模型回答`() {
        // 回归：曾经只按"外层 content 优先"取文本、忘了先看角色 ⇒ 摘要会变成用户的原话，
        // 而且"有内容"、不报错（本项目最怕的静默错）。
        val onlyUser = """
            {"data":[{"info":{"id":"m1","type":"user"},"content":[{"type":"text","text":"帮我改文件"}]}]}
        """.trimIndent()
        assertNull("只有用户消息时必须返回 null", KnowledgeBaseSummarizer.assistantTextFromMessages(onlyUser))

        val mixed = """
            {"data":[
              {"info":{"id":"m1","type":"user"},"content":[{"type":"text","text":"帮我改文件"}]},
              {"info":{"id":"m2","type":"assistant"},"content":[{"type":"text","text":"好的，改好了"}]}
            ]}
        """.trimIndent()
        assertEquals("好的，改好了", KnowledgeBaseSummarizer.assistantTextFromMessages(mixed))
    }

    @Test
    fun `只有在 parts 里时才读外层 parts`() {
        val json = """
            {"data":[
              {"info":{"id":"m1","type":"assistant"},"parts":[{"type":"text","text":"走了 parts"}]}
            ]}
        """.trimIndent()
        assertEquals("走了 parts", KnowledgeBaseSummarizer.assistantTextFromMessages(json))
    }

    @Test
    fun `响应无信封或解析不了时返回 null 而不是乱猜`() {
        assertNull("空串", KnowledgeBaseSummarizer.unwrapArray(""))
        assertNull("不是 JSON", KnowledgeBaseSummarizer.unwrapArray("这是一个错误页"))
        assertNull("对象但没有 data 数组", KnowledgeBaseSummarizer.unwrapArray("""{"ok":true}"""))
        assertNull("消息解析不出助手文本时", KnowledgeBaseSummarizer.assistantTextFromMessages("""{"data":[]}"""))
    }

    // ── ③ 解析摘要 ──────────────────────────────────────────────────────────

    @Test
    fun `TAB 分隔的标准输出能全部解析`() {
        val known = setOf("人物.txt", "大纲.md")
        val out = KnowledgeBaseSummarizer.parseSummaries(
            "人物.txt\t主角是个铁匠\n大纲.md\t故事的三幕结构",
            known,
        )
        assertEquals(2, out.size)
        assertEquals("主角是个铁匠", out["人物.txt"])
        assertEquals("故事的三幕结构", out["大纲.md"])
    }

    @Test
    fun `有序号与列表符号的行也能解析`() {
        val known = setOf("a.txt", "b.md")
        val out = KnowledgeBaseSummarizer.parseSummaries(
            """
            - a.txt	第一条
            2. b.md：第二条
            """.trimIndent(),
            known,
        )
        assertEquals("第一条", out["a.txt"])
        assertEquals("第二条", out["b.md"])
    }

    @Test
    fun `模型编出来的文件名一律拒收`() {
        val known = setOf("真的.txt")
        val out = KnowledgeBaseSummarizer.parseSummaries(
            "真的.txt\t有这条\n编的.txt\t没有这条",
            known,
        )
        assertEquals(1, out.size)
        assertNull("不存在的文件不许进摘要", out["编的.txt"])
    }

    @Test
    fun `摘要过长会截断 空摘要会丢弃`() {
        val known = setOf("长.txt", "空.txt")
        val long = "字".repeat(500)
        val out = KnowledgeBaseSummarizer.parseSummaries("长.txt\t$long\n空.txt\t   ", known)
        assertEquals(KnowledgeBaseSummarizer.MAX_SUMMARY_CHARS, out["长.txt"]!!.length)
        assertNull("空摘要不写入", out["空.txt"])
    }

    @Test
    fun `路径里的冒号不会被误当分隔符`() {
        val known = setOf("C:\\doc\\a.txt")
        // 只有第一个 TAB 才是分隔；没有 TAB 时冒号要 ≥2 位才算分隔，且这里文件名本身含冒号
        val out = KnowledgeBaseSummarizer.parseSummaries("C:\\doc\\a.txt\t讲的是文件", known)
        assertEquals("讲的是文件", out["C:\\doc\\a.txt"])
    }

    // ── ④ 回写清单（幂等）────────────────────────────────────────────────────

    @Test
    fun `没有摘要小节时新起一节并保留原有内容`() {
        val body = "# 资料库目录\n\n共 1 个文件：\n\n- `a.txt` — 12 B\n\n---\n\n## 给 AI 的提示（重要）\n\n- 只读\n"
        val out = KnowledgeBaseSummarizer.renderInto(body, mapOf("a.txt" to "讲了一件事"))
        assertTrue("新节要出现", out!!.contains("## 文件摘要"))
        assertTrue("摘要要在", out.contains("讲了一件事"))
        assertTrue("原有提示节必须还在", out.contains("## 给 AI 的提示（重要）"))
        assertTrue("原有的文件清单必须还在", out.contains("- `a.txt` — 12 B"))
    }

    @Test
    fun `重复调用是幂等的 不会越堆越长`() {
        val body = "# 资料库目录\n\n## 给 AI 的提示（重要）\n\n- 只读\n"
        val once = KnowledgeBaseSummarizer.renderInto(body, mapOf("a.txt" to "旧摘要"))!!
        val twice = KnowledgeBaseSummarizer.renderInto(once, mapOf("a.txt" to "新摘要"))!!
        assertEquals("摘要小节只能有一个", 1, Regex("## 文件摘要").findAll(twice).count())
        assertTrue("要换成新摘要", twice.contains("新摘要"))
        assertFalse("旧摘要不该留着", twice.contains("旧摘要"))
        assertTrue("提示节仍在", twice.contains("## 给 AI 的提示（重要）"))
    }

    @Test
    fun `空摘要集不产生任何写入内容`() {
        assertNull("全空 ⇒ 返回 null", KnowledgeBaseSummarizer.renderInto("# 目录\n", emptyMap()))
        assertNull("只有空值也算空", KnowledgeBaseSummarizer.renderInto("# 目录\n", mapOf("a" to "  ")))
    }

    @Test
    fun `摘要是追加在提示节之前 不吞掉清单主体`() {
        val body = "# 资料库目录\n\n- `a.txt` — 1 B\n\n## 给 AI 的提示（重要）\n\n- 只读\n"
        val out = KnowledgeBaseSummarizer.renderInto(body, mapOf("a.txt" to "摘要"))!!
        val iSummary = out.indexOf("## 文件摘要")
        val iHint = out.indexOf("## 给 AI 的提示")
        val iList = out.indexOf("- `a.txt` — 1 B")
        assertTrue("清单在最前", iList in 1 until iSummary)
        assertTrue("摘要在提示之前", iSummary < iHint)
    }

    // ── ⑤ 权限闸门 ──────────────────────────────────────────────────────────

    @Test
    fun `摘要任务的任何权限请求都自动拒绝`() {
        assertTrue(KnowledgeBaseSummarizer.shouldAutoDeny("edit", "/workspace/资料库/原始/a.txt"))
        assertTrue(KnowledgeBaseSummarizer.shouldAutoDeny("bash", null))
        assertTrue(KnowledgeBaseSummarizer.shouldAutoDeny(null, null))
    }

    @Test
    fun `拒绝回执是三态里的 reject`() {
        assertEquals("""{"decision":"reject"}""", KnowledgeBaseSummarizer.denyReplyBody())
    }

    // ── ⑥ 文案 ──────────────────────────────────────────────────────────────

    @Test
    fun `失败文案说人话 且不吓唬用户`() {
        assertTrue(KnowledgeBaseSummarizer.failureLine("超时").contains("清单照旧可用"))
        assertTrue(KnowledgeBaseSummarizer.failureLine(null).contains("原因未知"))
        assertTrue(KnowledgeBaseSummarizer.failureLine("   ").contains("原因未知"))
    }
}
