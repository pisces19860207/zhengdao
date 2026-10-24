// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批 · 测试夹具定位（不复制夹具正文，只从仓库的 eval/evolution/ 读）。
package com.example.zhengdao.baopu.evolution.eval

import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object EvolutionTestSupport {

    fun repoRoot(): File {
        var dir: File? = File("").absoluteFile
        repeat(4) {
            val probe = dir ?: error("找不到仓库根（eval/evolution/cases.json）")
            if (File(probe, "eval/evolution/cases.json").isFile) return probe
            dir = probe.parentFile
        }
        error("找不到仓库根（eval/evolution/cases.json）：从 ${File("").absolutePath} 起向上 4 层都没找到")
    }

    fun fixtureDir(): File = File(repoRoot(), "eval/evolution")

    fun casesFile(): File = File(fixtureDir(), "cases.json")

    fun policyFile(): File = File(fixtureDir(), "fake-policy.json")

    fun candidate(name: String): EvolutionCandidate =
        EvolutionCandidate.load(File(fixtureDir(), "candidates/$name.json"))

    fun cases(): List<EvolutionCase> = EvolutionCases.load(casesFile())

    fun policy(): FakePolicyModel = FakePolicyModel.load(policyFile())

    fun evaluate(name: String, reportDir: File? = null): EvolutionEval.Outcome =
        EvolutionEval.evaluate(
            candidate = candidate(name),
            cases = cases(),
            casesSha256 = EvolutionCases.sha256(casesFile()),
            policy = policy(),
            reportDir = reportDir,
            ranAt = 1_760_000_000_000L,
        )

    fun codes(report: JSONObject): List<String> {
        val arr = report.optJSONArray("reasons") ?: return emptyList()
        return (0 until arr.length()).map { arr.getJSONObject(it).getString("code") }
    }

    fun caseIds(report: JSONObject, code: String): List<String> {
        val arr = report.optJSONArray("reasons") ?: return emptyList()
        for (i in 0 until arr.length()) {
            val r = arr.getJSONObject(i)
            if (r.getString("code") != code) continue
            val ids = r.getJSONArray("caseIds")
            return (0 until ids.length()).map { ids.getString(it) }
        }
        return emptyList()
    }

    fun caseFlag(report: JSONObject, id: String, key: String): Boolean {
        val arr = report.getJSONArray("cases")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) return o.getBoolean(key)
        }
        error("报告里没有场景 $id：${JSONArray((0 until arr.length()).map { arr.getJSONObject(it).getString("id") })}")
    }

    fun caseStep(report: JSONObject, id: String, key: String): Int {
        val arr = report.getJSONArray("cases")
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.getString("id") == id) return o.getInt(key)
        }
        error("报告里没有场景 $id")
    }
}
