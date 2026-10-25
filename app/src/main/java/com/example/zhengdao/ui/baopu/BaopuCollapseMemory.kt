// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui.baopu

/**
 * 第二百零九批 · 折叠态的**按消息 id 记忆**（进程级内存，零 IO）。
 *
 * 为什么需要一个对象，而不是卡片里的 `remember`：
 * - 「同一会话内滚动回来仍是折叠态，不因重组弹回」⇒ LazyColumn 把行滚出屏外就
 *   **销毁那一行的组合**，行内 `remember` 连状态一起没——只有活在组合之外的
 *   记忆，才能在行被重新拼回来时原样递回去（派工单负例 3）；
 * - 「跨会话不记」⇒ 本对象只活在进程里，进程一死连 Map 带键一起蒸发；
 *   消息 id 是库表自增主键（全库唯一，不会跨会话撞号），按 id 记不会串到别的会话。
 *
 * 红线（派工单负例 5）：本对象**只有一张内存 Map**——没有文件、没有 SharedPreferences、
 * 没有 Context；折叠动作写这里，不碰 [BaopuLongformStore] 的落盘与台账。
 */
internal object BaopuCollapseMemory {

    private val lock = Any()
    private val states = HashMap<String, Boolean>()

    /** 这条消息的折叠态；没记过 ⇒ null（调用方回落 `shouldDefaultCollapse`）。 */
    fun get(messageId: String): Boolean? = synchronized(lock) { states[messageId] }

    /** 记下这条消息的折叠态（用户点卡头那一刻写一次）。 */
    fun put(messageId: String, collapsed: Boolean) {
        synchronized(lock) { states[messageId] = collapsed }
    }

    /** 忘掉一条（测试用；正常流程不主动忘）。 */
    fun forget(messageId: String) {
        synchronized(lock) { states.remove(messageId) }
    }

    /** 全忘（测试用；也留给将来「清会话」入口对齐「跨会话不记」）。 */
    fun clear() {
        synchronized(lock) { states.clear() }
    }

    /** 当前记着多少条（测试与交付报告读数用）。 */
    fun size(): Int = synchronized(lock) { states.size }
}
