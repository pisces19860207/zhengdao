// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第九十二批 · 工具调度：**读并行 ＋ 写单飞 ＋ 工具级超时/取消**（派工单 §1）。
//
// 病灶（审计 P2-9 的更正在先，本批是它的正面解法）：工具执行此前是**一条单线程串行**——
// 一个慢工具（网络/大文件/等用户）会把后面所有工具堵住，而写工具又必须保持顺序。
// 「一个池 ＋ 排队」同时承担了两种互斥需求，结果两边都不满意：
// 慢读拖死整批，写又不敢并行。
//
// 本文件只做三件事（**纯判据 ＋ 一个有界执行器**）：
// 1. 分类：把每个调用归到「读并行」或「串行」两条 lane（[laneOf]，依据是工具元数据
//    `readOnly` / `parallelSafe` / 权限级——未标注一律按写处理，保守）；
// 2. 排段：把一批调用切成**段**（[plan]）——连续的读合成一段可并行，写各自独占一段；
//    段与段之间是**硬屏障**，所以「写之后再读一定读到新值」是结构保证，不是靠自觉；
// 3. 跑段：把一个并行段交给有界池并发执行，带**每调用 deadline**、段 deadline、
//    整批 deadline 与**停止/取消**（[ToolBatchRunner]）。串行段仍走引擎原有那条路
//    （识别确认卡、等用户点批准），本文件不碰它。
//
// ⚠️ 纪律：本文件**不改**任何单个工具的成功/失败语义——读到就是 Ok、超时就是
// Failed（原文与 [ToolRunner.timeoutFailure] 逐字相同）、崩了就是 Failed
// （[ToolRunner.crashFailure]）。并行只改「谁先谁后、几个一起」，不改「结果是什么」。
package com.example.zhengdao.baopu.agent

import java.util.concurrent.Callable
import java.util.concurrent.ExecutorService
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * 一条调度 lane（第九十二批 §1 的两条规则）。
 *
 * [PARALLEL_READ] 的准入门槛是**三条同时成立**（见 [ToolScheduling.laneOf]）：
 * 工具的 `readOnly`、显式的 `parallelSafe`、以及免确认（`FREE`）。
 * 少一条就落到 [SERIAL]——宁可少并行，不许让写工具或确认卡混进并发里。
 */
internal enum class ToolLane {
    /** 只读且明确标了可并行：可以和同段里的其它读**同时**跑。 */
    PARALLEL_READ,

    /** 其余一律独占一段、按提交顺序执行（写工具、浏览器、等人、确认卡工具…）。 */
    SERIAL,
}

/**
 * 一段调度（[ToolScheduling.plan] 的产物）。
 *
 * [Parallel] 只装 `PARALLEL_READ` 的调用（≥2 个；只有一个读时也退化成 [Serial]，
 * 不为单个调用白开一段并发）；[Serial] 装一个调用，含确认卡等所有非并行形态。
 */
internal sealed interface ToolStage {
    data class Parallel(val calls: List<ToolCall>) : ToolStage
    data class Serial(val call: ToolCall) : ToolStage
}

/**
 * 调度的纯判据（零 Android / 零 IO / 零线程 / 零时钟；JVM 直测）。
 */
internal object ToolScheduling {

    /**
     * 读并行的并发上限（= 读池线程数，结构性封顶）。
     *
     * 为什么是 3 而不是「越多越好」：① 真机是内存受限设备（低内存反复杀进程是已知常事），
     * 3 个文件读同时在内存里峰值可控；② 本机磁盘是 eMMC/UFS，读并发的收益在 2–3 就见顶，
     * 再多只是抢 IO 队列；③ 派工单给的口径就是「最多 2–3 个」。
     * 池大小与这个常量**必须一致**（[ToolRunner.readExecutor] 直接用它建池）——
     * 并发上限不是靠计数器自觉，是线程数算死了。
     */
    const val MAX_READ_PARALLEL = 3

