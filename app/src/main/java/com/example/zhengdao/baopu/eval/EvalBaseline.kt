// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十批 ③ · `eval/baseline.json` 的读取口径。
//
// 这份文件原本只有 A 段水位（通过率 / 平均步数 / 哪几条过了）。90 批按派工单 §1 给 B/C 两层
// **加了条目**，于是多出一个必须当场处理干净的坑：**「没实测」和「全过」在 JSON 里可以长得
// 一模一样**——都是"没有失败记录"。谁把前者读成后者，门禁就从「拦退化」变成「替没跑过的东西
// 背书」，那是这仓最反感的一种假话（"看着成功其实没有"）。
//
// 所以这里把三件事钉成代码，而不是写在注释里：
// 1. 一条 B/C 条目要么带读数，要么明写没跑（`measured=false` ＋ 非空 `status`），**中间态不存在**；
// 2. `measured=false` 的条目**拿不出读数**（[Layer.readingOrNull] 返回 null）——
//    消费方只能按「没有证据 ⇒ DEFER」办，不许补 0、更不许补 1；
// 3. 想伪造「UNRUN 却写着 passing」这类条目蒙过去 ⇒ [parse] 当场抛（宁可起不来，同 [EvalCases.parse]）。
//
// **A 段一字节没动**是这一批的硬要求：旧水位是别人花真金白银跑出来的，改它只能由人删文件重跑
// （见 `eval/README.md`「基线不会自动刷高」）。这里只**追加** `personas` 段。
package com.example.zhengdao.baopu.eval

import org.json.JSONObject

/**
 * 基线文件的读法（**纯函数**，不碰文件——读盘由调用方负责，便于 JVM 里把口径锁死）。
 */
internal object EvalBaseline {

    /** 「这一层没实测」的唯一合法标记值（写进条目的 `status`）。 */
    const val UNRUN = "UNRUN_NO_KEY"

    /** B/C 条目挂在哪一个键下（A 段水位仍在顶层）。 */
    const val PERSONAS = "personas"

    /**
     * 一层（B 或 C）的账。
     *
     * [cases] 是**这一层该跑的用例名册**（真源仍是 `eval/cases.json`，基线只记名不记判据，
     * 免得两处各存一份、哪天对不上）；[passing] 只有在 [measured] 为真时才允许非空。
     */
    internal data class Layer(
        val persona: String,
        val measured: Boolean,
        val status: String,
        val passRate: Double?,
        val avgSteps: Double?,
        val cases: List<String>,
        val passing: List<String>,
        val note: String,
    ) {
        /**
         * 能拿来判退化的通过率；**没实测就是 null**。
         *
         * 这是整份文件的要害：null 的分支必须存在，且调用方拿到 null 只能 DEFER。
         * 「查不到读数就按 1.0 算」是这个门禁最容易长出来的假绿，所以它在这里无路可走。
         */
        fun readingOrNull(): Double? = passRate.takeIf { measured }

        /** 给人看的一行：要么读数要么原因，不留空白。 */
        fun label(): String = if (measured) {
            "$persona 实测 ${passing.size}/${cases.size}（passRate $passRate，avgSteps $avgSteps）"
        } else {
            "$persona 未实测（$status）：${cases.size} 条待跑，不许当通过"
        }
    }

    /** 整份基线：A 段水位 ＋ 各层条目。 */
    internal data class Doc(
        /** A 段（存量水位口径）的通过率——**只认顶层字段**，层里的读数不会串进来。 */
        val passRate: Double,
        val avgSteps: Double,
        val cases: Int,
        val passing: Set<String>,
        val model: String?,
        val determinismLabel: String?,
        val layers: Map<String, Layer>,
    ) {
        /**
         * 某一层的条目；**null = 基线里根本没这一层**（老基线就是这种）。
         *
         * 注意它与「这层全挂」不是一回事：上层消费方两种都得按「没有可比的数」办。
         */
        fun layer(id: String): Layer? = layers[id]

        /** 没实测的层名单（报告与日志要用它说清"哪些数还没跑"）。 */
        fun unrunLayers(): List<String> = layers.values.filter { !it.measured }.map { it.persona }
    }

