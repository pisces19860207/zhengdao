// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · **离场开关（纯状态）**（388 派工单第一件）。
// SharedPreferences 是唯一真相（项目惯例：`zhengdao-*` 一个用途一个文件）；
// [changeTick] 是 Compose 可观察的"变了"信号（照 AppearancePrefs 的样板，不 recreate）。
//
// 三条纪律：
// 1. **这里只有状态，没有裁决**：开/关/降级/暂停的值与读写口在这，"该不该降"归
//    UnattendedCadence，"该不该停"归 judgeUnattended——状态层不替判定层做主。
// 2. **锁屏判定是本仓第一处 KeyguardManager**：[lockedNow] 用系统钥匙串的"现在锁着吗"，
//    读不到（getSystemService 空/异常）⇒ false——锁屏只影响"卡弹不弹"，
//    读不出锁态宁可多问一次（卡被压错才是事故），与电量探针的 fail-closed 方向相反，
//    因为这两处的"错"代价不同。
// 3. **awayAndLocked 是引擎唯一的问口**：RunEngine 弹卡前只问这一句（装配层注入），
//    引擎不摸 SP、不摸 KeyguardManager。
package com.example.zhengdao.unattended

import android.app.KeyguardManager
import android.content.Context
import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import com.example.zhengdao.core.RunLog

object UnattendedStatus {

    private const val SP = "zhengdao-unattended"
    private const val KEY_ARMED = "armed"
    private const val KEY_ARMED_AT = "armed_at_ms"
    private const val KEY_AUTO_DISARM_AT = "auto_disarm_at_ms"
    private const val KEY_SCOPE = "scope"
    private const val KEY_DEGRADED = "degraded"
    private const val KEY_LAST_SUMMARY = "last_summary_at_ms"
    private const val KEY_LAST_BEAT = "last_beat_at_ms"
    private const val KEY_PAUSED = "paused_wires"

    /** 偏好变更信号（Compose 可观察；只表示"变了"，读方 remember(tick) 重读）。 */
    internal val changeTick = mutableIntStateOf(0)

    /**
     * 应用上下文（WeakReference：lint StaticFieldLeak 的口径——静态字段不攥 Context；
     * Application 与进程同寿，弱引实际上永不失效，但形状要守规矩）。
     */
    @Volatile
    private var appCtxRef: java.lang.ref.WeakReference<Context>? = null

    /** 装配口（ZhengdaoApp onCreate 调一次；此后无 Context 的读口才可用）。 */
    fun install(ctx: Context) {
        appCtxRef = java.lang.ref.WeakReference(ctx.applicationContext)
    }

    private fun ctx(): Context? = appCtxRef?.get()

    private fun prefs(ctx: Context?) =
        ctx?.getSharedPreferences(SP, Context.MODE_PRIVATE)

    private fun readBool(key: String, def: Boolean = false): Boolean =
        runCatching { prefs(ctx())?.getBoolean(key, def) }.getOrNull() ?: def

    private fun readLong(key: String): Long? =
        runCatching { prefs(ctx())?.getLong(key, 0L) }.getOrNull()?.takeIf { it > 0L }

    private fun write(block: (android.content.SharedPreferences.Editor) -> Unit) {
        runCatching { prefs(ctx())?.edit { block(this) } }
        changeTick.intValue++
    }

    // ── 开关本体 ──────────────────────────────────────────────────────────

    /** 现在开着值守吗。 */
    fun armed(): Boolean = readBool(KEY_ARMED)

    /** 开值守的时刻（没开过 ⇒ null）。 */
    fun armedAtMs(): Long? = if (armed()) readLong(KEY_ARMED_AT) else null

    /** 开值守时写下的托付范围说明（自由文本；圈内/圈外的机器判据归接线批，这里只是账）。 */
    fun scopeNote(): String = runCatching { prefs(ctx())?.getString(KEY_SCOPE, "") }.getOrNull().orEmpty()