    /** 等待并行段时的轮询粒度（毫秒）：够细到停止按钮不肉，又不至于空转烧 CPU。 */
    const val POLL_MS = 25L

    /**
     * 一个调用的 lane（唯一判据）。
     *
     * @param spec 注册表当前形态；null（未知工具）⇒ 串行（让管线自己去拒它）
     */
    fun laneOf(spec: ToolSpec?): ToolLane = when {
        spec == null -> ToolLane.SERIAL
        !spec.readOnly -> ToolLane.SERIAL
        !spec.parallelSafe -> ToolLane.SERIAL
        spec.permission != ToolPermission.FREE -> ToolLane.SERIAL
        else -> ToolLane.PARALLEL_READ
    }

    /**
     * 把一批调用排成段（**纯函数**，顺序即提交顺序）。
     *
     * 规则（派工单 §1）：
     * - 连续的 [ToolLane.PARALLEL_READ] 合成一段 [ToolStage.Parallel]（≥2 个；单个退化成串行）；
     * - 每个 [ToolLane.SERIAL] 独占一段 [ToolStage.Serial]；
     * - **段是屏障**：下一段一定等上一段整段收口才开始 ⇒「写与写不并行」「写之后的读一定
     *   在写完成之后启动」两条都是结构保证，不需要额外加锁或内存屏障。
     *
     * 例：`[读A, 读B, 写C, 读D]` ⇒ `Parallel(A,B)` → `Serial(C)` → `Serial(D)`。
     * 注意最后那个 `读D` 单独成段：它必须排在写之后（读到新值），不能和 A/B 合并。
     */
    fun plan(calls: List<ToolCall>, laneOf: (ToolCall) -> ToolLane): List<ToolStage> {
        val stages = ArrayList<ToolStage>()
        val buffer = ArrayList<ToolCall>()
        fun flush() {
            if (buffer.isEmpty()) return
            // 单个读不值得开并发（省一次线程切换，也让「一个读」的路径与以前完全一样）
            stages += if (buffer.size >= 2) ToolStage.Parallel(buffer.toList()) else ToolStage.Serial(buffer[0])
            buffer.clear()
        }
        for (call in calls) {
            if (laneOf(call) == ToolLane.PARALLEL_READ) {
                buffer += call
            } else {
                flush()
                stages += ToolStage.Serial(call)
            }
        }
        flush()
        return stages
    }

    /**
     * 一段的预算：并行段取**成员里最大的**（它们同时跑，最慢的那个说了算）；
     * 串行段就是它自己的。
     */
    fun stageBudgetMs(stage: ToolStage, timeoutOf: (ToolCall) -> Long): Long = when (stage) {
        is ToolStage.Serial -> timeoutOf(stage.call).coerceAtLeast(1L)
        is ToolStage.Parallel -> stage.calls.maxOf { timeoutOf(it).coerceAtLeast(1L) }
    }

    /**
     * **整批总预算**（派工单 §1「工具整体批次仍有总超时」）：各段预算之和。
     *
     * 为什么是「和」而不是拍一个固定数（比如 60 秒）：单工具上限本来就差着量级
     * （文件 5s / 浏览器 20s / `ask_user` 等用户 15 分钟）——拍死一个固定值，
     * 要么把正经的 `ask_user` 掐死，要么形同虚设。按段求和在**任何合法批次**上都够用，
     * 又能在「线程卡死不返回」这种病态情况下兜底：它一定有个上界。
     */
    fun batchBudgetMs(stages: List<ToolStage>, timeoutOf: (ToolCall) -> Long): Long =
        stages.sumOf { stageBudgetMs(it, timeoutOf) }

