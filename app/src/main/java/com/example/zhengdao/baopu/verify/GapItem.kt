// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十三批 · GapItem——**唯一差距格式**。
//
// 背景（派工单 313）：只看最终结果时「工具误用」查不出来——结果对了，过程可能是歪的。
// 要一条「过程监控 → 终端验收 → 回灌」的闭环，金吾卫（过程采样）与右相（验收发现）
// **共用同一份格式**，谁也不许另造一份批评体。
//
// 六字段（唯一格式，源级门禁钉死字段集，多一个少一个都红）：
//   source      来源（JINWU 金吾卫／RIGHT 右相／KANLI 勘验——本批只留位）；
//   kind        六类闭集（TOOL_MISUSE／SCHEMA_VIOLATION／MISSING_EVIDENCE／
//               HASH_MISMATCH／NO_LEDGER／GOAL_DRIFT）；
//   target      计划项（plan:<n>）或动作 id（action:<callId>／action:<tool>:<argsDigest>）；
//   evidence    证据（账行／路径+hash／工具名+参数摘要；没有证据的差距不进账＝L14 同款）；
//   minimalFix  一句话最小满足条件（≤60 字；禁价值判断词——判据⑥）；
//   attempts    该差距被**记录到**的累计次数（同项合并时累加；≥3 ⇒ 交人，不再回灌）。
package com.example.zhengdao.baopu.verify

/** 差距来源（闭集三家；KANLI 本批只留位，勘验接线后启用）。 */
internal enum class GapSource(val wire: String) {
    JINWU("jinwu"),
    RIGHT("right"),
    KANLI("kanli"),
}

/** 差距六类（闭集，派工单点名；没有「其它」——塞不进六类的差距不配回灌）。 */
internal enum class GapKind(val wire: String) {
    TOOL_MISUSE("tool_misuse"),
    SCHEMA_VIOLATION("schema_violation"),
    MISSING_EVIDENCE("missing_evidence"),
    HASH_MISMATCH("hash_mismatch"),
    NO_LEDGER("no_ledger"),
    GOAL_DRIFT("goal_drift"),
}

internal data class GapItem(
    val source: GapSource,
    val kind: GapKind,
    val target: String,
    val evidence: List<String>,
    val minimalFix: String,
    val attempts: Int = 1,
) {
    /** 同项键：kind+target 相同即同项（跨来源合并按它对——判据③）。 */
    val gapKey: String get() = "${kind.wire}|${target.trim()}"

    init {
        require(target.isNotBlank()) { "差距缺对象（计划项或动作 id）" }
        require(evidence.isNotEmpty() && evidence.none { it.isBlank() }) {
            "差距缺证据（L14：没有证据的结论不进账）"
        }
        require(minimalFix.isNotBlank()) { "差距缺最小满足条件" }
        require(minimalFix.length <= MAX_FIX_CHARS) { "最小满足条件要一句话（≤$MAX_FIX_CHARS 字）" }
        require(attempts >= 1) { "attempts 从 1 起（被记录到的次数）" }
        require(BANNED_VALUE_WORDS.none { minimalFix.contains(it) }) { "最小满足条件禁价值判断词" }
    }

    companion object {
        /** 一句话的形制上限（再长就不是「最小满足条件」了）。 */
        const val MAX_FIX_CHARS = 60

        /**
         * 回灌文案禁词（判据⑥：回灌不许夹价值判断）。
         * 源级门禁扫两个源文件的字面量时**豁免带 BANNED_VALUE_WORDS 字样的行**
         * （闸本身得能写出来；删了闸、词进了别处，门禁照样红）。
         */
        val BANNED_VALUE_WORDS = listOf("建议", "应该", "更好")
    }
}

/** 同项合并与排序（纯函数；板在 [GapFeedback]，判据在这里）。 */
internal object GapRules {

    fun keyOf(item: GapItem): String = item.gapKey

    /**
     * 同项合并（判据③）：attempts 累加、证据并集去重、来源取在先的一家
     * （枚举序即优先级：JINWU＞RIGHT＞KANLI——过程差距先于验收差距在册）。
     * minimalFix 取先到的那句（同一差距不该有两句改法——那是两个差距）。
     */
    fun merge(existing: GapItem, incoming: GapItem): GapItem = GapItem(
        source = minOf(existing.source, incoming.source),
        kind = existing.kind,
        target = existing.target.trim(),
        evidence = (existing.evidence + incoming.evidence).distinct(),
        minimalFix = existing.minimalFix,
        attempts = existing.attempts + incoming.attempts,
    )

    /** 回灌顺序（派工单：按 attempts 排序）：次数多的在前——最顽的差距最先被看见。 */
    fun orderOf(items: List<GapItem>): List<GapItem> =
        items.sortedWith(compareByDescending<GapItem> { it.attempts }.thenBy { it.target })
}
