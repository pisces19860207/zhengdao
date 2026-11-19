// 独立开发声明：本文件为本项目原创，不含第三方源码的拷贝。
//
// 第七百四十七批 · 人物锚定：两条线（派工单⑥，判据⑥）。
// 规格 §5.3 一句话立闸：「身体分和内在分是两条线，不合并成一条。」
// 所以本文件里没有 sum／total／基础喜欢 这类合并值——不是没算出来，是**不给出口**：
// 身体分答「想不想上」（§5.7），内在分答「想不想留」（§5.5），两数并排交给调用方，谁合并谁负责。
//
// 规格内部这一处是冲突的，已按派工单「未给的标 ○ 待核」处理并写进交付报告 §四：
// · §5.1 把基础喜欢写成 身体吸引(0-60)＋性癖吻合(0-25)＋内在初印象(0-15) 三段求和；
// · §5.3 又明写两条线不许合并。
// ⇒ 本批只实现三段各自的算法与两线的分档读数，**不做求和**；性癖吻合的「再折算」系数规格没给，标 ○ 待核。
package com.example.zhengdao.baopu.state.anchor

/** 身体分档（§5.7 那张表的五行；反应与会不会凑都照原文，不改写）。 */
enum class BodyGrade(val low: Int, val high: Int, val reaction: String, val approach: String) {
    PEAK(85, 100, "心跳快、口干、下腹发热、手心出汗；男的会硬、女的会湿", "会，而且按不住"),
    HIGH(65, 84, "有反应，但能压住；会多看一眼", "会找机会，但不急"),
    FLAT(45, 64, "没有生理反应；能客观评价（挺干净的、一般）", "不主动，对方凑过来也不躲"),
    COLD(25, 44, "无感，甚至觉得不合眼缘", "不会凑，被碰了会往回收"),
    NONE(0, 24, "生理上没有反应，甚至反感；男的硬不起来、女的一点都不湿", "凑不上来——装也装不出来"),
}

/** 四型开局（§5.4 的 2×2，阈值取 §7.4 结构图那两个判断框：身体分≥40、内在分≥40）。 */
enum class ReactionType(val cn: String, val note: String) {
    TOOL("工具型", "直接用，不交流，射完就完事"),
    OBSESSED("痴迷型", "又想要人又想要心，会吃醋，会缠"),
    IGNORE("无视型", "看不见你，除非你主动出声"),
    SLOW("慢热型", "先做朋友，再谈别的，最可能走心"),
    ;

    companion object {
        /** 阈值 40 出自 §7.4 的两个判断框，不是本批自己定的。 */
        const val THRESHOLD = 40

        /** 用两条线**各自**的高低查表——查的是两个数，不是它们的和（判据⑥）。 */
        fun of(body: Int, inner: Int): ReactionType = when {
            body >= THRESHOLD && inner >= THRESHOLD -> OBSESSED
            body >= THRESHOLD -> TOOL
            inner >= THRESHOLD -> SLOW
            else -> IGNORE
        }
    }
}

/**
 * 玩家填的偏好卡（§四 用法 B）：只填想要什么。
 * wanted／excluded 都用 §三 的 15 个 L1 标签——规格给的就是标签级，不落到细项。
 */
data class AnchorPreference(
    val wanted: List<String> = emptyList(),
    val bonus: List<String> = emptyList(),
    val excluded: List<String> = emptyList(),
    val freeNote: String = "",
) {
    init {
        (wanted + excluded).forEach { if (it !in AnchorFields.L1_TAGS) reject("偏好项「$it」不在 §三 的 15 个 L1 标签里") }
        if (bonus.any { it.isBlank() }) reject("加分项给了空串")
    }

    /** 命中 NPC 的几个 L1 标签（§5.1 身体吸引的输入，重复标签不重复计）。 */
    fun hits(npcLabels: List<String>): Int = wanted.toSet().intersect(npcLabels.toSet()).size

    /** 排除项命中没有：命中一个就归零（§5.1 明写「是归零，不是扣分」）。 */
    fun excludedHit(npcLabels: List<String>): Boolean = excluded.toSet().intersect(npcLabels.toSet()).isNotEmpty()

    private fun reject(message: String): Nothing = throw AnchorSpecException("偏好卡：$message")
}

/**
 * 身体分（0-100）。
 * 分数由调用方出示（读数、事件、偏好命中），本类不自己产生分数——
 * §5.1 的「第一眼」段是唯一有公式的部分，做成 firstSight 静态口，其余一律由外部喂。
 */