    /**
     * 分类自洽性检查（**门禁用**，返回空表 = 没毛病）。
     *
     * 两条不许出现的形态：
     * 1. `parallelSafe = true` 但 `readOnly = false` —— 写工具混进并行 = 丢更新/竞争写；
     * 2. `parallelSafe = true` 但权限是 CONFIRM —— 确认卡必须**先于执行**出，
     *    并行段里没有卡片的位置（放进去等于「先跑再问」，本批红线第一条）。
     *
     * 为什么把它写成纯函数而不是只写在测试里：测试拿**真实工具表**调它（回归门禁），
     * 负例再拿一张故意配错的表调它（证明门禁抓得住）——同一个判据两用，不会漂。
     */
    fun classificationErrors(specs: List<ToolSpec>): List<String> = specs.mapNotNull { spec ->
        when {
            spec.parallelSafe && !spec.readOnly ->
                "「${spec.name}」标了 parallelSafe 却不是 readOnly：写工具不许进并行段"
            spec.parallelSafe && spec.permission != ToolPermission.FREE ->
                "「${spec.name}」标了 parallelSafe 却是 ${spec.permission}：确认卡必须先于执行，不许并行"
            else -> null
        }
    }
}

/**
 * 一个并行调用的收口（[ToolBatchRunner.runParallel] 的产物，按提交顺序返回）。
 *
 * @param result 执行结局；[cancelled] = true 时为 null（**没采信**，不是成功也不是失败）
 * @param cancelled 用户点了停止、这次调用被中断（引擎据此记 `CANCELLED` 并收尾）
 * @param startedAtMs 提交时刻（**实测**：并行段的耗时从提交算起，不是从排到它算起）
 * @param endedAtMs 收口时刻
 */
internal data class StageItem(
    val call: ToolCall,
    val result: GuardPipeline.Result?,
    val cancelled: Boolean,
    val startedAtMs: Long,
    val endedAtMs: Long,
)

/**
 * 并行段的执行器（第九十二批）：把一段读工具**同时**丢进有界池，按 deadline 收口。
 *
 * 三条纪律：
 * 1. **不吞错**：任务里抛出来的东西一律折成 [ToolRunner.crashFailure] 的同一句原文
 *    （与串行路逐字相同），不产生第三种「意外」结局；
 * 2. **可中断**：`shouldStop()` 一为真就 `cancel(true)` 剩下那些——读工具不写盘，
 *    中断是安全的（写工具根本不进这里，它们的事务语义不受影响）；
 * 3. **不拖死**：每个调用有自己的 deadline，段和整批另有上界；到点即按超时收口，
 *    **一个慢读不会让整批陪等**。
 *
 * @param pool 读池（默认就是 [ToolRunner.readExecutor]；单测可注入小池验并发上限）
 * @param clock 时钟注入（单测可控；生产用墙钟）
 */
