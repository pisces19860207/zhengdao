// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 B · 预读回执闸：**状态板**（按 taskKey 攒本任务内的两张纸 + 落账）。
//
// ## 回执"本任务内有效"这条是怎么落的
// 用户裁定：回执一旦提交，**本任务内有效**（续跑不用重交，换任务要重交）。
// 所以板子的键是 `taskKey`（不是 runId）：续跑换一个 runId，taskKey 不变 ⇒ 回执还在；
// 换任务 ⇒ taskKey 变 ⇒ 查不到回执 ⇒ 重新要。这一条不靠"记住上次交过"，靠键本身。
//
// ## 为什么所有集合都有界（CAP）
// 板子是进程级单例，跟着 App 活。任务数无上限 ⇒ 状态无上限。有界 FIFO 的代价是
// "极老任务的态被挤掉"，那时它拿到的是一句「未交回执」——比内存无限涨要好，
// 而且账在盘上（[PreReadLedger]），挤掉的只是态，不是痕。
//
// ## 这一层不抛
// 外面套的是 [PreReadWiring] 的 fail-open（判据⑥「超时/异常 ⇒ 放行并记账」）。
// 板子自己保持简单：只做纯状态操作，真出错了由那一侧兜住。
package com.example.zhengdao.baopu.preread

import com.example.zhengdao.baopu.plan.PlanPledgeRules

/** 本任务内的预读状态（派单侧的单子、承接侧的回执、两条"要求"来源、已落的提示行）。 */
internal object PreReadBoard {

    /** 每个集合的上界（有界 FIFO，见文件头）。 */
    private const val CAP = 64

    /** 收到不认识的键时给的一个占位（宁可按①处理，也不拿空键去猜是哪个任务）。 */
    private const val NO_TASK = ""

    private val orders = LinkedHashMap<String, ReadFirstOrder>()
    private val receipts = LinkedHashMap<String, PreReadReceipt>()
    private val autoArmed = LinkedHashSet<String>()
    private val declared = LinkedHashSet<String>()

    /** 已落过「本任务未要求预读」的键（态①一个任务最多一行，不刷屏）。 */
    private val silentNoted = LinkedHashSet<String>()

    /** 已落过「放行」的键（态③第一次开闸记一行，之后每笔写不再记）。 */
    private val passNoted = LinkedHashSet<String>()

    /** 已落过「拦住」的「任务×工具」（引擎对 Failed 会重试一遍，不许攒两行重复）。 */
    private val blockNoted = LinkedHashSet<String>()

    /** 已提示过「有必读清单」的键（态②一句，不每笔写都念）。 */
    private val hinted = LinkedHashSet<String>()

    private var seq = 0

    // ── 派单侧 ──────────────────────────────────────────────────────────────

    /**
     * 附一张任务单（判据①）。**不合规不落**：返回 issue 列表，空列表＝收下。
     *
     * 为什么坏了不收而不是"收下半张"：清单是要逐条对账的，收下一条重复／超长／空目标的
     * 条目，收工对照就会拿它去比——假越界比没有越界更难查。
     */
    @Synchronized
    fun attach(order: ReadFirstOrder, now: Long = System.currentTimeMillis()): List<OrderIssue> {
        val issues = PreReadRules.orderIssues(order)
        if (issues.isNotEmpty()) return issues
        boundedPut(orders, order.taskKey, order)
        PreReadLedger.append(
            row(now, order.taskKey, PreReadEventKind.ORDER,
                "必读清单 ${order.items.size} 条｜要回执=${if (order.requireReceipt) "显式" else "否"}",
                order.items.map { it.render() }),
        )
        return emptyList()
    }

    /** 长程/跨会话 ⇒ 自动要求回执（裁定点名的第二来源；判定用 274 的 isLongTask，不另造）。 */
    @Synchronized
    fun armByLongTask(
        taskKey: String,
        itemCount: Int,
        crossSession: Boolean,
        now: Long = System.currentTimeMillis(),
    ): Boolean {
        if (taskKey.isBlank()) return false
        if (!PlanPledgeRules.isLongTask(itemCount, crossSession)) return false
        if (!boundedAdd(autoArmed, taskKey)) return false
        // 账上只记**改变了态**的那一次：没附清单 ⇒ 这一格只是先把"要求"记住
        // （清单后附上也生效），不写"闸启用"那种当时还不成立的话；
        // 派单侧已经显式勾了 ⇒ 来源写"显式"，自动这条不必重复立项。
        val order = orders[taskKey]
        if (order != null && !order.requireReceipt) {
            PreReadLedger.append(
                row(now, taskKey, PreReadEventKind.ARM,
                    "自动要求回执：长程或跨会话（${itemCount} 步${if (crossSession) "，跨会话" else ""}）⇒ 闸启用"),
            )
        }
        return true
    }

