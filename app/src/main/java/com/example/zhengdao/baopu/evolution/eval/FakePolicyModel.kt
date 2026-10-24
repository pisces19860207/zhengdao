// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批 · 假模型＝确定性决策表（设计 §3.1）。
//
// 三条纪律：
//   1. **没有随机数**：同一上下文永远同一动作（temperature=0 / seed=0 只是写进报告的口径）；
//   2. **只读上下文**：系统提示、用户话、工具名册、上一轮工具结果；不读候选文件、不读盘；
//   3. **脚本外不许装成功**：没有匹配规则 / 规则步数用完 ⇒ 明确失败（脚本耗尽），不静默当通过。
package com.example.zhengdao.baopu.evolution.eval

import java.io.File
import org.json.JSONObject

/** 假模型每一步看得到的东西（与真模型同一信息面，只是没有采样）。 */
internal data class PolicyContext(
    val systemPrompt: String,
    val userPrompt: String,
    val tools: List<String>,
    val history: List<String>,
    val step: Int,
)

internal sealed interface PolicyDecision {
    /** 说完了（这一例的最终回复）。 */
    data class Finish(val text: String) : PolicyDecision

    /** 要调一个工具（一次一步；参数原样进管线）。 */
    data class Call(val tool: String, val args: JSONObject) : PolicyDecision

    /** 决策表里还想继续/没有规则 ⇒ 这一例失败（`script_exhausted`），不许当通过。 */
    data class Exhausted(val ruleId: String, val why: String) : PolicyDecision
}

/**
 * 决策表。
 *
 * 规则按文件顺序匹配，**第一条命中的规则**决定这一步；每条规则有自己的游标
 * （所以「先 read_file 再 final」是一条规则里的两步，不同规则互不吃步数）。
 */
internal class FakePolicyModel private constructor(
    val modelName: String,
    val temperature: Double,
    val seed: Int,
    val sourceSha256: String,
    private val rules: List<Rule>,
) {

    /** 一臂一例一份脚本（游标不跨例共享，保证两臂与顺序都无关）。 */
    fun newScript(): PolicyScript = PolicyScript(rules)

    internal data class Rule(
        val id: String,
        val conditions: Conditions,
        val steps: List<Step>,
    )

    internal data class Conditions(
        val promptContains: String? = null,
        val promptNotContains: String? = null,
        val systemContains: String? = null,
        val systemNotContains: String? = null,
        val historyContains: String? = null,
        val hasTool: String? = null,
        val notHasTool: String? = null,
    ) {
        fun matches(ctx: PolicyContext): Boolean {
            val system = ctx.systemPrompt
            val prompt = ctx.userPrompt
            val history = ctx.history.joinToString("\n")
            return listOf(
                promptContains?.let { prompt.contains(it) } ?: true,
                promptNotContains?.let { !prompt.contains(it) } ?: true,
                systemContains?.let { system.contains(it) } ?: true,
                systemNotContains?.let { !system.contains(it) } ?: true,
                historyContains?.let { history.contains(it) } ?: true,
                hasTool?.let { ctx.tools.contains(it) } ?: true,
                notHasTool?.let { !ctx.tools.contains(it) } ?: true,
            ).all { it }
        }
    }

    internal data class Step(
        val finalText: String? = null,
        val tool: String? = null,
        val args: JSONObject? = null,
    )

    internal class PolicyScript(private val rules: List<Rule>) {
        private val cursors = IntArray(rules.size)

        fun next(ctx: PolicyContext): PolicyDecision {
            for ((index, rule) in rules.withIndex()) {
                if (!rule.conditions.matches(ctx)) continue
                val cursor = cursors[index]
                if (cursor >= rule.steps.size) {
                    return PolicyDecision.Exhausted(rule.id, "规则「${rule.id}」的步数用完了（第 ${ctx.step} 步）")
                }
                cursors[index] = cursor + 1
                val step = rule.steps[cursor]
                step.finalText?.let { return PolicyDecision.Finish(it) }
                val tool = step.tool ?: return PolicyDecision.Exhausted(rule.id, "坏步骤：既没有 final 也没有 tool")
                return PolicyDecision.Call(tool, step.args ?: JSONObject())
            }
            return PolicyDecision.Exhausted("no-rule", "第 ${ctx.step} 步没有匹配的规则（脚本之外）")
        }
    }

    companion object {
        const val DEFAULT_MODEL = "fake-policy/1"

        fun parse(json: String): FakePolicyModel {
            val o = JSONObject(json)
            val rulesJson = o.getJSONArray("rules")
            val rules = ArrayList<Rule>(rulesJson.length())
            val seen = HashSet<String>()
            for (i in 0 until rulesJson.length()) {
                val r = rulesJson.getJSONObject(i)
                val id = r.getString("id")
                require(id.isNotBlank()) { "决策表第 $i 条规则没有 id" }
                require(seen.add(id)) { "决策表规则 id 重复：$id" }
                val whenJson = r.optJSONObject("when") ?: JSONObject()
                val conditions = Conditions(
                    promptContains = whenJson.optString("promptContains").takeIf { it.isNotBlank() },
                    promptNotContains = whenJson.optString("promptNotContains").takeIf { it.isNotBlank() },
                    systemContains = whenJson.optString("systemContains").takeIf { it.isNotBlank() },
                    systemNotContains = whenJson.optString("systemNotContains").takeIf { it.isNotBlank() },
                    historyContains = whenJson.optString("historyContains").takeIf { it.isNotBlank() },
                    hasTool = whenJson.optString("hasTool").takeIf { it.isNotBlank() },
                    notHasTool = whenJson.optString("notHasTool").takeIf { it.isNotBlank() },
                )
                val stepsJson = r.getJSONArray("steps")
                require(stepsJson.length() > 0) { "决策表规则「$id」没有步骤" }
                val steps = ArrayList<Step>(stepsJson.length())
                for (j in 0 until stepsJson.length()) {
                    val s = stepsJson.getJSONObject(j)
                    val hasFinal = s.has("final")
                    val hasTool = s.has("tool")
                    require(hasFinal != hasTool) {
                        "决策表规则「$id」第 $j 步必须二选一：final 或 tool"
                    }
                    steps.add(
                        if (hasFinal) {
                            Step(finalText = s.getString("final"))
                        } else {
                            Step(tool = s.getString("tool"), args = s.optJSONObject("args") ?: JSONObject())
                        },
                    )
                }
                rules.add(Rule(id, conditions, steps))
            }
            require(rules.isNotEmpty()) { "决策表是空的：一例也跑不了" }
            return FakePolicyModel(
                modelName = o.optString("model", DEFAULT_MODEL),
                temperature = o.optDouble("temperature", 0.0),
                seed = o.optInt("seed", 0),
                sourceSha256 = EvolutionHash.sha256(json),
                rules = rules,
            )
        }

        fun load(file: File): FakePolicyModel = parse(file.readText(Charsets.UTF_8))
    }
}
