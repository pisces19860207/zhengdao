// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第九百三十五批 · 形态不推性评价：闭集（形态、评价维、来源、口味）。
//
// 由来：2026-11-26 用户「胸肌、腹肌、下体各个部位不能作为评判某个人的性张力、
// 性吸引力、性能力的标准，这些只有个人关注点和爱好点」。
//
// 本文件只定义三件事，不含判据（判据在 ShapeJudge.kt）：
// · [ShapeAspect]：能「看得见什么」的闭集——它只支撑描写；
// · [Judged]：能被评价的六个维；其中 EXPERIENCE／FERTILITY／MORAL 三个**恒不可由形态推出**；
// · [Source]：来源闭集。**没有「形态推出评价」这一格**——形态只是 [Source.MORPHOLOGY]，
//   它只许支撑描写，不许支撑张力/吸引力/能力；后三者各自有闭合的允许来源。
//
// 920 已经把「尺寸不推身高体重年龄」钉住、924 钉住「体积/质量/坠感互不推」；
// 本文件补第三根：**形态不推性评价**。所以这里不新造尺寸档，长度/粗细一律引用 920 的档位。
package com.example.zhengdao.baopu.state.bodyparts

/** 本单唯一来源口径。每条结论都引用它，不许出处不明。 */
const val SHAPE_SOURCE: String =
    "派工单-2026-11-26-第九百三十五批（形态不推性评价）＋本项目工程假设"

/** 口径成形日：闭集与夹具都以这一天为准。 */
const val SHAPE_AS_OF: String = "2026-11-26"

/**
 * 形态闭集：只描述「看得见什么」。
 * 十四条覆盖 805 的八部位（胸/腹/喉结等）、920 的三轴（长短/粗细）、
 * 以及最常被拿来说事的三处（阴囊松紧/睾丸大小/体毛与发量）。
 *
 * 名字里没有「大/小/强/弱」这类判词：档名在各自的档位枚举里，本枚举只给部位。
 */
enum class ShapeAspect(val label: String) {
    PEC_VOLUME("胸肌体积"),
    ABS_BLOCK("腹肌分块"),
    BODY_ROUNDNESS("体型圆润度"),
    GENITAL_LENGTH("下体长短"),
    GENITAL_GIRTH("下体粗细"),
    LARYNX("喉结"),
    SCROTUM_LAXITY("阴囊松紧"),
    TESTIS_SIZE("睾丸大小"),
    STATURE("身高"),
    WEIGHT("体重"),
    BODY_HAIR("体毛"),
    HAIR_LOSS("发量"),
    GAIT("走路姿势"),
    SPEECH("语速与声音"),
    ;

    companion object {
        const val COUNT = 14
    }
}

/**
 * 能被评价的六个维。前三个是本单的重点，后三个**恒不可由形态推出**：
 * 经验是经历、生育力是医学判断、品性是道德判断——三者都不住在身体形状里。
 *
 * 注意：这里没有「下体尺寸」这一维。尺寸是**形态本身**（920 的档），不是评价；
 * 把它塞进来会让「形态推尺寸」变成合法推导，正好破掉本单。
 */
