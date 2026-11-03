// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 三百三十批文件（agent/QuietMode.kt）的**装配闸**——`QuietMode` 定型之后一直生产零引用，
// 弹卡决策从没问过它；本文件把"弹卡前先问免打扰"接成一段可直测的纯逻辑，落账归引擎。
//
// 用户 2026-11-02（派工单原话）：
//   左相弹卡前查 QuietMode（静默期／夜间／专注期 ⇒ 延后并记一条"因免打扰延后"）；
//   **静默有上限**，超上限 ⇒ 转"检查点等待"，不许变成"永久不问"。
//
// 五条纪律（为什么长成下面这个样子）：
// 1. **判定与队列都从 QuietMode 现成的东西算**：静默＝[QuietMode.active]（授权），夜间／专注＝
//    [SessionShape.hush]（330 补充页"时段与节奏"那一格），静默上限＝[QuietLimits.MAX_MS]
//    （授权 ≤4 小时那把尺，不新造第二个 4 小时）。本文件不抄任何一处判据。
// 2. **延后不是丢弃**：进队列的每件事都带着原卡（[DeferredAsk.request]），静默结束按**入队序**
//    原样补问（判据②"按序补问（不丢）"）；本文件没有任何"清空队列"的口。
// 3. **静默有上限**：每件延后都带到期时刻（[DeferredAsk.dueMs] ＝ 首延后＋4 小时）——
//    夜间会天亮、授权会到期、专注期没有天然终点，到期尺是唯一的兜底；超上限的那件
//    在下一个检查点**必现**（哪怕还在静默期），这一格就是"不许永久不问"的机器依据。
// 4. **检查点等待也是账，不是垃圾桶**：队列满员（[QuietGateLimits.MAX_DEFERRED]）之后来的
//    事照样进队列，只是挂上 [DeferredAsk.checkpoint] 标记并换一行更响的账——
//    它在下一个检查点（她回话／下一张卡）必现，与"按序补问"共用同一条出口。
// 5. **本文件仍然一个写口都没有**：IO 与时钟归引擎（装配层），这里只有纯函数＋一个
//    由调用方串行驱动的队列（引擎单活跃门内驱动，v1 一次只会话，见 `RunConcurrency`）。
//
// 第三百八十八单补一笔（2026-11-03）：静默多了一个源——**离场值守＋锁屏**（388 派工单：
// "锁屏期间不弹卡（进队列）"）。实现只是给 [onAsk]／[takeNext]／[comebackLine] 加一个
// 默认 false 的 [awayLocked] 参数：330 的三格判据一个字没改，330/379 的既有测试原样绿；
// 凭据多写一行"离场值守·锁屏"。
package com.example.zhengdao.baopu.agent

/** 免打扰延后的容量上限。改这个数＝改"堆多少件必须升级成检查点等待"的制度，不许调用处各写一把。 */
internal object QuietGateLimits {

    /**
     * 延后队列最多几件（3 ＝ 与问询预算同一量级：第 4 件起转检查点等待）。
     *
     * 为什么不是"满了就拒"：拒了就是丢——判据②点名"不丢"。满员只改变**记账的响度**
     * （转检查点等待）与现身的时点（下一个检查点必现），从不变更"这件事要回到她面前"。
     */
    const val MAX_DEFERRED = 3
}

/** 弹卡闸的裁决（闭集三格；"既不问也不记"的第四格不存在）。 */
internal enum class QuietCardVerdict(val wire: String, val cn: String) {

    /** 此刻不静默 ⇒ 照常弹（调用方先按序补问积压，再出这张新卡）。 */
    ASK("ask", "照常弹"),

    /** 静默中且队列未满 ⇒ 不弹、进补问队列、记一条"因免打扰延后"。 */
    DEFER("defer", "因免打扰延后"),

    /** 静默中且队列已满 ⇒ 不弹、照样进队列（挂检查点标记）、记一条"转检查点等待"。 */
    CHECKPOINT_WAIT("checkpoint_wait", "转检查点等待"),
}

/**
 * 一次裁决的产物：裁决＋落账行＋进队列的那件（三样同源，调用方没有机会只取一半）。
 *
 * [line] 在 [QuietCardVerdict.ASK] 时为空串——"照常弹"没有要记的账，
 * 空串而不是 null 是为了调用方 `log(outcome.line)` 一行写完。
 */