    // ── 承接侧 ──────────────────────────────────────────────────────────────

    /**
     * 交回执（判据②）。**三行缺一行＝没交**：返回 gaps、不落账、不解锁。
     *
     * 为什么不齐就不落：一旦落了"缺行的回执"，态③就从"拦住"变成"放行"，
     * 那这条闸等于可以用一句"我读了"骗开——留痕也没留全。
     */
    @Synchronized
    fun submit(receipt: PreReadReceipt, now: Long = System.currentTimeMillis()): List<ReceiptGap> {
        val gaps = PreReadRules.receiptGaps(receipt)
        if (gaps.isNotEmpty()) return gaps
        val clean = receipt.copy(
            readLines = receipt.readLines.map { it.trim() }.filter { it.isNotEmpty() },
            plannedFiles = receipt.plannedFiles.map { it.trim() }.filter { it.isNotEmpty() },
            understanding = receipt.understanding.trim(),
            atMs = now,
            receiptId = "pr:$now:${++seq}",
        )
        boundedPut(receipts, clean.taskKey, clean)
        PreReadLedger.append(
            row(now, clean.taskKey, PreReadEventKind.RECEIPT,
                "回执三行：读了 ${clean.readLines.size} 条｜打算改 ${clean.plannedFiles.size} 个文件",
                clean.readLines.map { "读了 $it" } + "理解 ${clean.understanding}" +
                    clean.plannedFiles.map { "打算改 $it" }),
        )
        return emptyList()
    }

    /**
     * 声明"本任务不要求回执"（那句可操作提示给的第二条出口）。
     *
     * 只把闸退回 ②（提示不拦），**不动派单侧的单子**：单子留在账里，回看时两张纸都还在，
     * 收工照样对照。声明不是销毁。
     */
    @Synchronized
    fun declareNotRequired(taskKey: String, why: String = "", now: Long = System.currentTimeMillis()): Boolean {
        if (taskKey.isBlank()) return false
        if (!boundedAdd(declared, taskKey)) return false
        PreReadLedger.append(row(now, taskKey, PreReadEventKind.DECLARED,
            "声明不要求回执${if (why.isNotBlank()) "：$why" else ""}（单子照留，收工照样对照）"))
        return true
    }

    // ── 闸 ──────────────────────────────────────────────────────────────────

    /** 这个任务现在哪一态（三态的合成只走 [PreReadRules.mode] 这一处）。 */
    @Synchronized
    fun modeFor(taskKey: String): PreReadMode =
        PreReadRules.mode(orders[taskKey], taskKey in autoArmed, taskKey in declared)

    /** 本任务是否已有**齐**的回执（态③放行的凭据）。 */
    @Synchronized
    fun hasReceipt(taskKey: String): Boolean = receipts[taskKey] != null

    /**
     * 一笔工具调用的结论（含该落的账行）。放行与否只看 [PreReadRules.gate]，
     * 这里只加"落痕"这一侧：态①首次一行、拦住一行、首次开闸一行。
     */
    @Synchronized
    fun gateFor(taskKey: String, tool: String, isWrite: Boolean, now: Long = System.currentTimeMillis()): GateResult {
        val mode = modeFor(taskKey)
        val r = PreReadRules.gate(mode, isWrite, taskKey in receipts, tool)
        when {
            r.blocked -> if (boundedAdd(blockNoted, "$taskKey|$tool")) {
                PreReadLedger.append(row(now, taskKey, PreReadEventKind.BLOCK, r.message, listOf("工具 $tool")))
            }
            isWrite && mode == PreReadMode.OFF && boundedAdd(silentNoted, taskKey) ->
                PreReadLedger.append(row(now, taskKey, PreReadEventKind.SILENT, "本任务未要求预读（闸不启用）"))
            isWrite && mode == PreReadMode.ENFORCED && boundedAdd(passNoted, taskKey) ->
                PreReadLedger.append(row(now, taskKey, PreReadEventKind.PASS, "回执已交 ⇒ 放行（首笔写：$tool）"))
        }
        return r
    }

