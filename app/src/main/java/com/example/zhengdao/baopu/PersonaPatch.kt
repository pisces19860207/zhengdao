// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第九十六批 §1/§2（98 总单子项 B：A/B/C 分层与个性化 v1）。
//
// 三条口径（测试与源码门禁照它钉）：
// 1. **A 不可变**：本文件不出现 `A_KERNEL` 这个值——[PersonaPatchScope] 只有 B/C 两档，
//    「给 A 写补丁」在**编译期**就写不出来（不是运行时才拒）；A 的只读判据仍在
//    [PersonaRules.canWrite]（刀 0b 起恒 false）。
// 2. **B/C 是结构化补丁，不是自由文本**：一条 [PersonaPatch] = 作用域 + 若干条
//    白名单条款（[PersonaSectionKind] 四选一）+ 理由；收自由文本/角色卡整卡的
//    JSON 在 [PersonaPatchRules.parsePatches] 里直接判非法。
// 3. **只许追加**：渲染结果必须满足 [PersonaRules.appendOnly]（改/删既有条款一律拒）。
//
// 本文件零 Android 依赖、零 I/O（纯判据 + 审计文本），JVM 单测直接断言。
package com.example.zhengdao.baopu

import org.json.JSONArray
import org.json.JSONObject

/** typed patch 的作用域——**只有 B/C 两档**（A 内核不是可写目标，连枚举值都不给）。 */
enum class PersonaPatchScope(val key: String) {
    /** 用户级基础人设（跟 App 走；写入必须「全局」同意）。 */
    B_GLOBAL("b"),

    /** 当前工作区适配（跟工作区走；默认只写当前工作区）。 */
    C_PROJECT("c"),
}

/** 白名单条款类别（自由文本不许进；这四类之外一律判非法）。 */
enum class PersonaSectionKind(val key: String, val heading: String) {
    STYLE("style", "风格"),
    RULES("rules", "规矩"),
    FACTS("facts", "事实"),
    HABITS("habits", "习惯"),
    ;

    companion object {
        fun fromKey(raw: String): PersonaSectionKind? = values().firstOrNull { it.key == raw.trim().lowercase() }
    }
}

/** 一条条款（类别 + 正文）。 */
data class PersonaSection(val kind: PersonaSectionKind, val text: String)

/**
 * 一条**结构化**人设补丁。
 *
 * @param scope B/C 二选一（A 写不进来）
 * @param workspaceId C 层必填（创建时的工作区标识）；B 层必须为 null（它跟 App 走）
 * @param sections 白名单条款；同一类别至多一条（typed = 结构清楚，不是一坨散文）
 * @param reason 为什么要加（审计与卡片都显示它；空理由不许入库）
 * @param candidateId 来源候选 id（86 批 `ProposalCandidate.id`；工具直调时为空）
 */
data class PersonaPatch(
    val scope: PersonaPatchScope,
    val workspaceId: String?,
    val sections: List<PersonaSection>,
    val reason: String,
    val candidateId: String? = null,
)

/** 校验/解析的结论。 */
sealed interface PersonaPatchParse {
    /** 合法补丁（可能多条）。 */
    data class Ok(val patches: List<PersonaPatch>) : PersonaPatchParse

    /** 非法（原因直接给人看；**一个字节都不许落盘**）。 */
    data class Rejected(val reason: String) : PersonaPatchParse
}

/**
 * typed patch 的纯判据：校验、渲染、解析（含「拒角色卡」）。
 *
 * 数字都写死在这里（报告里同口径）：单条 ≤800 字符、整补丁 ≤2000 字符、条款 ≤4 条。
 * 上限的意义：人设是**约束文件**，不是第二个聊天记录；一次塞太多等于把缰绳换成绳子。
 */
internal object PersonaPatchRules {

    const val MAX_SECTION_CHARS = 800
    const val MAX_PATCH_CHARS = 2_000
    const val MAX_SECTIONS = 4

    /** 允许出现在补丁 JSON 里的键（**多一个就拒**——角色卡整卡就是这么被挡住的）。 */
    private val ALLOWED_PATCH_KEYS = setOf("scope", "workspaceId", "sections", "reason", "candidateId")
    private val ALLOWED_SECTION_KEYS = setOf("kind", "text")

