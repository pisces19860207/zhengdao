// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第九十九批 §1.5.2 eval 证据口径的 JVM 用例：**只认最近一次发版结果文件**，
// 没有 ⇒ null（上层明写 eval=null ⇒ policy 判 DEFER），不许拿旧结果冒充、不许默认过线。
//
// 第一百六十八批：同文件补两件事——发版证据带 `source`/`ranAt`（决策卡与审计要标
// 来源与日期）；本机生产证据 `ProdEvalResultFile` 的形状判据（schema＋ranAt 必须有）。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.EvolutionEval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class EvalResultFileTest {

    private fun report(
        passRate: Double? = 1.0,
        ids: List<String> = listOf("case-1", "case-2"),
    ): String {
        val cases = org.json.JSONArray()
        ids.forEach { cases.put(org.json.JSONObject().put("id", it).put("ok", true)) }
        return org.json.JSONObject()
            .apply { if (passRate != null) put("passRate", passRate) }
            .put("model", "deepseek-flash")
            .put("cases", cases)
            .toString()
    }

    // ── 拿不到 ⇒ null（不许默认过线）─────────────────────────────────────

    @Test
    fun `空JSON与坏JSON_一律null`() {
        assertNull(EvalResultFile.parse(""))
        assertNull(EvalResultFile.parse("{}"))
        assertNull(EvalResultFile.parse("不是 JSON"))
        assertNull(EvalResultFile.parse("[]"))
    }

    @Test
    fun `缺passRate_不算有结果`() {
        // 有用例没通过率 ⇒ 读不出「过没过线」⇒ null（不拿 0 或 0.8 冒充）
        assertNull(EvalResultFile.parse(report(passRate = null)))
    }

    @Test
    fun `越界与NaN分数_不收`() {
        assertNull(EvalResultFile.parse(report(passRate = 1.5)))
        assertNull(EvalResultFile.parse(report(passRate = -0.1)))
        assertNull(EvalResultFile.parse("""{"passRate":"abc"}"""))
    }

    @Test
    fun `有效结果_分数与用例id`() {
        val ev = EvalResultFile.parse(report(passRate = 0.9, ids = listOf("case-1", "case-3")))
        assertEquals(EvolutionEval(score = 0.9, caseIds = listOf("case-1", "case-3")), ev)
        // 恰好 0 与 1 都合法（边界含）
        assertEquals(0.0, EvalResultFile.parse(report(passRate = 0.0))!!.score, 1e-9)
        assertEquals(1.0, EvalResultFile.parse(report(passRate = 1.0))!!.score, 1e-9)
    }

    // ── 第一百六十八批：来源与日期 ─────────────────────────────────────────

    @Test
    fun `发版证据_来源恒为RELEASE_旧报告没有ranAt就是null`() {
        val ev = EvalResultFile.parse(report(passRate = 0.9))!!
        assertEquals("这个对象读的就是 assets 里那份 ⇒ 恒发版证据", EvalEvidenceSource.RELEASE, ev.source)
        assertNull(
            "report() 没写 ranAt（2026-10-21 rerun 那批也没有）⇒ null，卡上显示「日期不详」",
            ev.ranAt,
        )
    }

    @Test
    fun `报告带ranAt时_日期一起标_非正数按没有`() {
        val json = org.json.JSONObject()
            .put("passRate", 0.8)
            .put("ranAt", 1_792_000_000_000L)
            .put("cases", org.json.JSONArray().put(org.json.JSONObject().put("id", "c1").put("ok", true)))
            .toString()
        val ev = EvalResultFile.parse(json)!!
        assertEquals(EvalEvidenceSource.RELEASE, ev.source)
        assertEquals(1_792_000_000_000L, ev.ranAt)
        // ranAt 是 0/坏值 ⇒ 当没有（不拿 0 当 1970 年，也不编）
        val zero = org.json.JSONObject().put("passRate", 0.8).put("ranAt", 0).toString()
        assertNull(EvalResultFile.parse(zero)!!.ranAt)
    }

    // ── 第一百六十八批：本机生产证据（ProdEval 产物的读壳）────────────────

    private fun prodReport(
        passRate: Double? = 1.0,
        ranAt: Long? = 1_792_000_000_000L,
        schema: String? = "zhengdao-prod-eval/1",
        ids: List<String> = listOf("a1", "a2"),
    ): String {
        val cases = org.json.JSONArray()
        ids.forEach { cases.put(org.json.JSONObject().put("id", it).put("ok", true)) }
        return org.json.JSONObject()
            .apply { if (schema != null) put("schema", schema) }
            .apply { if (passRate != null) put("passRate", passRate) }
            .apply { if (ranAt != null) put("ranAt", ranAt) }
            .put("model", "deepseek-flash")
            .put("cases", cases)
            .toString()
    }

    @Test
    fun `生产证据_形状对_来源是个人生产_带ranAt`() {
        val ev = ProdEvalResultFile.parse(prodReport(passRate = 0.5))!!
        assertEquals(0.5, ev.score, 1e-9)
        assertEquals(EvalEvidenceSource.PERSONAL_PROD, ev.source)
        assertEquals(1_792_000_000_000L, ev.ranAt)
        assertEquals(listOf("a1", "a2"), ev.caseIds)
    }

    @Test
    fun `生产证据_schema不对_一律null`() {
        assertNull(ProdEvalResultFile.parse(prodReport(schema = "zhengdao-prod-eval/2")))
        assertNull(ProdEvalResultFile.parse(prodReport(schema = null)))
    }

    @Test
    fun `生产证据_缺ranAt_null_判不了新鲜度就不猜`() {
        assertNull(ProdEvalResultFile.parse(prodReport(ranAt = null)))
        assertNull(ProdEvalResultFile.parse(prodReport(ranAt = 0L)))
    }

    @Test
    fun `生产证据_分数缺失或越界或坏JSON_null`() {
        assertNull(ProdEvalResultFile.parse(prodReport(passRate = null)))
        assertNull(ProdEvalResultFile.parse(prodReport(passRate = 1.5)))
        assertNull(ProdEvalResultFile.parse("不是 JSON"))
        assertNull(ProdEvalResultFile.parse("[]"))
    }

    @Test
    fun `坏用例行只跳过那一条_不让整份作废`() {
        val json = """
            {"passRate":0.5,"cases":[{"id":"case-1"},{"no_id":true},{"id":""},{"id":"case-4"}]}
        """.trimIndent()
        assertEquals(listOf("case-1", "case-4"), EvalResultFile.parse(json)!!.caseIds)
    }

    // ── 构建期桥：report-*.json ⇒ assets/eval/eval-result.json ─────────────

    @Test
    fun `构建期stage任务_挑最新report并落固定名字`() {
        val kts = File("build.gradle.kts").readText(Charsets.UTF_8)
        assertTrue("stage 任务必须还在", kts.contains("StageEvalAssetsTask"))
        assertTrue("必须挑 report-*.json", kts.contains("report-"))
        assertTrue("必须落成固定名字 eval-result.json", kts.contains("eval-result.json"))
        assertTrue("按文件名时间戳挑最大（最近一次）", kts.contains("maxByOrNull"))
        // 与 50 批那条门禁同一口径：仓库 eval/ 仍是唯一真源
        assertTrue(kts.contains("""evalDir.set(rootProject.layout.projectDirectory.dir("eval"))"""))
    }

    @Test
    fun `运行时只认固定assets路径`() {
        assertEquals("eval/eval-result.json", EvalResultFile.ASSET_PATH)
        assertEquals("eval-result.json", EvalResultFile.FILE_NAME)
    }
}
