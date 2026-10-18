// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

/**
 * 网络探针的状态机（纯逻辑，可单测）。
 *
 * ## 为什么要有它（2026-10-14 用户点名的用途）
 * 终端页顶部那条「网络不通」横幅，用户实测**有用**——它就是「代理 App 被系统后台杀掉」的证据。
 * 但它必须同时满足两件事：
 * 1. 代理中途被杀要**报得出来**（所以不能"一次会话只探一次"）；
 * 2. 网络恢复了要**自己消失**（用户原先抱怨的正是"网络早就好了，横幅还挂着"）。
 *
 * 于是探针改成周期性的"活体指示"，本类负责判定，规则：
 * - **第一次观测立刻定状态** —— 终端页刚打开时不给用户 40 秒的"未知"；
 * - 之后 **连续 [failThreshold] 次失败**才判"不通"（抵掉一次网络抖动，别让横幅闪）；
 * - **一次成功立刻**判"通"（恢复要快，这正是用户要的那个信号）。
 *
 * [observe] 返回"这次观测有没有让状态翻转"，调用方据此决定要不要刷横幅 / 写日志
 * —— 同一状态下的重复探测**不产生重复日志**（这正是 2026-10-14 另一处被抱怨的刷屏）。
 *
 * @param failThreshold 连续失败多少次才判"不通"；默认 2（一次抖动不报警）。
 */
class NetWatch(private val failThreshold: Int = 2) {

    private var failures = 0
    private var primed = false

    /** 当前判定：是否连通。初值 true（未知即乐观，避免没探之前就报警）。 */
    var online: Boolean = true
        private set

    /** 已经观测过多少次（诊断用）。 */
    var observations: Int = 0
        private set

    /**
     * 喂一次观测结果。
     *
     * @return true = 这次观测让 [online] 翻转了（或这是第一次观测）；false = 状态没变。
     */
    fun observe(ok: Boolean): Boolean {
        observations += 1
        if (!primed) {
            primed = true
            online = ok
            failures = if (ok) 0 else 1
            return true
        }
        val before = online
        if (ok) {
            failures = 0
            online = true
        } else {
            failures += 1
            if (failures >= failThreshold) online = false
        }
        return online != before
    }

    /** 累计失败次数（只读；第二十一批 D 段：「真有账可清」判据，日志不刷屏）。 */
    val failureCount: Int get() = failures

    /** 清掉累计的失败次数（终端页离开/回来时调用）；**不改变**当前判定。 */
    fun resetFailures() {
        failures = 0
    }

    companion object {
        /** 活体探针周期：20 秒 —— 快得足以当"代理被杀"的报警，又不至于费电（只在终端页可见时跑）。 */
        const val PROBE_INTERVAL_MS = 20_000L
    }
}