    /** 校验一条补丁；合法 ⇒ null，非法 ⇒ 给人看的原因。 */
    fun validate(patch: PersonaPatch): String? {
        if (patch.reason.isBlank()) return "补丁必须写理由（reason 不能为空）"
        if (patch.reason.length > 200) return "理由太长（≤200 字）"
        if (patch.sections.isEmpty()) return "补丁至少要有一条条款"
        if (patch.sections.size > MAX_SECTIONS) return "条款太多（≤$MAX_SECTIONS 条）"
        val kinds = patch.sections.map { it.kind }
        if (kinds.size != kinds.toSet().size) return "同一类别只能有一条（别把同一类拆成好几段）"
        patch.sections.forEach { s ->
            if (s.text.isBlank()) return "条款正文不能为空（${s.kind.heading}）"
            if (s.text.length > MAX_SECTION_CHARS) return "单条条款太长（${s.kind.heading} ≤$MAX_SECTION_CHARS 字符）"
        }
        when (patch.scope) {
            PersonaPatchScope.C_PROJECT -> {
                if (patch.workspaceId.isNullOrBlank()) return "C 层补丁必须带 workspaceId（跟工作区走）"
            }
            PersonaPatchScope.B_GLOBAL -> {
                // B 跟 App 走、不属于任何工作区：带着 workspaceId 说明调用方把作用域搞混了
                if (!patch.workspaceId.isNullOrBlank()) return "B 层补丁不该带 workspaceId（它跟 App 走，不属于某个工作区）"
            }
        }
        if (render("", patch.sections).length > MAX_PATCH_CHARS) return "补丁总长超限（≤$MAX_PATCH_CHARS 字符）"
        return null
    }

    /**
     * 渲染：把条款按「`## 类别` + 正文」追加到现有正文末尾。
     *
     * 顺序恒为**传入顺序**（调用方按 STYLE/RULES/FACTS/HABITS 排好），不排序——
     * 同一条补丁两次渲染必须逐字节相同（确定性，评测/回放要用）。
     */
    fun render(current: String, sections: List<PersonaSection>): String {
        val block = sections.joinToString("\n\n") { s ->
            "## ${s.kind.heading}\n${s.text.trim()}"
        }
        val base = current.trimEnd()
        return if (base.isEmpty()) block else "$base\n\n$block"
    }

    /**
     * 解析一批 typed patch（导出/导入与候选桥都用它）。
     *
     * 这里是「**不导入角色卡**」的落点（2026-10-20 用户：破坏性大）：
     * - 顶层必须是数组，元素必须是对象；
     * - 键只许 [ALLOWED_PATCH_KEYS]（角色卡的 `name/description/personality/first_mes`
     *   一进来就撞「未知键」⇒ 整批拒，不做「能认多少认多少」的宽容解析）；
     * - `sections` 里的键只许 `kind/text`，`kind` 必须命中文白名单。
     *
     * 任何一条不合法 ⇒ **整批拒**（返回 [PersonaPatchParse.Rejected]），不做部分导入：
     * 半批入库比整批拒更难回滚。
     */
    fun parsePatches(raw: String): PersonaPatchParse {
        val arr = runCatching { JSONArray(raw) }.getOrNull()
            ?: return PersonaPatchParse.Rejected("不是补丁数组（角色卡/自由文本一律不收）")
        if (arr.length() == 0) return PersonaPatchParse.Rejected("补丁数组是空的")
        if (arr.length() > 20) return PersonaPatchParse.Rejected("一次最多导入 20 条补丁")
        val out = mutableListOf<PersonaPatch>()
        for (i in 0 until arr.length()) {
            val obj = arr.optJSONObject(i)
                ?: return PersonaPatchParse.Rejected("第 ${i + 1} 条不是对象")
            val keys = obj.keys().asSequence().toSet()
            val unknown = keys - ALLOWED_PATCH_KEYS
            if (unknown.isNotEmpty()) {
                return PersonaPatchParse.Rejected(
                    "第 ${i + 1} 条有未知字段（${unknown.sorted().joinToString("、")}）——只收结构化补丁，不收角色卡整卡",
                )
            }
            val scopeKey = obj.optString("scope").trim().lowercase()
            val scope = PersonaPatchScope.values().firstOrNull { it.key == scopeKey }
                ?: return PersonaPatchParse.Rejected("第 ${i + 1} 条 scope 只许 b/c（A 内核不可写）")
            val ws = if (obj.has("workspaceId") && !obj.isNull("workspaceId")) obj.optString("workspaceId").trim() else null
            val secArr = obj.optJSONArray("sections")
                ?: return PersonaPatchParse.Rejected("第 ${i + 1} 条缺 sections")
            val sections = mutableListOf<PersonaSection>()
            for (j in 0 until secArr.length()) {
                val so = secArr.optJSONObject(j)
                    ?: return PersonaPatchParse.Rejected("第 ${i + 1} 条第 ${j + 1} 个条款不是对象")
                val secUnknown = so.keys().asSequence().toSet() - ALLOWED_SECTION_KEYS
                if (secUnknown.isNotEmpty()) {
                    return PersonaPatchParse.Rejected("条款里有未知字段（${secUnknown.sorted().joinToString("、")}）")
                }
                val kind = PersonaSectionKind.fromKey(so.optString("kind"))
                    ?: return PersonaPatchParse.Rejected(
                        "条款类别只许 ${PersonaSectionKind.values().joinToString("/") { it.key }}",
                    )
                sections += PersonaSection(kind, so.optString("text"))
            }
            val patch = PersonaPatch(
                scope = scope,
                workspaceId = ws,
                sections = sections,
                reason = obj.optString("reason"),
                candidateId = if (obj.has("candidateId")) obj.optString("candidateId").ifBlank { null } else null,
            )
            validate(patch)?.let { return PersonaPatchParse.Rejected("第 ${i + 1} 条：$it") }
            out += patch
        }
        return PersonaPatchParse.Ok(out)
    }

