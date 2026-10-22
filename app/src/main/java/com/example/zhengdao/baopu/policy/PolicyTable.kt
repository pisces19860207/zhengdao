// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百二十二批 · 政策表 v1：**唯一读表入口**。
//
// 三条纪律：
// 1. **严格解析**：未知键、版本不认、类型不对、越界、缺项 ⇒ 整张表拒绝；
//    不做「认得的就用、认不得的跳过」——那是 DSL 的玩法，本批不是政策引擎。
// 2. **整表原子**：任何一条不合格就一个字段都不采用，由 [PolicyRuntime] 按默认/上一份执行。
// 3. **纯函数**：本文件不碰 Android、不读盘、不写盘；读文件由 [PolicyAssets] 注入。
//    这样 JVM 单测能把每一条边界直接钉死。
package com.example.zhengdao.baopu.policy

import com.example.zhengdao.baopu.EvolutionRisk
import com.example.zhengdao.baopu.EvolutionRiskCategory
import com.example.zhengdao.baopu.agent.ToolGroup
import org.json.JSONArray
import org.json.JSONObject

/**
 * `policy_v1.json` 的读法。
 *
 * 文件形状（字段说明与允许范围见本批交付报告）：
 * ```json
 * {
 *   "schema": "zhengdao-policy",
 *   "version": 1,
 *   "description": "…",
 *   "toolGroups": { "defaultVisible": ["core", "edit", "compat"] },
 *   "approval": {
 *     "groupReaskThreshold": 3,
 *     "riskLevels": {
 *       "PERSONA_CORE": "HIGH", "TOOL_PERMISSION": "HIGH", "ROOTFS_SYSTEM": "HIGH",
 *       "SKILL_BODY_STRUCTURE": "MEDIUM", "CROSS_SKILL_IMPACT": "MEDIUM",
 *       "PROMPT_TWEAK": "LOW", "SKILL_BODY_MINOR": "LOW"
 *     }
 *   },
 *   "eval": { "floor": 0.8, "minEffectSamples": 3 }
 * }
 * ```
 */
internal object PolicyTable {

    /** 顶层 `schema` 的合法值。 */
    const val SCHEMA = Policy.SCHEMA

    /** 本读表器认识的唯一版本号。 */
    const val VERSION = Policy.VERSION

    /** 读表结果（三态；不存在「读坏了但先用着」的第四态）。 */
    sealed interface Load {
        /** 来源（资产路径之类；审计用；不含密钥）。 */
        val source: String

        /** 表完整通过校验。 */
        data class Loaded(val policy: Policy, override val source: String) : Load

        /** 读取返回 null（文件不存在）；读取本身抛异常归 [Invalid]，不冒充“不存在”。 */
        data class Missing(override val source: String) : Load

        /** 表在，但不合格；[reason] 是给人看的原因。 */
        data class Invalid(val reason: String, override val source: String) : Load
    }

    /**
     * 读一份表：读不到 ⇒ [Load.Missing]；读到了但不合格 ⇒ [Load.Invalid]。
     *
     * **不在这里决定回退到哪份政策**——那是 [PolicyRuntime.install] 的事。
     * 本函数只回答「这份表能不能用、为什么不能用」。
     *
     * @param source 审计里的来源名（不含密钥；资产路径即可）
     * @param read 实际读取（生产传 assets 读取；测试传字符串）
     */
    fun load(source: String, read: () -> String?): Load {
        val text = try {
            read()
        } catch (t: Throwable) {
            return Load.Invalid("读取失败：${t.javaClass.simpleName}: ${t.message.orEmpty()}", source)
        } ?: return Load.Missing(source)
        return try {
            Load.Loaded(parse(text), source)
        } catch (t: Throwable) {
            val reason = t.message?.takeIf { it.isNotBlank() } ?: t.javaClass.simpleName
            Load.Invalid(reason, source)
        }
    }

