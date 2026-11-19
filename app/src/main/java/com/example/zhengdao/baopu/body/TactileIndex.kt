// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百五十批 · 触觉索引（第二索引）：触点×动作×手感四维 → 部位。
// 真源＝docs/facts/deictic-index.json 的 tactile 段（与 .md 双真相同提交）。
// 三件齐（触点+动作+手感四维）缺一拒判；手感只写摸起来怎样，不写诊断(728 红线)。
// 强特征规则优先，兜底规则(如「只说软」)仅无强特征命中时生效。
// 纯函数、零模型、零联网、零随机、零时钟；缺字段/空表/非法档 ⇒ 抛（fail-closed）。
package com.example.zhengdao.baopu.body

import java.io.File
import org.json.JSONObject

/** 一条触觉键：三件齐才合法（contact∈触点闭集、action∈动作闭集、四维档位齐）。 */
data class TactileKey(
    val contact: String,
    val action: String,
    val feel: Map<String, String>,
)

/** 触觉规则：触点/动作闭集＋手感四维约束＋可选强特征 → 部位。 */
data class TactileRule(
    val id: String,
    val contacts: List<String>,
    val actions: List<String>,
    val feel: Map<String, List<String>>,
    val requiresFeature: Boolean,
    val features: List<String>,
    val zones: List<String>,
    val distinguish: String,
    val source: String,
    val asOf: String,
)

/** 触觉命中结果。zones 按规则序去重；ambiguous=部位≥2；distinguish 给区分办法。 */
data class TactileResult(
    val key: TactileKey,
    val zones: List<String>,
    val matchedRules: List<String>,
    val ambiguous: Boolean,
    val distinguish: String?,
)

/**
 * 触觉索引查询器。构造时吃真源的 tactile 段；probe 为纯函数。
 */
class TactileIndex(
    val contacts: List<String>,
    val actions: List<String>,
    val feelDims: List<String>,
    val feelGrades: Map<String, List<String>>,
    val rules: List<TactileRule>,
) {
    init {
        require(contacts.isNotEmpty()) { "触点闭集为空，拒绝装配" }
        require(actions.isNotEmpty()) { "动作闭集为空，拒绝装配" }
        require(feelDims.isNotEmpty() && feelDims.all { feelGrades[it]?.isNotEmpty() == true }) {
            "手感档位表不齐，拒绝装配"
        }
        require(rules.isNotEmpty()) { "触觉规则为空，拒绝装配" }
        for (r in rules) {
            require(r.contacts.all { it in contacts }) { "规则 ${r.id} 触点悬空" }
            require(r.actions.all { it in actions }) { "规则 ${r.id} 动作悬空" }
            for ((dim, grades) in r.feel) {
                require(dim in feelDims) { "规则 ${r.id} 手感维未定义：$dim" }
                require(grades.all { it in (feelGrades[dim] ?: emptyList()) }) { "规则 ${r.id} 档位非法：$dim" }
            }
        }
    }

    /** 三件齐校验；缺一或非法档 ⇒ 抛（拒判）。 */
    fun validate(key: TactileKey) {
        require(key.contact.isNotBlank() && key.contact in contacts) { "触点缺失或不在闭集：'${key.contact}'" }
        require(key.action.isNotBlank() && key.action in actions) { "动作缺失或不在闭集：'${key.action}'" }
        require(key.feel.keys == feelDims.toSet()) {
            "手感四维必须齐（触点+动作+手感缺一拒判）：缺 ${(feelDims.toSet() - key.feel.keys).joinToString("、")}"
        }
        for (dim in feelDims) {
            val v = key.feel[dim]
            require(v != null && v in (feelGrades[dim] ?: emptyList())) { "手感维 $dim 档位非法：'$v'" }
        }
    }

    /**
     * 触觉判别。feature 为强特征短语（如「边缘有台阶」「薄皮」），可为空。
     * 强特征规则优先；无强特征命中时兜底规则才生效。
     */
    fun probe(key: TactileKey, feature: String = ""): TactileResult {
        validate(key)
        val hits = rules.filter { matches(it, key, feature) }
        val strong = hits.filter { it.requiresFeature }
        val effective = if (strong.isNotEmpty()) strong else hits
        val zones = linkedSetOf<String>()
        effective.forEach { zones.addAll(it.zones) }
        val distinguish = if (zones.size >= 2) {
            effective.firstOrNull { it.zones.size >= 2 }?.distinguish
                ?: effective.joinToString("；") { it.distinguish }.ifBlank { null }
        } else null
        return TactileResult(key, zones.toList(), effective.map { it.id }, zones.size >= 2, distinguish)
    }

    private fun matches(rule: TactileRule, key: TactileKey, feature: String): Boolean {
        if (key.contact !in rule.contacts || key.action !in rule.actions) return false
        for ((dim, allowed) in rule.feel) {
            if (key.feel[dim] !in allowed) return false
        }
        if (rule.requiresFeature && rule.features.none { it in feature }) return false
        return true
    }

    companion object {
        fun load(repoRoot: File): TactileIndex =
            parse(JSONObject(File(repoRoot, DeicticIndex.REL_PATH).readText()).getJSONObject("tactile"))

        fun parse(o: JSONObject): TactileIndex {
            require(DeicticIndex.req(o, "source").isNotBlank()) { "tactile 缺 source" }
            val dims = DeicticIndex.strList(o, "feel_dims")
            val gradeObj = o.getJSONObject("feel_grades")
            val grades = dims.associateWith { DeicticIndex.strList(gradeObj, it) }
            val rules = o.getJSONArray("rules")
            return TactileIndex(
                contacts = DeicticIndex.strList(o, "contacts"),
                actions = DeicticIndex.strList(o, "actions"),
                feelDims = dims,
                feelGrades = grades,
                rules = (0 until rules.length()).map { parseRule(rules.getJSONObject(it)) },
            )
        }

        private fun parseRule(o: JSONObject): TactileRule {
            val feelObj = o.getJSONObject("feel")
            val feel = feelObj.keys().asSequence().associateWith { DeicticIndex.strList(feelObj, it) }
            return TactileRule(
                id = DeicticIndex.req(o, "id"),
                contacts = DeicticIndex.strList(o, "contacts"),
                actions = DeicticIndex.strList(o, "actions"),
                feel = feel,
                requiresFeature = o.optBoolean("requiresFeature"),
                features = DeicticIndex.strList(o, "features"),
                zones = DeicticIndex.strList(o, "zones"),
                distinguish = DeicticIndex.req(o, "distinguish"),
                source = DeicticIndex.req(o, "source"),
                asOf = DeicticIndex.req(o, "asOf"),
            )
        }
    }
}