    /**
     * 86 批候选 → typed patch（候选桥）。
     *
     * 只接受 `PERSONA_APPEND`（技能正文/工具规则候选不许从这条路进人设）；
     * 候选 payload 是一段自然语言 ⇒ 落成**一条 RULES 条款**（结构仍然typed，
     * 不会变成「整卡入库」）。
     */
    fun fromCandidate(
        candidate: ProposalCandidate,
        scope: PersonaPatchScope,
        workspaceId: String?,
    ): PersonaPatch? {
        if (candidate.kind != ProposalKind.PERSONA_APPEND) return null
        val text = candidate.payload.trim()
        if (text.isEmpty() || text.length > MAX_SECTION_CHARS) return null
        val patch = PersonaPatch(
            scope = scope,
            workspaceId = if (scope == PersonaPatchScope.C_PROJECT) workspaceId else null,
            sections = listOf(PersonaSection(PersonaSectionKind.RULES, text)),
            reason = "任务后候选：${candidate.target}",
            candidateId = candidate.id,
        )
        return if (validate(patch) == null) patch else null
    }
}

/**
 * 人设补丁的留痕（`[persona]` 可 grep）。
 *
 * 形制照 [com.example.zhengdao.baopu.agent.ToolGroupAudit]：**只产出文本**，
 * 落盘由装配层把 [sink] 接到 `RunLog.log`（与 `[kernel]` / `[group]` 同一口径）。
 */
internal object PersonaAudit {

    const val TAG = "[persona]"

    @Volatile
    var sink: ((String) -> Unit)? = null

    /** 应用成功（[scopeLabel] = 「工作区」/「全局」；[source] = 「卡片」/「导入」/「设置页」）。 */
    fun applied(layer: PersonaLayer, scopeLabel: String, source: String, sections: List<PersonaSection>): String =
        "$TAG 应用 ${layer.name}（$scopeLabel）来源=$source 条款=${sections.joinToString(",") { it.kind.key }}"
            .also { emit(it) }

    /** 拒绝/未同意（**不许静默**：拒了也要有一行）。 */
    fun rejected(reason: String, source: String): String =
        "$TAG 未应用 来源=$source 原因=$reason".also { emit(it) }

    /** 重置一层（先备份再写空）。 */
    fun reset(layer: PersonaLayer): String = "$TAG 重置 ${layer.name}（先备份）".also { emit(it) }

    private fun emit(line: String) {
        sink?.invoke(line)
    }
}
