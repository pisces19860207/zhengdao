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
import com.example.zhengdao.baopu.habit.habitOnRunSettled
import com.example.zhengdao.baopu.insula.insulaOnRunSettled
import com.example.zhengdao.baopu.subconscious.subconsciousOnRunSettled
import com.example.zhengdao.core.RunLog

/** 收工事件的后台维护入口（暗卫＋巡按＋676 回响＋521 岛叶/纹状体；前两件共用一套触发判据，其余自带闸）。 */
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
        // 第六百七十六批：下意识回响——收工即一次机会（「每 N 轮或空闲时」那一格），
        // 节奏由它自带的天闸（≤1 次/天）与左相（免打扰窗口内延后）管，这里只递钟点与账根。
        // 模型不可用 ⇒ 它内部退回规则版（不崩、不空转），本行一个字都不用知道。
        if (safe("回响") { subconsciousOnRunSettled(ctx, nowMs) } != null) ran++
        // 第五百二十一批：岛叶自调——先读体征调四参数（并发度/批量/重试间隔/延后重活），
        // 纹状体那一行消费它的现值；岛叶不可用 ⇒ 纹状体按出厂默认走（逐字节兜底口径）。
        if (safe("岛叶自调") { insulaOnRunSettled(ctx, nowMs) } != null) ran++
        // 第五百二十一批：纹状体固化——收工即一次机会：本轮轨迹刚落库，正是看
        // "同一流程是否又成了 N 次"的时刻；只出建议卡，采纳走既有许愿流水线。
        if (safe("纹状体固化") { habitOnRunSettled(ctx, nowMs) } != null) ran++
        return ran
    }

    private inline fun <T> safe(job: String, block: () -> T?): T? = runCatching(block).onFailure {
        RunLog.log("后台维护：$job 那一件抛出 ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()
}
