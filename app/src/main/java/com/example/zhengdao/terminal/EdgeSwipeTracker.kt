// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

/**
 * 「左边缘右滑返回」的手势判定（纯状态机，可单测；不碰 View、不消费事件）。
 *
 * ## 背景（2026-10-08 真机实测重写，见 ERRATA E-023）
 * 终端页原先唯一的出口是左上角 11dp 的红点，单手够不到；旧实现把监听挂在子 View 上、
 * 识别带 32dp / 阈值 56dp 又与真人落点错开，留下 140–190px 死区。现在改成 Activity 层
 * **旁观**手势：本类只负责"这次滑动算不算右滑返回"，事件照旧交回给 `super`，
 * 终端自己的滚动/选词不受影响。
 *
 * ## 判据（与搬移前逐字一致）
 * 起手点在边缘带内 → 跟踪；移动时同时满足
 * 1. 横向位移走够触发距离（`dx > triggerPx`）；
 * 2. 横向占优（`dx > dy`）——纵向滑动一律放过，终端继续滚动；
 * 3. 纵向没跑偏（`dy < slopPx`）；
 * 4. **不处于"正在选词"**（2026-10-10 UIUX 审计 B7）：长按选中后拖动选择手柄，
 *    轨迹恰好是"横向 >40dp、纵向 <40dp"，与右滑返回判据同形；不查选词状态就会
 *    "想选一段命令"被判成"右滑关闭"，整屏没了且无撤销。
 *
 * 命中后内部即清跟踪，同一次手势不会被重复判成两次关闭（MOVE 命中后紧跟的 UP 不再触发）。
 *
 * @see com.example.zhengdao.TerminalActivity.dispatchTouchEvent 唯一调用方
 */
internal class EdgeSwipeTracker {

    private var startX = 0f
    private var startY = 0f
    private var tracking = false

    /** 最近一次 [onDrag] 的横向位移（诊断用：命中日志要打 dx/dy/startX）。 */
    var lastDx: Float = 0f
        private set

    /** 最近一次 [onDrag] 的纵向位移（绝对值）。 */
    var lastDy: Float = 0f
        private set

    /** 本次手势的起手点横坐标（诊断用）。 */
    val startXValue: Float get() = startX

    /** 当前是否处于"从边缘起手"的跟踪状态（诊断/测试用）。 */
    val isTracking: Boolean get() = tracking

    /**
     * ACTION_DOWN：起手点在边缘带内才开始跟踪。
     *
     * @return 是否进入跟踪（false 时后续 [onDrag] 一律不判）。
     */
    fun onDown(x: Float, y: Float, edgePx: Float): Boolean {
        tracking = x <= edgePx
        if (tracking) {
            startX = x
            startY = y
        }
        return tracking
    }

    /**
     * ACTION_MOVE / ACTION_UP 共用的一次位移判定（UP 兜底：有些设备最后一帧 MOVE 会丢）。
     *
     * @param selectingText **惰性**求值：只有真的在跟踪时才去问选词状态，普通滚动零开销。
     *   搬移前它同样只在 `edgeTracking` 分支里求值。
     * @return true = 这次滑动算「右滑返回」（内部已清跟踪）。
     */
    fun onDrag(
        x: Float,
        y: Float,
        triggerPx: Float,
        slopPx: Float,
        selectingText: () -> Boolean,
    ): Boolean {
        if (!tracking) return false
        lastDx = x - startX
        lastDy = kotlin.math.abs(y - startY)
        return if (!selectingText() && lastDx > triggerPx && lastDx > lastDy && lastDy < slopPx) {
            tracking = false
            true
        } else {
            false
        }
    }

    /** ACTION_UP 收尾：不论是否命中都结束跟踪（与搬移前 `if (ACTION_UP) edgeTracking = false` 一致）。 */
    fun onUp() {
        tracking = false
    }

    /** ACTION_CANCEL：系统抢走事件序列，结束跟踪。 */
    fun onCancel() {
        tracking = false
    }
}