    /**
     * 解析整份基线。坏 JSON / 坏条目**直接抛**（`IllegalArgumentException` 之类由 JSON 与 require 给出）。
     *
     * 顶层 `passRate`、`avgSteps` 沿用旧读法：缺一个就抛——老基线文件里它们本来就在。
     */
    fun parse(text: String): Doc {
        val o = JSONObject(text)
        val passingArr = o.optJSONArray("passing")
        val passing = (0 until (passingArr?.length() ?: 0)).map { passingArr!!.getString(it) }.toSet()
        val layers = LinkedHashMap<String, Layer>()
        o.optJSONObject(PERSONAS)?.let { personas ->
            val byLayer = personas.optJSONObject("layers") ?: JSONObject()
            for (id in byLayer.keys()) {
                require(id in EvalCases.PERSONAS) {
                    "$PERSONAS 里有不认识的人设层：$id（合法值：${EvalCases.PERSONAS.joinToString("/")}）"
                }
                val entry = byLayer.getJSONObject(id)
                val measured = entry.optBoolean("measured", false)
                val rate = if (entry.isNull("passRate")) null else entry.getDouble("passRate")
                val steps = if (entry.isNull("avgSteps")) null else entry.getDouble("avgSteps")
                val listed = entry.optJSONArray("cases")
                val caseIds = (0 until (listed?.length() ?: 0)).map { listed!!.getString(it) }
                val passedArr = entry.optJSONArray("passing")
                val passed = (0 until (passedArr?.length() ?: 0)).map { passedArr!!.getString(it) }
                val status = entry.optString("status")
                // 名册空着＝这条账不存在。实测与未实测都不许有这种条目（未实测的更要写清待跑谁）
                require(caseIds.isNotEmpty()) { "$id 没记任何用例：名册空着＝这条账不存在，读它的人只会以为没这层" }
                if (!measured) {
                    require(passed.isEmpty()) {
                        "$id 写着未实测（measured=false）却填了 passing ${passed.size} 条——这是伪造基线"
                    }
                    require(rate == null) { "$id 写着未实测却填了 passRate $rate——这是伪造基线" }
                    require(status.isNotBlank()) { "$id 没实测必须写明为什么（status 不许留空）" }
                } else {
                    requireNotNull(rate) { "$id 说实测了却没有 passRate（实测条目必须带 0–1 的读数）" }
                    require(rate in 0.0..1.0) { "$id 的 passRate 越界：$rate" }
                }
                layers[id] = Layer(
                    persona = id,
                    measured = measured,
                    status = if (measured) status.ifBlank { "MEASURED" } else status,
                    passRate = rate,
                    avgSteps = steps,
                    cases = caseIds,
                    passing = passed,
                    note = entry.optString("note"),
                )
            }
        }
        return Doc(
            passRate = o.getDouble("passRate"),
            avgSteps = o.getDouble("avgSteps"),
            cases = o.optInt("cases", passing.size),
            passing = passing,
            model = o.optString("model").takeIf { it.isNotBlank() },
            determinismLabel = o.optJSONObject("determinism")?.optString("label")?.takeIf { it.isNotBlank() },
            layers = layers,
        )
    }

    /**
     * 读不出来一律 null——与调用方原有口径一致：**没有基线就按「没有可比对象」办**，
     * 绝不因为解析炸了就当成"基线是 0 分"把仓库判红（那是另一种假话）。
     */
    fun parseOrNull(text: String?): Doc? =
        text?.let { runCatching { parse(it) }.getOrNull() }

    /**
     * 重写基线时把**已有的人工条目**带过去（自动跑批只该动 A 段水位，不该顺手抹掉 B/C 的账）。
     *
     * 为什么需要它：`EvalGateTest` 在「没有基线」那一条路径上是**整份重写** baseline.json 的，
     * 谁哪天删了基线重跑，刚立起来的 B/C 名册就跟着没了——那等于用一次自动写回消掉了人工记录。
     * 新基线自己带了 `personas` 就以新的为准（那是人手工改过的），坏的前值不硬拼（保留新份）。
     */
    fun withPreservedPersonas(fresh: JSONObject, previousText: String?): JSONObject {
        if (fresh.has(PERSONAS)) return fresh
        val previous = previousText?.let { runCatching { JSONObject(it).optJSONObject(PERSONAS) }.getOrNull() }
        return if (previous == null) fresh else fresh.put(PERSONAS, previous)
    }
}
