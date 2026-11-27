// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十九批 · 青春发育史：五维档位闭集 + 里程碑顺序表。
//
// 口径（2026-11-27 用户定稿，后来者不许自行加禁）：
// · 只看「为什么写」：不以性为目的 ⇒ 全部允许（身体、年龄、细节都行）；
// · 唯一硬红：未成年 ＋ 以性为目的（三要素任一：性场景／特写性器官／性化凝视）；
// · 撤销过紧口径：年龄数值、身体变化细节、既往史细节都不再禁；
// · 仍禁两条：医学诊断结论、由发育推性能力与品行（见 PubertyGuard）。
//
// 本文件只定义「有什么可写」：五维、四档、五里程碑。判据在 PubertyJudge.kt。
// 这里刻意没有年龄门槛、没有数值阈值——五维只给档，档序就是全部信息。
package com.example.zhengdao.baopu.state.bodyparts

/** 本单唯一来源口径。每条结论都引用它，不许出处不明。 */
const val PUBERTY_SOURCE: String =
    "派工单-2026-11-27-第九百三十九批（青春发育史）＋本项目工程假设"

/** 口径成形日：档位定义与夹具都以这一天为准。 */
const val PUBERTY_AS_OF: String = "2026-11-27"

/**
 * 五维闭集：只回答「写到哪一档」。
 *
 * 五条与派工单逐字对应，一个不多一个不少：加一维就必须同时改 [COUNT] 与计数锁。
 * 这里没有性器官维——那是 805／775 的账，本单只写发育过程。
 */
enum class PubertyDim(val label: String) {
    GROWTH_SPURT("生长突增"),
    VOICE_CHANGE("变声"),
    BODY_HAIR("体毛"),
    SHAPE_SHIFT("体型转变"),
    SOCIAL_SIDE("社会侧"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/**
 * 四档：未起／初起／渐显／定型。档序 0..3 连续且单调。
 *
 * 档名里没有年龄、没有数值、没有「早／晚」这类判词——因为同一档可以是十四岁，也可以是十七岁；
 * 把年龄写进档名，等于给「发育早晚」发一张诊断书，正是本单要避免的越界。
 */
enum class PubertyTier(val order: Int, val label: String) {
    NOT_STARTED(0, "未起"),
    EARLY(1, "初起"),
    DEVELOPING(2, "渐显"),
    SETTLED(3, "定型"),
    ;

    /** 是否已经越过某一档（含本档）。比较只看档序，不看年龄。 */
    fun atLeast(other: PubertyTier): Boolean = order >= other.order

    companion object {
        const val COUNT = 4
    }
}

/**
 * 五个里程碑：常见先后，不是硬顺序。
 *
 * [order] 是常见次序的序号，供 [MilestoneOrder] 建表；它**不是**门槛，
 * 比它晚或早都不说明任何事（个体的先后本来就可换）。
 */
enum class PubertyMilestone(val order: Int, val label: String) {
    GROWTH_SPURT(0, "生长突增"),
    BODY_HAIR(1, "体毛腋毛"),
    VOICE_LARYNX(2, "变声喉结"),
    MUSCLE_SHOULDER(3, "肌肉量与肩宽"),
    ODOR_ACNE(4, "体味痤疮"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/**
 * 里程碑顺序表：常见先后排成唯一一张表，同时把「可换」写成字面量。
 *
 * 为什么表与文案放在一起：只给表不给话，读的人会把表当成体检标准；
 * 只给话不给表，写的人又会各写一套。两者同处一个 object，谁改都得一起看。
 */
object MilestoneOrder {

    /** 「先后可换」的唯一写法。任何地方要提个体差异，都引这一条，不另造第二句。 */
    const val SWAPPABLE: String = "个体差异大、先后可换"

    /** 常见先后，逐条与派工单一致：生长突增 → 体毛腋毛 → 变声喉结 → 肌肉量与肩宽 → 体味痤疮。 */
    val SEQUENCE: List<PubertyMilestone> = listOf(
        PubertyMilestone.GROWTH_SPURT,
        PubertyMilestone.BODY_HAIR,
        PubertyMilestone.VOICE_LARYNX,
        PubertyMilestone.MUSCLE_SHOULDER,
        PubertyMilestone.ODOR_ACNE,
    )

    init {
        require(SEQUENCE.size == PubertyMilestone.COUNT) { "里程碑五条一个不漏" }
        require(SEQUENCE.toSet().size == PubertyMilestone.COUNT) { "里程碑不许重复成行" }
        require(SEQUENCE.map { it.order } == (0 until PubertyMilestone.COUNT).toList()) {
            "里程碑档序须从 0 连续：顺序表与枚举对不上"
        }
    }

    /** 实际顺序是不是常见顺序。 */
    fun isCommonOrder(observed: List<PubertyMilestone>): Boolean = observed == SEQUENCE

    /** 换过顺序。换序是常态，只标注，不判红。 */
    fun swapped(observed: List<PubertyMilestone>): Boolean = !isCommonOrder(observed)

    /** 表本身：序号 + 里程碑名。序号只是「常见第几步」，不是年龄。 */
    fun lines(observed: List<PubertyMilestone> = SEQUENCE): List<String> =
        observed.mapIndexed { index, milestone -> (index + 1).toString() + " " + milestone.label }

    /** 表下那一行注：可换 + 本次是常见还是换过。 */
    fun note(observed: List<PubertyMilestone> = SEQUENCE): String {
        val which = if (isCommonOrder(observed)) "常见顺序" else "换过顺序"
        return SWAPPABLE + "（本次：" + which + "）"
    }
}
