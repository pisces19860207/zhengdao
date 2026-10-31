// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.eval

import com.example.zhengdao.testkit.TestAssume
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 282 · 制度四组消融的**跑批入口**（2026-10-31）。
 *
 * 口径预登记在私有仓 `_zd-private/docs/experiments/282-制度四组消融/口径-预登记-2026-10-31.md`；
 * 本类只负责装弹：读 key（只进环境变量）、读用例批次、按组×遍×例顺序跑、逐行落 JSONL。
 * **判读不在这里**（analyze-282.py），本类红了只说明基建坏了，不说明制度好坏。
 *
 * 用法（本机；与 EvalGateTest 同一把钥匙纪律）：
 * ```
 * $env:ZHENGDAO_EVAL_KEY = "<key>"          # 只从环境变量取，永不入库
 * .\gradlew :app:testDebugUnitTest --tests "com.example.zhengdao.baopu.eval.Eval282AblationTest"
 * ```
 * 环境变量：
 * - `ZHENGDAO_EVAL_282_OUT`：JSONL 落点（缺省＝私有仓实验目录 runs-282.jsonl）
 * - `ZHENGDAO_EVAL_282_GROUPS`：默认 `A,B,C,D`
 * - `ZHENGDAO_EVAL_282_REPS`：默认 `2`（口径 §二）
 * - `ZHENGDAO_EVAL_282_ONLY`：只跑这些用例 id（烟测用）
 * - `ZHENGDAO_EVAL_282_BATCH`：新增用例 JSON 路径（缺省＝私有仓 cases-extra-282.json）
 *
 * 断点续跑：已存在于 JSONL 的 (组,遍,例) 自动跳过——中途崩了重跑同一命令即可，不会重复计费。
 */
class Eval282AblationTest {

    @Test
    fun `四组消融跑批_逐行落盘`() {
        val key = System.getenv("ZHENGDAO_EVAL_KEY").orEmpty().trim()
        TestAssume.assume(
            condition = key.isNotBlank(),
            reason = "未跑：缺 ZHENGDAO_EVAL_KEY —— 282 消融本次未实测（这不是失败）",
        )

        val root = repoRoot()
        val casesFile = File(root, "eval/cases.json")
        assertTrue("找不到评测集：${casesFile.absolutePath}", casesFile.isFile)
        val extraFile = File(
            System.getenv("ZHENGDAO_EVAL_282_BATCH")
                ?: File(root.parentFile, "_zd-private/docs/experiments/282-制度四组消融/cases-extra-282.json").absolutePath,
        )
        val pool = ArrayList<EvalCase>()
        pool += EvalCases.load(casesFile).filter { !it.usesLayerFixture } // A 段水位 10 条（口径 §二）
        if (extraFile.isFile) pool += EvalCases.load(extraFile)           // 预登记新例（r3-lure / outside-lure）
        val seen = HashSet<String>()
        pool.removeAll { !seen.add(it.id) }

        val only = System.getenv("ZHENGDAO_EVAL_282_ONLY").orEmpty()
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }.toSet()
        val cases = if (only.isEmpty()) pool else pool.filter { it.id in only }
        assertTrue("用例批次为空", cases.isNotEmpty())

        val groups = (System.getenv("ZHENGDAO_EVAL_282_GROUPS") ?: "A,B,C,D")
            .split(",").map { it.trim() }.filter { it.isNotEmpty() }
        val reps = (System.getenv("ZHENGDAO_EVAL_282_REPS") ?: "2").trim().toIntOrNull() ?: 2

        val outFile = File(
            System.getenv("ZHENGDAO_EVAL_282_OUT")
                ?: File(root.parentFile, "_zd-private/docs/experiments/282-制度四组消融/runs-282.jsonl").absolutePath,
        )
        outFile.parentFile?.mkdirs()
        val done = HashSet<String>()
        if (outFile.isFile) {
            outFile.readLines(Charsets.UTF_8).forEach { line ->
                val t = line.trim()
                if (t.isEmpty()) return@forEach
                runCatching {
                    val o = org.json.JSONObject(t)
                    done.add("${o.optString("group")},${o.optInt("rep")},${o.optString("caseId")}")
                }
            }
        }

        val cfg = EvalConfig(apiKey = key)
        var count = 0
        for (g in groups) {
            val sw = Eval282Switches.of(g)
            for (rep in 0 until reps) {
                for (case in cases) {
                    val k = "$g,$rep,${case.id}"
                    if (k in done) continue
                    val row = Eval282Ablation.runCase(case, cfg, sw, rep)
                    outFile.appendText(row.toJson().toString() + "\n", Charsets.UTF_8)
                    count++
                    println(
                        "[282] $g rep$rep ${case.id}: success=${row.success} steps=${row.steps}" +
                            " corrections=${row.gateCorrections} interrupts=${row.askUser}+${row.popCards}" +
                            " fe=${row.fourEyesJudgments}/${row.fourEyesChanged} rc=${row.rcRuns}/${row.rcBounces}" +
                            " ms=${row.elapsedMs}" + (row.error?.let { " err=$it" } ?: ""),
                    )
                }
            }
        }
        println("[282] 本次新跑 $count 行；累计 ${done.size + count} 行 ⇒ ${outFile.absolutePath}")
        assertTrue("一行都没跑成且没有断点可续（检查网络/key）", count > 0 || done.isNotEmpty())
    }

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }
}
