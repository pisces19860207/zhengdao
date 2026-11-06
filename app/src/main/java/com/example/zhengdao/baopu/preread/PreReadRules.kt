// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 B · 预读回执闸：**判据层**（纯函数，零 IO、零全局状态、零时钟）。
//
// 这一层只回答四个问题，答案全部可单测直接算出来：
//   ① 这个任务现在处于哪一态（[mode]：不启用／提示不拦／启用）；
//   ② 这一笔要不要拦（[gate]：只读永不拦，写按态＋有没有回执）；
//   ③ 回执三行齐不齐（[receiptGaps]：缺一行＝没交）；
//   ④ 收工时两张纸对得上吗（[scopeDiff]／[readCoverage]）。
//
// ## 三态是用户 2026-11-06 裁定写死的，不许含糊
// ① **无清单** ⇒ 闸不启用，不拦任何写工具，只记一行「本任务未要求预读」。
//    为什么必须这样：否则连"删一个临时文件"都要先写回执，等于把主流程卡死。
// ② **有清单但未要求回执** ⇒ 提示不拦（仍可写），收工时对照「读了没」落一行。
// ③ **有清单且要求回执** ⇒ 启用：未交回执前写工具一律报错拦住；交了 ⇒ 放行。
//
// ## "要求回执"只有两个来源（裁定点名，缺一不可写清）
// · 显式＝派单侧在清单里带 `requireReceipt: true`（[ReadFirstOrder.requireReceipt]）；
// · 自动＝长程或跨会话 ⇒ 自动要求，**判定用的是 274 批 [com.example.zhengdao.baopu.plan.PlanPledgeRules.isLongTask]
//   那一个函数**（≥5 步／跨会话），不在本批另造第二套长程判定。
// 两条来源在这里是**或**关系（[mode]），谁也不覆盖谁：派单人没勾、但任务被判长程 ⇒ 仍 ③；
// 派单人勾了、任务很短 ⇒ 也 ③（那是派单人的明示，不因步数少而失效）。
package com.example.zhengdao.baopu.preread

/** 闸的三态（用户 2026-11-06 裁定的编号原样搬进代码，看账的时候对得上号）。 */
internal enum class PreReadMode(val wire: String, val cn: String) {
    /** ① 无清单：闸不启用，只记一行。 */
    OFF("off", "未要求预读"),

    /** ② 有清单、未要求回执：提示不拦。 */
    NOTIFY("notify", "提示不拦"),

    /** ③ 有清单且要求回执：未交回执 ⇒ 写工具拦住。 */
    ENFORCED("enforced", "回执闸启用"),
    ;
}

/**
 * 一笔工具调用经过闸之后的结论（[PreReadRules.gate] 的返回）。
 *
 * 为什么把 mode 也带回来：拦住与否要能在账/log 里说清"当时是哪一态"，
 * 只回一个 bool，事后翻账看不出是"没要求"还是"要求了但已放行"。
 */
internal data class GateResult(
    val blocked: Boolean,
    val mode: PreReadMode,
    /** 拦＝那句可操作提示；放＝一句状态说明（可以是空串）。 */
    val message: String,
)

/** 收工对照的结论（判据③：改了清单外 ⇒ 记账并提出，**不阻断**）。 */
internal data class ScopeDiff(
    /** 回执里说了、这轮真改了（对上了）。 */
    val matched: List<String>,
    /** 回执说了但没动——说了没做，账上一行，不阻断。 */
    val notTouched: List<String>,
    /** 动了但回执没说——越界改文件，账上一行，不阻断。 */
    val outOfScope: List<String>,
) {
    /** 有没有要提出的（三者任一不空 ⇒ 收工那一行要写东西）。 */
    val hasFindings: Boolean get() = matched.isEmpty() || notTouched.isNotEmpty() || outOfScope.isNotEmpty()
}

/** 必读清单的"读了没"（态②收工那一条；态③也照算，供回看）。 */
internal data class ReadCoverage(val covered: List<String>, val uncovered: List<String>) {
    val allCovered: Boolean get() = uncovered.isEmpty()
}

/** 那句可操作提示的固定前缀（测试与探针按它对上，不许各处改写措辞）。 */
internal const val BLOCK_HEAD = "先交回执：读了哪些 / 我的理解 / 打算改什么；或声明本任务不要求回执"

internal object PreReadRules {

    // ── ① 态：这个任务闸开到哪一档 ──────────────────────────────────────────

