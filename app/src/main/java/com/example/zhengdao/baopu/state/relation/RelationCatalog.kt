// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十一批 · 人际状态层 · 唯一目录表（段 A）。
// 边界：不重做 670/747 的 RelationTrack 11 态；「格」只以 RelationState 的形式被引用。
// 907 S5：关系与生理反应分离——本包不认识任何生理反应类型。
// 表格驱动：所有数值只有这一处真相源；改表必须同步计数锁与夹具。
package com.example.zhengdao.baopu.state.relation

import com.example.zhengdao.baopu.state.persona.PersonaValues
import com.example.zhengdao.baopu.state.persona.ValueAxis
import com.example.zhengdao.baopu.state.track.RelationState

internal const val RELATION_SOURCE = "921 人际状态层 / 907 S5"
internal const val RELATION_AS_OF = "2026-11-27"

/** 七类关系（锁死；上下级按 A→B 的方向记类，反向另存）。 */
internal enum class RelationKind(val label: String, val rank: Int) {
    KIN("亲属", 6),
    PARTNER("伴侣", 7),
    COLLEAGUE("同事", 3),
    SUPERIOR("上下级", 4),
    FRIEND("朋友", 5),
    ACQUAINTANCE("熟人", 2),
    STRANGER("陌生人", 1);

    companion object {
        const val COUNT = 7
    }
}

/** 脸认档：明显脸盲不许写「一眼认出」。 */
internal enum class FaceRecognition(val label: String) {
    NORMAL("正常"),
    WEAK("偏弱"),
    OBVIOUS("明显脸盲");

    companion object {
        const val COUNT = 3
    }
}

/** 可指事件的四类（上升必须挂其中一类并写明 ref）。 */
internal enum class EventKind(val label: String) {
    HELP("帮忙"),
    TOGETHER("共处"),
    SHARED("共同经历"),
    CRISIS("危机");

    companion object {
        const val COUNT = 4
    }
}

/** 拒绝/退回/保持距离三态：关系不是单调上升。 */
internal enum class RefusalMode(val label: String) {
    REJECT("拒绝"),
    WITHDRAW("退回"),
    KEEP_DISTANCE("保持距离");

    companion object {
        const val COUNT = 3
    }
}

/** 三值判定与 805/935 同口径，不另立第四种词。 */
internal enum class RelVerdict(val label: String) {
    PASS("通过"),
    NEED_CHECK("提示"),
    DENY("红");
}

/** 一类关系的可达上限；conflict 全程 0–100，另由范围构造守。 */
internal data class Ceiling(val trust: Int, val dependency: Int, val intimacy: Int) {
    init {
        require(listOf(trust, dependency, intimacy).all { it in 0..3 }) {
            "ceiling 只能取 0–3 档：$this"
        }
    }

    fun of(dim: String): Int = when (dim) {
        "trust" -> trust
        "dependency" -> dependency
        "intimacy" -> intimacy
        else -> throw IllegalArgumentException("未知维度：$dim")
    }

    companion object {
        fun tier(value: Int): String = when (value) {
            0 -> "无"
            1 -> "低"
            2 -> "中"
            3 -> "高"
            else -> "越界"
        }
    }
}

/** 目录与起始值表：纯函数，零 IO、零装配。 */
internal object RelationCatalog {
    const val DIM_MAX = 3
    const val CONFLICT_MAX = 100
    const val DIM_COUNT = 4
    const val PSYCH_COUNT = 2

    /** 同事 trust 上限=2（中）——派工单钉死的一格。 */
    val CEILINGS: Map<RelationKind, Ceiling> = mapOf(
        RelationKind.KIN to Ceiling(3, 3, 2),
        RelationKind.PARTNER to Ceiling(3, 3, 3),
        RelationKind.COLLEAGUE to Ceiling(2, 1, 1),
        RelationKind.SUPERIOR to Ceiling(2, 2, 1),
        RelationKind.FRIEND to Ceiling(3, 2, 2),
        RelationKind.ACQUAINTANCE to Ceiling(1, 1, 1),
        RelationKind.STRANGER to Ceiling(0, 0, 0),
    )

    /** 无锚定时只允许这张表的陌生人低值。 */
    val STRANGER_START = RelationDims(0, 0, 0, 0)

    private val BASE: Map<RelationKind, RelationDims> = mapOf(
        RelationKind.KIN to RelationDims(2, 2, 0, 2),
        RelationKind.PARTNER to RelationDims(2, 2, 0, 2),
        RelationKind.COLLEAGUE to RelationDims(1, 0, 0, 1),
        RelationKind.SUPERIOR to RelationDims(1, 1, 0, 1),
        RelationKind.FRIEND to RelationDims(2, 1, 0, 2),
        RelationKind.ACQUAINTANCE to RelationDims(1, 0, 0, 1),
        RelationKind.STRANGER to STRANGER_START,
    )

    /** 670 的格中，这几态算「已经有过实质往来」，只影响起始档，不改 11 态拓扑。 */
    private val TRACK_LATE = setOf(
        RelationState.T4P, RelationState.T5, RelationState.T6,
        RelationState.TE1, RelationState.TE2, RelationState.TG,
    )

    init {
        require(CEILINGS.size == RelationKind.COUNT) { "ceiling 表须覆盖七类关系" }
        require(BASE.size == RelationKind.COUNT) { "起始表须覆盖七类关系" }
        require(CEILINGS.keys == BASE.keys) { "ceiling 与起始表键不一致" }
    }

    /**
     * 起始值只从锚定来：人物设定 BASE + 670 的格 + 930 的 valueAlign。
     * topicOverlap 不在参数里——兴趣只推 rapport，永远推不到 trust。
     */
    fun startDims(kind: RelationKind, track: RelationState?, valueAlign: Int): RelationDims {
        require(valueAlign in 0..3) { "valueAlign 只取 0–3：$valueAlign" }
        val cap = CEILINGS.getValue(kind)
        val base = BASE.getValue(kind)
        val late = track in TRACK_LATE
        val trustGain = (if (valueAlign >= 3) 1 else 0) + (if (late) 1 else 0)
        return RelationDims(
            trust = minOf(cap.trust, base.trust + trustGain),
            dependency = minOf(cap.dependency, base.dependency + if (late) 1 else 0),
            conflict = 0,
            intimacy = minOf(cap.intimacy, base.intimacy),
        )
    }

    /** valueAlign 只数八轴中同档的个数，不读 topicOverlap。 */
    fun valueAlign(a: PersonaValues?, b: PersonaValues?): Int {
        if (a == null || b == null) return 0
        val ma = a.weights.associate { it.axis to it.tier }
        val mb = b.weights.associate { it.axis to it.tier }
        val same = ValueAxis.entries.count { ma[it] != null && ma[it] == mb[it] }
        return when {
            same >= 7 -> 3
            same >= 5 -> 2
            same >= 3 -> 1
            else -> 0
        }
    }

    /** 表的一行文字，供夹具打印四维表。 */
    fun ceilingLine(kind: RelationKind): String {
        val c = CEILINGS.getValue(kind)
        return kind.label + "|trust=" + Ceiling.tier(c.trust) +
            "|dependency=" + Ceiling.tier(c.dependency) +
            "|intimacy=" + Ceiling.tier(c.intimacy) +
            "|conflict=0.." + CONFLICT_MAX
    }
}
