// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百三十单 · MCP 工具上下文膨胀（全局预算＋按需装配）——装配纯函数。
//
// 装配口径（派工单原话级）：
// ① **内置必带**（它们不进本函数——内置的 token 由调用方先扣掉，剩给 MCP 的额度
//    就是 [McpAssemblyPlan.budgetTokens]）；
// ② **pin 必装**：用户钉住的工具无条件装，哪怕总额超出预算（判据②）；
// ③ 其余按「单轮意图相关 → 最近用过（LRU，没有记录的兜底连接序）→ 连接顺序」
//    排序，**整装整卸**装到额度为止——schema 一个字节都不改（判据⑤的红线）；
// ④ 被挤出的，给模型**一句话**说明（不给名字清单、更不给 schema）；
// ⑤ 有意图文本且不预筛就会挤出时启用预筛，说明换成「本轮只装了 X/Y 个外部工具」。
//
// 本文件是纯函数：零 Android、零 IO、不改任何入参——单测直测，引擎每步调一次。
package com.example.zhengdao.baopu.mcp

import org.json.JSONObject
import java.util.Locale

/** 一个 MCP 候选（只读投影；schema 本体留在调用方，装配器根本拿不到引用——想截断都没处下手）。 */
internal data class McpAssemblyCandidate(
    /** 注册进 ToolRegistry 的名字（`mcp_<server>_<tool>`）。 */
    val wireName: String,
    /** 服务器上的原始名字（提示文案与 pin 的对面板友好形态）。 */
    val remoteName: String,
    /** 描述（意图打分的干草垛；已是 McpSchema.describe 清洗过的形态）。 */
    val description: String,
    /** schema 估算 token（[McpToolBudget.schemaTokens] 量好带来，本层不再碰 schema）。 */
    val schemaTokens: Int,
    /** 连接序：server 在 McpToolSource 里的登记序（0 起）。 */
    val serverSeq: Int,
    /** 注册序：工具在它 server 里的注册序（0 起）。 */
    val toolSeq: Int,
)

/** 一次装配的读数（纯数据；面板与 [ToolDeclSnapshot] 都吃它）。 */
internal data class McpAssemblyPlan(
    /** 装载名单（有序 = 请求里的顺序；registry 的可见名单就设它）。 */
    val loadedNames: List<String>,
    /** 被挤出的名单（有序 = 挤出顺序；面板/日志用）。 */
    val evictedNames: List<String>,
    /** 用户钉住且已装载的（必装的那批；单独列是为面板与测试说话有据）。 */
    val pinnedLoadedNames: List<String>,
    /** 给模型的一句话（零挤出 = null）。 */
    val notice: String?,
    /** 预筛是否真的生效（生效 ⇒ 说明用 X/Y 措辞）。 */
    val prefiltered: Boolean,
    /** 本轮给 MCP 的额度（全局预算 − 内置已占）。 */
    val budgetTokens: Int,
    /** 已装载 MCP 的 token 合计（pin 挤进来时可能超过额度——如实记，不许抹平）。 */
    val usedTokens: Int,
    /** 候选总数（Y）。 */
    val totalCandidates: Int,
) {
    val loadedCount: Int get() = loadedNames.size
    val evictedCount: Int get() = evictedNames.size
    /** 「装了全部」＝ 一具没挤（registry 那侧此时不设名单，行为与未装配逐字节一致）。 */
    val loadsAll: Boolean get() = evictedNames.isEmpty()
}

internal object McpToolAssembly {

    /** 挤出说明的第一段（判据③：一句话、不给 schema）。 */
    const val NOTICE_BUDGET = "有 %d 个外部工具未装载：本轮工具 schema 超出预算，整装整卸、没有截断；" +
        "要常驻请让用户在面板上钉住（pin）"

    /** 预筛生效时的说明（判据⑤：X/Y 措辞）。 */
    const val NOTICE_PREFILTER = "本轮只装了 %d/%d 个外部工具：其余 %d 个按本轮意图与最近使用取舍未装载；" +
        "要常驻请让用户在面板上钉住（pin）"

