// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 冷启动「起会话」的**防重入闸门**（§B 顺序 6 / P1-3，2026-10-14）。
 *
 * ## 为什么需要它
 * `SessionManager.start()` 从主线程挪到后台线程之后（见 `TerminalActivity.startSessionOffMainThread`），
 * `SessionManager.hasSession()` 在它跑完之前一直是 `false` —— 而入口有两个：
 * `onResume`（每次回前台）与 `onNewIntent`（复用实例点通知栏回来），二者可在启动完成前各来一次。
 * 原先同步执行时，第二次调用天然看到 `hasSession() == true`，所以没这个问题；改成异步后必须有闸门，
 * 否则会**起两条会话**（同一句启动命令注入两遍、正要启动的 Agent 被第二次顶掉——正是
 * `SessionManager.hasSession()` KDoc 里记过的那个老症状）。
 *
 * ## 语义（判据要求：初始化**幂等 / 可重入**）
 * - [tryEnter] 只有**第一个**调用者拿到 true，其余在启动结束前一律 false；
 * - [exit] 之后重新可进（无论上一次成功还是失败）⇒ 幂等：重复调用不会累积状态；
 * - 失败路径**也必须** [exit]（否则闸门永久卡死，用户再也进不了终端）。
 *
 * 抽成独立类是为了能 JVM 单测（它是纯逻辑，不含 Android）。真机读数归蓝总管采。
 */
internal class SessionStartGate {

    private val inFlight = AtomicBoolean(false)

    /** 尝试进入「启动中」。已在启动中则返回 false —— 这一次调用应当被**忽略**（不是排队）。 */
    fun tryEnter(): Boolean = inFlight.compareAndSet(false, true)

    /** 启动结束（成功、失败、被销毁都要调）。之后 [tryEnter] 重新可用。 */
    fun exit() {
        inFlight.set(false)
    }

    /** 当前是否正在启动（观察用）。 */
    val isInFlight: Boolean get() = inFlight.get()
}
