// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 驱动面（本单真正的产出）与交接面（本单唯一允许的去向）。
//
// 【追加口径的最终版】职业只保留四类驱动，各有明确物理机制：
// · 体型与肌肉——体力负荷（归 815 定档，本包只给方向，绝不写死体型值）；
// · 皮肤与手——摩擦与暴露（归 805／807）；
// · 衣物磨损——摩擦（归 881 的磨损档）；
// · 汗量与味型——发热（归 806／827）。
// **不驱动**：洗澡频率、局部清洁、清洁档、意愿档、卫生档——这四类根本不在本轴上，
// 结构上就没有这一格，比写一百句「不许推断」可靠（判据④）。
//
// 判据②：方向只许给 ↑／↓／中性三值，**不许拿「因人而异」顶替方向**。
// 判据③：每条驱动必须同时带 [Spread]（个体差异档）——方向是倾向，不是断定。
// 两者缺一即红：光有方向没个体差异＝刻板，光有个体差异没方向＝糊过去。
package com.example.zhengdao.baopu.state.occupation

/** 四条驱动轴。[home] 记这一格的方向最后交给谁定档，本包不代它定。 */
enum class DriveAxis(val key: String, val label: String, val home: String) {
    BUILD("build", "体型与肌肉", "815"),
    SKIN("skinHand", "皮肤与手", "805+807"),
    WEAR("garmentWear", "衣物磨损", "881"),
    SWEAT("sweatOdor", "汗量与味型", "806+827"),
    ;

    companion object {
        const val COUNT = 4

        /**
         * 每轴成立的物理机制（派工单追加：这四项有明确机制，才允许留作驱动）。
         * 给了方向却举不出机制＝把印象当因果，检查器按 C3 判红。
         */
        fun mechanismsOf(axis: DriveAxis): Set<Mech> = when (axis) {
            BUILD -> setOf(Mech.LOAD)
            SKIN -> setOf(Mech.FRICTION, Mech.EXPOSURE)
            WEAR -> setOf(Mech.FRICTION)
            SWEAT -> setOf(Mech.HEAT, Mech.LOAD)
        }
    }
}

/** 方向三值：↑／↓／中性。判据②「不许写因人而异糊过去」——中性也是一个方向。 */
enum class Dir(val label: String) {
    UP("↑"),
    DOWN("↓"),
    FLAT("中性"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 物理机制四类：体力负荷／暴露／摩擦／发热。 */
enum class Mech(val label: String) {
    LOAD("体力负荷"),
    EXPOSURE("暴露"),
    FRICTION("摩擦"),
    HEAT("发热"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/**
 * 个体差异档。派工单判据③要求「必须写个体差异大」，所以这里**只有一个取值**：
 * 结构上就写不出「个体差异小」，也就没法把倾向说成断定。留成字段（而不是删掉）是为了
 * 「抽掉这一格必须被判红」这件事有个抓手——C3 按位核它非空。
 */
enum class Spread(val label: String) {
    LARGE("个体差异大"),
    ;

    companion object {
        const val COUNT = 1
    }
}

/**
 * 一个工种的四面方向。五格**全部可空**，为的是「改一半」必须能被检查器抓出来：
 * 方向缺＝判据②红；机制空＝无因果红；个体差异缺＝判据③红。
 */
data class DriveSet(
    val build: Dir?,
    val skin: Dir?,
    val wear: Dir?,
    val sweat: Dir?,
    val mechanisms: Set<Mech>?,
    val spread: Spread?,
) {
    /** 轴 → 方向，顺序与 [DriveAxis.entries] 一致，检查器按位报缺。 */
    fun dirOf(axis: DriveAxis): Dir? = when (axis) {
        DriveAxis.BUILD -> build
        DriveAxis.SKIN -> skin
        DriveAxis.WEAR -> wear
        DriveAxis.SWEAT -> sweat
    }

    fun missingAxes(): List<DriveAxis> = DriveAxis.entries.filter { dirOf(it) == null }
}

/**
 * 交接面：本包的产出**只许**流向这十个去向；后三条是**禁区**——
 * 枚举里留着它们，是为了让「有人把职业接到卫生档上」这件事当场可测、可判红，
 * 而不是靠人记得不许写。生产表对禁区三值的引用数必须恒为 0（C5 机检）。
 *
 * 允许侧的两条 883 相关交接都是**条件／可用集**输入（场所菜单、驻勤时段），
 * 不是清洁档：谁来干这活都不改变他个不个人愿意洗（判据：双向刻板都算红）。
 */
enum class Handoff(val key: String, val target: String, val allowed: Boolean) {
    DRIVE_BUILD_815("drive.build", "815 体型与肌肉档", true),
    DRIVE_SKIN_805("drive.skin", "805／807 皮肤与手", true),
    DRIVE_WEAR_881("drive.wear", "881 衣物磨损档", true),
    DRIVE_SWEAT_827("drive.sweat", "806／827 汗量与味型", true),
    STRUCTURE_FLY_881("link.fly", "881 开合形态与内衣层输入", true),
    ODOR_SOURCE_827("p2.odor", "827 味型来源（第二期）", true),
    TRACE_BODY_805("p2.trace", "805／807 痕迹（第二期）", true),
    HABITAT_883("p2.habitat", "883 条件档的客观输入（第二期）", true),
    ERA_GARMENT_881("era.garment", "881 衣物可用集", true),
    ERA_VENUE_883("era.venue", "883 条件档的场所来源", true),
    DUTY_WINDOW_883("duty.window", "883 条件档的时段来源", true),
    BAN_HABIT_883("ban.habit", "禁区：职业→883 意愿档", false),
    BAN_TIER_883("ban.tier", "禁区：职业→883 条件档本身", false),
    BAN_DERIVED_883("ban.derived", "禁区：职业→883 派生档", false),
    ;

    companion object {
        const val ALLOWED_COUNT = 11
        const val BANNED_COUNT = 3

        fun banned(): List<Handoff> = listOf(BAN_HABIT_883, BAN_TIER_883, BAN_DERIVED_883)
    }
}

/**
 * 四面驱动的固定交接：每个工种都必须把四个方向各交一处，少一格就是驱动面缺格（C5 按位报）。
 * 写成文件级 val（不是枚举伴生里的 val）——枚举伴生引用自身条目有初始化次序风险，不值得赌一轮编译。
 */
val DRIVE_HANDS: List<Handoff> = listOf(
    Handoff.DRIVE_BUILD_815, Handoff.DRIVE_SKIN_805, Handoff.DRIVE_WEAR_881, Handoff.DRIVE_SWEAT_827,
)
