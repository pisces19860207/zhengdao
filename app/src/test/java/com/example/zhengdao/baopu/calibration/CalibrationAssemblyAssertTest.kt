// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百七十四批 · 源码门禁（判据⑤ + 工单追加三 + 边界）。
// 剥注释后扫本单源码与 UI 侧，每道闸都配"注入自检"负例，防 contains 恒真。
//
// 闸要咬住的四件事：
//   ① 装配层零闸引用：判定（RiskMatrix/GuardPipeline）与落库（persist/BaopuSql）一个都不许出现；
//   ② 不新增检索实现：本单只复用 711/719/723 的解析器；
//   ③ 形神不做用户开关：UI 里既没有"形神开关"也没有"敏感注入开关"，更不许有人直接改总开关；
//   ④ 722 三行格式与 738 预算原样保留（本单只装配，不重造、不抬高上限）。
package com.example.zhengdao.baopu.calibration

import com.example.zhengdao.SrcRoots
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CalibrationAssemblyAssertTest {

    private val dir = "app/src/main/java/com/example/zhengdao/baopu/calibration/"
    private val ownFiles = listOf(
        "CalibrationAssembly.kt", "CalibrationBodySource.kt", "CalibrationPhysiology.kt", "CalibrationGovernance.kt",
    ).map { dir + it }

    /** 判据⑤：闸/判定/落库记号一个都不许出现在本单装配码里。 */
    private val gateTokens = listOf(
        "RiskMatrix", "GuardPipeline", "contractLine", "persistContractLine",
        "insertRunLog", "BaopuSql", "TaskModeBoundary", "RiskGate", "AskTierRules",
        "PlanContractRules", "persist(",
    )

    /** 判据⑦：检索是"复用而非新造"，本包一个都不许自己实现。 */
    private val retrievalTokens = listOf(
        "KbRetriever", "RecallIndexSearch", "SessionSearchStore", "倒排", "TFIDF", "bm25", "BM25", "cosine", "embedding",
    )

    private fun codeOfOwn(): String = ownFiles.joinToString("\n") { stripComments(source(it)) }

    private fun source(rel: String) = SrcRoots.find(rel).readText()

    /** UI 侧全部源码（装配不许在界面里留开关，见追加三）。 */
    private fun uiCode(): String {
        val files = SrcRoots.javaRoots()
            .map { File(it, "com/example/zhengdao/ui") }
            .filter { it.isDirectory }
            .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
        assertTrue("必须扫到 UI 源码，否则这道闸是空的：${files.size}", files.isNotEmpty())
        return files.joinToString("\n") { stripComments(it.readText()) }
    }

    // ── ① 不越权 ──────────────────────────────────────────────────────────

    @Test
    fun `判据五_装配源码零闸引用`() {
        val code = codeOfOwn()
        gateTokens.forEach { tok -> assertFalse("装配码不得出现闸记号 $tok", code.contains(tok)) }
    }

    @Test
    fun `判据五_装配只交文本不改判定`() {
        val code = codeOfOwn()
        assertFalse("不得调用判定", "classify(" in code)
        assertFalse("不得调用闸执行", "executeInline" in code)
        assertTrue("三行必须交给 738 的注入接缝", code.contains("CalibrationWiring.readbackProvider"))
        assertTrue("条目必须交给 738 的条目缝", code.contains("CalibrationWiring.entryProvider"))
    }

    // ── ② 不新增检索 ──────────────────────────────────────────────────────

    @Test
    fun `判据七_装配不新增检索实现`() {
        val code = codeOfOwn()
        retrievalTokens.forEach { tok -> assertFalse("不得引用检索实现 $tok", code.contains(tok)) }
        assertFalse("不得自定义检索类", Regex("class .*Retriever").containsMatchIn(code))
        assertTrue("取数须复用 711/719 的解析（经 738 的缝）", code.contains("CalibrationSource.fromFactSlices") || code.contains("factsProvider"))
    }

    // ── ③ 形神不做用户开关 ────────────────────────────────────────────────

    @Test
    fun `追加三_UI里没有形神开关与敏感注入开关`() {
        val ui = uiCode()
        listOf("形神开关", "敏感注入开关", "校准注入开关", "重建校准块").forEach { tok ->
            assertFalse("设置页/界面出现「$tok」即红", ui.contains(tok))
        }
        assertFalse("界面不许直接改注入总开关", ui.contains("CalibrationWiring.enabled ="))
    }

    @Test
    fun `追加三_治理中心只读看本场注入了什么`() {
        val screen = stripComments(source("app/src/main/java/com/example/zhengdao/ui/baopu/GovernanceCenterScreen.kt"))
        val block = stripComments(source("app/src/main/java/com/example/zhengdao/ui/baopu/GovernanceCenterCalibration.kt"))
        assertTrue("那一行必须取自治理层快照，不在界面里另拼一遍", screen.contains("CalibrationGovernance.snapshot()"))
        assertTrue("屏里必须挂上这块（只读、屏内、无抽屉入口）", screen.contains("CalibrationSection(summary = snapshot.calibration)"))
        listOf("Switch(", "Checkbox(", "onClick", "halt(", "save(", "CalibrationWiring.enabled").forEach { tok ->
            assertFalse("只读块里出现写口/开关记号 $tok 即红", block.contains(tok))
        }
    }

    @Test
    fun `追加三_应急关闭要角色加确认`() {
        val code = codeOfOwn()
        assertTrue("关闭入口必须验角色", code.contains("who == WHO"))
        assertTrue("关闭入口必须验确认词", code.contains("confirm == CONFIRM"))
    }

    // ── ④ 722/738 原样（只装配，不重造、不放宽）──────────────────────────

    @Test
    fun `边界_722三行格式与预算原样`() {
        val rb = source("app/src/main/java/com/example/zhengdao/baopu/state/readback/ReadbackLines.kt")
        assertTrue("722 接缝仍在", rb.contains("fun asContextNotes(): List<String>"))
        assertTrue("三行上限仍是 200", rb.contains("MAX_TOTAL_TOKENS = 200"))
        assertTrue("每行上限仍是 90", rb.contains("MAX_LINE_CHARS = 90"))
        val body = source("app/src/main/java/com/example/zhengdao/baopu/state/readback/BodyReadback.kt")
        listOf("turgor", "出汗", "心率档", "疲劳").forEach {
            assertTrue("状态行四项仍在 722 里：$it", body.contains("\"$it\""))
        }
    }

    @Test
    fun `边界_738触发与预算未放宽`() {
        val inject = source(dir + "CalibrationInject.kt")
        assertTrue("L1 上限仍是 3", inject.contains("L1_MAX = 3"))
        assertTrue("L2 上限仍是 8", inject.contains("L2_MAX = 8"))
        assertTrue("条目正文上限仍是 60", source(dir + "CalibrationSource.kt").contains("MAX_ENTRY_CHARS = 60"))
    }

    @Test
    fun `边界_本单不重造三行格式`() {
        val code = codeOfOwn()
        assertFalse("不得在本单写死行头「[身体]」", code.contains("\"[身体]\""))
        assertFalse("不得在本单写死行头「[状态]」", code.contains("\"[状态]\""))
        assertFalse("不得在本单写死行头「[词档]」", code.contains("\"[词档]\""))
        assertFalse("不得在本单重造词档行拼接", code.contains("zones.joinToString"))
        assertTrue("不变量行必须现取 722 渲染", code.contains("BodyReadback.invariantLine"))
        assertTrue("状态行必须现取 722 留位", code.contains("BodyReadback.statusLine"))
    }

    @Test
    fun `边界_默认值一个都不写死`() {
        val code = codeOfOwn()
        assertFalse("不许出现默认身高", Regex("""heightCm\s*=\s*\d""").containsMatchIn(code))
        assertFalse("不许出现默认体脂", Regex("""bodyFatPct\s*=\s*\d""").containsMatchIn(code))
        assertFalse("不许给疲劳填默认档", Regex("""疲劳[":＝]+(清醒|轻|中)""").containsMatchIn(code))
    }

    /**
     * 658 的合成基线（温和/适中/静息/短时/平静/典型）一旦进装配码，状态行就会把编出来的档
     * 当成逐场读数（"疲劳=清醒"那条红线）。档名只能来自真解，真解只吃带出处的逐场档位。
     */
    @Test
    fun `边界_不许拿658合成基线当读数`() {
        val code = codeOfOwn()
        assertFalse("装配码不得引用 658 的合成基线", code.contains("BASELINE"))
        assertTrue("这道闸必须咬得住样例", stripComments("val i = PhysioInputs.BASELINE // 注释里的不算").contains("BASELINE"))
    }

    @Test
    fun `边界_状态行的档只从658真解取`() {
        val code = codeOfOwn()
        assertTrue("必须现调 658 求解器，不许自己拼档名", code.contains("LightPhysiologySolver.solve"))
        assertTrue("必须走 658 自己的档名", code.contains(".grade.label"))
    }

    // ── 闸自检（负例）：证明闸有牙 ────────────────────────────────────────

    @Test
    fun `闸自检_注入风险记号必被咬住`() {
        val poisoned = stripComments("val x = RiskMatrix.classify(/* c */ call) // 注释")
        assertTrue("样例必须被闸咬住", gateTokens.any { poisoned.contains(it) })
    }

    @Test
    fun `闸自检_UI里假装的形神开关必被咬住`() {
        val poisoned = stripComments("Switch(checked = 形神开关.value) // 注释里的不算")
        assertTrue("界面出现该字样必须被咬住", poisoned.contains("形神开关"))
    }

    @Test
    fun `闸自检_假装的默认身高必被咬住`() {
        val poisoned = stripComments("val s = HumanBodySpec(heightCm = 170.0)")
        assertTrue("默认身材必须被咬住", Regex("""heightCm\s*=\s*\d""").containsMatchIn(poisoned))
    }

    // ── helpers（与 738 门禁同一套剥注释法）───────────────────────────────

    private fun stripComments(src: String): String {
        val out = StringBuilder(src.length)
        var i = 0
        while (i < src.length) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            when {
                two == "//" -> i = skipLine(src, i, out)
                two == "/*" -> i = skipBlock(src, i, out)
                src[i] == '"' -> i = copyString(src, i, out)
                else -> { out.append(src[i]); i++ }
            }
        }
        return out.toString()
    }

    private fun skipLine(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        out.append("  ")
        while (i < src.length && src[i] != '\n') { out.append(' '); i++ }
        return i
    }

    private fun skipBlock(src: String, start: Int, out: StringBuilder): Int {
        var i = start + 2
        var depth = 1
        out.append("  ")
        while (i < src.length && depth > 0) {
            val two = if (i + 1 < src.length) src.substring(i, i + 2) else ""
            if (two == "/*") { depth++; out.append("  "); i += 2 }
            else if (two == "*/") { depth--; out.append("  "); i += 2 }
            else { if (src[i] != '\n') out.append(' ') else out.append('\n'); i++ }
        }
        return i
    }

    private fun copyString(src: String, start: Int, out: StringBuilder): Int {
        var i = start
        out.append(src[i]); i++
        while (i < src.length) {
            out.append(src[i])
            if (src[i] == '\\' && i + 1 < src.length) { out.append(src[i + 1]); i += 2; continue }
            if (src[i] == '"') { i++; break }
            i++
        }
        return i
    }
}
