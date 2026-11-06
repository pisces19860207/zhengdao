// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百零四单 · 471「停机在环」的**停机状态**（软停/硬停两档）。
// 派工单原话：软停（不发起新动作）/ 硬停（中止当前动作，先有 470 兜底）两档；
// **停机持久化**：重启 App / 重启手机仍是停的；**解除只能人做**——这是唯一保留的人工点。
//
// 四条纪律（照 UnattendedStatus 388 的房型）：
// 1. **SharedPreferences 是唯一真相**（`zhengdao-safety` 一个用途一个文件）：
//    SP 落在内部存储 ⇒ App 重启在、手机重启也在——判据⑥「重启后仍停机」的结构保证。
// 2. **这里只有状态，没有动作**：写值与读口在这；"中止当前动作"归 SafetyWiring
//    （它拿得到引擎），"该不该拦新动作"归 GuardPipeline 的监督钩子与各自动入口的闸。
// 3. **解除只认人工**：[lift] 的 `byHuman` 参数是唯一通道；模型（工具调用）传
//    byHuman=false ⇒ 拒（判据⑦）。本类不认识"模型"以外还有谁能不是人。
// 4. **单向加档**：软停可升硬停（硬停是软停的全部＋中止在飞），硬停不因软停请求降档。
package com.example.zhengdao.baopu.safety

import androidx.compose.runtime.mutableIntStateOf
import androidx.core.content.edit
import com.example.zhengdao.core.RunLog

/** 停机档位（闭集三格；wire 落盘用，认不出按 NONE 处理＝宁可不冤枉它停机）。 */
enum class HaltLevel(val wire: String, val cn: String) {
    /** 运行中（没有停）。 */
    NONE("none", "运行中"),

    /** 软停：不再发起新动作，当前步跑完即停（469③ 的「叫停」就是它）。 */
    SOFT("soft", "软停"),

    /** 硬停：中止当前动作（470 已先兜底记好原件），停机期调度/值守/事件一律不启动。 */
    HARD("hard", "硬停"),
    ;

    companion object {
        /** 认不出就 NONE（坏值不静默当"停机"，也不当"硬停"）。 */
        fun ofWire(wire: String): HaltLevel = entries.firstOrNull { it.wire == wire } ?: NONE
    }
}

/** 一次停机的留证（469④：谁停的/何时/当时在做什么——三样都要有才叫证）。 */
data class HaltRecord(
    val level: HaltLevel,
    val by: String,
    val atMs: Long,
    val where: String,
) {
    /** 起居注/账本用的一行人话。 */
    val line: String get() = "${level.cn}｜谁停的：$by｜何时：$atMs｜当时：$where"
}

/**
 * 停机闸（进程级单例；状态层，不做动作——见文件头纪律 2）。
 *
 * [changeTick] 照 UnattendedStatus 的样板：写一次加一，Compose 的卡 remember(tick) 重读。
 */
object HaltGate {

    private const val SP = "zhengdao-safety"
    private const val KEY_LEVEL = "halt_level"
    private const val KEY_BY = "halt_by"
    private const val KEY_AT = "halt_at_ms"
    private const val KEY_WHERE = "halt_where"

    /** 解除被拒时模型会看到的原话（判据⑦的钉子；文案只此一处）。 */
    const val LIFT_REFUSED_LINE: String = "解除停机只能由人工在安全台操作（唯一保留的人工点），模型调用一律拒绝"

    /** 偏好变更信号（Compose 可观察；只表示"变了"）。 */
    internal val changeTick = mutableIntStateOf(0)

    /** 弱引 Context（lint StaticFieldLeak 口径，照 UnattendedStatus 同一条）。 */
    @Volatile
    private var appCtxRef: java.lang.ref.WeakReference<android.content.Context>? = null

    /** 装配口（ZhengdaoApp onCreate 调一次）。 */
    fun install(ctx: android.content.Context) {
        appCtxRef = java.lang.ref.WeakReference(ctx.applicationContext)
    }

    private fun prefs() =
        appCtxRef?.get()?.getSharedPreferences(SP, android.content.Context.MODE_PRIVATE)

    private fun readLevel(): HaltLevel = runCatching {
        HaltLevel.ofWire(prefs()?.getString(KEY_LEVEL, HaltLevel.NONE.wire).orEmpty())
    }.getOrDefault(HaltLevel.NONE)

    private fun write(block: (android.content.SharedPreferences.Editor) -> Unit) {
        runCatching { prefs()?.edit { block(this) } }
        changeTick.intValue++
    }

    // ── 读口 ─────────────────────────────────────────────────────────────

    /** 现在停机吗（任何档都算停——软停/硬停都拦新动作）。 */
    fun halted(): Boolean = readLevel() != HaltLevel.NONE

    /** 现在的档位。 */
    fun level(): HaltLevel = readLevel()

    /** 是不是硬停（硬停才"中止当前动作"）。 */
    fun hardHalted(): Boolean = readLevel() == HaltLevel.HARD

    /** 停机留证（没停 ⇒ null）。 */
    fun record(): HaltRecord? {
        if (readLevel() == HaltLevel.NONE) return null
        val p = prefs() ?: return null
        return runCatching {
            HaltRecord(
                level = readLevel(),
                by = p.getString(KEY_BY, "").orEmpty().ifEmpty { "未知" },
                atMs = p.getLong(KEY_AT, 0L),
                where = p.getString(KEY_WHERE, "").orEmpty().ifEmpty { "未记" },
            )
        }.getOrNull()
    }

    // ── 写口（叫停的两档） ───────────────────────────────────────────────

    /**
     * 软停（469③「叫停」＝这一档）：不再发起新动作，当前步跑完即停。
     * 已是硬停 ⇒ 原样保留（单向加档，文件头纪律 4）。
     */
    fun softStop(by: String, where: String, nowMs: Long) {
        if (readLevel() == HaltLevel.HARD) return
        putStop(HaltLevel.SOFT, by, where, nowMs)
        RunLog.log("安全台：软停（$by）——$where")
    }

    /** 硬停：软停的全部＋中止当前动作（中止本身归 SafetyWiring，它拿得到引擎）。 */
    fun hardStop(by: String, where: String, nowMs: Long) {
        putStop(HaltLevel.HARD, by, where, nowMs)
        RunLog.log("安全台：硬停（$by）——$where")
    }

    private fun putStop(level: HaltLevel, by: String, where: String, nowMs: Long) {
        write {
            it.putString(KEY_LEVEL, level.wire)
                .putString(KEY_BY, by)
                .putLong(KEY_AT, nowMs)
                .putString(KEY_WHERE, where)
        }
    }

    // ── 解除（唯一保留的人工点） ─────────────────────────────────────────

    /**
     * 解除停机。
     *
     * @param byHuman 只有人工入口（安全台页）许传 true；模型路径传 false ⇒ 拒且**状态不动**。
     * @return true＝真解除了；false＝被拒（模型调用/本来就没停）。
     */
    fun lift(byHuman: Boolean, nowMs: Long): Boolean {
        if (!byHuman) return false
        if (readLevel() == HaltLevel.NONE) return false
        write {
            it.putString(KEY_LEVEL, HaltLevel.NONE.wire)
                .putString(KEY_BY, "")
                .putLong(KEY_AT, 0L)
                .putString(KEY_WHERE, "")
        }
        RunLog.log("安全台：人工解除停机（$nowMs）")
        return true
    }
}
