// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百一十批 · 纪元更新（epoch）：**一周动一次，纪内钉死**。
//
// 三条规矩与它们的由来：
//
//   1. **一周一纪元，周一起算**。纪内判据快照固定，只许攒证据，不许热改。
//      为什么是周：证据要攒够才有可比性；为什么卡在周一：把「什么时候能换」从人的
//      临场判断里拿走，进化就没有「顺手调一下」的空间。
//   2. **只在纪元边界换表**，走的是 证据→候选→回放（309）→锚定（本单）→御案 这条链。
//      [CriteriaSnapshot] 的构造器会替表里每条判据再核一遍不可进化清单，
//      所以「把硬闸装进新表」这条路在类型上就走不通（负例由结构兜住，不靠运气）。
//   3. **严重缺陷可即时回滚上一纪元快照**。回滚**不是进化**：它没有审批入参、不查纪元边界、
//      不进候选通道——一旦让它走审批，止损就变成「等一个人点头」。
//
// 每纪元留一行纪元账：`纪元号｜候选数｜通过数｜否决数｜换表与否（三态之一）`。
// 三态口径照 309：`已应用`／`已回滚`／`显式无变更`，**不许第四态**（「再讨论一轮」不是结论）。
//
// 零 Android／零 IO／零时钟：`now` 由调用方给。纪元号是纯整数除法——若改成读一次表时
// 问「今天几号」，同一张表会在午夜前后给出两个纪元号。
package com.example.zhengdao.baopu.policy

import java.time.LocalDate

private const val DAY_MS = 24L * 3_600_000L

/** 1970-01-01 是周四；加 4 让 7 天桶正好切在周一（1970-01-05 是纪元 0 的周一）。 */
private const val FIRST_MONDAY_DAYS = 4L

/**
 * 一个纪元＝一个自然周（周一起）。[ordinal] 是从 1970-01-05 那周起的单调序号，
 * [monday] 只是给人看的标签。
 */
internal data class EpochId(val ordinal: Long, val monday: LocalDate) {

    fun label(): String = "E$ordinal（周一 $monday 起）"

    companion object {
        /** 由时刻算纪元号（不读时钟）。 */
        fun of(nowMs: Long): EpochId {
            val days = Math.floorDiv(nowMs, DAY_MS)
            val ordinal = Math.floorDiv(days - FIRST_MONDAY_DAYS, 7)
            return EpochId(ordinal = ordinal, monday = LocalDate.ofEpochDay(ordinal * 7 + FIRST_MONDAY_DAYS))
        }
    }
}

/** 表里的一条判据：给人读的那两句，加它当初要动的锚点。 */
internal data class CriterionEntry(
    val id: String,
    val description: String,
    val target: String,
    /** 它是哪一纪被准入的（换表时看得见它是不是「攒了一周才动」）。 */
    val admittedInEpoch: Long,
)

/**
 * 一纪内生效的判据快照：建好就不再改。
 *
 * 构造期自检（任何一条不满足**根本建不出来**）：
 * - 序号必须 ≥1（0 是「还没建过」，不是快照）；
 * - 判据 id 不许重复；
 * - 每条判据的锚点都不许命中不可进化清单——想绕过闸把硬闸装进新表＝构造失败。
 */
internal data class CriteriaSnapshot(
    val epoch: EpochId,
    val seq: Long,
    val criteria: List<CriterionEntry>,
) {
    init {
        require(seq >= 1L) { "快照序号必须 ≥1" }
        val dup = criteria.map { it.id }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        require(dup.isEmpty()) { "同一张表里判据 id 重复：${dup.sorted()}" }
        val hit = criteria.firstOrNull { ImmutableCriteria.match(it.target) != null }
        require(hit == null) {
            "新表想装进不可进化清单里的判据：${hit?.id} 要动" +
                "「${ImmutableCriteria.match(hit?.target ?: "")?.cn}」——这三条只有用户亲手改才行"
        }
    }

    companion object {
        /** 建表唯一入口（序号由调用方递增：本文件既不读时钟也不存计数器）。 */
        fun of(epoch: EpochId, seq: Long, criteria: List<CriterionEntry>): CriteriaSnapshot =
            CriteriaSnapshot(epoch = epoch, seq = seq, criteria = criteria.toList())
    }
}

