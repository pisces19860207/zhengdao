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
 * - **可撤销**（176 §2「可编辑/撤销未生效插话」）：`remove` 按原文相等撤第一枪，
 *   撤到了返回 true；没撤到（不存在/已注入）返回 false——调用方据此决定界面
 *   收不收回那个 chip。撤销与 drainAll 并发时由 CLQ 逐元素线性化兜底：
 *   要么撤在注入前（不注入），要么注入先发生（撤销返回 false，界面如实物件）。
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

    /**
     * 撤销一条还没生效的插话（176 §2；UI 线程调）。
     *
     * 按**原文相等**撤第一枪（界面 chip 上显示的就是这句原文）；两条一样时
     * 撤掉先入队的那条，FIFO 顺序对其余条目不变。
     *
     * @return true = 撤到了；false = 队列里没有这条（从没插过／已经注入过了）。
     */
    fun remove(text: String): Boolean = q.remove(text)

    /**
     * 按 FIFO 拷一份当前积压（176 §2 界面显示用）。
     *
     * 返回的是**拷贝**：界面拿到哪一拍的就显示哪一拍，后进来的下拍才出现；
     * 弱一致遍历只可能少看到刚并发入队的那条，不可能看到重复或乱序。
     */
    fun snapshot(): List<String> = ArrayList(q)
}
