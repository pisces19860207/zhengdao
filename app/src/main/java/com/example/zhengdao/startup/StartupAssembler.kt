// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.startup

/**
 * 装配状态机（**纯 Kotlin**：装配动作与时钟都是注入的 ⇒ JVM 单测能造"装配必炸"）。
 *
 * ## 它到底负责什么
 *
 * 三件事，每件都对着一条判据：
 *   1. [ensure] —— 冷启装一次；**失败不自动重试**。自动重试会让"装配坏了的机器每次
 *      进前台都再炸一次"，而炸出来的东西没人看；重试是用户的动作（判据④）。
 *   2. [retry] —— 用户点"重试"才再装一次；异常消除了就真的能进主界面（判据④）。
 *   3. [enterSafeMode] —— 只从失败态进；进了就不再装配（抱朴不可用，判据③）。
 *
 * ## 为什么异常在这里捕，而不是在调用方 runCatching
 *
 * 历史上这个位置是 `runCatching { engine() }.onFailure { 退回懒建 }` —— 那条兜底把
 * "装配失败"变成了一次静默降级：错误页没有、日志只有一行，下一次取用落到组合期再炸。
 * 629 把它改成：**捕在这里、记在这里、状态交给界面**，调用方没有"吞掉"的机会。
 *
 * ## 捕什么、不捕什么
 *
 * - 捕 `Exception`：装配链上的 `IllegalStateException` / `NullPointerException` /
 *   `IllegalArgumentException` 等全部归此（617 那次冷启闪退就是 `IllegalStateException`）。
 * - 捕 `LinkageError`：`NoClassDefFoundError` / `ExceptionInInitializerError` 是装配阶段
 *   最典型的一种失败（类没进包、静态初始化炸了），它**不是** Exception，不单独列就漏。
 * - **不捕** `VirtualMachineError`（OOM / StackOverflow）与 `ThreadDeath`：那时候进程
 *   自己都要活不下去了，画错误页只会画出一屏更费解的东西 —— 这类不许被"降级"包装。
 */
internal class StartupAssembler(
    private val assemble: () -> Unit,
    private val now: () -> Long = { System.currentTimeMillis() },
) {

    /** 当前状态（闭集四格，见 [StartupState]）。 */
    var state: StartupState = StartupState.Idle
        private set

    /** 真装过几次（诊断与单测读数；"没自动重试"这条判据靠它数）。 */
    var attempts: Int = 0
        private set

    /** 冷启入口：只在 [StartupState.Idle] 时动手，失败后**不会**再来一遍。 */
    fun ensure(): StartupState {
        if (state == StartupState.Idle) runOnce()
        return state
    }

    /** 用户点"重试"／"退出安全模式"：失败态与安全模式都算重新来一次；已成功则什么都不做。 */
    fun retry(): StartupState {
        when (state) {
            StartupState.Idle, is StartupState.Failed, is StartupState.SafeMode -> runOnce()
            StartupState.Ready -> Unit
        }
        return state
    }

    /** 用户点"以安全模式继续"：**只从失败态可进**；进了就不再装配。 */
    fun enterSafeMode(): StartupState {
        val failed = state as? StartupState.Failed
        if (failed != null) state = StartupState.SafeMode(failed.failure)
        return state
    }

    private fun runOnce() {
        attempts++
        state = try {
            assemble()
            StartupState.Ready
        } catch (e: Exception) {
            StartupState.Failed(StartupFailure.of(e, now()))
        } catch (e: LinkageError) {
            StartupState.Failed(StartupFailure.of(e, now()))
        }
    }
}
