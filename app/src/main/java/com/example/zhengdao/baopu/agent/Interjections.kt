// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单《第一百八十九批 · 步数分段预算＋无进展熔断》§1.4.2 ——
// 「插话随时可用……插话进 FIFO，在下一个安全点（当前工具调用返回后 / 下一步开始前）注入；
// 插话 ≠ 打断，不触发停止」。判据「连续插 3 条 ⇒ 按 FIFO 注入 3 条、不丢不重；
// 插话吞掉一条 ⇒ 用例红」要求这个队列可脱离引擎单独 JVM 直测 ⇒ 单独成文件。
//
// 边界：这只里**引擎侧**的插话半（176 拆两半账：插话半先做）。运行中输入框保持可用的
// UI 半、以及调用 [RunEngine.interject] 的那行接线，归 176（本批红线不许碰 ui/baopu/**）。
package com.example.zhengdao.baopu.agent

/**
 * 运行中插话的 FIFO（189 §1.4.2）。
 *
 * 线程契约：`offer` 在 UI 线程调（176 的输入框），`drainAll` 在引擎线程调
 * （步循环头部这个安全点）⇒ 用并发队列，不加第二把锁。
 *
 * 语义三条（判据逐条对应）：
 * - **不丢不重**：`drainAll` 一次取空、按入队顺序返回（FIFO）；再取必空。
 * - **≠ 打断**：本类不碰 `stopRequested`、不碰在飞调用——注入只是往历史里
 *   添一条用户消息，模型在下一步看到它。
 * - **空白不收**：空串/纯空白没有可注入的内容，`offer` 直接拒绝（返回 false），
 *   不让空行占一个安全点。
 */
internal class InterjectionQueue {

    private val q = java.util.concurrent.ConcurrentLinkedQueue<String>()

    /** 入队一条插话；空白拒绝（返回 false），其余永远收下（返回 true）。 */
    fun offer(text: String): Boolean {
        if (text.isBlank()) return false
        q.add(text)
        return true
    }

    /** 取空并按 FIFO 返回（安全点注入用；一次调用拿走全部，不重不漏）。 */
    fun drainAll(): List<String> {
        val out = ArrayList<String>(q.size)
        while (true) {
            val t = q.poll() ?: break
            out += t
        }
        return out
    }

    /** 当前积压条数（只读观测；测试与日志用）。 */
    fun pending(): Int = q.size
}
