// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十四批 ③（按需注入）的判据测试。
// 这一批要的是「**文件可以越来越长，每轮注入近似恒定**」，同时一条硬话不能破：
// **没超预算 ⇒ 逐字不变**（口径 1）。所以这里既有"降级后只剩常驻"的用例，
// 也有"短文件一个字都没被动过"的用例——后者是前者的许可证，缺了它本批就是破口径。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PersonaInjectionTest {

    // ── 口径 1：默认口径逐字不变 ──

    @Test
    fun `没超预算的层_逐字不变`() {
        val src = persona()
        val r = PersonaInjection.prepare(src, PersonaLayer.C_PROJECT, QUERY_TABLE, budget = 10_000)
        assertEquals("没降级就得原样交出去", src, r.text)
        assertFalse("没降级", r.degraded)
        assertFalse(r.budgetConflict)
        assertEquals(0, r.injectedClauses)
        assertEquals(0, r.omittedClauses)
        assertEquals("整层 10 条都在册", 10, r.totalClauses)
        assertEquals("没降级时整层都算常驻", 10, r.residentClauses)
    }

    @Test
    fun `空文本与缺层都原样返回`() {
        listOf(null, "", "   ").forEach { src ->
            val r = PersonaInjection.prepare(src, PersonaLayer.B_GLOBAL, "写作")
            assertEquals("空层不许造出内容", src.orEmpty(), r.text)
            assertFalse(r.degraded)
            assertEquals(0, r.totalClauses)
        }
    }

    /** 判据⑤ 的另一半：A 内核**不参与**按需注入（再长也整段留着）。 */
    @Test
    fun `A内核恒等原样_长文本也不削减`() {
        val src = personaWith(30)
        val r = PersonaInjection.prepare(src, PersonaLayer.A_KERNEL, QUERY_TABLE, budget = 100)
        assertEquals("A 内核逐字不变", src, r.text)
        assertFalse(r.degraded)
        assertFalse(r.budgetConflict)
    }

    // ── 常驻段 ──

    @Test
    fun `常驻段每轮都在_续跑轮没有新话也在`() {
        val src = persona()
        listOf("", "随便说点什么").forEach { query ->
            val r = PersonaInjection.prepare(src, PersonaLayer.C_PROJECT, query, budget = BUDGET, tokensOf = chars)
            val head = PersonaRules.splitResident(src).first
            assertTrue("常驻段必须整段在（本轮 query＝「$query」）：${r.text}", r.text.startsWith(head))
            assertTrue("这份长度该降级了", r.degraded)
        }
    }

    /** 口径 3：常驻段自己就超预算 ⇒ **只报不删**（不许为达标静默砍规矩）。 */
    @Test
    fun `常驻段超预算_只报不删`() {
        val src = persona()
        val r = PersonaInjection.prepare(src, PersonaLayer.C_PROJECT, QUERY_TABLE, budget = 10, tokensOf = chars)
        assertTrue("要报出冲突", r.budgetConflict)
        assertTrue("常驻段一个字都没被削：${r.text}",
            r.text.startsWith(PersonaRules.splitResident(src).first))
        assertEquals("预算被常驻吃满 ⇒ 按需一条不注", 0, r.injectedClauses)
        assertEquals("没注的都要在索引里数着", 7, r.omittedClauses)
    }

    // ── 判据：按任务检索 ──

    @Test
    fun `判据六_无关任务不注入写作段`() {
        val r = PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, QUERY_TABLE, budget = BUDGET, tokensOf = chars)
        assertTrue("命中的表格段要注入：${r.text}", r.text.contains("数字汇总要给出合计行"))
        assertFalse("无关的写作段一个字都不进来：${r.text}", r.text.contains("形容词"))
        assertFalse(r.text.contains("## 写作"))
        assertFalse("预算够时也不许把无关节顺手塞进来：${r.text}", r.text.contains("archived"))
    }

    @Test
    fun `命中写作任务时注入写作段`() {
        val r = PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, "这段散文太啰嗦，帮我把长句拆短些",
            budget = BUDGET, tokensOf = chars)
        assertTrue("写作段进来了：${r.text}", r.text.contains("形容词"))
        assertFalse("表格段仍不进：${r.text}", r.text.contains("合计行"))
    }

    @Test
    fun `没命中时只多一行索引`() {
        val r = PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, "今天天气晴暖",
            budget = BUDGET, tokensOf = chars)
        assertEquals("一条按需都没注", 0, r.injectedClauses)
        assertEquals("其余全在未注入栏里", r.totalClauses - r.residentClauses, r.omittedClauses)
        assertTrue("只多一行索引：${r.text}",
            r.text.endsWith(PersonaInjection.indexNote(r.omittedClauses, PersonaLayer.C_PROJECT)))
    }

    @Test
    fun `索引行给得出能读的文件名或路径`() {
        val c = PersonaInjection.indexNote(3, PersonaLayer.C_PROJECT)
        val b = PersonaInjection.indexNote(3, PersonaLayer.B_GLOBAL)
        assertTrue("要说清没注入几条：$c", c.contains("另有 3 条"))
        assertTrue("要给出 C 层文件名：$c", c.contains(PersonaFiles.FILE_PERSONA_PROJECT))
        assertTrue("B 层用 B 层的文件名：$b", b.contains(PersonaFiles.FILE_PERSONA))
        assertTrue("要告诉模型怎么读全文：$c", c.contains("read_file"))
        // 引擎给路径时**必须用路径**：裸名按工作区根解析，B 层在 App 私有目录根本打不开
        val path = "/sdcard/下载/抱朴记忆/人设-本项目.md"
        assertTrue("给了路径就别只报名字：${PersonaInjection.indexNote(3, PersonaLayer.C_PROJECT, path)}",
            PersonaInjection.indexNote(3, PersonaLayer.C_PROJECT, path).contains(path))
        val r = PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, "今天天气晴暖",
            pathHint = path, budget = BUDGET, tokensOf = chars)
        assertTrue("降级那行要能被 read_file 直接用：${r.text}", r.text.endsWith(path + "」全文）"))
    }

    @Test
    fun `注入量不超预算_文件变长五倍也没跟着涨`() {
        val small = PersonaInjection.prepare(personaWith(6), PersonaLayer.C_PROJECT, QUERY_TABLE,
            budget = BUDGET, tokensOf = chars)
        val big = PersonaInjection.prepare(personaWith(30), PersonaLayer.C_PROJECT, QUERY_TABLE,
            budget = BUDGET, tokensOf = chars)
        assertTrue("两份都该降级：$small / $big", small.degraded && big.degraded)
        assertTrue("条款数差出一个量级：${small.totalClauses} vs ${big.totalClauses}",
            big.totalClauses > small.totalClauses * 3)
        val slack = chars(PersonaInjection.indexNote(999, PersonaLayer.C_PROJECT))
        assertTrue("小文件注入 ${small.tokens}", small.tokens <= BUDGET + slack)
        assertTrue("大文件注入 ${big.tokens}", big.tokens <= BUDGET + slack)
    }

    /** 真尺子那一条：默认预算 2500 token（工单给的数），按 [TokenEstimator] 也算得过来。 */
    @Test
    fun `默认预算是两千五`() {
        assertEquals(2_500, PersonaInjection.RESIDENT_BUDGET_TOKENS)
        val src = personaWith(30)
        assertTrue("这份 fixture 必须超默认预算才测得到降级",
            TokenEstimator.estimate(src) > PersonaInjection.RESIDENT_BUDGET_TOKENS)
        val r = PersonaInjection.prepare(src, PersonaLayer.C_PROJECT, QUERY_TABLE)
        assertTrue("降级了才谈恒定", r.degraded)
        assertTrue("注入 ${r.tokens} token", r.tokens <= PersonaInjection.RESIDENT_BUDGET_TOKENS +
            TokenEstimator.estimate(PersonaInjection.indexNote(999, PersonaLayer.C_PROJECT)))
    }

    // ── 确定性（前缀缓存的命根子）──

    @Test
    fun `同一份文件同一句话每轮算出同一串`() {
        val src = persona()
        val a = PersonaInjection.prepare(src, PersonaLayer.B_GLOBAL, QUERY_TABLE, budget = BUDGET, tokensOf = chars)
        val b = PersonaInjection.prepare(src, PersonaLayer.B_GLOBAL, QUERY_TABLE, budget = BUDGET, tokensOf = chars)
        assertEquals("注入文本必须逐字节相同", a.text, b.text)
        assertEquals("计数也必须相同", a.omittedClauses, b.omittedClauses)
    }

    @Test
    fun `命中多节时按原文顺序落回_不按分数插队`() {
        val r = PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, "数字汇总的长句要拆短，段落不超三段",
            budget = BUDGET, tokensOf = chars)
        assertTrue("两节都命中：${r.text}", r.text.contains("## 写作") && r.text.contains("## 表格"))
        assertTrue("注入顺序跟着文件走（写作在前）：${r.text}",
            r.text.indexOf("## 写作") < r.text.indexOf("## 表格"))
    }

    @Test
    fun `虚词不进检索键`() {
        val q = PersonaInjection.keysOf("的了是在")
        assertTrue("单字虚词要被丢掉：$q", q.none { it in setOf("的", "了", "是", "在") })
        assertEquals("整句都是虚词 ⇒ 谁都命中不了", 0,
            PersonaInjection.score(q, PersonaInjection.keysOf("我的书我的事")))
        val keys = PersonaInjection.keysOf("数字汇总 Excel")
        assertTrue("汉字二元组要进键：$keys", keys.contains("数字") && keys.contains("汇总"))
        assertTrue("ASCII 词按小写进键：$keys", keys.contains("excel"))
    }

    // ── 可观测（体检那一行）──

    @Test
    fun `体检行把注入量与条数都说出来`() {
        val items = listOf(
            PersonaInjection.prepare(persona(), PersonaLayer.B_GLOBAL, QUERY_TABLE, budget = BUDGET, tokensOf = chars),
            PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, "今天天气晴暖", budget = BUDGET, tokensOf = chars),
        )
        val line = PersonaInjection.report(items)
        assertTrue("要说注入多少 token：$line", line.contains("本轮人设注入") && line.contains("token"))
        assertTrue("要说共几条：$line", line.contains("共 ${items.sumOf { it.totalClauses }} 条"))
        assertTrue("要拆出常驻/按需：$line",
            line.contains("常驻 ${items.sumOf { it.residentClauses }}＋按需 ${items.sumOf { it.injectedClauses }}"))
        assertTrue("未注入的条数也要报：$line", line.contains("未注入 ${items.sumOf { it.omittedClauses }}"))
    }

    @Test
    fun `没降级时体检行说全部常驻`() {
        val items = listOf(PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, "写作", budget = 10_000))
        assertTrue("整句口径：${PersonaInjection.report(items)}",
            PersonaInjection.report(items).endsWith("（全部常驻）"))
    }

    @Test
    fun `常驻超预算时体检行要留一句`() {
        val items = listOf(
            PersonaInjection.prepare(persona(), PersonaLayer.C_PROJECT, QUERY_TABLE, budget = 10, tokensOf = chars),
        )
        assertTrue("要说「未削减」：${PersonaInjection.report(items)}",
            PersonaInjection.report(items).contains("常驻超预算，未削减"))
    }

    // ── 接线（③ 落在引擎轮次入口，不碰组装器）──

    @Test
    fun `注入接在引擎轮次入口_组装器一个字没改`() {
        val engine = code("src/main/java/com/example/zhengdao/baopu/agent/RunEngine.kt")
        val assembler = code("src/main/java/com/example/zhengdao/baopu/ContextAssembler.kt")
        assertEquals("B 与 C 各算一次", 2, Regex("PersonaInjection\\.prepare\\(").findAll(engine).count())
        assertTrue("每轮要 log 注入量", engine.contains("PersonaInjection.report("))
        assertTrue("进组装器的是注入后的文本", engine.contains("globalInjected.text"))
        assertEquals("两层都得把**能打开的路径**交给索引行", 2, Regex("pathHint = personaFiles").findAll(engine).count())
        assertFalse("组装器不许自己再判注入（前缀缓存的不变式在这儿）", assembler.contains("PersonaInjection"))
    }

    // ── helpers ──

    /** 按字符数的测试量尺：判据要的是「预算内的才注」，与尺子口径无关。 */
    private val chars: (String) -> Int = { it.length }

    /** 常驻 38 ＋ 写作 38 ＋ 表格 29 ＝ 105：预算给 130 才装得下两节，而全文 240 字 ⇒ 必降级。 */
    private val BUDGET = 130

    private val QUERY_TABLE = "把这份表里的数字汇总"

    /**
     * 常驻三条 ＋「写作」「表格」两节 ＋ 一节**纯英文**的无关归档。
     *
     * 词汇刻意不重叠（含刻意的拉丁填充节），才测得出「无关任务不注入」；
     * 那一节也用来把全文撑长——注入预算够不够，不能靠"文件本来就短"蒙过去。
     */
    private fun persona(): String = "## 基本规矩\n" +
        "- 先给结论，再给理由。\n" +
        "- 不堆术语。\n" +
        "- 不许编造事实。\n\n" +
        "## 写作\n" +
        "- 散文少用形容词，比喻最多一个。\n" +
        "- 长句要拆短，段落不超三段。\n\n" +
        "## 表格\n" +
        "- 数字汇总要给出合计行。\n" +
        "- 表头不许改动。\n\n" +
        "## 归档\n" +
        "- archived notes for the idle shelf only.\n" +
        "- archived memos for the idle drawer only.\n" +
        "- archived logs for the idle basket only.\n"

    /** n 节互不重叠的人设（用来验「文件变长、注入不变」）；第 3 节才是表格相关。 */
    private fun personaWith(n: Int): String = buildString {
        append("## 基本规矩\n- 先给结论。\n- 不堆术语。\n- 不许编造。\n\n")
        for (i in 1..n) {
            append("## 第${i}节\n")
            append("- 第${i}节甲：${"这一节的规矩要说清细节，不要含糊。".repeat(4)}\n")
            append("- 第${i}节乙：${"另有一条配套的约束跟着上面走，不必另说。".repeat(4)}\n")
            if (i == 3) append("- 数字汇总要给出合计行，表头不许随便动。\n")
            append("\n")
        }
    }

    private fun code(rel: String): String =
        File(rel).let { if (it.isFile) it else File("app/$rel") }
            .readText(Charsets.UTF_8)
            .replace("\r\n", "\n")
            .let { Regex("(?s)/\\*.*?\\*/").replace(it, "") }
            .let { Regex("(?m)^[ \\t]*//.*$").replace(it, "") }
}
