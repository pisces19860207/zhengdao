package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.LlmProtocol
import com.example.zhengdao.baopu.Provider
import com.example.zhengdao.testkit.TestAssume
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 进化门禁（2026-10-17）。
 *
 * 它回答一个问题：**改了一版提示词或工具策略之后，抱朴干活的本事有没有变差。**
 *
 * 用法（本机）：
 * ```
 * $env:ZHENGDAO_EVAL_KEY = "sk-…"      # 只从环境变量取，不入库
 * .\gradlew :app:testDebugUnitTest --tests "com.example.zhengdao.baopu.eval.*"
 * ```
 * - 没有 key ⇒ **跳过**（不是失败）：门禁不能因为没带钥匙就把仓库变红。
 * - 没有 `eval/baseline.json` ⇒ 跑一遍、把这次结果写成基线，不判红（第一次没有可比对象）。
 * - 有基线 ⇒ 通过率不低于基线 −10 个百分点、平均步数不超基线 ×1.2，否则红。
 *
 * 容差为什么是 −10 而不是 −5：本轮评测集只有 10 条用例，**每一条就是 10 个百分点**，
 * 而模型输出本身有抖动（同一个提示词，偶尔一条会换个说法/多绕一步）。卡死在 −5 等于
 * 「一条都不许抖」，第一次误报之后人就会开始无视门禁——那比放水更糟。留一条的余量，
 * 同时把「上轮过、这轮挂」的用例单独点名打出来，仍然看得见退化。
 *
 * 判红之后的下一步不是"改判据"，是看 `eval/report-*.json` 里哪几条挂了、为什么。
 *
 * **基线是人为提升的水位，不会被自动刷高**：要让新水位生效，删掉 `eval/baseline.json`
 * 再跑一次（它会记下当次的通过率与「哪几条通过」）。这样做是刻意的——自动刷高等于
 * 门禁自己走下坡路。
 */
class EvalGateTest {

    @Test
    fun `回放评测集_不退化才允许合入`() {
        val key = System.getenv("ZHENGDAO_EVAL_KEY").orEmpty().trim()
        TestAssume.assume(
            condition = key.isNotBlank(),
            reason = "未跑：缺 ZHENGDAO_EVAL_KEY —— 进化门禁本次未实测（这不是失败）",
        )

        val root = repoRoot()
        val casesFile = File(root, "eval/cases.json")
        assertTrue("找不到评测集：${casesFile.absolutePath}", casesFile.isFile)
        val only = System.getenv("ZHENGDAO_EVAL_ONLY").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val all = EvalCases.load(casesFile)
        // 第八十四批：带 persona fixture 的用例只用写死的样板人设（B/C 层 is not 盘上人设），
        // 默认不进本门禁——自检/门禁的口径是盘上人设。拨 ZHENGDAO_EVAL_PERSONA=1 才放行，
        // 用来显式回放「B/C 层真的进了系统提示」这条通路。
        val personaMode = System.getenv("ZHENGDAO_EVAL_PERSONA") == "1"
        val pool = if (personaMode) all else all.filter { it.persona.isEmpty() }
        val cases = if (only.isEmpty()) pool else pool.filter { it.id in only }
        assertTrue("ZHENGDAO_EVAL_ONLY=${only.joinToString(",")} 一条都没匹配上", cases.isNotEmpty())
        // 第九十四批 ②：eval 显式 temperature=0；seed 由环境变量决定
        // （不设 = 不发，结果里标 NON_DETERMINISTIC——不假装可复现）。
        val determinism = EvalDeterminism(seed = EvalDeterminism.seedFromEnv())
        val cfg = EvalConfig(
            apiKey = key,
            temperature = determinism.temperature,
            seed = determinism.seed,
        )

        val started = System.currentTimeMillis()
        val results = cases.map { EvalHarness.runCase(it, cfg) }
        val pass = results.count { it.ok }
        val rate = pass.toDouble() / cases.size
        val avgSteps = results.map { it.steps }.average().takeIf { !it.isNaN() } ?: 0.0
        val tokensIn = results.sumOf { it.tokensIn }
        val tokensOut = results.sumOf { it.tokensOut }
        val elapsed = System.currentTimeMillis() - started

        writeReport(root, results, rate, avgSteps, elapsed, tokensIn, tokensOut, cfg, determinism)

        val baseline = readBaseline(root)
        println("进化门禁：$pass/${cases.size} 通过（${"%.0f".format(rate * 100)}%），平均 ${"%.1f".format(avgSteps)} 步，token 入 $tokensIn 出 $tokensOut，用时 ${elapsed / 1000}s")
        println("  确定性：${determinism.label()} —— ${determinism.reason()}")
        results.filter { !it.ok }.forEach { r ->
            println("  ✗ ${r.id}：${r.failures.joinToString("；")}")
        }

        if (baseline == null) {
            writeBaseline(root, rate, avgSteps, results.filter { it.ok }.map { it.id }, cfg, determinism)
            println("  （首次运行：已把本次结果写成 eval/baseline.json，下次起按它判退化）")
            return
        }
        // 口径一变（换模型/换通道/确定性档变了）读数就**不可比**——如实打一行，
        // 而不是拿两个不同口径的通过率硬比（那是假门禁）。
        baseline.model?.let {
            if (it != cfg.model) {
                println("  ⚠ 基线与本次不是同一个模型（基线 $it / 本次 ${cfg.model}），通过率对比不可信")
            }
        }
        baseline.determinismLabel?.let {
            if (it != determinism.label()) {
                println("  ⚠ 确定性档变了（基线 $it / 本次 ${determinism.label()}），抖动幅度不可比")
            }
        }
        val nowPassing = results.filter { it.ok }.map { it.id }.toSet()
        // 基线只记「通过率」会掩盖一种最要命的情况：整体没掉多少，但某条特定本事没了。
        // 所以把「上轮过、这轮挂」单独点名。
        val broke = baseline.passing - nowPassing
        if (broke.isNotEmpty()) println("  ⚠ 上轮过、这轮挂：${broke.joinToString("、")}")
        val floor = (baseline.rate - 0.10).coerceAtLeast(0.0)
        assertTrue(
            "通过率退化：本次 ${"%.0f".format(rate * 100)}%，基线 ${"%.0f".format(baseline.rate * 100)}%，下限 ${"%.0f".format(floor * 100)}%",
            rate >= floor,
        )
        assertTrue(
            "平均步数变多：本次 ${"%.1f".format(avgSteps)}，基线 ${"%.1f".format(baseline.avgSteps)}，上限 ${"%.1f".format(baseline.avgSteps * 1.2)}",
            avgSteps <= baseline.avgSteps * 1.2,
        )
    }


