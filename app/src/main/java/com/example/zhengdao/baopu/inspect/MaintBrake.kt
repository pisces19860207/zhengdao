// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第四百七十九批 · **两级刹车**的判据层（四器共用一套；纯函数，零 Android、零时钟、零读写）。
//
// 用户 2026-11-08 定的三层：
//   一级（自动降频）：电量＜20% 且未充电 · 设备发烫 · 前台正在会话 · 最近 30 分钟已有自动动作 ·
//                     正在跑重活 ⇒ **本轮延后，不打扰用户**（一次都不问）。这五条的具体判据
//                     住在 `Opportunistic.blockedBy`（511 立的那四条加上本批两条），本文件不重写。
//   二级（急停）：软停/硬停 —— **沿用 471 的语义，不新造**（`safety/HaltGate`）：停机期间四器
//                 一律不启动。本文件只做"停机 ⇒ 说不行"这一句，停机状态本身还是 HaltGate 的。
//   单件（暂停）：某一只出了问题 ⇒ 只停它一只（[com.example.zhengdao.baopu.maintenance.MaintPause]），
//                 其余照跑；到期自动恢复。
//
// 为什么判定要单独一层：三条闸的**顺序**本身就是语义（急停盖过单件、单件盖过降频），把它写进
// 四个装配点里就是四份"顺序"，改一处漏三处。这里一份，四处都问它。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.maintenance.MaintDevice
import com.example.zhengdao.baopu.maintenance.MaintPause

/** 两级刹车＋单件暂停的判据（纯函数；时刻、停机态、停到几点都由调用方给）。 */
internal object MaintBrake {

    /** 急停那一句（四器共用一份话术；停机状态本身在 `safety/HaltGate`，本层只引这一句）。 */
    const val WHY_HALT: String = "急停中（软停/硬停）⇒ 四器一律不启动"

    /** 落账行的共同前缀（一本 run_log 里一眼能捞出后台维护那几行）。 */
    const val TAG: String = "自动维护"

    /**
     * 这一趟让不让它启动。
     *
     * @param manual 人当场点的那一下（true）：**只看急停**——停一手停的是"自动"，不是人的手；
     *               但急停是例外（471：停机期间四器一律不启动，人点的也算启动）。
     * @return null ＝ 放行；非 null ＝ 不让跑的人话（落账时原样写进去）
     */
    fun blocked(
        device: MaintDevice,
        halted: Boolean,
        pausedUntilMs: Long,
        nowMs: Long,
        manual: Boolean = false,
    ): String? = when {
        halted -> WHY_HALT
        !manual && MaintPause.stillPaused(pausedUntilMs, nowMs) ->
            "单件暂停中（${device.cn} 还停 ${MaintPause.leftLabel(pausedUntilMs - nowMs)}）"
        else -> null
    }

    /** 「谁在问这一趟」的人话（周期＝7 天下限那一截，收工事件＝负载那一截）。 */
    fun askCn(source: AskSource): String = when (source) {
        AskSource.MANUAL -> "人手"
        AskSource.EVENT -> "收工事件"
        AskSource.SCHEDULED -> "周期作业"
    }

    /**
     * 延后那一行账（判据②要求"满足 ⇒ 延后**并落账**"）。
     *
     * 三格齐全：谁问的（[askCn]）· 当时的机会读数（[readout]）· 为什么没跑（[why]）。
     * 不落这一行的话，"一晚上一次都没跑"在账上只能靠猜。
     */
    fun deferredLine(device: MaintDevice, source: AskSource, why: String, readout: String): String =
        "$TAG｜${device.cn}｜延后｜谁问：${askCn(source)}｜读数：$readout｜为什么：$why"

    /**
     * 执行那一行账（判据⑤：谁触发 · 机会读数 · 结果一行）。
     *
     * [triggerCn] 是"自动/负载/7 天下限"里的那一个（[TriggerReason.cn]）；[tail] 是结果一行
     * （暗卫＝抽了几条、巡按＝报告 headline、御医＝体检 headline）。
     */
    fun runningLine(
        device: MaintDevice,
        triggerCn: String,
        readout: String,
        tail: String,
    ): String = "$TAG｜${device.cn}｜执行｜谁触发：$triggerCn｜读数：$readout｜结果：$tail"

    /** 暂停被拦下来那一行（与延后同一形；标注它是单件那一道闸，不混进"降频"）。 */
    fun pausedLine(device: MaintDevice, source: AskSource, untilMs: Long, nowMs: Long): String =
        "$TAG｜${device.cn}｜延后｜谁问：${askCn(source)}｜" +
            "为什么：单件暂停中（还停 ${MaintPause.leftLabel(untilMs - nowMs)}）"
}