    /**
     * 严格解析。任何不合格都抛 [IllegalArgumentException]（`require` 自带）。
     *
     * 顺序：先验身份（schema/version），再验各段；未知键在每一层都拒绝。
     */
    fun parse(text: String): Policy {
        val root = try {
            JSONObject(text)
        } catch (t: Throwable) {
            throw IllegalArgumentException("不是合法 JSON：${t.message ?: t.javaClass.simpleName}")
        }

        require(strictString(root, "schema", "顶层") == SCHEMA) {
            "schema 必须是 $SCHEMA（不是本项目的政策表）"
        }
        require(strictInt(root, "version", "顶层") == VERSION) {
            "版本不认识：本读表器只认 $VERSION"
        }
        rejectUnknown(root, setOf("schema", "version", "description", "toolGroups", "approval", "eval"), "顶层")
        // description 只给人看，不参与判据；写了就只能是字符串
        root.opt("description")?.let { require(it is String) { "顶层 description 必须是字符串" } }

        val groupsObj = strictObject(root, "toolGroups", "顶层")
        rejectUnknown(groupsObj, setOf("defaultVisible"), "toolGroups")
        val visibleArr = strictArray(groupsObj, "defaultVisible", "toolGroups")
        require(visibleArr.length() > 0) { "toolGroups.defaultVisible 不许为空" }
        val keys = (0 until visibleArr.length()).map { i ->
            val raw = visibleArr.get(i)
            require(raw is String) { "toolGroups.defaultVisible[$i] 必须是字符串" }
            val key = raw.trim()
            require(key.isNotEmpty()) { "toolGroups.defaultVisible[$i] 不许为空串" }
            ToolGroup.fromKey(key) ?: throw IllegalArgumentException(
                "toolGroups.defaultVisible[$i] 不是认得的组：$raw",
            )
        }
        require(keys.toSet().size == keys.size) { "toolGroups.defaultVisible 有重复组" }
        val defaultVisible = keys.toSet()

        val approvalObj = strictObject(root, "approval", "顶层")
        rejectUnknown(approvalObj, setOf("groupReaskThreshold", "riskLevels"), "approval")
        val threshold = strictInt(approvalObj, "groupReaskThreshold", "approval")
        require(threshold in 1..10) { "approval.groupReaskThreshold 越界：$threshold（只许 1–10）" }

        val risksObj = strictObject(approvalObj, "riskLevels", "approval")
        val riskKeys = EvolutionRiskCategory.entries.map { it.name }.toSet()
        rejectUnknown(risksObj, riskKeys, "approval.riskLevels")
        val riskLevels = LinkedHashMap<EvolutionRiskCategory, EvolutionRisk>()
        EvolutionRiskCategory.entries.forEach { category ->
            val raw = strictString(risksObj, category.name, "approval.riskLevels")
            val level = EvolutionRisk.entries.firstOrNull { it.name == raw }
                ?: throw IllegalArgumentException(
                    "approval.riskLevels.${category.name} 不是合法等级：$raw（只许 HIGH/MEDIUM/LOW）",
                )
            riskLevels[category] = level
        }

        val evalObj = strictObject(root, "eval", "顶层")
        rejectUnknown(evalObj, setOf("floor", "minEffectSamples"), "eval")
        val floor = strictDouble(evalObj, "floor", "eval")
        val minSamples = strictInt(evalObj, "minEffectSamples", "eval")

        // 最后交给 typed 模型再验一遍（范围/底线/七类齐全）；构造器抛的也是 IllegalArgumentException
        return Policy(
            version = VERSION,
            toolGroups = ToolGroupPolicy(defaultVisible = defaultVisible),
            approval = ApprovalPolicy(groupReaskThreshold = threshold, riskLevels = riskLevels),
            eval = EvalPolicy(floor = floor, minEffectSamples = minSamples),
        )
    }

    // ── 严格取值（org.json 的 optInt/optBoolean 会把 "3" / 1 悄悄收下，这里不许） ──

    private fun raw(o: JSONObject, key: String, where: String): Any {
        require(o.has(key) && !o.isNull(key)) { "$where.$key 缺失" }
        return o.get(key)
    }

    private fun strictString(o: JSONObject, key: String, where: String): String {
        val v = raw(o, key, where)
        require(v is String) { "$where.$key 必须是字符串（读到 ${typeName(v)}）" }
        return v
    }

    private fun strictInt(o: JSONObject, key: String, where: String): Int {
        val v = raw(o, key, where)
        val n = v as? Number ?: throw IllegalArgumentException("$where.$key 必须是整数（读到 ${typeName(v)}）")
        val d = n.toDouble()
        require(d.isFinite() && d % 1.0 == 0.0) { "$where.$key 必须是整数（读到 $v）" }
        require(d >= Int.MIN_VALUE.toDouble() && d <= Int.MAX_VALUE.toDouble()) { "$where.$key 超出整数范围：$v" }
        return d.toInt()
    }

    private fun strictDouble(o: JSONObject, key: String, where: String): Double {
        val v = raw(o, key, where)
        val n = v as? Number ?: throw IllegalArgumentException("$where.$key 必须是数字（读到 ${typeName(v)}）")
        val d = n.toDouble()
        require(d.isFinite()) { "$where.$key 必须是有限数字（读到 $v）" }
        return d
    }

    private fun strictObject(o: JSONObject, key: String, where: String): JSONObject {
        val v = raw(o, key, where)
        require(v is JSONObject) { "$where.$key 必须是对象（读到 ${typeName(v)}）" }
        return v
    }

    private fun strictArray(o: JSONObject, key: String, where: String): JSONArray {
        val v = raw(o, key, where)
        require(v is JSONArray) { "$where.$key 必须是数组（读到 ${typeName(v)}）" }
        return v
    }

    private fun rejectUnknown(o: JSONObject, allowed: Set<String>, where: String) {
        val extra = o.keys().asSequence().filter { it !in allowed }.toList()
        require(extra.isEmpty()) {
            "$where 有不认识的键：${extra.joinToString("、")}" +
                "（政策表不是 DSL：新参数必须先改代码并升版本）"
        }
    }

    private fun typeName(v: Any): String = when (v) {
        is String -> "字符串"
        is Number -> "数字"
        is Boolean -> "布尔"
        is JSONObject -> "对象"
        is JSONArray -> "数组"
        else -> v.javaClass.simpleName
    }
}
