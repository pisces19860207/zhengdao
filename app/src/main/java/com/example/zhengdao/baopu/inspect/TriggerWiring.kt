// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第五百一十一批 · **事件驱动**的那一截（判据④：不做常驻轮询）。
//
// 入口只有一处：[com.example.zhengdao.ui.baopu.BaopuChatController] 的收工家族
// （`EngineEvent.Settled` —— 单活跃门已释放、`run_log` 已落库，是"这一轮真结束了"的
// 唯一权威信号，432/428 收尾触发都用它）。
//
// 为什么在这里问负载：累计任务数／工具调用数都写在 `run_trace`／`run_log` 里，
// 收工时正是"刚写完一批"的时刻；判据层自己不看时钟也不读库（[Opportunistic] 只收数）。
//
// 两条纪律：
// 1. **顺序问、不并发**：先暗卫（轻）后巡按（重），两件各自抢 [HeavyWork] 的锁；
//    巡按还有"30 分钟无自动动作"这一条，跑完暗卫它就自己让开（判据层的事，不在本文件）。
// 2. **崩了只记一行**：收尾trigger 不许把会话收尾带下去（65/89 口径）。
//    调用方要把它放到 IO 上（巡按是重活），这里同步跑完就返回。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import com.example.zhengdao.core.RunLog

/** 收工事件的后台维护入口（暗卫＋巡按共用一套触发判据）。 */
internal object TriggerWiring {

    /**
     * 一轮会话收工 ⇒ 问一次"负载够不够、机会齐不齐"。
     *
     * @return 这一趟真跑了几件（0 是最常见的结果：条件不齐或负载没到阈）——给门禁与日志用
     */
    fun onRunSettled(ctx: Context, nowMs: Long = System.currentTimeMillis()): Int {
        var ran = 0
        if (safe("暗卫") { NocturnalAuto.runDue(ctx, nowMs, AskSource.EVENT) } != null) ran++
        if (safe("巡按") { PatrolAuto.runDue(ctx, nowMs, AskSource.EVENT) } != null) ran++
        return ran
    }

    private inline fun <T> safe(job: String, block: () -> T?): T? = runCatching(block).onFailure {
        RunLog.log("后台维护：$job 那一件抛出 ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
}