    /**
     * 开值守（幂等：已开着就原样保留最初的时刻与范围）。
     *
     * 第四百四十一单加法：[autoDisarmAtMs] 是"到点自动收值守"的时刻（一句话值守带时长时
     * 由接线层算好写进来；null ＝ 没带时长，不自动收）。只写值，到没到期**判不归这里**
     * （归服务的节拍，见 UnattendedDutyService.autoDisarmPass——状态层不替判定层做主）。
     */
    fun arm(scope: String, nowMs: Long, autoDisarmAtMs: Long? = null) {
        if (armed()) return
        write {
            it.putBoolean(KEY_ARMED, true)
                .putLong(KEY_ARMED_AT, nowMs)
                .putString(KEY_SCOPE, scope.trim())
                .putBoolean(KEY_DEGRADED, false)
                .putLong(KEY_LAST_SUMMARY, 0L)
                .putLong(KEY_AUTO_DISARM_AT, autoDisarmAtMs ?: 0L)
        }
        RunLog.log("离场值守：开（范围：${scope.trim().ifEmpty { "未写" }}）")
    }

    /** 收值守（幂等；自动到期时刻一并清——留着就是下次误收的雷）。 */
    fun disarm() {
        if (!armed()) return
        write {
            it.putBoolean(KEY_ARMED, false)
                .putLong(KEY_AUTO_DISARM_AT, 0L)
        }
        RunLog.log("离场值守：收")
    }

    // ── 自动到期（441 加法：一句话值守带时长 ⇒ 到点自动收） ───────────────

    /** 自动收值守的时刻（没带时长 ⇒ null；已收 ⇒ null）。 */
    fun autoDisarmAtMs(): Long? = if (armed()) readLong(KEY_AUTO_DISARM_AT) else null

    // ── 降级（B级汇总超时；单行道，复工＝收值守再开） ────────────────────

    fun degraded(): Boolean = readBool(KEY_DEGRADED)

    fun setDegraded(value: Boolean) = write { it.putBoolean(KEY_DEGRADED, value) }

    // ── 汇总节拍与心跳（服务与看门狗的凭据） ────────────────────────────

    fun lastSummaryAtMs(): Long? = readLong(KEY_LAST_SUMMARY)

    fun setLastSummaryAtMs(ms: Long) = write { it.putLong(KEY_LAST_SUMMARY, ms) }

    fun lastBeatMs(): Long? = readLong(KEY_LAST_BEAT)

    /** 服务心跳打点。 */
    fun beat(nowMs: Long) = write { it.putLong(KEY_LAST_BEAT, nowMs) }

    // ── 环境暂停（理由集随进程死亡落 SP，活着时是内存态） ────────────────

    internal fun pausedReasons(): Set<UnattendedEnvReason> {
        val wire = runCatching { prefs(ctx())?.getString(KEY_PAUSED, "") }.getOrNull().orEmpty()
        return wire.split(',').filter { it.isNotBlank() }
            .mapNotNull { UnattendedEnvReason.ofWire(it) }
            .toSet()
    }

    internal fun setPausedReasons(reasons: Set<UnattendedEnvReason>) = write {
        it.putString(KEY_PAUSED, reasons.joinToString(",") { r -> r.wire })
    }

    // ── 锁屏与引擎问口 ───────────────────────────────────────────────────

    /** 现在锁着屏吗（本仓第一处 KeyguardManager；读不到 ⇒ false，见文件头纪律 2）。 */
    fun lockedNow(): Boolean {
        val ctx = ctx() ?: return false
        return runCatching {
            val km = ctx.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.isKeyguardLocked ?: false
        }.getOrDefault(false)
    }

    /** 引擎弹卡前的唯一问口：开着值守**且**锁着屏 ⇒ 卡不弹、进队列。 */
    fun awayAndLocked(): Boolean = armed() && lockedNow()
}
