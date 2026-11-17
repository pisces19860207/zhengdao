// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

/**
 * 第六百八十批 · 手摇时钟（判据「停止后 3 秒兜底」的夹具）。
 *
 * 不 sleep、不靠线程：测试里 `advance(2_999)` 与 `advance(1)` 精确地卡在超时线两侧，
 * 于是「3 秒前不许收口、3 秒整必须收口」可以被确定性断言，而不是赌 CI 有多慢。
 */
internal class ManualScheduler : VoiceScheduler {

    private class Task(val dueAt: Long, val id: Int, val run: () -> Unit)

    private val tasks = mutableListOf<Task>()
    private var now = 0L
    private var nextId = 0

    override fun post(delayMs: Long, task: () -> Unit): Cancellable {
        val id = nextId++
        tasks += Task(now + delayMs, id, task)
        return Cancellable { tasks.removeAll { it.id == id } }
    }

    /** 往前走到 now + delayMs，按到期先后执行（含执行过程中新排的表）。 */
    fun advance(delayMs: Long) {
        val target = now + delayMs
        while (true) {
            val due = tasks.filter { it.dueAt <= target }.minByOrNull { it.dueAt } ?: break
            tasks.remove(due)
            now = due.dueAt
            due.run()
        }
        now = target
    }

    /** 还没到点的表；收口之后应当为 0（看门狗不许漏撤）。 */
    fun pending(): Int = tasks.size
}