enum class Judged(val label: String) {
    TENSION("性张力"),
    ATTRACTION("性吸引力"),
    ABILITY("性能力"),
    EXPERIENCE("性经验"),
    FERTILITY("生育力"),
    MORAL("品性"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/**
 * 来源闭集十格。每一格都对应一个真实出处，**没有一格叫「因为他是这个形状」之外的形态推导**：
 * [MORPHOLOGY] 是形态本身，它合法——但只支撑描写；一旦挂到张力/吸引力/能力上，判据当场红。
 *
 * 868 与 911 目前只有派工单、还没有代码（868 待派、911 待派）：这里只留**来源位**，
 * 不提前实现它们的档位。等它们落地时按同名的 code 对接，不改本文件。
 */
enum class Source(val code: String, val label: String) {
    HARDNESS_810("810", "硬度 EHS（810 实测档）"),
    PHASE_868("868", "起／维持／消退（868）"),
    FREQ_911("911", "活动频率（911）"),
    BEHAVIOR("行为", "具体行为"),
    OBSERVED("观测", "当场观测"),
    SETTING("设定", "人物设定"),
    MEASURED("实测", "实测读数"),
    PREFERENCE("口味", "某一个角色的口味"),
    MORPHOLOGY("形态", "形态本身（只支撑描写）"),
    RUMOR("传闻", "体相传闻"),
    ;

    companion object {
        const val COUNT = 10
    }
}

/** 谁在说这句话。同一句传闻，叙述者说是红，角色说是偏见（WARN）。 */
enum class Voice(val label: String) {
    NARRATOR("叙述者"),
    CHARACTER("角色"),
}

/** 口味三档：偏好／无感／不喜。它是「谁的口味」，不是客观档。 */
enum class PreferenceTier(val label: String) {
    LIKES("偏好"),
    NEUTRAL("无感"),
    DISLIKES("不喜"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/**
 * 一条口味：谁（[who]）对哪个形态（[about]）持什么态度（[tier]）。
 *
 * `who` 必须写清——这是判据④的机器落点：没有 `who` 的「喜欢肌肉」读起来像客观评价，
 * 有了 `who` 才读得成「这个人的口味」。构造即拦，不让空 who 进系统。
 */
data class Preference(
    val who: String,
    val about: ShapeAspect,
    val tier: PreferenceTier,
) {
    init {
        require(who.isNotBlank()) { "口味必须写清是谁的：缺 who" }
    }

    /** 一行：谁 + 对什么 + 什么态度。不带任何评价词。 */
    fun line(): String = who + "：对「" + about.label + "」" + tier.label
}

/**
 * 评价维与允许来源的唯一对照表。
 *
 * 三条硬约束（构造时即校验，表坏了不该等到判据才发现）：
 * · 六个评价维一个不漏、一个不多；
 * · ABILITY 恰为 810／868／911／行为四源，顺序固定（判据③）；
 * · TENSION 恰为观测／设定／实测三源，ATTRACTION 恰为口味一源，后三维为空集。
 */
object ShapeRules {

    /** 恒不可由形态推出：经验、生育力、品性。落在这些维上的形态推导无条件红，不因声音降级。 */
    val HARD_DIMS: Set<Judged> = setOf(Judged.EXPERIENCE, Judged.FERTILITY, Judged.MORAL)

    /** 能力的四个来源，顺序固定：810 → 868 → 911 → 行为。 */
    val ABILITY_SOURCES: List<Source> = listOf(
        Source.HARDNESS_810, Source.PHASE_868, Source.FREQ_911, Source.BEHAVIOR,
    )

    /** 张力只从「观测／设定／实测」来：形态不是来源。 */
    val TENSION_SOURCES: List<Source> = listOf(
        Source.OBSERVED, Source.SETTING, Source.MEASURED,
    )

    /** 吸引力只从「某个角色的口味」来。 */
    val ATTRACTION_SOURCES: List<Source> = listOf(Source.PREFERENCE)

    private val ALLOWED: Map<Judged, Set<Source>> = mapOf(
        Judged.TENSION to TENSION_SOURCES.toSet(),
        Judged.ATTRACTION to ATTRACTION_SOURCES.toSet(),
        Judged.ABILITY to ABILITY_SOURCES.toSet(),
        Judged.EXPERIENCE to emptySet(),
        Judged.FERTILITY to emptySet(),
        Judged.MORAL to emptySet(),
    )

    init {
        require(ALLOWED.keys == Judged.entries.toSet()) { "评价维与来源表对不上：六维一个不漏" }
        require(ABILITY_SOURCES.map { it.code } == listOf("810", "868", "911", "行为")) {
            "能力只许来自 810／868／911／行为，顺序固定"
        }
        require(TENSION_SOURCES.map { it.code } == listOf("观测", "设定", "实测")) {
            "张力只许来自观测／设定／实测"
        }
        require(Source.MORPHOLOGY !in ABILITY_SOURCES) { "形态不是能力的来源" }
        require(Source.MORPHOLOGY !in TENSION_SOURCES) { "形态不是张力的来源" }
        (Judged.entries - HARD_DIMS).forEach { dim ->
            require(ALLOWED.getValue(dim).isNotEmpty()) { dim.label + " 的允许来源不能为空" }
        }
        HARD_DIMS.forEach { dim ->
            require(ALLOWED.getValue(dim).isEmpty()) { dim.label + " 恒不可由任何来源推出" }
        }
    }

    /** 这个评价维允许的来源（空集＝无论拿什么都算红）。 */
    fun allowed(dim: Judged): Set<Source> = ALLOWED.getValue(dim)

    /** 这一维是否恒不可推。 */
    fun isHard(dim: Judged): Boolean = dim in HARD_DIMS

    /**
     * 一条来源能不能支撑某一维。形态（[Source.MORPHOLOGY]）对任何评价维都返回 false——
     * 这是「无形态推导边」的机器表述：不是靠注释，是靠这张表。
     */
    fun supports(dim: Judged, source: Source): Boolean =
        source != Source.MORPHOLOGY && source in allowed(dim)
}
