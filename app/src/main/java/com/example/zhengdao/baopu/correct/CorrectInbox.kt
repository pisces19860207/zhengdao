// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十四批 C（阶段三前段）· 边看边纠的**回放态与纠正收件箱**（进程级一份）。
//
// 职责只有两件：
//   · 回放进行中的可观察状态（谁挂的：TeachReplay 的执行段 begin/end）——
//     阶段一曾挂账「回放中无可观察状态源」，本文件就是那个状态源；
//   · 纠正收件箱：回放在跑时，用户在抱朴说的那句话（经 RunEngine.interject 的既有插话
//     通道转发，RunEngine 只加一行转发、插话语义一字未改）落在这里；TeachExecutor 的
//     **步间暂停闸**读它——有纠正 ⇒ 当前步跑完即暂停（当步结束即暂停，判据①）。
//
// 边界：
//   · 收件箱不持久化（回放是内存里的进行时；进程死了回放本身也没了——与 507 停机盘记
//     是两种性质）；一次只留**最新一句**（用户连说几句时，最后一句是当前意图）；
//   · 不出网、不落正文到任何账本——纠正文本只进 executor 的暂停报告与模型历史
//     （插话本来的落点），本类不留副本。
package com.example.zhengdao.baopu.correct

/** 回放进行中的状态快照。 */
internal data class ReplayRunning(val skillName: String, val totalSteps: Int)

/** 回放暂停闸＋纠正收件箱（进程级一份；TeachExecutor 的 pauseGate 读它）。 */
internal object CorrectInbox {

    @Volatile private var running: ReplayRunning? = null

    /** 最新一条待处理纠正（null＝没有；teachExecutor 每步开头查一次）。 */
    @Volatile private var pendingCorrection: String? = null

    /** 回放开始（TeachReplay 执行段前调；幂等，重复调只刷新）。 */
    fun beginReplay(skillName: String, totalSteps: Int) {
        running = ReplayRunning(skillName, totalSteps)
        pendingCorrection = null
    }

    /** 回放结束（执行段 finally 调；暂停/失败/成功一律清）。 */
    fun endReplay() {
        running = null
        pendingCorrection = null
    }

    /** 回放是否在跑（聊天层据此把用户那句话路由成纠正）。 */
    fun active(): ReplayRunning? = running

    /**
     * RunEngine.interject 的转发口：回放在跑 ⇒ 这句插话就是**针对回放的纠正**。
     * 插话本来的落点不变（仍在引擎 FIFO、下个安全点进模型历史）——本口只是**加挂一份**
     * 给回放暂停闸；没有回放在跑时安静地什么都不做。
     */
    fun noteInterjection(text: String) {
        if (running != null && text.isNotBlank()) pendingCorrection = text
    }

    /** 暂停闸读数：非空 ⇒ 当前步跑完即暂停（判据①）；读完**不清**——暂停报告与模型历史都要它。 */
    fun pendingCorrection(): String? = pendingCorrection
}
