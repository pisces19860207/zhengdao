// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百二十一批 · 状态结构与锚定：构造即守范围，坏状态造不出来。
// intimacy 与性无关（907 S5）：本文件只有关系档，没有反应档。
package com.example.zhengdao.baopu.state.relation

import com.example.zhengdao.baopu.state.persona.PersonaValues
import com.example.zhengdao.baopu.state.track.RelationState

/** 四维：trust/dependency/intimacy 0–3，conflict 0–100。 */
internal data class RelationDims(
    val trust: Int,
    val dependency: Int,
    val conflict: Int,
    val intimacy: Int,
) {
    init {
        require(trust in 0..RelationCatalog.DIM_MAX) { "trust 只取 0–3：$trust" }
        require(dependency in 0..RelationCatalog.DIM_MAX) { "dependency 只取 0–3：$dependency" }
        require(intimacy in 0..RelationCatalog.DIM_MAX) { "intimacy 只取 0–3：$intimacy" }
        require(conflict in 0..RelationCatalog.CONFLICT_MAX) { "conflict 只取 0–100：$conflict" }
    }

    fun plus(delta: DimsDelta): RelationDims = RelationDims(
        trust = trust + delta.trust,
        dependency = dependency + delta.dependency,
        conflict = conflict + delta.conflict,
        intimacy = intimacy + delta.intimacy,
    )

    fun lines(): List<String> = listOf(
        "trust=$trust", "dependency=$dependency", "conflict=$conflict", "intimacy=$intimacy",
    )
}

/** 两心理维，只给档、不给数值理由。 */
internal data class PsychDims(
    val fearOfAbandon: Int = 0,
    val needToBeNeeded: Int = 0,
) {
    init {
        require(fearOfAbandon in 0..RelationCatalog.DIM_MAX) { "fearOfAbandon 只取 0–3" }
        require(needToBeNeeded in 0..RelationCatalog.DIM_MAX) { "needToBeNeeded 只取 0–3" }
    }
}

/** 一次可指事件：ref 非空才叫「可指」。 */
internal data class RelationEvent(val kind: EventKind, val ref: String) {
    init {
        require(ref.isNotBlank()) { "事件必须有可指 ref（写清哪一次）" }
    }
}

/** 上升量：只接受非负。 */
internal data class DimsDelta(
    val trust: Int = 0,
    val dependency: Int = 0,
    val conflict: Int = 0,
    val intimacy: Int = 0,
) {
    init {
        require(trust in 0..3 && dependency in 0..3 && intimacy in 0..3 && conflict in 0..100) {
            "上升量越界：$this"
        }
    }

    val steps: Int get() = trust + dependency + intimacy
}

internal enum class RelationStatus(val label: String) {
    ACTIVE("在册"),
    REFUSAL("拒绝/退回"),
}

/**
 * 起始锚：人物设定（settingLine）或 670 的格（track）二选一必须有；
 * 930 的 values 只用来算 valueAlign，脸认档随人物设定。
 */
internal data class RelationAnchor(
    val kind: RelationKind,
    val track: RelationState? = null,
    val persona: PersonaValues? = null,
    val settingLine: String = "",
    val face: FaceRecognition = FaceRecognition.NORMAL,
    val psych: PsychDims = PsychDims(),
    val anchored: Boolean = true,
    val valueAlign: Int = 0,
) {
    init {
        require(valueAlign in 0..3) { "valueAlign 只取 0–3" }
        if (anchored) {
            require(track != null || settingLine.isNotBlank()) { "锚定必须给人物设定或 670 的格" }
        } else {
            require(kind == RelationKind.STRANGER && track == null && settingLine.isBlank()) {
                "无锚定只能是陌生人"
            }
        }
    }

    companion object {
        fun stranger(): RelationAnchor = RelationAnchor(
            kind = RelationKind.STRANGER,
            anchored = false,
        )

        fun fromSetting(
            kind: RelationKind,
            settingLine: String,
            track: RelationState? = null,
            mine: PersonaValues? = null,
            theirs: PersonaValues? = null,
            face: FaceRecognition = FaceRecognition.NORMAL,
            psych: PsychDims = PsychDims(),
        ): RelationAnchor = RelationAnchor(
            kind = kind,
            track = track,
            persona = mine,
            settingLine = settingLine,
            face = face,
            psych = psych,
            valueAlign = RelationCatalog.valueAlign(mine, theirs),
        )
    }
}

/** 一格＝一个方向。from/to 是人物名，A→B 与 B→A 是两份独立对象。 */
internal data class DirectedCell(
    val from: String,
    val to: String,
    val kind: RelationKind,
    val dims: RelationDims,
    val psych: PsychDims,
    val anchor: RelationAnchor,
    val rapport: Int = 0,
    val topicOverlap: Int = 0,
    val togetherCount: Int = 0,
    val privateEventCount: Int = 0,
    val events: List<RelationEvent> = emptyList(),
    val status: RelationStatus = RelationStatus.ACTIVE,
) {
    init {
        require(from.isNotBlank() && to.isNotBlank() && from != to) { "方向必须两个不同人物" }
        require(rapport in 0..3) { "rapport 只取 0–3" }
        require(topicOverlap in 0..3) { "topicOverlap 只取 0–3" }
        require(togetherCount >= 0 && privateEventCount >= 0) { "共处与私人事件数不得为负" }
    }

    companion object {
        fun started(from: String, to: String, anchor: RelationAnchor): DirectedCell {
            val dims = if (anchor.anchored) {
                RelationCatalog.startDims(anchor.kind, anchor.track, anchor.valueAlign)
            } else {
                RelationCatalog.STRANGER_START
            }
            return DirectedCell(
                from = from,
                to = to,
                kind = anchor.kind,
                dims = dims,
                psych = anchor.psych,
                anchor = anchor,
            )
        }
    }

    /** 上升：一步一事件，事件与 ref 一起留账。 */
    fun rise(delta: DimsDelta, event: RelationEvent): DirectedCell = copy(
        dims = dims.plus(delta),
        events = events + event,
    )

    /** 拒绝/退回/保持距离：只降不升，不挂事件。 */
    fun refusal(mode: RefusalMode): DirectedCell {
        val next = when (mode) {
            RefusalMode.REJECT -> dims.copy(
                trust = maxOf(0, dims.trust - 1),
                intimacy = maxOf(0, dims.intimacy - 1),
                conflict = minOf(100, dims.conflict + 20),
            )
            RefusalMode.WITHDRAW -> dims.copy(
                dependency = maxOf(0, dims.dependency - 1),
                intimacy = maxOf(0, dims.intimacy - 1),
                conflict = minOf(100, dims.conflict + 10),
            )
            RefusalMode.KEEP_DISTANCE -> dims.copy(
                trust = maxOf(0, dims.trust - 1),
                intimacy = maxOf(0, dims.intimacy - 1),
                conflict = minOf(100, dims.conflict + 5),
            )
        }
        return copy(dims = next, status = RelationStatus.REFUSAL)
    }
}

/** 双向对的形状。Merged 只给负例夹具：生产装配永远只造 Two。 */
internal sealed interface PairShape {
    data class Two(val a: DirectedCell, val b: DirectedCell) : PairShape

    /** 把 A→B 与 B→A 合成一格——R2 的负例，永远判红。 */
    data class Merged(val cell: DirectedCell) : PairShape
}