internal class ToolBatchRunner(
    private val pool: ExecutorService = ToolRunner.readExecutor,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val pollMs: Long = ToolScheduling.POLL_MS,
) {

    private class Slot(
        val call: ToolCall,
        val submittedAtMs: Long,
        val deadlineAtMs: Long,
        val future: Future<GuardPipeline.Result>,
    )

    /**
     * 跑一个并行段。
     *
     * @param timeoutOf 单工具时限（生产口径 = `ToolSpec.timeoutMs`）
     * @param execute 执行一个调用；第一个参数是它在**本段里的下标**（引擎据此取回
     *   已经过完 ①②③ 的 `Prepared.Ready`——不按下标而按 `ToolCall` 回查的话，
     *   同一批里两次一模一样的调用会撞键）
     * @param shouldStop 停止信号（引擎的 `stopRequested`）
     * @param deadlineAtMs 整批的总时限（含前面已花的段）；`Long.MAX_VALUE` = 不限
     */
    fun runParallel(
        stage: ToolStage.Parallel,
        timeoutOf: (ToolCall) -> Long,
        execute: (index: Int, call: ToolCall) -> GuardPipeline.Result,
        shouldStop: () -> Boolean,
        deadlineAtMs: Long = Long.MAX_VALUE,
    ): List<StageItem> {
        val submittedAt = clock()
        val stageCap = submittedAt + stage.calls.maxOf { timeoutOf(it).coerceAtLeast(1L) }
        val stageDeadline = minOf(stageCap, deadlineAtMs)
        val slots = stage.calls.mapIndexed { index, call ->
            val budget = timeoutOf(call).coerceAtLeast(1L)
            Slot(
                call = call,
                submittedAtMs = submittedAt,
                deadlineAtMs = minOf(submittedAt + budget, stageDeadline),
                future = pool.submit(Callable { execute(index, call) }),
            )
        }

        val out = ArrayList<StageItem>(slots.size)
        var stopSeen = false
        for (slot in slots) {
            // 已经停止：剩下的一个都不等（取消 + 如实记 cancelled）
            if (stopSeen) {
                slot.future.cancel(true)
                out += StageItem(slot.call, null, true, slot.submittedAtMs, clock())
                continue
            }
            var result: GuardPipeline.Result? = null
            var cancelled = false
            while (true) {
                if (shouldStop()) {
                    // 恰好在停止信号之前已经跑完的，仍然采信它的结果——
                    // 把已经发生的事记成「没发生」就是撒谎（第五十四批 P1-1 的同一条线）。
                    if (slot.future.isDone) result = runCatching { slot.future.get() }.getOrNull()
                    if (result == null) {
                        slot.future.cancel(true)
                        cancelled = true
                        stopSeen = true
                    }
                    break
                }
                val remain = slot.deadlineAtMs - clock()
                if (remain <= 0L) break // 到点未回：下面按超时收口
                try {
                    result = slot.future.get(minOf(pollMs, remain), TimeUnit.MILLISECONDS)
                    break
                } catch (e: TimeoutException) {
                    // 轮询窗口到期，回 while 头再查一次停止/总时限（不是工具超时）
                } catch (e: java.util.concurrent.CancellationException) {
                    cancelled = true
                    break
                } catch (e: Exception) {
                    // 崩了就崩了：走与串行路**同一句**原文（crashText 会把 Future 的
                    // ExecutionException 包装挖掉，两条 lane 打印的类名因此一致）
                    result = GuardPipeline.Result.Failed(slot.call, ToolRunner.crashText(slot.call.name, e))
                    break
                }
            }
            if (result == null && !cancelled) {
                // deadline 到点还没回来：取消线程，并按**与串行路同一句**的原文收口。
                // 分清是「工具自己超时」还是「被整批/本段的总时限掐掉」——回放时看得出是谁到点了。
                slot.future.cancel(true)
                val ownDeadline = slot.submittedAtMs + timeoutOf(slot.call).coerceAtLeast(1L)
                result = if (slot.deadlineAtMs < ownDeadline) {
                    GuardPipeline.Result.Failed(slot.call, batchTimeoutFailure(slot.call.name, timeoutOf(slot.call)))
                } else {
                    GuardPipeline.Result.Failed(slot.call, ToolRunner.timeoutText(slot.call.name, timeoutOf(slot.call)))
                }
            }
            out += StageItem(slot.call, result, cancelled, slot.submittedAtMs, clock())
        }
        return out
    }

    companion object {
        /**
         * 被**整批/本段总时限**掐掉时的失败原文。
         *
         * 为什么单独一句：它和「工具自己超时」成因不同（那边是工具太慢，这边是批次预算到点，
         * 常见于前面有个线程卡死），回放时必须分得开。文案里保留「超时」两个字——
         * [FailurePolicy.classifyTool] 按这个词判 `TOOL_TIMEOUT`，分类不会掉到 `TOOL_ERROR`。
         */
        fun batchTimeoutFailure(toolName: String, timeoutMs: Long): String =
            "工具「$toolName」执行超时：本批总时限到点（本工具上限 $timeoutMs ms）——可重试一次"
    }
}