    /**
     * 三态判定（**唯一**一处合成两条来源的地方）。
     *
     * @param order 任务单（null＝这任务压根没附清单）
     * @param autoRequired 长程/跨会话自动要求（由调用方拿 [com.example.zhengdao.baopu.plan.PlanPledgeRules.isLongTask] 算）
     * @param declaredNotRequired 承接侧明说"本任务不要求回执"（[BLOCK_HEAD] 后半句那个出口）
     */
    fun mode(order: ReadFirstOrder?, autoRequired: Boolean, declaredNotRequired: Boolean): PreReadMode {
        if (order == null || !order.hasItems) return PreReadMode.OFF
        // 明说不要回执 ⇒ 退回"提示不拦"。单子本身不动（回看时两张纸都还在）。
        if (declaredNotRequired) return PreReadMode.NOTIFY
        return if (order.requireReceipt || autoRequired) PreReadMode.ENFORCED else PreReadMode.NOTIFY
    }

    // ── ② 拦不拦 ────────────────────────────────────────────────────────────

    /**
     * 这一笔走不走（判据⑤「只拦写不拦读」在代码里只此一处）。
     *
     * @param isWrite 工具是否写（生产侧口径＝ `!spec.readOnly`，与 287 围栏同一个真值）
     * @param hasReceipt 本任务是否已有**齐**的回执（缺行＝没有，见 [receiptGaps]）
     */
    fun gate(mode: PreReadMode, isWrite: Boolean, hasReceipt: Boolean, tool: String): GateResult {
        if (!isWrite) return GateResult(false, mode, "")
        return when (mode) {
            PreReadMode.OFF -> GateResult(false, mode, "")
            PreReadMode.NOTIFY -> GateResult(false, mode, "")
            PreReadMode.ENFORCED ->
                if (hasReceipt) GateResult(false, mode, "")
                else GateResult(true, mode, blockLine(tool))
        }
    }

    /** 拦住时返回给模型的那一句（裁定原文逐字，前面带工具名，后面不带第二套说法）。 */
    fun blockLine(tool: String): String =
        "抱朴预读：未交回执 ⇒ 写工具「$tool」不执行。$BLOCK_HEAD。"

    // ── ③ 回执齐不齐 ────────────────────────────────────────────────────────

    /** 任务单哪里不合规（空清单不算不合规——那是"没附"，态①）。 */
    fun orderIssues(order: ReadFirstOrder): List<OrderIssue> {
        val bad = mutableListOf<OrderIssue>()
        if (order.taskKey.isBlank()) bad += OrderIssue.BLANK_TASK
        if (order.items.size > PreReadLimits.MAX_ITEMS) bad += OrderIssue.TOO_MANY_ITEMS
        val targets = LinkedHashSet<String>()
        for (item in order.items) {
            if (item.target.isBlank()) bad += OrderIssue.UNKNOWN_TARGET
            else if (!targets.add(item.target.trim())) bad += OrderIssue.DUPLICATE_TARGET
            if (item.target.length > PreReadLimits.MAX_LINE_CHARS ||
                item.why.length > PreReadLimits.MAX_LINE_CHARS
            ) {
                bad += OrderIssue.TOO_LONG
            }
        }
        return bad.distinct()
    }

    /**
     * 回执三行齐不齐（**空列表＝齐＝算交了**）。
     *
     * 判据②「缺一行就当没交」：三行里任何一行空白 ⇒ 返回对应 gap，
     * 调用方（[PreReadBoard.submit]）不落库、不解锁。放水一条，闸就成了摆设。
     */
    fun receiptGaps(r: PreReadReceipt): List<ReceiptGap> {
        val bad = mutableListOf<ReceiptGap>()
        if (r.taskKey.isBlank()) bad += ReceiptGap.BLANK_TASK
        val reads = r.readLines.map { it.trim() }.filter { it.isNotEmpty() }
        val files = r.plannedFiles.map { it.trim() }.filter { it.isNotEmpty() }
        if (reads.isEmpty()) bad += ReceiptGap.BLANK_READS
        if (r.understanding.isBlank()) bad += ReceiptGap.BLANK_UNDERSTANDING
        if (files.isEmpty()) bad += ReceiptGap.BLANK_FILES
        if (reads.size > PreReadLimits.MAX_READ_LINES) bad += ReceiptGap.TOO_MANY_READS
        if (files.size > PreReadLimits.MAX_PLANNED_FILES) bad += ReceiptGap.TOO_MANY_FILES
        val tooLong = reads.any { it.length > PreReadLimits.MAX_LINE_CHARS } ||
            files.any { it.length > PreReadLimits.MAX_LINE_CHARS } ||
            r.understanding.length > PreReadLimits.MAX_LINE_CHARS
        if (tooLong) bad += ReceiptGap.TOO_LONG
        return bad.distinct()
    }