    private fun repoRoot(): File {
        var d: File? = File(".").absoluteFile
        repeat(6) {
            if (d != null && File(d, "eval/cases.json").isFile) return d!!
            d = d?.parentFile
        }
        return File("..").absoluteFile
    }

    // ── 基线 / 报告 ──────────────────────────────────────────────────────

    private data class Baseline(
        val rate: Double,
        val avgSteps: Double,
        val passing: Set<String> = emptySet(),
        /** 基线记下的模型名（null = 老基线文件没这字段）。 */
        val model: String? = null,
        /** 基线记下的确定性档标签（null = 老基线文件没这字段）。 */
        val determinismLabel: String? = null,
    )

    private fun readBaseline(root: File): Baseline? {
        val f = File(root, "eval/baseline.json")
        if (!f.isFile) return null
        return runCatching {
            val o = JSONObject(f.readText(Charsets.UTF_8))
            val arr = o.optJSONArray("passing")
            val passing = (0 until (arr?.length() ?: 0)).map { arr!!.getString(it) }.toSet()
            Baseline(
                rate = o.getDouble("passRate"),
                avgSteps = o.getDouble("avgSteps"),
                passing = passing,
                model = o.optString("model").takeIf { it.isNotBlank() },
                determinismLabel = o.optJSONObject("determinism")?.optString("label")?.takeIf { it.isNotBlank() },
            )
        }.getOrNull()
    }

    private fun writeBaseline(
        root: File,
        rate: Double,
        avgSteps: Double,
        passing: List<String>,
        cfg: EvalConfig,
        determinism: EvalDeterminism,
    ) {
        val o = JSONObject()
            .put("passRate", rate)
            .put("avgSteps", avgSteps)
            // 第九十四批 ②：模型名**从 cfg 取**。旧实现写死 "deepseek-flash"，
            // 换个 model 跑出来的基线仍然自称同一个模型——那是假的复现口径。
            .put("model", cfg.model)
            .put("provider", cfg.providerName)
            .put("determinism", determinism.toJson())
            .put("cases", passing.size)
            .put("passing", JSONArray(passing))
            .put("recordedAt", System.currentTimeMillis())
        File(root, "eval/baseline.json").writeText(o.toString(2) + "\n", Charsets.UTF_8)
    }

    private fun writeReport(
        root: File,
        results: List<CaseResult>,
        rate: Double,
        avgSteps: Double,
        elapsedMs: Long,
        tokensIn: Int,
        tokensOut: Int,
        cfg: EvalConfig,
        determinism: EvalDeterminism,
    ) {
        val arr = JSONArray()
        for (r in results) {
            arr.put(
                JSONObject()
                    .put("id", r.id)
                    .put("ok", r.ok)
                    .put("steps", r.steps)
                    .put("tokensIn", r.tokensIn)
                    .put("tokensOut", r.tokensOut)
                    .put("toolCalls", JSONArray(r.toolCalls))
                    .put("failures", JSONArray(r.failures))
                    .put("finalText", r.finalText.take(400))
                    .put("dir", r.dir?.absolutePath ?: ""),
            )
        }
        val o = JSONObject()
            // 第九十四批 ②：通道/模型/参数一起进报告。报告是「复现这次读数」的唯一凭据，
            // 少一个参数就等于让别人拿不到同一份读数。
            .put("provider", cfg.providerName)
            .put("model", cfg.model)
            .put("baseUrl", cfg.baseUrl)
            .put("determinism", determinism.toJson())
            .put("passRate", rate)
            .put("avgSteps", avgSteps)
            .put("elapsedMs", elapsedMs)
            .put("tokensIn", tokensIn)
            .put("tokensOut", tokensOut)
            .put("cases", arr)
        val f = File(root, "eval/report-${System.currentTimeMillis()}.json")
        f.writeText(o.toString(2) + "\n", Charsets.UTF_8)
        println("  报告：${f.absolutePath}")
    }
}
