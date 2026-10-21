// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：第一百一十七批派工单《抱朴多任务并发 v1》§并发模型。
package com.example.zhengdao.baopu.agent

import java.util.ArrayDeque
import java.util.concurrent.Future

/**
 * 排队中的一条用户任务（第一百一十七批）。
 *
 * 为什么文本/图片随条目走、不放在引擎的单字段里：同一会话可以连着排两条带图消息，
 * 单字段会被后一条覆盖 ⇒ 第一条的图张冠李戴。图片路径由 [RunEngine] 随
 * `MessagePersisted` 事件回传，控制器按事件那条消息自己的路径补 image part。
 */
internal data class QueuedTurn(
    val sessionId: Long,
    val text: String,
    val imageRelPaths: List<String> = emptyList(),
    /** `run_queue.id`（0 = 刚入队、落库 Future 还没回来）。 */
    val rowId: Long = 0L,
    /** 入队时提交给单写者线程的插入 Future（恢复出来的老行没有它）。 */
    val persist: Future<Long>? = null,
)

/** 单活跃门当前握着谁（纯判据的输入，不直接读 AtomicReference）。 */
internal enum class GateOwner { NONE, SAME_SESSION, OTHER_SESSION }

/** `RunEngine.send` 的同步结果：调用方据此决定要不要清输入框。 */
internal enum class SendAcceptance {
    /** 门是空的：已入队并立刻开跑。 */
    STARTED,

    /** 本会话正在跑：已入队，等这一轮收尾后自动接力。 */
    QUEUED,

    /** 空文本（不提示，输入框本来就没东西）。 */
    REFUSED_EMPTY,

    /** 门被别的会话占着：v1 明确拒绝，不跨会话排队。 */
    REFUSED_BUSY_OTHER_SESSION,

    /** 本会话队列已满。 */
    REFUSED_QUEUE_FULL,
    ;

    val accepted: Boolean get() = this == STARTED || this == QUEUED
}

/**
 * 任务级并发判据（**纯函数**，JVM 可测）。
 *
 * 三层边界里只有这一层是本批新增的：会话级沿用全局单活跃门，工具级沿用第九十二批
 * 的读并行/写单飞。这里不碰任何线程，只回答「这条消息该不该收、收进哪儿」。
 */
internal object RunQueueRules {
    /** 每会话最多排队条数（用户 2026-10-21 口径：够用，不许无限攒）。 */
    const val MAX_QUEUED = 8

    fun acceptance(owner: GateOwner, queued: Int, blank: Boolean): SendAcceptance = when {
        blank -> SendAcceptance.REFUSED_EMPTY
        owner == GateOwner.OTHER_SESSION && !RunConcurrency.crossSessionSendAllowed() ->
            SendAcceptance.REFUSED_BUSY_OTHER_SESSION
        queued >= MAX_QUEUED -> SendAcceptance.REFUSED_QUEUE_FULL
        owner == GateOwner.SAME_SESSION -> SendAcceptance.QUEUED
        else -> SendAcceptance.STARTED
    }

    /**
     * 这一轮收尾后能不能自动抽下一条。
     *
     * 只有 `DONE` 才接力：用户点停止（`stopRequested`）或其他任何 `ABORTED` 都暂停，
     * 失败不许被「下一条悄悄跑起来」掩盖（接第八十七批的可报告失败）。
     */
    fun canAutoAdvance(lastState: RunState, stopRequested: Boolean, queued: Int): Boolean =
        lastState == RunState.DONE && !stopRequested && queued > 0
}

/**
 * 未来并行的接口（v1 不实现）。
 *
 * 会话级现在就是 1：第二个会话发送会被 [RunQueueRules] 明确拒绝。将来要把
 * `maxParallelSessions` 调到 >1，才需要改门与调度器；任务级 FIFO 与单事务边界不用重写。
 * 把判据放这里而不是散在引擎里，是为了让「v1 不并行」成为可测的事实，而不是注释里的口号。
 */
internal object RunConcurrency {
    const val maxParallelSessions = 1

    fun canStartAnother(activeSessions: Int): Boolean = activeSessions < maxParallelSessions

    /** v1：跨会话发送一律不接（不排进别人的队列、不抢别人的门）。 */
    fun crossSessionSendAllowed(): Boolean = maxParallelSessions > 1
}

/**
 * 每会话一条 FIFO（内存镜像）。
 *
 * 线程纪律：所有方法 `@Synchronized`；UI 线程只做入队/计数的同步读，引擎线程取队首。
 * 持久化是另一条链（`run_queue` 表，单写者线程）——内存先给出即时计数，落库 Future
 * 跟着条目走，开跑前再取它的 id 做「删队列行 ＋ 插用户消息」的同事务。
 */
internal class SessionRunQueue {
    private val queues = LinkedHashMap<Long, ArrayDeque<QueuedTurn>>()

    @Synchronized
    fun enqueue(turn: QueuedTurn): Int {
        val q = queues.getOrPut(turn.sessionId) { ArrayDeque() }
        q.addLast(turn)
        return q.size
    }

    @Synchronized
    fun size(sessionId: Long): Int = queues[sessionId]?.size ?: 0

    @Synchronized
    fun hasItems(sessionId: Long): Boolean = size(sessionId) > 0

    @Synchronized
    fun snapshot(sessionId: Long): List<QueuedTurn> = queues[sessionId]?.toList() ?: emptyList()

    /** 取队首（开跑前调；取走即从内存队列消失，库里那行由开跑事务删）。 */
    @Synchronized
    fun poll(sessionId: Long): QueuedTurn? {
        val q = queues[sessionId] ?: return null
        val turn = q.pollFirst() ?: return null
        if (q.isEmpty()) queues.remove(sessionId)
        return turn
    }

    /** 清空本会话（返回被清的条目，调用方负责落库删除；不负责停当前轮）。 */
    @Synchronized
    fun clear(sessionId: Long): List<QueuedTurn> {
        val q = queues.remove(sessionId) ?: return emptyList()
        return q.toList()
    }

    /** 恢复：只在内存里本来就空时接管，避免与刚入队的镜像重复。 */
    @Synchronized
    fun restore(sessionId: Long, turns: List<QueuedTurn>) {
        if (turns.isEmpty()) return
        val existing = queues[sessionId]
        if (existing != null && existing.isNotEmpty()) return
        queues[sessionId] = ArrayDeque(turns)
    }

    /** 撤销刚入队的一条（只用于「抢门失败」这种极端竞态；正常路径不走）。 */
    @Synchronized
    fun remove(sessionId: Long, turn: QueuedTurn): Boolean {
        val q = queues[sessionId] ?: return false
        val ok = q.removeIf { it === turn }
        if (q.isEmpty()) queues.remove(sessionId)
        return ok
    }

    /** 当前有排队条目的会话（恢复/诊断用）。 */
    @Synchronized
    fun sessions(): Set<Long> = queues.keys.toSet()
}