    /**
     * 装配计划（纯函数）。
     *
     * @param candidates 全部候选（顺序任意——排序是本函数的活；重复名字以第一个为准）
     * @param mcpAllowanceTokens 给 MCP 的额度（全局预算 − 内置已占；0 = 只有 pin 能进）
     * @param pins 用户钉住的 wireName（不在候选里的忽略）
     * @param lru wireName → 最后使用时刻；没有记录的名字按最旧处理（连接序兜底）
     * @param intentText 本轮用户原话（null/空 = 不预筛）
     */
    fun plan(
        candidates: List<McpAssemblyCandidate>,
        mcpAllowanceTokens: Int,
        pins: Set<String>,
        lru: Map<String, Long>,
        intentText: String? = null,
    ): McpAssemblyPlan {
        val unique = candidates.distinctBy { it.wireName }
        if (unique.isEmpty()) {
            return McpAssemblyPlan(
                loadedNames = emptyList(),
                evictedNames = emptyList(),
                pinnedLoadedNames = emptyList(),
                notice = null,
                prefiltered = false,
                budgetTokens = mcpAllowanceTokens,
                usedTokens = 0,
                totalCandidates = 0,
            )
        }
        val pinned = unique.filter { it.wireName in pins }.map { it.wireName }
        val score = intentScoreOf(unique, intentText)
        val prefilter = wouldEvict(unique, mcpAllowanceTokens) && score.isNotEmpty()
        val ordered = unique.sortedWith(order(pins, lru, score, prefilter))
        val packed = pack(ordered, pins, mcpAllowanceTokens)
        val evicted = unique.size - packed.loaded.size
        val notice = when {
            evicted <= 0 -> null
            prefilter -> String.format(
                Locale.ROOT,
                NOTICE_PREFILTER,
                packed.loaded.size,
                unique.size,
                evicted,
            )
            else -> String.format(Locale.ROOT, NOTICE_BUDGET, evicted)
        }
        return McpAssemblyPlan(
            loadedNames = packed.loaded.map { it.wireName },
            evictedNames = ordered.map { it.wireName } - packed.loaded.map { it.wireName }.toSet(),
            pinnedLoadedNames = pinned,
            notice = notice,
            prefiltered = prefilter,
            budgetTokens = mcpAllowanceTokens,
            usedTokens = packed.used,
            totalCandidates = unique.size,
        )
    }

    /** 排序：pin 在前 →（预筛时）意图分高在前 → 最近用过在前 → 连接序、注册序兜底。 */
    private fun order(
        pins: Set<String>,
        lru: Map<String, Long>,
        score: Map<String, Int>,
        prefilter: Boolean,
    ): Comparator<McpAssemblyCandidate> =
        compareBy<McpAssemblyCandidate> { it.wireName !in pins }
            .thenBy { if (prefilter) -(score[it.wireName] ?: 0) else 0 }
            .thenByDescending { lru[it.wireName] ?: 0L }
            .thenBy { it.serverSeq }
            .thenBy { it.toolSeq }

    /** 打分：没有意图文本 ⇒ 空表（预筛自然关闭）。 */
    private fun intentScoreOf(
        candidates: List<McpAssemblyCandidate>,
        intentText: String?,
    ): Map<String, Int> {
        val scorer = McpToolBudget.intentScorer(intentText)
        val scored = candidates.associate { it.wireName to scorer(it.wireName, it.description) }
        val hits = scored.filterValues { it > 0 }
        return hits
    }

    /** 不预筛会不会挤出（预筛只在「装不下」时启用；装得下就全装，别画蛇添足）。 */
    private fun wouldEvict(candidates: List<McpAssemblyCandidate>, allowance: Int): Boolean =
        candidates.sumOf { it.schemaTokens } > allowance

    /**
     * 装到额度为止：pin 无条件装（超预算也装——判据②）；其余按序整装整卸。
     *
     * 「整装整卸」就是判据⑤的落地：一个候选要么完整进 [Packed.loaded]，要么完整被挤——
     * 本函数没有任何改写 schema 的入口，想截断连字段都摸不着。
     */
    private fun pack(
        ordered: List<McpAssemblyCandidate>,
        pins: Set<String>,
        allowance: Int,
    ): Packed {
        val loaded = ArrayList<McpAssemblyCandidate>(ordered.size)
        var used = 0
        for (candidate in ordered) {
            val isPin = candidate.wireName in pins
            if (isPin || used + candidate.schemaTokens <= allowance) {
                loaded.add(candidate)
                used += candidate.schemaTokens
            }
        }
        return Packed(loaded, used)
    }

    private data class Packed(val loaded: List<McpAssemblyCandidate>, val used: Int)
}
