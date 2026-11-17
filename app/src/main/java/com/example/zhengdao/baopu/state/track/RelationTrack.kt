// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十批 · 神轨第二机：关系轨迹（派工单追加②）。
// 数据来源：证道设计报告/附件-关系轨迹/关系轨迹-转移表.json（2,661 B，2026-11-16 核）；
// 上游规格：人物锚定图.dot（用户手绘）＋人物锚定与越界轨迹.md v1.1。
// 拓扑锁死：11 态 13 转移，改动须人批＋升 TRACK_SPEC_MAJOR（与第一机共用门禁）。
// 推进语义：调用方提议目标态，引擎只验「边存在＋guard 证据齐」——不发明自动推进。
package com.example.zhengdao.baopu.state.track

/** 关系轨迹 11 态（名称与含义锁死）。 */
enum class RelationState { T0, T3, T4, T4P, T5, T6, TE1, TE2, TG, TT, TX }

/** 转移边：guard 为 null ＝ 无前置条件；非 null ＝ 须出示同名证据（触发牌/回流判定等）。 */
data class RelationEdge(val from: RelationState, val to: RelationState, val guard: String?)

/** 环境修饰 C1–C4：规格只给定性描述、未给数值语义 ⇒ 只作数据携带，不发明概率（欠约束已报）。 */
enum class RelationModifier(val label: String, val note: String) {
    C1("全控机构", "触发牌更容易出现（军队/警察/监狱/消防/国安）"),
    C2("半封闭", "要事件推"),
    C3("开放", "没有掩护，只能靠躲/要主动"),
    C4("高压特殊", "入掩护池"),
}

/** 转移表与牌表：逐条照抄 JSON（数据层已核，这里只做翻译）。 */
object RelationTable {
    val EDGES: List<RelationEdge> = listOf(
        RelationEdge(RelationState.T0, RelationState.T3, null),
        RelationEdge(RelationState.T3, RelationState.TT, null),
        RelationEdge(RelationState.TT, RelationState.T3, "没有"),
        RelationEdge(RelationState.TT, RelationState.T4, "出现"),
        RelationEdge(RelationState.T4, RelationState.TX, null),
        RelationEdge(RelationState.T4, RelationState.T5, null),
        RelationEdge(RelationState.TX, RelationState.TG, null),
        RelationEdge(RelationState.TG, RelationState.TE1, "缺一条"),
        RelationEdge(RelationState.TG, RelationState.T4P, "全中"),
        RelationEdge(RelationState.T4P, RelationState.TE2, "不回"),
        RelationEdge(RelationState.T4P, RelationState.T5, "复联"),
        RelationEdge(RelationState.T5, RelationState.T6, "靠日常做饭·留灯·买药"),
        RelationEdge(RelationState.T5, RelationState.TX, "崩了"),
    )

    /** TT 触发牌（guards.TT_触发牌）。 */
    val TRIGGER_CARDS: List<String> = listOf("酒", "伤", "夜哨", "离别", "庆祝")

    /** TG 回流三条件（guards.TG_回流三条件）：缺一不回流。 */
    val BACKFLOW_THREE: List<String> = listOf(
        "内在分>=60(灵魂契合)",
        "走过第4格(已经发生过)",
        "走的是X(没好好结束)",
    )

    /** TG 回流触发牌补充（guards.TG_回流触发牌_补充）。第 7 张原文带性指向词，
     *  公开仓纪律以中性占位呈现（原文在私有侧附件 JSON，条数与语义不变，已报）。 */
    val BACKFLOW_SUPPLEMENT_CARDS: List<String> = listOf(
        "凌晨一点后", "喝到位的酒", "下雨", "相似气味", "相似背影", "梦到",
        "私密场景走神", "听到消息",
    )

    /** 与形轨的耦合点（coupling_to_form_track）：形轨未建（660 在途），此处只留数据缝。 */
    const val COUPLING_TO_FORM_TRACK = "身体分决定\"有没有反应\"（md 5.7）⇒ 形轨 M(心理唤起) 的先验"

    /** irreversible：T4 越界一次不可逆。 */
    const val IRREVERSIBLE = "T4 越界（一次，不可逆）"

    /** 回流三条件判定：全中 ⇒ 「全中」证据；缺任何一条 ⇒ 「缺一条」（三条件锁死）。 */
    fun backflowEvidence(innerScoreAtLeast60: Boolean, crossedFourth: Boolean, endedBadly: Boolean): String =
        if (innerScoreAtLeast60 && crossedFourth && endedBadly) "全中" else "缺一条"
}

/**
 * 关系轨迹状态机。证据（触发牌/回流判定/日常事件）由六体读数或用户输入出示，
 * 引擎不产生证据；越界（T4）一旦发生记 irreversible 标记，禁止回到越界前状态。
 */
class RelationTrack(val modifier: RelationModifier? = null) {
    var state: RelationState = RelationState.T0
        private set

    /** T4 越界不可逆标记（一次即永久）。 */
    var crossed: Boolean = false
        private set

    /** 提议转移到 to：边必须存在、guard 证据必须齐、越界后不许回越界前。成功返回 true。 */
    fun step(to: RelationState, evidence: Set<String> = emptySet()): Boolean {
        val edge = RelationTable.EDGES.firstOrNull { it.from == state && it.to == to } ?: return false
        if (edge.guard != null && edge.guard !in evidence) return false
        if (crossed && to in PRE_CROSS_STATES) return false
        state = to
        if (to == RelationState.T4) crossed = true
        return true
    }

    private companion object {
        val PRE_CROSS_STATES = setOf(RelationState.T0, RelationState.T3, RelationState.TT)
    }
}