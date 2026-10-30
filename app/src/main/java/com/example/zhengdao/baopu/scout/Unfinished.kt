// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · **起居注的「未办成单」——先只做数据结构**（派工单最后两节）。
//
// 用户 2026-10-30：「防止用户忘事…在决策中心的建议那里出现…不管弹卡，只是归纳整理哪些没做、
// 为什么失败…」＋「用户也能一键转到那张驳回错了的卡（给用户一个后悔的权利）」。
//
// 本批只交**数据与判据**，不画界面（页面等 278 的决策中心定稿）。四列固定：
//   **想做的事｜结果｜原因｜能不能补**，另加一格 `reasonRef`——"原因可点回原卡"的引用字段。
//
// 三条硬约束（写在类型里）：
//   ① **只列事实与入口**：本文件没有任何"替你决定弹不弹"的开关（那是宰相的事）；
//   ② **同一件事只列一行**：按"想做的事"归一（[keyOf]），重复发生只累计次数、更新时间；
//   ③ **原因必须可点开证据**：`reasonRef` 形如 `ask:<卡 id>` / `permission:<能力>` /
//      `audit:<条目>` / `evidence:<巡按 id>`，空引用只允许出现在"原因未知"以外的情况——
//      构造期 `require` 会拦住"有原因没出处"的半截账。
package com.example.zhengdao.baopu.scout

/** 结果（三选一，闭集）：没做／失败／半截。 */
internal enum class UnfinishedOutcome(val wire: String, val cn: String) {
    NOT_DONE("not_done", "没做"),
    FAILED("failed", "失败"),
    PARTIAL("partial", "半截"),
    ;

    companion object {
        fun ofWire(wire: String): UnfinishedOutcome? = entries.firstOrNull { it.wire == wire }
    }
}

/** 原因（闭集，派工单点名五类 ＋ 其他）：缺权限／没找到路径／被驳回／工具不可用／模型判错。 */
internal enum class UnfinishedReason(val wire: String, val cn: String) {
    MISSING_PERMISSION("missing_permission", "缺权限"),
    PATH_NOT_FOUND("path_not_found", "没找到路径"),
    REJECTED("rejected", "被驳回"),
    TOOL_UNAVAILABLE("tool_unavailable", "工具不可用"),
    MODEL_MISJUDGED("model_misjudged", "模型判错"),
    OTHER("other", "其他"),
    ;

    companion object {
        fun ofWire(wire: String): UnfinishedReason? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 未办成单的一行。
 *
 * [reasonRef] 是**后悔权的引用字段**：`ask:<卡 id>` 能一键跳回当初那张卡（原样重现当时的问题、
 * 选项、你选了什么）。本批只存引用，跳转与改判是 UI 批次的事——但字段与编码先定死，
 * 免得后面为了"能点"再改一次账本格式（改格式＝老账读不出来）。
 */
internal data class UnfinishedItem(
    /** 想做的事（人话；也是"同一件事只列一行"的归一依据）。 */
    val intent: String,
    val outcome: UnfinishedOutcome,
    val reason: UnfinishedReason,
    /** 结果/原因的一句事实补充（例如"相册里那 3 张没整理"），可为空。 */
    val reasonDetail: String = "",
    /** 证据出处（见文件头四种形态）；[UnfinishedReason.OTHER] 允许为空，其余必须非空。 */
    val reasonRef: String = "",
    /** 现在能不能补（能不能补 ≠ 要不要补：补不补归用户）。 */
    val fixable: Boolean = false,
    /** 能补时的那句入口文案（例如「去开「相机」权限」）。 */
    val fixHint: String = "",
    /** 最近一次发生的时间。 */
    val atMs: Long = 0L,
    /** 同一件事累计发生次数（**仍只列一行**）。 */
    val count: Int = 1,
) {
    init {
        require(intent.isNotBlank()) { "intent must not be blank" }
        require(count >= 1) { "count must be at least 1" }
        require(reason == UnfinishedReason.OTHER || reasonRef.isNotBlank()) {
            "reason must point back to evidence: $reason"
        }
        if (reasonRef.isNotBlank()) {
            require(reasonRef.substringBefore(':').isNotBlank() && reasonRef.contains(':')) {
                "reasonRef must look like <source>:<id>"
            }
        }
        require(!fixable || fixHint.isNotBlank()) { "fixable items must say how" }
    }
}

internal object UnfinishedRules {

    /** 归一键：同一件事（跨原因、跨次数）只列一行。 */
    fun keyOf(item: UnfinishedItem): String = item.intent.trim().replace(Regex("\\s+"), " ")

    /**
     * 合并一行：同 key ⇒ 只更新事实（结果/原因/出处/时间），次数累加，位置不变。
     *
     * 为什么"次数累加"而不是"覆盖"：用户看到的是"这件事失败了 3 次"，而不是"最后一次失败"——
     * 前者才配叫"归纳"，后者只是一条日志。
     */
    fun merge(current: List<UnfinishedItem>, incoming: UnfinishedItem): List<UnfinishedItem> {
        val key = keyOf(incoming)
        val idx = current.indexOfFirst { keyOf(it) == key }
        if (idx < 0) return current + incoming
        val old = current[idx]
        val merged = incoming.copy(count = old.count + 1)
        return current.toMutableList().apply { this[idx] = merged }
    }

    /**
     * 从一条权限**驳回**账造一行（原因＝被驳回；带"当时想做的事"）。
     *
     * 与 [fromMissingPermission] 分开：驳回是"用户选了不"，缺权限是"还没开"——
     * 两者在未办成单里都要能看出来，因为前者能点回那张卡改判，后者只需给个入口。
     */
    fun fromRejected(record: ScoutRecord, intent: String, atMs: Long, cardRef: String): UnfinishedItem =
        UnfinishedItem(
            intent = intent,
            outcome = UnfinishedOutcome.NOT_DONE,
            reason = UnfinishedReason.REJECTED,
            reasonDetail = "当时没开「${record.capability.cn}」权限",
            reasonRef = cardRef,
            fixable = true,
            fixHint = "去开「${record.capability.cn}」权限",
            atMs = atMs,
        )

    /** 从斥候的"未授权/被收回"造一行（还没走到驳回，但也干不了活）。 */
    fun fromMissingPermission(record: ScoutRecord, intent: String, atMs: Long): UnfinishedItem =
        UnfinishedItem(
            intent = intent,
            outcome = UnfinishedOutcome.NOT_DONE,
            reason = UnfinishedReason.MISSING_PERMISSION,
            reasonDetail = "「${record.capability.cn}」现在没开",
            reasonRef = "permission:${record.capability.wire}",
            fixable = true,
            fixHint = "去开「${record.capability.cn}」权限",
            atMs = atMs,
        )

    /** 从斥候的"本机做不了"造一行（原因＝工具不可用，**不可补**——别骗人说再试一次就行）。 */
    fun fromUnavailable(record: ScoutRecord, intent: String, atMs: Long): UnfinishedItem =
        UnfinishedItem(
            intent = intent,
            outcome = UnfinishedOutcome.NOT_DONE,
            reason = UnfinishedReason.TOOL_UNAVAILABLE,
            reasonDetail = record.reason,
            reasonRef = record.ref(),
            fixable = false,
            atMs = atMs,
        )
}
