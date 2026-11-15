// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 B（阶段二）· 技能草案（231 切片 B 的 JSON 落形）。
//
// 字段名照 231 原文：goal／params／skeleton／variableSteps／evidence——这是「意图归纳」
// 的产物形状。要点（231 逐句）：
//   · **由模型归纳，但必须落成结构化 JSON 并被校验**——[SkillDraftRules.validate] 就是
//     那道门：字段缺失／**定位全空 ⇒ 拒收**（判据①）、步缺证据 ⇒ 拒收（判据②，
//     「没有证据也算归纳成功」在结构上不存在）；
//   · 草案进**草稿区**（不是正式技能）——本文件是纯数据，写口在 [SkillDraftStore]，
//     转正走人批（ObserveCenter.confirmDraft → TeachReplayStore.saveConfirmed）；
//   · **必须列出证据**：每步 evidence 指回观察账的会话文件与序号（可点开回到对应行）；
//   · 值**不出页**（阶段一红线延续）：skeleton 步没有 value 字段——变化字段以 `param`
//     名出现（判据④「断言字面量已拔掉」在数据结构上成立），真值在人批确认时填。
package com.example.zhengdao.baopu.learn

import org.json.JSONArray
import org.json.JSONObject

/** 草案 schema（只认这一版；格式变更必须升号）。 */
internal const val DRAFT_SCHEMA: String = "zl-draft/1"

/** 观察面身份（阶段一的 ObserveFaces 原样复用——定位判据看它，十级定位同源）。 */
internal typealias DraftLocator = ObserveFaces

/** 草案一步（skeleton 的一行）。 */
internal data class DraftStep(
    val index: Int,
    val intent: String,
    val kind: String, // click/type/select/scroll（观察事件 wire）
    val url: String,
    val locator: DraftLocator,
    val param: String?, // 非空＝该步是参数步（字面量已拔掉，判据④）
    val optional: Boolean, // 偶发步（C 归并降级）
    val candidates: List<DraftLocator>, // 定位冲突两套并存（判据⑤；空＝无冲突）
    val expect: List<String>, // 校验点 wire（urlChanged/rowCountChange——观察证据能推的两种）
    val evidence: List<String>, // "观察/会话-<id>.jsonl#seq<n>"——点开回到对应行
) {
    /** 定位是否全空（判据①的判据本体：主定位全字段空且没有候选）。 */
    fun locatorBlank(): Boolean = candidates.isEmpty() && listOf(
        locator.tag, locator.role, locator.name, locator.aria, locator.testid,
        locator.id, locator.inputName, locator.placeholder, locator.inputType,
        locator.formLabel, locator.xp,
    ).all { it.isBlank() }

    fun toJson(): JSONObject = JSONObject()
        .put("i", index)
        .put("intent", intent)
        .put("a", kind)
        .put("url", url)
        .put("el", locator.toJson())
        .also { if (param != null) it.put("param", param) }
        .put("opt", optional)
        .put("cands", JSONArray(candidates.map { it.toJson() }))
        .put("expect", JSONArray(expect))
        .put("ev", JSONArray(evidence))

    companion object {
        fun fromJson(o: JSONObject): DraftStep? = runCatching {
            DraftStep(
                index = o.optInt("i", 0),
                intent = o.optString("intent"),
                kind = o.optString("a"),
                url = o.optString("url"),
                locator = ObserveFaces.fromJson(o.getJSONObject("el")),
                param = o.optString("param").takeIf { it.isNotEmpty() },
                optional = o.optBoolean("opt", false),
                candidates = o.optJSONArray("cands")?.let { a ->
                    (0 until a.length()).mapNotNull { k ->
                        runCatching { ObserveFaces.fromJson(a.getJSONObject(k)) }.getOrNull()
                    }
                } ?: emptyList(),
                expect = o.optJSONArray("expect")?.let { a -> (0 until a.length()).map { k -> a.optString(k) } } ?: emptyList(),
                evidence = o.optJSONArray("ev")?.let { a -> (0 until a.length()).map { k -> a.optString(k) } } ?: emptyList(),
            )
        }.getOrNull()
    }
}

/** 一个参数（231：{name, from}；example 不落盘——真值在人批确认时填，字面量拔掉）。 */
internal data class DraftParam(val name: String, val from: String) {
    fun toJson(): JSONObject = JSONObject().put("name", name).put("from", from)