internal data class QuietCardOutcome(
    val verdict: QuietCardVerdict,
    val line: String,
    val deferred: DeferredAsk?,
)

/**
 * 一件被静默压住的事（原卡原样带着，补问时一字不改地重递）。
 *
 * [dueMs] 是"静默对它的上限"：过了这一刻，哪怕授权还在、天还没亮，
 * 下一件检查点就是它现身的地方——免打扰不许把人永久关在门外（330 纪律 3 的排队版）。
 */
internal data class DeferredAsk(
    /** 主键（补问账与落账行都指它；`quiet:<时刻>#<序>`，同毫秒两件也分得开）。 */
    val id: String,
    /** 首次延后的时刻（重延不改写它：等待时长从第一压起算）。 */
    val atMs: Long,
    /** 静默上限：[atMs]＋[QuietLimits.MAX_MS]。 */
    val dueMs: Long,
    /** 凭什么压的（"免打扰授权中""夜间时段""专注期（连发指令中）"，多源用＋相连）。 */
    val why: String,
    /** 队列满员之后来的那件＝true：下一个检查点必现，不等静默结束。 */
    val checkpoint: Boolean,
    /** 原卡（[AskUserRequest] 原样；补问走原出口，不再过闸）。 */
    val request: AskUserRequest,
) {
    /** 卡面上那一句话（日志与补问账里指名用的；问题组取第一问）。 */
    val headline: String
        get() = request.questions.firstOrNull()?.title?.takeIf { it.isNotBlank() } ?: request.question
}

/**
 * 左相弹卡闸（330 的 [QuietMode] 在"要弹卡那一刻"的唯一询问口；纯逻辑，零 IO、零时钟）。
 *
 * 两个口子，配对使用：
 * - [onAsk]——**每张卡出之前**问一次：静默中就压进队列并给出落账行；
 * - [takeNext]——**每个检查点**（她回话／下一张卡已获准要弹）取下一件该补问的，
 *   取出的顺序恒等于入队顺序（判据②"按序补问"）。
 *
 * 方法做成 `synchronized`：onAsk 在工具线程（ask_user 沉在 `askUser` 里）、takeNext 在
 * 引擎线程（轮次入口），两线程只许在队列这一处会合；粒度是一把内部锁，别无共享。
 */
internal class QuietCardGate {

    private val queue = ArrayDeque<DeferredAsk>()
    private var seq: Int = 0

    /** 队列里现在压着几件（含检查点等待）。 */
    val size: Int get() = synchronized(this) { queue.size }

    /** 队列里检查点等待的有几件（[stateLine] 用）。 */
    val checkpointCount: Int get() = synchronized(this) { queue.count { it.checkpoint } }

    /**
     * 一张卡出之前问闸。
     *
     * 静默＝[QuietMode.active]（授权）或 [SessionShape.hush]（夜间／专注）或 [awayLocked]
     * （388：离场值守＋锁屏 ⇒ 不弹卡，进队列）。
     * 满员与否只决定挂 [DeferredAsk.checkpoint] 与账行措辞——队列满了也**不拒**（纪律 4）。
     */
    fun onAsk(
        grant: QuietGrant?,
        shape: SessionShape,
        nowMs: Long,
        request: AskUserRequest,
        awayLocked: Boolean = false,
    ): QuietCardOutcome {
        if (!hushed(grant, shape, nowMs, awayLocked)) return QuietCardOutcome(QuietCardVerdict.ASK, "", null)
        val item = synchronized(this) {
            enqueue(grant, shape, nowMs, request, queue.size >= QuietGateLimits.MAX_DEFERRED, awayLocked)
        }
        val verdict = if (item.checkpoint) QuietCardVerdict.CHECKPOINT_WAIT else QuietCardVerdict.DEFER
        val line = if (item.checkpoint) {
            "转检查点等待｜${item.headline}｜${item.why}，延后已满 ${QuietGateLimits.MAX_DEFERRED} 件" +
                "，下一检查点必现（不许永久不问）"
        } else {
            "因免打扰延后｜${item.headline}｜凭=${item.why}｜静默上限 ms ${item.dueMs}"
        }
        return QuietCardOutcome(verdict, line, item)
    }

