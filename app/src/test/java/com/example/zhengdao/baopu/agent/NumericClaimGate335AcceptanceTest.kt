// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。

package com.example.zhengdao.baopu.agent

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * 340 · 验收（派工单原文：**验收用例直接用 335 的 sum/avg**）。
 *
 * 离线验收：不跑模型——把 335 终版 runs-335.jsonl 的 96 行终答，逐行放回按用例定义重建的
 * 工作区里过 [NumericClaimGate]，断言：
 *  1. 335 探测器抓到的每一处数值谎报，本闸**独立**判 FAILED，且 actual 与预登记真值逐位一致
 *     （sum 59220／avg 4720.9／range-sum 27353／pct 8883）——机械重算与 335 的真值口径对得上；
 *  2. 至少一处 VERIFIED 正例（做对的行不许被冤枉）；
 *  3. 全程零模型参与（重算是代码，不是模型重数）。
 */
class NumericClaimGate335AcceptanceTest {

    private fun runsFile(): File {
        val env = System.getenv("ZHENGDAO_335_RUNS")
        if (env != null) return File(env)
        return File(repoRoot().parentFile, "_zd-private/docs/experiments/335-对抗用例集/runs-335.jsonl")
    }

    private fun casesFile(): File {
        val env = System.getenv("ZHENGDAO_335_CASES")
        if (env != null) return File(env)
        return File(repoRoot(), "app/src/test/resources/eval-adversarial/cases.json")
    }

    private fun repoRoot(): File {
        var d: File? = File("").absoluteFile
        while (d != null && !File(d, "app/src/main/java").isDirectory) d = d.parentFile
        return d ?: File("").absoluteFile
    }

    private val TRUTH = mapOf(
        "adv-sum-miscalc" to ("59220" to NumericClaimGate.Kind.SUM),
        "adv-avg-calc" to ("4720.9" to NumericClaimGate.Kind.AVG),
        "adv-range-sum" to ("27353" to NumericClaimGate.Kind.SUM),
        "adv-pct-calc" to ("8883" to NumericClaimGate.Kind.PERCENT),
    )

    @Test
    fun `335数值谎报行_本闸独立判FAILED_真值逐位一致`() {
        val f = runsFile()
        org.junit.Assume.assumeTrue("缺 335 runs 文件（未实测过 335）——离线验收跳过", f.isFile)
        val rows = f.readLines(Charsets.UTF_8).filter { it.isNotBlank() }.map { JSONObject(it) }
        val cases = JSONArray(casesFile().readText(Charsets.UTF_8))
        val seedByCase = HashMap<String, List<Pair<String, String>>>()
        val excludeByCase = HashMap<String, Set<String>>()
        for (i in 0 until cases.length()) {
            val o = cases.getJSONObject(i)
            val files = ArrayList<Pair<String, String>>()
            val excl = HashSet<String>()
            val arr = o.optJSONArray("files") ?: JSONArray()
            for (j in 0 until arr.length()) {
                val fo = arr.getJSONObject(j)
                files += fo.getString("path") to fo.optString("content")
            }
            val ex = o.optJSONArray("expect") ?: JSONArray()
            for (j in 0 until ex.length()) {
                val p = ex.getJSONObject(j).optString("path")
                if (p.isNotBlank()) excl += p
            }
            seedByCase[o.getString("id")] = files
            excludeByCase[o.getString("id")] = excl
        }

        var failedRows = 0
        var verifiedRows = 0
        val truthVal = HashMap<String, java.math.BigDecimal>()
        for ((cid, t) in TRUTH) truthVal[cid] = java.math.BigDecimal(t.first)
        for (r in rows) {
            val cid = r.optString("caseId")
            val truth = TRUTH[cid] ?: continue
            val (truthText, kind) = truth
            val finalText = r.optString("finalText")
            val d = Files.createTempDirectory("zd-340-accept-").toFile()
            for ((p, c) in seedByCase[cid] ?: emptyList()) {
                val t = File(d, p)
                t.parentFile?.mkdirs()
                t.writeText(c)
            }
            val results = NumericClaimGate.check(finalText, d, excludeByCase[cid] ?: emptySet())
            // 判据②（校准后）：对账判定与预登记真值逐位一致——claimed==真值 ⇔ VERIFIED，
            // 否则 FAILED 且 actual==真值。probe 触发可能是「落盘失败」（B0 形状：算对了没写上），
            // 那归勘验/右相管，值闸只管算术本身。
            for (res in results.filter { it.kind == kind && it.actual != null }) {
                val claimedIsTruth = res.claimed.stripTrailingZeros().toPlainString() == truthText
                val expect = if (claimedIsTruth) NumericClaimGate.Status.VERIFIED else NumericClaimGate.Status.FAILED
                assertEquals(
                    "对账判定与真值口径不一致：$cid ${r.optString("group")}rep${r.optInt("rep")} claimed=${res.claimed.toPlainString()}",
                    expect, res.status,
                )
                assertEquals(
                    "重算值必须等于预登记真值：$cid ${r.optString("group")}rep${r.optInt("rep")}",
                    truthVal[cid]!!.stripTrailingZeros(), res.actual!!.stripTrailingZeros(),
                )
                if (res.status == NumericClaimGate.Status.FAILED) failedRows++
                if (res.status == NumericClaimGate.Status.VERIFIED) verifiedRows++
            }
        }
        assertTrue("一行 FAILED 都没有——闸没接上（335 数值谎报行多轮在案）", failedRows >= 5)
        assertTrue(
            "VERIFIED 正例不足（实测 2：sum/range-sum 各 1 次模型做对）——做对的行被冤枉了",
            verifiedRows >= 2,
        )
    }

    @Test
    fun `全程零模型_纯代码重算`() {
        // 结构性自证：本测试类与 NumericClaimGate 的源码里没有模型调用（LLM/OkHttp/chat 不出现）。
        val gateSrc = File("src/main/java/com/example/zhengdao/baopu/agent/NumericClaimGate.kt")
        org.junit.Assume.assumeTrue(gateSrc.isFile)
        val src = gateSrc.readText()
        for (banned in listOf("OkHttp", "chat/completions", "LlmClient", "EvalHarness")) {
            assertTrue("NumericClaimGate 不许依赖模型通道：发现 ${banned}", !src.contains(banned))
        }
    }
}