    companion object {
        fun fromJson(o: JSONObject): DraftParam? = runCatching {
            val name = o.optString("name")
            if (name.isBlank()) return null
            DraftParam(name, o.optString("from"))
        }.getOrNull()
    }
}

/** 一条技能草案（进草稿区，不转正；转正＝人批后经 DraftConverter 落成 zl-teach/2）。 */
internal data class SkillDraft(
    val id: String,
    val goal: String,
    val targetSite: String,
    val params: List<DraftParam>,
    val skeleton: List<DraftStep>,
    val variableSteps: List<Int>,
    val mergedFrom: Int, // ≥2 ⇒ 标「N 次观察归并」（判据③）；1＝单次观察直出
    val sourceSessions: List<String>, // 来源观察会话 id（evidence 可回溯）
) {
    /** 有定位冲突步（两套候选并存待裁——确认转正前必须由人裁定，裁前不许落成技能）。 */
    fun hasConflict(): Boolean = skeleton.any { it.candidates.isNotEmpty() }

    fun toJson(): JSONObject = JSONObject()
        .put("schema", DRAFT_SCHEMA)
        .put("id", id)
        .put("goal", goal)
        .put("targetSite", targetSite)
        .put("params", JSONArray(params.map { it.toJson() }))
        .put("skeleton", JSONArray(skeleton.map { it.toJson() }))
        .put("variableSteps", JSONArray(variableSteps))
        .put("mergedFrom", mergedFrom)
        .put("sessions", JSONArray(sourceSessions))

    companion object {
        fun fromJson(o: JSONObject): SkillDraft? = runCatching {
            if (o.optString("schema") != DRAFT_SCHEMA) return null
            SkillDraft(
                id = o.optString("id"),
                goal = o.optString("goal"),
                targetSite = o.optString("targetSite"),
                params = o.optJSONArray("params")?.let { a ->
                    (0 until a.length()).mapNotNull { k -> DraftParam.fromJson(a.getJSONObject(k)) }
                } ?: emptyList(),
                skeleton = o.optJSONArray("skeleton")?.let { a ->
                    (0 until a.length()).mapNotNull { k -> DraftStep.fromJson(a.getJSONObject(k)) }
                } ?: emptyList(),
                variableSteps = o.optJSONArray("variableSteps")?.let { a -> (0 until a.length()).map { k -> a.optInt(k) } } ?: emptyList(),
                mergedFrom = o.optInt("mergedFrom", 1),
                sourceSessions = o.optJSONArray("sessions")?.let { a -> (0 until a.length()).map { k -> a.optString(k) } } ?: emptyList(),
            )
        }.getOrNull()
    }
}

/** 校验产物（闭集两态；问题一次列全）。 */
internal sealed interface DraftCheck {
    object Ok : DraftCheck
    data class Invalid(val problems: List<String>) : DraftCheck
}

/** 草案的结构校验门（模型归纳也好、程序归纳也好，都要过这一道——「不靠自觉」）。 */
internal object SkillDraftRules {

    /** 定位全空的定稿说法（判据①断言到这四个字上，改字＝改判据）。 */
    const val BLANK_LOCATOR = "定位全空"

    /** 无证据的定稿说法（判据②断言到这一句上）。 */
    const val NO_EVIDENCE = "没有证据"

    fun validate(draft: SkillDraft): DraftCheck {
        val problems = mutableListOf<String>()
        if (draft.goal.isBlank()) problems += "goal 不能为空（归纳必须有意图）"
        if (draft.skeleton.isEmpty()) problems += "skeleton 不能为空（轨迹里没有可成步的操作）"
        draft.skeleton.filter { it.locatorBlank() }.forEach { step ->
            problems += "第 ${step.index} 步$BLANK_LOCATOR（无候选可裁）——拒收"
        }
        draft.skeleton.filter { it.evidence.isEmpty() }.forEach { step ->
            problems += "第 ${step.index} 步$NO_EVIDENCE（指不回观察账）——拒收"
        }
        draft.skeleton.filter { it.param != null && it.candidates.isEmpty() }.forEach { step ->
            val declared = draft.params.any { p -> p.name == step.param }
            if (!declared) problems += "第 ${step.index} 步声明参数「${step.param}」但 params 里没有它"
        }
        draft.skeleton.filter { it.candidates.size == 1 }.forEach { step ->
            problems += "第 ${step.index} 步候选只有 1 条（冲突必须两套并存，单候选是坏形）"
        }
        return if (problems.isEmpty()) DraftCheck.Ok else DraftCheck.Invalid(problems)
    }
}
