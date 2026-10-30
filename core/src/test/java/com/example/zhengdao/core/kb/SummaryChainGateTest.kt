// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.core.kb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 301 的**静态门禁**：钉那些"编译得过、跑起来也对，但规矩已经破了"的事。
 *
 * 为什么行为测试不够：下面四条都是**不做也不会报错**的形态 ——
 * - 摘要改成调模型（多一次网络请求，单测照样绿，因为单测没网）
 * - 摘要写盘顺手更新了 `KEY_SIG`（清单照常重建，只是下一次该重算的改动被跳过）
 * - 全量重算被自动流程也走了（只是慢，不报错）
 * - `prune`/`staleness` 写了却没在刷新路径上被调用（死代码，测试全绿）
 *
 * 每条判据都带**自检**：拿一段"故意写坏"的源码喂给同一个判据函数，必须报红。
 * 不然门禁写错了也会永远绿（这个仓库吃过这个亏，见 `NoTerminalWordingGateTest` 的抬头）。
 */
class SummaryChainGateTest {

    private fun src(rel: String): String =
        File("src/main/java/com/example/zhengdao/core/kb/$rel").readText(Charsets.UTF_8)

    // ── 剥注释与字面量（门禁只看代码）───────────────────────────────────────

    /**
     * 去掉注释、并把字符串/字符字面量的**内容**清空（引号一并去掉）。
     *
     * 为什么要清空而不只是剥注释：判据要按花括号配平来切函数体，
     * 字符串里的 `{`/`}`（本项目文案里不少）会把配平算错。
     */
    private fun codeOnly(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val c = src[i]
            val nx = if (i + 1 < src.length) src[i + 1] else ' '
            when {
                c == '/' && nx == '/' -> {
                    while (i < src.length && src[i] != '\n') i++
                }

                c == '/' && nx == '*' -> {
                    i += 2
                    while (i + 1 < src.length && !(src[i] == '*' && src[i + 1] == '/')) {
                        if (src[i] == '\n') out.append('\n')
                        i++
                    }
                    i += 2
                }

                src.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i + 2 < src.length && !src.startsWith("\"\"\"", i)) i++
                    i += 3
                }

                c == '"' -> {
                    i++
                    while (i < src.length && src[i] != '"') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                }

                c == '\'' -> {
                    i++
                    while (i < src.length && src[i] != '\'') {
                        if (src[i] == '\\') i++
                        i++
                    }
                    i++
                }

                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** 切出一个函数的**函数体**（按花括号配平，输入须已 [codeOnly]）。 */
    private fun bodyOf(code: String, signature: String): String {
        val at = code.indexOf(signature)
        assertTrue("门禁锚点丢了：$signature（改了签名就得改门禁，别让它空转）", at >= 0)
        val open = code.indexOf('{', at)
        assertTrue("找不到函数体的起始花括号：$signature", open >= 0)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        return code.substring(open)
    }

    // ── 判据 ⑤：不调模型（纯字符串规则）─────────────────────────────────────

    /** 一旦出现这些，就不是"纯字符串规则"了。 */
    private val modelTokens = listOf(
        "OkHttp", "okhttp3", "HttpURLConnection", "java.net.", "Socket(", "openConnection",
        "buildPrompt", "PROMPT_HEADER", "renderInto", "OpenAI", "api_key", "apiKey", "https://",
    )

    private fun modelViolations(code: String): List<String> = modelTokens.filter { code.contains(it) }

    @Test
    fun `判据5_摘要生成全程不调模型`() {
        val code = codeOnly(src("KnowledgeBaseSummaries.kt"))
        assertTrue("一个字节都没读到（cwd=${File("").absolutePath}）", code.length > 2000)
        assertEquals("摘要生成链里出现了网络/模型符号 ⇒ 判据⑤破了", emptyList<String>(), modelViolations(code))
    }

    @Test
    fun `判据5自检_注入网络调用会被咬住`() {
        assertTrue(
            "判据写错了：一段明摆着的网络调用居然没被咬住",
            modelViolations("""fun f() { java.net.URL("x").openConnection() }""").isNotEmpty(),
        )
        assertTrue(modelViolations("""val c = OkHttpClient()""").isNotEmpty())
        assertTrue(modelViolations("""buildPrompt(listOf())""").isNotEmpty())
        assertTrue("正常代码不许误报", modelViolations("""fun f(a: String) = a.trim()""").isEmpty())
    }

    /**
     * 清单里那行说明是**写给用户和 agent 看的**。E-080 的教训是"文案与行为直接冲突"：
     * 行为早已不调模型，说明行却还写着"借抱朴的免费模型生成" —— 301 顺手改准，并在这里钉住。
     */
    @Test
    fun `判据5_清单说明行不许再宣称调了模型`() {
        val note = KnowledgeBaseSummarizer.SUMMARY_NOTE
        assertTrue("要写明不联网/不调模型（用户最在意的那句话）", note.contains("不调模型"))
        assertFalse("E-080 的旧话不许回潮", note.contains("免费模型"))
    }

