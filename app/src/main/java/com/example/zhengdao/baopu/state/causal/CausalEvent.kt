// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：事件日志结构。
//
// 一条事件＝「什么时候（[CausalEvent.at]，场景刻度整数）／谁引起的（[CausalEvent.parentEventId]）／
// 是什么（[CausalEvent.kind]）／落在哪（[CausalEvent.site]）／多强（[CausalEvent.intensity]）／
// 立刻看得见什么（[CausalEvent.immediate]）／过后还会怎样（[CausalEvent.delayed]）」。
//
// 三处刻意的硬约束：
//   ① **滞后项必带 825 跨度**（[DelayedGrade.span] 不是可空）——「过一段」这种话在类型上写不出来。
//   ② **立即项不许带跨度**（`GradeWrite` 本身没有 span 字段）——想给立即项加时间，得先改类型，
//      改类型就得过本包的门禁与测试，不可能悄悄加。
//   ③ **增量写法必须已有基线**（[GradeWrite.By] 在无基线时返回 null，回放记一条红，不默认 0）：
//      825 §二「缺项 ⇒ 显式标未设定」，静默给 0 就等于凭空造了一个「本来没事」。
package com.example.zhengdao.baopu.state.causal

/** 一次「把某个维度写到某档」的动作。两种写法，闭集。 */
internal sealed interface GradeWrite {
    val dim: CausalDim

    /** 定到某档（绝对）。528 的观察档、658 的疲劳档都是这么写的。 */
    data class To(override val dim: CausalDim, val level: Int) : GradeWrite

    /** 在当前档上进/退几档（相对）。疲劳累积、汗水干去是这么写的。 */
    data class By(override val dim: CausalDim, val delta: Int) : GradeWrite

    /**
     * 在当前档 [current] 上算出新档。
     *
     * 返回 null＝**没有基线**：增量写法丢掉了唯一能算的起点。调用方必须把这条当红报，
     * 而不是拿 0 顶上去（顶上去就等于宣布「他本来是完好/清醒的」，那句话没人说过）。
     */
    fun appliedTo(current: Int?): Int? = when (this) {
        is To -> dim.clamp(level)
        is By -> current?.let { dim.clamp(it + delta) }
    }

    /** 越界判定：绝对写法给了区间外的档。相对写法越界由 [appliedTo] 钳位后不再判红（钳位是明确的）。 */
    fun escape(): Boolean = this is To && !dim.inRange(level)

    companion object {
        /** 语义糖：把维度定到某档。 */
        fun to(dim: CausalDim, level: Int): GradeWrite = To(dim, level)

        /** 语义糖：在当前档上加减。 */
        fun by(dim: CausalDim, delta: Int): GradeWrite = By(dim, delta)
    }
}

/** 滞后后果：一条写入 ＋ 它到期要多久（825 四档之一，不可空）。 */
internal data class DelayedGrade(val write: GradeWrite, val span: Span825)

/**
 * 一条事件。
 *
 * [at] 是**场景刻度**（整数，由调用方给），不是墙钟——本包不读时间、不算差值，
 * 刻度只用来说明「父子事件谁先谁后」（父的刻度大于子的 ⇒ 判红）。
 *
 * [note] 是溯源短注（例：`657 sweat.grade=2`），渲染时附在行尾；空串表示没有可写的溯源。
 */
internal data class CausalEvent(
    val eventId: String,
    val parentEventId: String?,
    val at: Int,
    val kind: CausalKind,
    val site: SiteRef,
    val intensity: Int,
    val mechanism: InjuryMechanism? = null,
    val immediate: List<GradeWrite> = emptyList(),
    val delayed: List<DelayedGrade> = emptyList(),
    val note: String = "",
) {
    /** 强度档的上限：与 728 观察档同一刻度（0–3），本包不另立一套强度刻度。 */
    val intensityInRange: Boolean get() = intensity in 0..INTENSITY_MAX

    companion object {
        const val INTENSITY_MAX = 3
    }
}

/**
 * 事件日志：一串事件＋按 id 的索引。
 *
 * 顺序**不进索引**：回放要按（刻度, 日志内次序）稳定排序，而**日志内次序**是调用方给的
 * 唯一稳定信息（同刻度的两条事件谁先？日志顺序说了算，不靠 id 字典序，免得改个名就换结果）。
 */
internal data class CausalLog(val events: List<CausalEvent>) {

    val byId: Map<String, CausalEvent> = buildMap {
        events.forEach { e -> if (!containsKey(e.eventId)) put(e.eventId, e) }
    }

    fun event(eventId: String): CausalEvent? = byId[eventId]

    fun indexOf(eventId: String): Int = events.indexOfFirst { it.eventId == eventId }

    /** 稳定排序：同刻度保持日志内相对次序。 */
    fun ordered(): List<CausalEvent> =
        events.withIndex().sortedWith(compareBy({ it.value.at }, { it.index })).map { it.value }

    /** 从根到该事件的祖先链（含自身），断链处停下——断链本身由检查器判红。 */
    fun ancestry(eventId: String): List<CausalEvent> {
        val out = ArrayList<CausalEvent>()
        var cur = byId[eventId]
        var guard = 0
        while (cur != null && guard <= events.size) {
            out.add(cur)
            cur = cur.parentEventId?.let { byId[it] }
            guard++
        }
        return out.asReversed()
    }

    companion object {
        fun of(vararg events: CausalEvent): CausalLog = CausalLog(events.toList())
    }
}
