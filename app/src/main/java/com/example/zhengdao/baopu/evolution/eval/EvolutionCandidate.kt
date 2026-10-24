// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十三批（2026-10-24）· 进化评估/回归（进化环②）。
// 设计：证道设计报告/进化评估-设计-2026-10-24.md §2／§9。
//
// 本文件只放**候选包**与哈希：不读盘、不调模型、不写技能/人设。
package com.example.zhengdao.baopu.evolution.eval

import java.io.File
import java.security.MessageDigest
import org.json.JSONArray
import org.json.JSONObject

/** 一个候选的类别（v1 五类；最后两类在 162 前只许 fixture 影射）。 */
internal enum class EvolutionKind {
    SKILL_BODY,
    PERSONA_APPEND,
    TOOL_RULE,
    MEMORY_NOTE,
    PLAN_RULE,
}

/**
 * 候选包（一次只评一个）。
 *
 * @param candidateId 唯一标识；同一 id 复跑必须逐字节同结果（时间戳除外）
 * @param kind 候选类别
 * @param target 落点（技能名／B/C 层／工具规则 id／记忆条 id／计划规则 id）
 * @param delta 追加或替换的正文（UTF-8）；空 delta 是「无差异」负例
 * @param evidenceIds 来源证据 id（只存引用，不存正文）；`fixture:` 前缀 = 机制自测夹具
 */
internal data class EvolutionCandidate(
    val candidateId: String,
    val kind: EvolutionKind,
    val target: String,
    val delta: String,
    val evidenceIds: List<String> = emptyList(),
) {
    init {
        require(candidateId.isNotBlank()) { "candidateId 不能为空" }
        require(evidenceIds.none { it.isBlank() }) { "evidenceIds 里不能有空串" }
    }

    /** 是否是机制自测夹具（不是生产候选）。 */
    val fixtureMapped: Boolean get() = evidenceIds.any { it.startsWith(FIXTURE_PREFIX) }

    /**
     * 162 未交付前，`MEMORY_NOTE` / `PLAN_RULE` 的真实候选一律 DEFER（用户 2026-10-24 拍板 §14.3）。
     * 只有带 `fixture:` 证据的机制自测候选可以跑。
     */
    val mechanismOnly: Boolean get() = kind in MECHANISM_ONLY_KINDS && !fixtureMapped

    /** 空正文 = 灌进去也没变化（机制自测的「无差异」负例）。 */
    val hasDelta: Boolean get() = delta.isNotBlank()

    fun toJson(): JSONObject = JSONObject()
        .put("candidateId", candidateId)
        .put("kind", kind.name)
        .put("target", target)
        .put("delta", delta)
        .put("evidenceIds", JSONArray(evidenceIds))

    /** 稳定字节串（键序写死；JSON 转义走 `JSONObject.quote`，不吃平台默认编码）。 */
    fun canonicalText(): String = buildString {
        append("candidateId=").append(candidateId).append('\n')
        append("kind=").append(kind.name).append('\n')
        append("target=").append(JSONObject.quote(target)).append('\n')
        append("delta=").append(JSONObject.quote(delta)).append('\n')
        append("evidenceIds=").append(evidenceIds.joinToString(",") { JSONObject.quote(it) }).append('\n')
    }

    fun sha256(): String = EvolutionHash.sha256(canonicalText())

    companion object {
        /** 机制自测夹具的证据前缀（生产证据是 `run_log:` / `skill_event:` 等）。 */
        const val FIXTURE_PREFIX = "fixture:"

        val MECHANISM_ONLY_KINDS: Set<EvolutionKind> = setOf(
            EvolutionKind.MEMORY_NOTE,
            EvolutionKind.PLAN_RULE,
        )

        fun parse(json: String): EvolutionCandidate {
            val o = JSONObject(json)
            val kindName = o.getString("kind")
            val kind = EvolutionKind.values().firstOrNull { it.name == kindName }
                ?: throw IllegalArgumentException("未知候选类别：$kindName（允许：${EvolutionKind.values().joinToString("、")}）")
            val ids = ArrayList<String>()
            o.optJSONArray("evidenceIds")?.let { arr ->
                for (i in 0 until arr.length()) ids.add(arr.getString(i))
            }
            return EvolutionCandidate(
                candidateId = o.getString("candidateId"),
                kind = kind,
                target = o.optString("target", ""),
                delta = o.optString("delta", ""),
                evidenceIds = ids,
            )
        }

        fun load(file: File): EvolutionCandidate = parse(file.readText(Charsets.UTF_8))
    }
}

/** 单候选入口的结论（设计 §2：传多个 ⇒ 配置错误，不出判定）。 */
internal sealed interface CandidateSelection {
    data class Single(val candidate: EvolutionCandidate) : CandidateSelection

    data class Rejected(val code: String, val reason: String) : CandidateSelection
}

internal object EvolutionRequest {
    /** 与设计 §2 的 `single_candidate=false` 对齐的机器码。 */
    const val CODE_SINGLE_CANDIDATE = "single_candidate_required"

    fun select(candidates: List<EvolutionCandidate>): CandidateSelection = when (candidates.size) {
        1 -> CandidateSelection.Single(candidates[0])
        0 -> CandidateSelection.Rejected(
            CODE_SINGLE_CANDIDATE,
            "一次只评一个候选：这次一个都没给",
        )
        else -> CandidateSelection.Rejected(
            CODE_SINGLE_CANDIDATE,
            "一次只评一个候选：这次给了 ${candidates.size} 个（${candidates.joinToString { it.candidateId }}）",
        )
    }
}

/** SHA-256（十六进制小写）；两臂指纹与候选指纹共用这一个函数。 */
internal object EvolutionHash {
    fun sha256(text: String): String = sha256(text.toByteArray(Charsets.UTF_8))

    fun sha256(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }
}