/** 换表结果（可读原因，没有分数）。 */
internal data class SwapResult(
    val swapped: Boolean,
    val reason: String,
)

/** 回滚结果。回滚**没有审批入参**：结构上就走不到「等一个人点头」那一步。 */
internal data class RollbackResult(
    val rolledBack: Boolean,
    val reason: String,
)

/** 纪元账「换表与否」的三态（照 309，闭集，不许第四态）。 */
internal enum class EpochState(val cn: String) {
    APPLIED("已应用"),
    ROLLED_BACK("已回滚"),
    NO_CHANGE("显式无变更"),
}

/** 纪元账的一行。列就是派工单那五列，不加不减。 */
internal data class EpochLedgerRow(
    val epoch: Long,
    val candidates: Int,
    val adopted: Int,
    val refused: Int,
    val state: EpochState,
) {
    /** 落盘形态：`纪元号｜候选｜通过｜否决｜三态`。 */
    fun wire(): String = "$epoch|$candidates|$adopted|$refused|${state.name}"

    /** 计数自洽：通过＋否决超过候选数，说明有东西在闸外被放行过。 */
    fun consistent(): Boolean =
        candidates >= 0 && adopted >= 0 && refused >= 0 && adopted + refused <= candidates
}

/** 纪元的当前态与账。**唯一换表写口**是 [swap]；回滚另走 [rollbackToPrevious]。 */
internal object EpochRuntime {

    @Volatile
    private var current: CriteriaSnapshot? = null

    @Volatile
    private var previous: CriteriaSnapshot? = null

    /** 每纪元的三个计数：候选／通过／否决。 */
    private val counters = LinkedHashMap<Long, IntArray>()

    /**
     * 回滚事件账（2026-10-31 用户裁定 Q3：回滚零参数、不问审批，**但必须落一行账**）。
     *
     * 为什么要单独记：`state = ROLLED_BACK` 只挂在「当前纪元」那一行上，
     * 下一个纪元开始后它就被覆盖了 —— 事后问「什么时候退回去的、从哪退到哪」会查不到。
     * 这里记成**只增不改的事件序列**。
     */
    private val rollbackEvents = ArrayList<String>()

    @Volatile
    private var state: EpochState = EpochState.NO_CHANGE

    /** 当前生效快照（没换过表就是 null，由调用方决定退回哪份默认）。 */
    fun currentSnapshot(): CriteriaSnapshot? = current

    /** 上一纪元快照（回滚的目标）。 */
    fun previousSnapshot(): CriteriaSnapshot? = previous

    fun ledgerState(): EpochState = state

    /** 回滚事件账（按发生顺序，只增不改）。空 = 从没回滚过。 */
    fun rollbackLedger(): List<String> = ArrayList(rollbackEvents)

    /**
     * 记一次准入读数进纪元账。
     *
     * 通过＝ADMIT；否决＝REFUSED 与 RETURNED 两类——退回候选区这一轮没有采纳，
     * 但它与硬拦的差别在 [CheckItem] 的逐条原因里给人看，不藏进数字。
     */
    fun observe(admission: Admission, nowMs: Long) = synchronized(this) {
        val bucket = counters.getOrPut(EpochId.of(nowMs).ordinal) { IntArray(3) }
        bucket[0] += 1
        if (admission.admitted()) {
            bucket[1] += 1
        } else {
            bucket[2] += 1
        }
        Unit
    }

    /**
     * 换表：**只在纪元边界**发生。新表必须属于「此刻所在的那一纪」，且必须比当前表新；
     * 同纪内再换＝热改，倒退着装＝那不是进化（该走回滚）。
     */
    fun swap(next: CriteriaSnapshot, nowMs: Long): SwapResult = synchronized(this) {
        val blocked = rejectReason(next, nowMs)
        if (blocked != null) {
            return@synchronized SwapResult(false, blocked)
        }
        val cur = current
        previous = cur
        current = next
        state = EpochState.APPLIED
        if (cur == null) {
            SwapResult(true, "首张判据表生效：${next.epoch.label()}（${next.criteria.size} 条）")
        } else {
            SwapResult(true, "换表：${cur.epoch.label()} → ${next.epoch.label()}（${next.criteria.size} 条）")
        }
    }

