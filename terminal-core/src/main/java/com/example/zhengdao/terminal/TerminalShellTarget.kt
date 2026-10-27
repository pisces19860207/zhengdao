// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.terminal

/**
 * 「点通知回哪儿」的**唯一落点**（第二百四十一批新增，第三刀的接缝）。
 *
 * 为什么要有这个类：保活/缓存/安装三条通知的 `PendingIntent` 原本写的是
 * `Intent(ctx, MainActivity::class.java)`——`MainActivity` 属于主 APP（`:app`），
 * 而本模块在它**下面**，编译期就看不见这个类。三条通知是本刀必须搬走的（它们和
 * [SessionService] 在同一个保活链上），于是把"落点"从**类引用**降级成**组件名**：
 *
 * ```kotlin
 * // 搬前（:app 里）
 * Intent(ctx, MainActivity::class.java)
 * // 搬后（:terminal-core 里）
 * Intent().setClassName(ctx, TerminalShellTarget.LAUNCH_ACTIVITY)
 * ```
 *
 * **运行时完全等价**：`Intent(Context, Class)` 内部就是
 * `setComponent(new ComponentName(context, cls))`，而 `setClassName(Context, String)`
 * 是 `setComponent(new ComponentName(context, className))`——同一个包名、同一个类名、
 * 同一个 ComponentName。三条通知的请求码（4101/4102/4103）与 `filterEquals` 口径一字未动。
 *
 * 第三刀（`:app-term`）把这里换成终端 APP 自己的主界面即可，**三个调用点都不用再动**。
 * 等价性由 `:app` 的单测 `TerminalShellTargetEquivalenceTest` 钉住（它同时看得见两边）。
 */
object TerminalShellTarget {

    /**
     * 通知点回的主界面组件名（与搬前 `MainActivity::class.java` 同一个类）。
     *
     * 注意：这是**类名字符串**，不是 import——主 APP 改名/挪包时必须同步这里，
     * 单测会立刻红（见 KDoc 末尾）。
     */
    const val LAUNCH_ACTIVITY = "com.example.zhengdao.MainActivity"
}
