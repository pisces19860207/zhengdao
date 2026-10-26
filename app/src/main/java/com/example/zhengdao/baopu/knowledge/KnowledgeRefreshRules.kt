// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-24-第一百八十二批 §0.5（不赌 FileObserver；不做后台无脑全扫；
// 手动触发＋进前台一次增量；周期扫描仅前台兜底；大批量进度可取消）。
package com.example.zhengdao.baopu.knowledge

import java.util.concurrent.atomic.AtomicBoolean

/**
 * 刷新触发与门控（**纯函数**，JVM 直测）。
 *
 * 这张表就是「后台无脑扫」负例的判据：除 [Trigger.START]（进程启动时的一次性增量）
 * 与 [Trigger.MANUAL]/[Trigger.ORGANIZE_DONE]（用户正在界面上动手）之外，
 * **只要 App 不在前台就不许跑**；自动刷新关掉时只剩手动。
 */
internal object KnowledgeRefreshRules {

    /** 可选周期兜底（仅前台）。10 分钟与派工单示例一致。 */
    const val PERIOD_MS = 10L * 60L * 1000L

    /** MediaStore 通知的防抖窗口（同一次保存会连发多个事件）。 */
    const val OBSERVER_DEBOUNCE_MS = 2_000L

    enum class Trigger(val key: String) {
        START("start"),
        FOREGROUND("foreground"),
        IMPORT_DONE("import"),
        MEDIASTORE("mediastore"),
        PERIODIC("periodic"),
        MANUAL("manual"),
        ORGANIZE_DONE("organize"),
    }

    data class Gate(val run: Boolean, val why: String)

    /**
     * 这次触发该不该跑。
     *
     * @param lastPeriodAt 上次周期兜底的时间（0＝还没跑过）
     */
    fun gate(
        trigger: Trigger,
        foreground: Boolean,
        autoEnabled: Boolean,
        lastPeriodAt: Long,
        now: Long,
        periodMs: Long = PERIOD_MS,
    ): Gate {
        if (trigger != Trigger.MANUAL && !autoEnabled) {
            return Gate(false, "自动刷新已关（只有手动触发）")
        }
        return when (trigger) {
            // 进程启动的一次性增量：那一刻可能还没有 Activity started，允许跑一次；
            // 它不是「后台常驻」，跑完就停，后续一律要前台。
            Trigger.START -> Gate(true, "")
            Trigger.MANUAL, Trigger.ORGANIZE_DONE -> Gate(true, "")
            Trigger.FOREGROUND, Trigger.IMPORT_DONE, Trigger.MEDIASTORE ->
                if (foreground) Gate(true, "") else Gate(false, "App 不在前台，不扫")
            Trigger.PERIODIC -> when {
                !foreground -> Gate(false, "App 不在前台，周期兜底不跑")
                lastPeriodAt > 0 && now - lastPeriodAt < periodMs -> Gate(false, "还没到周期")
                else -> Gate(true, "")
            }
        }
    }

    /** 进度话术（界面上只说人话；0/0 时不说“0/0”）。 */
    fun progressLine(done: Int, total: Int): String = when {
        total <= 0 -> ""
        done >= total -> "完成 $total 个"
        else -> "$done / $total"
    }
}

/** 一次刷新的状态（装配层 volatile 持有；UI 轮询只读）。 */
internal enum class RefreshState { IDLE, RUNNING, DONE, CANCELLED, FAILED }

internal data class RefreshStatus(
    val state: RefreshState = RefreshState.IDLE,
    val reason: String = "",
    val done: Int = 0,
    val total: Int = 0,
    val files: Int = 0,
    val added: Int = 0,
    val modified: Int = 0,
    val removed: Int = 0,
    val elapsedMs: Long = 0,
    val at: Long = 0,
    val message: String = "",
)

/** 取消闸门 ＋ 进度（只跨线程传计数，不装内容）。 */
internal class RefreshControl {

    private val cancelled = AtomicBoolean(false)

    @Volatile
    var done: Int = 0
        private set

    @Volatile
    var total: Int = 0
        private set

    fun cancel() {
        cancelled.set(true)
    }

    fun isCancelled(): Boolean = cancelled.get()

    fun progress(done: Int, total: Int) {
        this.done = done
        this.total = total
    }

    fun reset() {
        cancelled.set(false)
        done = 0
        total = 0
    }
}