    /**
     * 态②开工那一句提示（一个任务最多提示一次；不拦，只说）。
     * 返回 null＝不需要提示（态①／已提示过／没有单子）。
     */
    @Synchronized
    fun hintAtStart(taskKey: String): String? {
        if (modeFor(taskKey) != PreReadMode.NOTIFY) return null
        val order = orders[taskKey] ?: return null
        if (!boundedAdd(hinted, taskKey)) return null
        return "有必读清单，建议先读（${order.items.size} 条）：${order.items.firstOrNull()?.render().orEmpty()}"
    }

    /**
     * 收工对照（判据③）。**只落账、只提出，不阻断**——返回的 diff 给调用方 log 一句。
     *
     * 没有单子也没有回执 ⇒ null（无事可对照，不编一行空账）。
     */
    @Synchronized
    fun closeout(taskKey: String, touched: List<String>, now: Long = System.currentTimeMillis()): ScopeDiff? {
        val order = orders[taskKey]
        val receipt = receipts[taskKey]
        if (order == null && receipt == null) return null
        val diff = receipt?.let { PreReadRules.scopeDiff(it.plannedFiles, touched) }
        val cover = order?.let { PreReadRules.readCoverage(it, receipt) }
        val lines = mutableListOf<String>()
        if (cover != null) {
            lines += "必读清单：对到 ${cover.covered.size} 条，未读到 ${cover.uncovered.size} 条"
            cover.uncovered.forEach { lines += "  ? 未读到 $it" }
        }
        if (diff != null) {
            lines += "改动对照：对上 ${diff.matched.size}｜说改未改 ${diff.notTouched.size}｜清单外 ${diff.outOfScope.size}"
            diff.notTouched.forEach { lines += "  ! 说改未改 $it" }
            diff.outOfScope.forEach { lines += "  ! 清单外改动 $it" }
        }
        PreReadLedger.append(row(now, taskKey, PreReadEventKind.CLOSEOUT,
            "收工对照：本轮动过 ${touched.size} 个文件${if (diff?.outOfScope.isNullOrEmpty()) "" else "｜有提出项"}",
            lines))
        return diff
    }

    // ── 回看 ────────────────────────────────────────────────────────────────

    /** 一屏回看（判据④「它当时怎么理解的」）：任务单 → 回执 →（给了 touched 就再加）对照。 */
    @Synchronized
    fun review(taskKey: String, touched: List<String> = emptyList()): List<String> =
        PreReadRules.reviewText(
            orders[taskKey],
            receipts[taskKey],
            if (touched.isEmpty()) null else PreReadRules.scopeDiff(receipts[taskKey]?.plannedFiles.orEmpty(), touched),
        )

    /** 当前挂着的单子（探针/设置页读；不暴露可变的 map）。 */
    @Synchronized
    fun orderFor(taskKey: String): ReadFirstOrder? = orders[taskKey]

    /** 当前生效的回执（同上）。 */
    @Synchronized
    fun receiptFor(taskKey: String): PreReadReceipt? = receipts[taskKey]

    /** 测试专用：清空全部态与账（谁用谁还）。 */
    @Synchronized
    fun resetForTest() {
        orders.clear()
        receipts.clear()
        autoArmed.clear()
        declared.clear()
        silentNoted.clear()
        passNoted.clear()
        blockNoted.clear()
        hinted.clear()
        seq = 0
        PreReadLedger.resetForTest()
    }

    // ── 内部 ────────────────────────────────────────────────────────────────

    /**
     * 认领一个键（已认过 ⇒ false，不再落第二行）。有界 FIFO：超界从最老的开始挤。
     * 新键一律 true——这里不许反。
     */
    private fun boundedAdd(set: LinkedHashSet<String>, key: String): Boolean {
        if (key == NO_TASK || !set.add(key)) return false
        while (set.size > CAP) set.remove(set.first())
        return true
    }

    /** 存一份态（按插入顺序有界：挤掉最老那个任务）。 */
    private fun <T> boundedPut(map: LinkedHashMap<String, T>, key: String, value: T) {
        map[key] = value
        while (map.size > CAP) map.remove(map.keys.first())
    }

    private fun row(now: Long, taskKey: String, kind: PreReadEventKind, text: String, detail: List<String> = emptyList()) =
        PreReadRow(id = "pr:$now:${++seq}", atMs = now, taskKey = taskKey, kind = kind, text = text, detail = detail)
}