    /**
     * 检查点取下一件该补问的（取到即出队）。
     *
     * 可现身＝不静默（静默结束，整队按序放行）或这一件**本来就等检查点**（满员转来的）
     * 或这一件**已过静默上限**（天还没亮也得到点现身——纪律 3 的兜底格）。
     * 队首不可现身就停：后件的顺序不许越过前件（判据②"按序"的另一半）。
     */
    fun takeNext(grant: QuietGrant?, shape: SessionShape, nowMs: Long, awayLocked: Boolean = false): DeferredAsk? =
        synchronized(this) {
            val head = queue.firstOrNull() ?: return@synchronized null
            val due = nowMs >= head.dueMs
            if (hushed(grant, shape, nowMs, awayLocked) && !head.checkpoint && !due) return@synchronized null
            queue.removeFirst()
        }

    /** 这一件是凭什么现身的（补问账行的开头；调用方在取出**之后**问，顺序天然对上）。 */
    fun comebackLine(
        item: DeferredAsk,
        grant: QuietGrant?,
        shape: SessionShape,
        nowMs: Long,
        awayLocked: Boolean = false,
    ): String {
        val reason = when {
            !hushed(grant, shape, nowMs, awayLocked) -> "静默结束按序补问"
            item.checkpoint -> "检查点补问（转等待的那件）"
            else -> "静默超上限，检查点补问"
        }
        return "补问｜${item.id}｜$reason｜${item.headline}"
    }

    /** 队列快照（"每笔可查"的取用口；调用方只读，不许从这里改队列）。 */
    fun snapshot(): List<DeferredAsk> = synchronized(this) { queue.toList() }

    /**
     * 一行可查的静默状态（判据③"一句话进静默可查"的读数口；恒一行）。
     *
     * 三样都在：授权态（含还剩几分钟）／延后几件（其中检查点几件）／最老一件等了多久、
     * 上限多少。队列空且未授权时也照印——"没有静默"本身就是一个可查的答案。
     */
    fun stateLine(grant: QuietGrant?, nowMs: Long): String {
        val (count, oldestAtMs) = synchronized(this) {
            queue.size to queue.minOfOrNull { it.atMs }
        }
        val grantCell = when (val state = QuietMode.stateOf(grant, nowMs)) {
            QuietState.ACTIVE ->
                "免打扰中（还剩 ${QuietMode.remainingMs(grant, nowMs) / QuietLimits.MINUTE_MS} 分钟）"
            else -> state.cn
        }
        val queueCell = if (count == 0) {
            "延后 0 件"
        } else {
            val waited = (nowMs - oldestAtMs!!) / QuietLimits.MINUTE_MS
            "延后 $count 件（检查点等待 ${checkpointCount}）｜最老一件已等 $waited/${QuietLimits.MAX_MINUTES} 分钟"
        }
        return "[免打扰] $grantCell｜$queueCell"
    }

    /** 入队（锁内调用；主键的序号在这里只增不减）。 */
    private fun enqueue(
        grant: QuietGrant?,
        shape: SessionShape,
        nowMs: Long,
        request: AskUserRequest,
        checkpoint: Boolean,
        awayLocked: Boolean = false,
    ): DeferredAsk {
        seq += 1
        val item = DeferredAsk(
            id = "quiet:$nowMs#$seq",
            atMs = nowMs,
            dueMs = nowMs + QuietLimits.MAX_MS,
            why = quietWhy(grant, shape, nowMs, awayLocked),
            checkpoint = checkpoint,
            request = request,
        )
        queue.addLast(item)
        return item
    }

    companion object {

        /** 凭什么压的（多源按"离场→授权→夜间→专注"排，命中几个连几个；四格全空到不了这里）。 */
        private fun quietWhy(grant: QuietGrant?, shape: SessionShape, nowMs: Long, awayLocked: Boolean = false): String =
            buildList {
                if (awayLocked) add("离场值守·锁屏")
                if (QuietMode.active(grant, nowMs)) add("免打扰授权中")
                if (shape.atNight) add("夜间时段")
                if (shape.busy) add("专注期（连发指令中）")
            }.joinToString("＋")

        /** 此刻静默吗（离场锁屏／授权／形状，任一为真即静默；[QuietMode.active] 是唯一授权判据）。 */
        private fun hushed(grant: QuietGrant?, shape: SessionShape, nowMs: Long, awayLocked: Boolean = false): Boolean =
            awayLocked || QuietMode.active(grant, nowMs) || shape.hush
    }
}
