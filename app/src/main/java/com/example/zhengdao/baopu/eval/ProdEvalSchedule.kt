// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十八批（派工单 §2）：ProdEval 调用点的**节流与单飞**（会话收尾触发用）。
//
// 三条口径：
// 1. **节流**：同一台设备 24h 最多跑一次——收尾事件可能一分钟来好几次，
//    而每一轮烧的是用户的额度（派工单 §2「如 24h 一次」）；
// 2. **单飞**：上一轮还在 IO 上跑就不排第二次（跑一轮要一两分钟，会话收尾比它勤）；
// 3. **纯判定**：[shouldRun] 不碰 Context——「该不该跑」要在 JVM 里被钉子钉住，
//    不能靠真机点出来（本仓无 Robolectric，Context 相关的只能靠纯函数＋源码门禁）。
package com.example.zhengdao.baopu.eval

import android.content.Context
import androidx.core.content.edit
import java.util.concurrent.atomic.AtomicBoolean

internal object ProdEvalSchedule {

    /** 两次跑批之间的最小间隔（派工单 §2：24h 一次）。 */
    const val INTERVAL_MS = 24L * 60 * 60 * 1000

    /** 上次**真跑成**的时刻（prefs；只在 Recorded 之后写——NotRun 不写，理由见 [markRan]）。 */
    private const val PREF_KEY_LAST_RAN = "eval_prod_last_ran"

    /** 单飞门（进程级；防的是「同时在跑」，与进程重启无关）。 */
    private val inFlight = AtomicBoolean(false)

    /**
     * 该不该跑（**纯函数**；负例靶子：把 enabled/network 判断删了 ⇒ 关着也跑，本条红）。
     *
     * @param enabled 用户开关（[ProdEvalGate.enabledNow]；false ⇒ 一律不跑）
     * @param networkAvailable 网络可用（查询失败按不通处理——不赌）
     * @param lastRanAt 上次跑成的时刻；null = 从来没跑过（首次放行）
     * @param now 当前时刻（调用方传，便于 JVM 直测边界）
     * @param intervalMs 节流间隔（默认 [INTERVAL_MS]）
     */
    fun shouldRun(
        enabled: Boolean,
        networkAvailable: Boolean,
        lastRanAt: Long?,
        now: Long,
        intervalMs: Long = INTERVAL_MS,
    ): Boolean {
        if (!enabled || !networkAvailable) return false
        if (lastRanAt == null) return true
        return now - lastRanAt >= intervalMs
    }

    /** 现读上次跑成时刻（读不到／没记过 ⇒ null = 从来没跑过）。 */
    fun lastRanAt(ctx: Context): Long? = runCatching {
        prefs(ctx).getLong(PREF_KEY_LAST_RAN, 0L)
    }.getOrNull()?.takeIf { it > 0L }

    /**
     * 记一次「跑成了」（只由接线层在 [ProdEvalOutcome.Recorded] 之后调用）。
     *
     * 为什么 NotRun 不记：没 key／没网／用例读不到的时候记一笔，会把接下来 24h
     * 全堵上——等用户把 key 补好，还得再干等一天。节流堵的是「真跑过的」。
     */
    fun markRan(ctx: Context, at: Long) {
        runCatching {
            prefs(ctx).edit { putLong(PREF_KEY_LAST_RAN, at) }
        }
    }

    /** 抢单飞门：抢到 ⇒ true（这一轮归我）；已有在跑 ⇒ false。 */
    fun tryAcquire(): Boolean = inFlight.compareAndSet(false, true)

    /** 放单飞门（抢到就一定在 finally 里放；没抢到不许放——那是别人的门）。 */
    fun release() {
        inFlight.set(false)
    }

    private fun prefs(ctx: Context) =
        ctx.getSharedPreferences(ProdEvalGate.PREFS_NAME, Context.MODE_PRIVATE)
}
