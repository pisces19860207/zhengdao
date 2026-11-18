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

import com.example.zhengdao.bridge.PortIrreversible
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
    val expect: List<String>, // 校验点 wire（urlChanged／rowCountChange；697 起归纳只出 urlChanged）
    val evidence: List<String>, // "观察/会话-<id>.jsonl#seq<n>"——点开回到对应行

    /**
     * 697：该步是**导航步**——点下去页面事实变了（换了文档／换了 URL／节点数变了）。
     *
     * 为什么不把 `kind` 直接写成 `"navigate"`：`kind` 是**回放动作**的闭集
     * （[com.example.zhengdao.baopu.TeachAction]：click/type/select/scroll/waitFor），
     * 塞个 navigate 进去，`DraftConverter` 会照实拒转（「不可回放」）——那一来更糟。
     * 所以导航是**这一步的性质**，不是另一个动作：动作仍是 click（回放就是点那个链接，
     * 跳转由页面自己完成），`navigate` 位说明「点完页面换了」。
     */
    val navigate: Boolean = false,

    /**
     * 697 窄口：导航步，但观察里**没有 URL 变化证据**（同页锚点／SPA 没改地址）。
     *
     * 置真 ⇒ 转正门允许这步没有 expect，但**必须同时**：是 click 导航步、evidence 非空、
     * 有节点数变化（见 [nodeCount]／[nodeCountAfter]）；技能正文会标「该步未校验跳转」。
     * 默认 false——窄口不是默认值，得由归纳层凭证据一处一处开。
     */
    val expectlessNavigate: Boolean = false,

    /** 该步事件行记到的节点数（观察面的 `nc`；窄口的证据之一，0＝没记）。 */
    val nodeCount: Int = 0,

    /** 该步之后**第一份**页事实的节点数（同上；两次相等＝页面没变过，窄口不开）。 */
    val nodeCountAfter: Int = 0,
) {
    /** 定位是否全空（判据①的判据本体：主定位全字段空且没有候选）。 */
    fun locatorBlank(): Boolean = candidates.isEmpty() && listOf(
        locator.tag, locator.role, locator.name, locator.aria, locator.testid,
        locator.id, locator.inputName, locator.placeholder, locator.inputType,
        locator.formLabel, locator.xp,
    ).all { it.isBlank() }

    /** 窄口只对「点击导航步」开（type/scroll 没有「跳转」可言）。 */
    fun isNavigateStep(): Boolean = navigate && kind == ObserveKind.CLICK.wire

    /** 窄口的三条证据是否齐（697 判据④：缺一条都不许过）。 */
    fun expectlessEvidenceOk(): Boolean =
        evidence.isNotEmpty() && nodeCount > 0 && nodeCountAfter > 0 && nodeCount != nodeCountAfter

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
        // 697 三列只在有值时才写（旧草案照读：没有这三列就是「不是导航步／没窄口」）
        .also { if (navigate) it.put("nav", true) }
        .also { if (expectlessNavigate) it.put("expectless_navigate", true) }
        .also { if (nodeCount > 0) it.put("nc", nodeCount) }
        .also { if (nodeCountAfter > 0) it.put("ncAfter", nodeCountAfter) }

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
                navigate = o.optBoolean("nav", false),
                expectlessNavigate = o.optBoolean("expectless_navigate", false),
                nodeCount = o.optInt("nc", 0),
                nodeCountAfter = o.optInt("ncAfter", 0),
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

    /** 还没裁的冲突步（672 判据①：有它就是「有冲突未裁」；裁完但裁 none 的不算在这里）。 */
    fun unresolvedSteps(adjudications: List<DraftAdjudication>): List<DraftStep> =
        DraftAdjudications.pending(this, adjudications)

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

    /**
     * 含不可逆动作的定稿说法（654 判据④断言到这三个字上）。
     *
     * 为什么这道闸在**结构门**而不是在提交工具里：两条归纳路（脚本侧 DraftInducer、
     * 模型侧 LearnSubmit）都要过门，闸只装在门上一处；装在提交口会变成两份判据。
     */
    const val IRREVERSIBLE = "不可逆"

    /** 冲突未裁的定稿说法（672 判据①③断言到这五个字上，改字＝改判据）。 */
    const val CONFLICT_UNRESOLVED = "有冲突未裁"

    /** 裁 none 的去处的定稿说法（672 判据②断言到这一句上）。 */
    const val RETEACH = "重新教这一步"

    /** 697 窄口的定稿说法（判据②断言到「未校验跳转」四个字上，改字＝改判据）。 */
    const val EXPECTLESS_MARK = "未校验跳转"

    /** 窄口三条证据缺一条时的说法（判据④断言到这一句上）。 */
    const val EXPECTLESS_NO_EVIDENCE = "窄口只在有证据时开"

    /** 697 判据③「凭空造断言」的定稿说法。 */
    const val EXPECT_FABRICATED = "凭空造断言"

    /**
     * 断言闭集（697 判据③）：观察面能证的就这两种。
     *
     * 闭集外的一律拒，而不是**悄悄丢掉**：`DraftConverter` 原先用 `mapNotNull` 把认不得的
     * wire 滤掉——那等于「写了条判据、盘上却没有」，人以为有断言、回放时没有。宁可当场拒，
     * 也不许伪造或吞掉。
     */
    val EXPECT_CLOSED: Set<String> = linkedSetOf("urlChanged", "rowCountChange")

    /** 未裁一句话（转正门与转换器的唯一文案，别处不许再拼一遍）。 */
    fun unresolvedProblem(index: Int): String =
        "第 $index 步$CONFLICT_UNRESOLVED（两种走法待人裁）——裁完再转正"

    /** 裁 none 一句话（⇒ 回到重新教这一步，不是「随便挑一套」）。 */
    fun reteachProblem(index: Int): String =
        "第 $index 步两套定位都不对——回「$RETEACH」"

    /**
     * 转正门（672 判据①④）：**有候选 ⇒ 必须先由人裁定**。
     *
     * 判据本体＝「把裁定落到草案上」（[DraftAdjudications.apply]）＋「落完再过一次结构门」
     * ——两步合一处，前后调用同一个函数即可断言结论变化（裁定前 Invalid「有冲突未裁」，
     * 裁定后 Ok）。裁 none 也在这里拦（[reteachProblem]），转换层不再各判一遍。
     */
    fun checkForPromotion(draft: SkillDraft, adjudications: List<DraftAdjudication>): DraftCheck =
        when (val applied = DraftAdjudications.apply(draft, adjudications)) {
            is DraftAdjudications.Applied.Blocked -> DraftCheck.Invalid(applied.problems)
            is DraftAdjudications.Applied.Ready -> validate(applied.draft)
        }

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
        draft.skeleton.forEach { step -> irreversibleProblem(step)?.let { problems += it } }
        draft.skeleton.filter { it.param != null && it.candidates.isEmpty() }.forEach { step ->
            val declared = draft.params.any { p -> p.name == step.param }
            if (!declared) problems += "第 ${step.index} 步声明参数「${step.param}」但 params 里没有它"
        }
        draft.skeleton.filter { it.candidates.size == 1 }.forEach { step ->
            problems += "第 ${step.index} 步候选只有 1 条（冲突必须两套并存，单候选是坏形）"
        }
        draft.skeleton.forEach { step -> expectlessProblems(step)?.let { problems += it } }
        problems += fabricatedProblems(draft)
        return if (problems.isEmpty()) DraftCheck.Ok else DraftCheck.Invalid(problems)
    }

    /**
     * 697 判据③：断言闭集外的写法**逐条列出来**（拒收理由）。
     *
     * 单开一个函数是为了不把结构门本身撑成「太复杂」——结构门是每个草案都要过的热点，
     * 它自己变复杂了就没人愿意改它（detekt 硬墙也只认圈复杂度，不看意图）。
     */
    private fun fabricatedProblems(draft: SkillDraft): List<String> = draft.skeleton.mapNotNull { step ->
        val strange = step.expect.filter { it !in EXPECT_CLOSED }
        if (strange.isEmpty()) {
            null
        } else {
            "第 ${step.index} 步的断言「${strange.joinToString("、")}」不在闭集（" +
                "${EXPECT_CLOSED.joinToString("／")}）——$EXPECT_FABRICATED，拒收"
        }
    }

    /**
     * 697 窄口的**三道锁**（判据②④）：标了「未校验跳转」的步，允许没有 expect，但
     *
     *   a) 必须是**点击导航步**（`kind=click` 且 `navigate` 位真）——不是随便哪个步都能开；
     *   b) evidence 非空（指不回观察账的步，一律按「没有证据」拒）；
     *   c) 有**节点数变化记录**（`nc` ≠ `ncAfter`，且两个都记到了）。
     *
     * 三条缺一条就拒——窄口是给「真跳转了但地址没变」的那一种现场开的，不是免检通道。
     * 另：既写了 expect 又标了窄口 ⇒ 自相矛盾（有断言就不叫「未校验」），一并拒。
     */
    private fun expectlessProblems(step: DraftStep): String? {
        if (!step.expectlessNavigate) return null
        if (step.expect.isNotEmpty()) {
            return "第 ${step.index} 步既写了断言（${step.expect.joinToString("、")}）又标「$EXPECTLESS_MARK」——两边挑一边"
        }
        if (!step.isNavigateStep()) {
            return "第 ${step.index} 步标了「$EXPECTLESS_MARK」，但动作不是点击导航步——窄口只对导航步开"
        }
        if (!step.expectlessEvidenceOk()) {
            return "第 ${step.index} 步标了「$EXPECTLESS_MARK」但$EXPECTLESS_NO_EVIDENCE" +
                "（证据 ${step.evidence.size} 处，节点数 ${step.nodeCount}→${step.nodeCountAfter}）"
        }
        return null
    }

    /**
     * 654 判据④：点击步的**可及名/身份面**命中不可逆词表 ⇒ 拒收（原因是「不可逆」）。
     *
     * 词表不新造：复用 [PortIrreversible]（端口/示教层/622 脚本交换同一份九词表），
     * 冲突步的每套候选也一起扫——候选里有「提交」就等于这条技能迟早会点提交。
     */
    private fun irreversibleProblem(step: DraftStep): String? {
        if (step.kind != ObserveKind.CLICK.wire) return null
        val faces = listOf(step.locator) + step.candidates
        val reason = faces.firstNotNullOfOrNull { f ->
            PortIrreversible.reasonIn(listOf(f.name, f.aria, f.testid, f.formLabel, f.placeholder))
        } ?: return null
        return "第 ${step.index} 步含${IRREVERSIBLE}动作（$reason）——拒收"
    }
}