    // ── 负例 ①：写盘不许更新 KEY_SIG ─────────────────────────────────────────

    private fun sigViolations(body: String): List<String> =
        listOf("KEY_SIG", "putString").filter { body.contains(it) }

    @Test
    fun `负例1_摘要落盘口不许碰 KEY_SIG`() {
        val code = codeOnly(src("KnowledgeBase.kt"))
        assertEquals(
            "writeSummaries 里出现了指纹写入 ⇒ 下一次该重算清单的改动会被跳过",
            emptyList<String>(),
            sigViolations(bodyOf(code, "fun writeSummaries(")),
        )
        assertEquals(
            "writeSummariesTo 里出现了指纹写入（同上）",
            emptyList<String>(),
            sigViolations(bodyOf(code, "internal fun writeSummariesTo(")),
        )
        assertEquals(
            "摘要刷新路径里出现了指纹写入（同上）",
            emptyList<String>(),
            sigViolations(bodyOf(code, "internal fun refreshSummaries(")),
        )
    }

    @Test
    fun `负例1自检_塞进 KEY_SIG 会被咬住`() {
        assertTrue(sigViolations("""prefs.edit { putString(KEY_SIG, "x") }""").isNotEmpty())
        assertTrue(sigViolations("""fun f() { val s = KEY_SIG }""").isNotEmpty())
        assertTrue("正常写盘不许误报", sigViolations("""atomicWriteChecked(idx, merged)""").isEmpty())
    }

    // ── 接线：链头/链尾真的接上了，全量重算只留手动一条路 ───────────────────

    @Test
    fun `接线_索引写完就刷摘要_链头真的被调用`() {
        val code = codeOnly(src("KnowledgeBase.kt"))
        assertTrue(
            "rebuildIndex 写完清单后必须触发摘要（判据①的接线点）",
            bodyOf(code, "fun rebuildIndex(").contains("refreshSummaries(ctx, force = forceSummaries)"),
        )
        assertTrue(
            "refreshSummaries 必须真的去取输入（链头 listRaw，原来它零调用）",
            bodyOf(code, "internal fun refreshSummaries(").contains("listRaw(ctx)"),
        )
        assertTrue(
            "refreshSummaries 必须走 writeSummaries 落盘（唯一落盘点不许被绕过）",
            bodyOf(code, "internal fun refreshSummaries(").contains("KnowledgeBaseSummaries.refresh("),
        )
        assertTrue(
            "writeSummaries 必须委托给 writeSummariesTo（别另开第二个写口）",
            bodyOf(code, "fun writeSummaries(").contains("writeSummariesTo("),
        )
    }

    @Test
    fun `接线_全量重算只有手动按钮一条路`() {
        val code = codeOnly(src("KnowledgeBase.kt"))
        assertEquals(
            "全量重算只许出现在手动「重新整理」里；自动流程（refresh/autoRefresh/复制后）一律不许带",
            1,
            Regex("forceSummaries\\s*=\\s*true").findAll(code).count(),
        )
        assertTrue(
            "手动按钮必须真的传 true",
            bodyOf(code, "fun requestRebuild(").contains("forceSummaries = true"),
        )
        for (auto in listOf("fun refresh(", "fun autoRefresh(", "fun rebuildAfterCopy(")) {
            assertFalse(
                "$auto 是自动流程，不许全量重算",
                bodyOf(code, auto).contains("forceSummaries"),
            )
        }
    }

    @Test
    fun `接线自检_自动流程也传 true 会被咬住`() {
        val poisoned = "fun autoRefresh() { rebuildIndex(ctx, forceSummaries = true) }"
        assertEquals(
            "判据写错了：自动流程带 true 也没被数出来",
            2,
            Regex("forceSummaries\\s*=\\s*true").findAll(poisoned + " forceSummaries = true").count(),
        )
    }

    // ── 判据 ③：淘汰与过期判据必须真的在刷新路径上 ──────────────────────────

    @Test
    fun `判据3_刷新路径真的用了过期判据与淘汰`() {
        val code = codeOnly(src("KnowledgeBaseSummaries.kt"))
        val refresh = bodyOf(code, "fun refresh(")
        assertTrue("过期判据没被用上 ⇒ 老摘要会一直冒充新的", refresh.contains("staleness("))
        assertTrue("淘汰没被用上 ⇒ 20MB/2000 条上限是摆设", refresh.contains("prune("))
        assertTrue("落盘口必须走 writeSummariesTo", refresh.contains("writeSummariesTo("))
    }

    @Test
    fun `判据3自检_把 prune 去掉会被咬住`() {
        val poisoned = "fun refresh() { staleness(null, null); persist(x, y) }"
        assertFalse(
            "判据写错了：没有 prune 也说有",
            bodyOf(poisoned, "fun refresh(").contains("prune("),
        )
    }
}