    /** 回执算不算交了（[receiptGaps] 的读法糖，调用点只关心这一个 bool）。 */
    fun receiptAccepted(r: PreReadReceipt): Boolean = receiptGaps(r).isEmpty()

    // ── ④ 收工对照 ──────────────────────────────────────────────────────────

    /**
     * 回执说改的文件 vs 实际改的（判据③）。
     *
     * 比对口径是"路径尾对齐"，不是字符串全等：回执里人写的是相对路径（`app/src/…/X.kt`），
     * 引擎记账的是绝对路径或工作区相对路径（287 起全盘写都能做）。全等会判出一片假越界，
     * 尾对齐只漏不误——越界这条**不阻断**，宁可多提出一条也不能少提出，但少到全是噪声
     * 就等于这条判据废了（219 批同一教训）。
     */
    fun scopeDiff(planned: List<String>, touched: List<String>): ScopeDiff {
        val left = touched.map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        val matched = mutableListOf<String>()
        val notTouched = mutableListOf<String>()
        for (p in planned.map { it.trim() }.filter { it.isNotEmpty() }.distinct()) {
            val idx = left.indexOfFirst { samePath(p, it) }
            if (idx < 0) {
                notTouched += p
            } else {
                matched += "$p→${left[idx]}"
                left.removeAt(idx)
            }
        }
        return ScopeDiff(matched = matched, notTouched = notTouched, outOfScope = left)
    }

    /** 路径尾对齐（双向后缀＋忽略分隔符差异）。 */
    private fun samePath(a: String, b: String): Boolean {
        val x = a.replace('\\', '/').trimEnd('/')
        val y = b.replace('\\', '/').trimEnd('/')
        return x == y || x.endsWith("/$y") || y.endsWith("/$x")
    }

    /**
     * 必读清单每一条在回执「读了哪些」里有没有对应（态②收工那一行，也是态③回看的那一栏）。
     *
     * 口径同样是尾对齐/子串：清单写 `GuardPipeline.kt:441`，回执写"读了 GuardPipeline 第②段之后"
     * ——这一条**算没读到**（要严格对得上才销账），只比 target 的文件名部分。
     */
    fun readCoverage(order: ReadFirstOrder, receipt: PreReadReceipt?): ReadCoverage {
        val reads = receipt?.readLines.orEmpty().map { it.trim().replace('\\', '/') }.filter { it.isNotEmpty() }
        val covered = mutableListOf<String>()
        val uncovered = mutableListOf<String>()
        for (item in order.items) {
            val key = pathPart(item.target)
            val hit = key.isNotEmpty() && reads.any { it.contains(key) || key.contains(it) }
            if (hit) covered += item.render() else uncovered += item.render()
        }
        return ReadCoverage(covered, uncovered)
    }

    /** 条目前面那段"读哪儿"：去掉 `:行号`／空格后的说明，只留可比对的路径主体。 */
    private fun pathPart(target: String): String =
        target.substringBefore('#').substringBefore(' ').trim().replace('\\', '/')

    /**
     * 可回看的一屏（判据④「它当时怎么理解的」）。
     *
     * 三栏顺序固定：任务单 → 回执 → 收工对照。回看的人要的是"当时挂的哪张单子、
     * 它怎么说的、最后动了什么"，顺序换了就得自己拼。
     */
    fun reviewText(order: ReadFirstOrder?, receipt: PreReadReceipt?, diff: ScopeDiff?): List<String> {
        val out = mutableListOf<String>()
        out += "任务单：${order?.takeIf { it.taskKey.isNotBlank() }?.let { o ->
            "${if (o.title.isNotBlank()) o.title else "(无标题)"}｜必读 ${o.items.size} 条｜" +
                "要回执=${if (o.requireReceipt) "显式" else "否"}"
        } ?: "(未附清单)"}"
        for (item in order?.items.orEmpty()) out += "  · ${item.render()}"
        out += "回执：${receipt?.let { r ->
            "读了 ${r.readLines.size} 条｜理解 ${r.understanding.take(40)}｜打算改 ${r.plannedFiles.size} 个文件"
        } ?: "(未交)"}"
        receipt?.readLines?.forEach { out += "  · 读了 $it" }
        if (receipt != null) out += "  · 理解 ${receipt.understanding}"
        receipt?.plannedFiles?.forEach { out += "  · 打算改 $it" }
        if (diff != null) {
            out += "收工对照：对上 ${diff.matched.size}｜说改未改 ${diff.notTouched.size}｜清单外 ${diff.outOfScope.size}"
            diff.notTouched.forEach { out += "  ! 说改未改 $it" }
            diff.outOfScope.forEach { out += "  ! 清单外改动 $it" }
        }
        return out
    }
}
