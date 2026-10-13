// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import android.os.Looper

/**
 * 「这段活只能在后台线程跑」的硬约束（外部审计报告 B4，2026-10-12 落地）。
 *
 * ## 为什么需要它
 *
 * `ResMonitor.measure()` 会 `Thread.sleep(700)` 再按 UID 全量扫 `/proc`，
 * `AgentProcesses.scan()` 则会读遍所有进程的 `cmdline`。今天两处调用方都在后台：
 * `ZhengdaoApp.autoCleanJunk()` 自己起 `Thread{}`（`ZhengdaoApp.kt:97-106`），
 * 体检卡的采样也走 IO。**但它们自己没有这条约束** —— 谁哪天顺手在 Compose 的
 * `remember{}` 或 Activity 回调里调一次，就是几百毫秒的主线程阻塞：轻则掉帧，
 * 重则 ANR 5 秒。审计把它列为 B 类隐患就是因为「函数签名上看不出这件事」。
 *
 * ## 为什么是 `check` 而不是静默兜底
 *
 * 项目原则「失败必须可见」：主线程调用是**编码错误**，应该立刻炸在开发者面前，
 * 而不是偷偷跑完再让用户在真机上等 5 秒。刻意**不**做成「自动切后台」——
 * 那会让调用方以为它是异步的，实际仍在原地等结果。
 *
 * ## 测试口径
 *
 * 两个 Looper 参数可注入：JVM 单测里 `Looper.getMainLooper()` 返回 null
 * （`unitTests.isReturnDefaultValues = true`），此时一律放行；要验证「抛」的分支，
 * 传两个相同的测试替身即可（见 `BackgroundOnlyTest`）。
 */
internal object BackgroundOnly {

    /**
     * @param what 调用点名字，写进异常消息，方便一眼定位。
     * @param mainLooper 主线程 Looper；null = 当前环境没有 Looper（JVM 单测）⇒ 放行。
     * @param currentLooper 当前线程的 Looper。
     */
    fun check(
        what: String,
        mainLooper: Any? = Looper.getMainLooper(),
        currentLooper: Any? = Looper.myLooper(),
    ) {
        if (mainLooper == null) return
        check(currentLooper !== mainLooper) {
            "$what 被主线程调用了：它会 sleep 几百毫秒并全量扫 /proc，" +
                "在主线程必然掉帧、严重时 ANR（审计 B4）。" +
                "请放进 Thread{} 或 withContext(Dispatchers.IO)。"
        }
    }
}