    /** 换表的三道拒绝；返回 null 才许换。 */
    private fun rejectReason(next: CriteriaSnapshot, nowMs: Long): String? {
        val now = EpochId.of(nowMs)
        if (next.epoch.ordinal > now.ordinal) {
            return "未到纪元边界：新表是 ${next.epoch.label()}，此刻还在 ${now.label()}"
        }
        val cur = current ?: return null
        if (next.epoch.ordinal == cur.epoch.ordinal) {
            return "纪内不许热改：${cur.epoch.label()} 的快照已固定（序号 ${cur.seq}），" +
                "本纪元只许攒证据；要动判据等周一边界"
        }
        if (next.epoch.ordinal < cur.epoch.ordinal) {
            return "不许把表往回装：${next.epoch.label()} 早于当前 ${cur.epoch.label()}。" +
                "倒退是**回滚**（走 rollbackToPrevious：即时、不走审批），不是进化"
        }
        return null
    }

    /**
     * 即时回滚上一纪元快照。三条纪律写死在这里：**不查纪元边界**（严重缺陷不等周一）、
     * **不接受审批参数**（回滚不是进化，走审批＝把止损交给人点头）、**不新增判据**（装的还是那张旧表）。
     */
    fun rollbackToPrevious(): RollbackResult = synchronized(this) {
        val cur = current
        val prev = previous
        if (cur == null || prev == null) {
            return@synchronized RollbackResult(
                false,
                "没有上一纪元快照可回（当前：${cur?.epoch?.label() ?: "无表"}）",
            )
        }
        current = prev
        previous = null
        state = EpochState.ROLLED_BACK
        // Q3：回滚必须落一行账（事件序列只增不改，见 rollbackEvents 注释）
        rollbackEvents += "${cur.epoch.label()}→${prev.epoch.label()}"
        RollbackResult(true, "已回滚：${cur.epoch.label()} → ${prev.epoch.label()}（即时，未走进化通道）")
    }

    /** 显式无变更：这一纪什么都没动也要留一行（否则分不清「没改」与「忘了记」）。 */
    fun markNoChange() = synchronized(this) {
        state = EpochState.NO_CHANGE
        Unit
    }

    /** 把此刻所在纪元的账结出来（一行）。 */
    fun ledgerRow(nowMs: Long): EpochLedgerRow = synchronized(this) {
        rowOf(EpochId.of(nowMs).ordinal)
    }

    /** 全部账行（按纪元号升序），供落盘与门禁比对。 */
    fun ledgerRows(): List<EpochLedgerRow> = synchronized(this) {
        counters.keys.sorted().map { rowOf(it) }
    }

    private fun rowOf(ordinal: Long): EpochLedgerRow {
        val bucket = counters[ordinal] ?: IntArray(3)
        val rowState = if (ordinal == current?.epoch?.ordinal) state else EpochState.NO_CHANGE
        return EpochLedgerRow(
            epoch = ordinal,
            candidates = bucket[0],
            adopted = bucket[1],
            refused = bucket[2],
            state = rowState,
        )
    }

    /** 测试／进程内重置用；生产不调用（照 `PolicyRuntime.reset` 的口径）。 */
    fun reset() = synchronized(this) {
        current = null
        previous = null
        counters.clear()
        state = EpochState.NO_CHANGE
        Unit
    }
}

/** 纪元账的读侧纪律（落盘文本由 CI 门禁比对，这里先钉住结构上的两条）。 */
internal object EpochLedger {

    /** 每纪元只许一行（同一周记两行＝有人在补写结论）。 */
    fun duplicateEpochs(rows: List<EpochLedgerRow>): List<Long> =
        rows.groupingBy { it.epoch }.eachCount().filterValues { it > 1 }.keys.sorted()

    /** 计数越界＝闸外放行过东西。 */
    fun inconsistentRows(rows: List<EpochLedgerRow>): List<EpochLedgerRow> = rows.filterNot { it.consistent() }
}