data class BodyLine(val score: Int, val basis: List<String> = emptyList()) {
    init {
        if (score !in 0..CEILING) throw AnchorSpecException("身体分只取 0-$CEILING：$score")
        if (basis.any { it.isBlank() }) throw AnchorSpecException("身体分的依据里有空串：不许无据给分")
    }

    val grade: BodyGrade get() = BodyGrade.values().first { score in it.low..it.high }

    /** 会不会主动凑（§5.7 表第三列）——判据⑥ 要的是这条线单独可读，不跟内在分混。 */
    val approach: String get() = grade.approach

    companion object {
        const val CEILING = 100

        /** §5.1 身体吸引：命中 L1 标签每个 ×15，最多算 4 个；排除项命中 ⇒ 直接归零。 */
        fun firstSight(pref: AnchorPreference, npcLabels: List<String>): BodyLine {
            if (pref.excludedHit(npcLabels)) return BodyLine(0, listOf("排除项命中：身体吸引归零（§5.1），不是扣分"))
            val hits = pref.hits(npcLabels)
            return BodyLine((minOf(hits, MAX_LABELS) * PER_LABEL).coerceAtMost(ATTRACT_CEILING), listOf("命中 $hits 个 L1 标签"))
        }

        const val PER_LABEL = 15
        const val MAX_LABELS = 4
        const val ATTRACT_CEILING = 60
    }
}

/**
 * 内在分（§5.5）：开局最多 15，天花板 85。
 * 涨靠「一起经历过事」这类事件，规格给的是定性条件 ⇒ 不发明数值；
 * 分数由调用方带着事件出示，没事件就还是开局值。
 */
data class InnerLine(val score: Int, val events: List<String> = emptyList()) {
    init {
        if (score !in 0..CEILING) throw AnchorSpecException("内在分只取 0-$CEILING：$score")
        if (events.any { it.isBlank() }) throw AnchorSpecException("内在分的依据里有空事件")
    }

    /** 天花板 85：规格明写「不是满分」，满到 100 反而是假的。 */
    val atCeiling: Boolean get() = score == CEILING

    companion object {
        const val CEILING = 85

        /** 开局：§5.1 内在初印象 0-15 是这一段的上限，超过就拒。 */
        fun opening(initial: Int, basis: List<String> = emptyList()): InnerLine {
            if (initial !in 0..START_CEILING) throw AnchorSpecException("开局内在分只取 0-$START_CEILING（§5.1 内在初印象）：$initial")
            return InnerLine(initial, basis)
        }

        const val START_CEILING = 15

        /** 回流条件①（§5·补：灵魂契合＝内在分≥60）——照规格给判断，不给换算。 */
        fun soulMatched(score: Int): Boolean = score >= SOUL_THRESHOLD
        const val SOUL_THRESHOLD = 60
    }
}

/** 性癖吻合（§5.1 第三段）：同向项取小值相加；「再折算」成 0-25 的系数规格没给 ⇒ ○ 待核。 */
data class KinkOverlap(val raw: Int, val sharedItems: List<String>) {
    val folded: String get() = "○ 待核：§5.1 只写「再折算」，0-25 的折算口径规格未给"

    companion object {
        fun of(a: Map<String, Int>, b: Map<String, Int>): KinkOverlap {
            val shared = a.keys.intersect(b.keys).toList().sorted()
            return KinkOverlap(shared.sumOf { minOf(a.getValue(it), b.getValue(it)) }, shared)
        }
    }
}

/**
 * 两条线并排（判据⑥ 的载体）。
 * 这里刻意没有 `val total`、没有 `operator fun plus`、没有任何合并数——
 * 判据⑥ 的门禁臂扫的就是这个类有没有多出合并口。
 */
data class AnchorLines(val body: BodyLine, val inner: InnerLine, val kink: KinkOverlap? = null) {
    /** 四型开局（§5.4）：用两条线各自的高低查表，不查合并值。 */
    val reaction: ReactionType get() = ReactionType.of(body.score, inner.score)

    /** 并排读数（面板用）：两条线分开成两行，中间不加和。 */
    fun readout(): List<String> = listOf(
        "身体分 ${body.score}｜${body.grade.name}：${body.grade.reaction}｜凑：${body.approach}",
        "内在分 ${inner.score}（开局≤${InnerLine.START_CEILING}／天花板 ${InnerLine.CEILING}）",
        kink?.let { "性癖重叠 raw=${it.raw}（${it.folded}）" } ?: "性癖吻合：双方性癖未给",
    )
}
