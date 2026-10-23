// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：第一百五十二批派工单《F 时序竞态运行期用例（可注入 SessionRunQueue 替身）》§1。
package com.example.zhengdao.baopu.agent

import java.util.concurrent.atomic.AtomicInteger

/**
 * 「取条 vs 停止清队」的同锁协议（150批 F 立的规矩，152批 F 抽成可注入替身缝）。
 *
 * 为什么抽出来：RunEngine 要真 SQLite/网络，JVM 起不来 ⇒ 「poll 后 stop 到达」的
 * 时序此前只能钉在源码结构上（RunEngineConcurrencyGateTest）。两个临界区收进本类后，
 * 运行期用例可以直接驱动**真实协议代码**＋替身队列，把存活不变量钉在行为上。
 * **只搬家不改规矩**：锁的粒度、临界区内的顺序与 150/151 批逐字一致。
 *
 * 协议（两侧共用 [lock]）：
 * - drain 侧 [pollForRun]：门内查停 → 取条 → 计在飞。stop 先拿锁 ⇒ 这条还排在队列里、
 *   被 clear 一起清掉并计入「清掉 N 条」；这条先拿锁 ⇒ 过闸，过了闸就必须进 runLoop
 *   （停止迟到由循环头看到停止标志收尾 Aborted，消息已落库、可续跑）。
 * - stop 侧 [onStopArrived]：门内置位 → 中断在飞（cancel/abandon 立即返回）→ 清队
 *   （引擎的 clearQueue：只提交写、不等落库）→ 引擎在门内读在飞计数拼文案。
 *   置位与清队不得在门外做，否则回到 150 批审计的「取出后永久丢失」。
 *
 * 状态归属：停止标志仍归引擎（全文件 19 处读写原样），经 [isStopped]/[markStopped]
 * 进出临界区；在飞计数 [polledInFlight] 归本类（151 批 F 口径，只用于把话说全）。
 * 临界区内全是非阻塞调用（cancel/abandon 立即返回；clear 只提交写、不等落库）。
 */
internal class QueueStopGate(
    private val queue: SessionRunQueue,
    /** 停止标志读口（引擎字段；替身可给 `AtomicBoolean::get`）。 */
    private val isStopped: () -> Boolean,
    /** 停止标志写口（只在 [lock] 临界区内被调）。 */
    private val markStopped: () -> Unit,
) {
    /** 已过闸（poll 出）但还没收尾的条数（151 批 F：不进「清掉 N 条」的账，分开说）。 */
    val polledInFlight = AtomicInteger(0)

    private val lock = Object()

    /** drain 侧临界区：门内查停＋取条＋计在飞（RunEngine.drainQueue 的唯一取条口）。 */
    fun pollForRun(sessionId: Long): QueuedTurn? = synchronized(lock) {
        if (isStopped()) null else queue.poll(sessionId)?.also {
            polledInFlight.incrementAndGet()
        }
    }

    /**
     * stop 侧临界区：门内 置位 → 中断在飞 → [body]（引擎在其中清队、读计数、拼文案）。
     *
     * [interrupt] 收 `activeCall.cancel()` ＋ `confirmFlow.abandon()`——它们与置位、
     * 清队的相对顺序必须保持 150 批定稿的形状（先置位再砍流再清队）。
     */
    fun <R> onStopArrived(interrupt: () -> Unit, body: () -> R): R = synchronized(lock) {
        markStopped()
        interrupt()
        body()
    }
}

/**
 * 停止通知文案（151 批定稿；152 批抽成纯函数，运行期用例可直测「清掉 N 条」与实清数一致）。
 *
 * [n] = clearQueue 实清条数；[running] = 已过闸未收尾条数。两条**分开说**——已 poll 出
 * 的那条不在内存队列里，混在一起说会让用户以为「都清完了」而续跑时又冒出一条。
 * null = 无可说（既没清也没在飞，不弹假通知）。
 */
internal fun stopNoticeText(n: Int, running: Int): String? = when {
    n > 0 && running > 0 -> "已停止：清掉 $n 条排队；另有 1 条已开始处理的消息会保留，可续跑"
    n > 0 -> "已停止，排队中的 $n 条也清掉了"
    running > 0 -> "已停止：正在处理的那条会停在当前步，消息保留可续跑"
    else -> null
}
