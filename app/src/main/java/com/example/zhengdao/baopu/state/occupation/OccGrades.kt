// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 档位词表：六维（派工单 §一②–⑥）＋第一期细档（追加「不够：要再细八项」①–④）。
//
// 三条口径写死在这里，免得一格一格解释：
// · [Dirt] 是**作业面的附着暴露档**（洁净＝无尘台面／检验台那类环境），**指环境不指人**；
//   它不是清洁状态、更不是评价，判据「职业不驱动卫生」由 OccupationGate 的 C5 与禁词表守。
// · [Sched]／[Shift] 只登记作息**形态**（几班倒、是否随叫随到），疲劳与状态的方向归 825／868。
// · 全部只给档名，不给分钟数、不给公斤数、不给分贝数（判据⑥：纯枚举与表）。
package com.example.zhengdao.baopu.state.occupation

/** 六维②日常体态负荷。 */
enum class Posture(val label: String) {
    SIT("久坐"),
    STAND("站立"),
    SQUAT("蹲伏"),
    CLIMB("攀爬"),
    CARRY("搬抬"),
    DRIVE("驾驶"),
    WALK("走动"),
    ;

    companion object {
        const val COUNT = 7
    }
}

/**
 * 六维③附着暴露档：指**作业面上会沾到什么**，不指人的清洁状态，也不是评价。
 * 与 883 完全脱钩：这一档不许合成任何清洁／意愿档（C5 禁区）。
 */
enum class Dirt(val label: String) {
    CLEAN("洁净（作业面无尘）"),
    GENERAL("一般"),
    DUST("粉尘"),
    OIL("油污"),
    MUD("泥水"),
    CHEMICAL("化学"),
    HEAT("高温"),
    ;

    companion object {
        const val COUNT = 7
    }
}

/** 六维④着装类别。 */
enum class Dress(val label: String) {
    CASUAL("便装"),
    WORKWEAR("工装"),
    UNIFORM("制服"),
    FORMAL("正装"),
    SPORTS("运动装"),
    NONE_FIXED("无固定"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/** 六维⑤作息形态。 */
enum class Sched(val label: String) {
    DAY("常规白班"),
    ROTATING("轮班"),
    NIGHT("夜班"),
    ON_CALL("随叫随到"),
    SEASONAL("季节性强"),
    LONG_DAY("长白"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/** 六维⑥危险暴露。治安＝面向冲突面的暴露类别，不含任何行动细节。 */
enum class Risk(val label: String) {
    NONE("无"),
    TOOL("器械"),
    HEAT("高温"),
    HEIGHT("高空"),
    PATHOGEN("病原"),
    PUBLIC_SAFETY("治安"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/** 第一期②室内外。 */
enum class Place(val label: String) {
    INDOOR("室内"),
    OUTDOOR("室外"),
    MIXED("内外穿梭"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 第一期②噪声三档（只给档，不给分贝）。 */
enum class Noise(val label: String) {
    QUIET("安静"),
    MID("中"),
    LOUD("吵"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 第一期②粉尘种别：派工单点名的水泥／木屑／棉尘／金属，加一个「无」。 */
enum class Dust(val label: String) {
    NONE("无"),
    CEMENT("水泥"),
    WOOD("木屑"),
    COTTON("棉尘"),
    METAL("金属"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 第一期②温区：常温／炉前／冷库。 */
enum class Temp(val label: String) {
    AMBIENT("常温"),
    FURNACE_FRONT("炉前"),
    COLD_STORE("冷库"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 第一期②湿度：干燥／一般／水产／洗衣。 */
enum class Moist(val label: String) {
    DRY("干燥"),
    GENERAL("一般"),
    AQUATIC("水产"),
    LAUNDRY("洗衣"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 第一期②化学暴露：溶剂／油漆／消毒水。 */
enum class Chem(val label: String) {
    NONE("无"),
    SOLVENT("溶剂"),
    PAINT("油漆"),
    DISINFECTANT("消毒水"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 第一期④搬抬重量档（无／轻／中／重，不给公斤）。 */
enum class Carry(val label: String) {
    NONE("无"),
    LIGHT("轻"),
    MID("中"),
    HEAVY("重"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 第一期④重复动作部位：腕／腰／肩。 */
enum class Joint(val label: String) {
    WRIST("腕"),
    BACK("腰"),
    SHOULDER("肩"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 第一期④连续站立时长档（只给档）。 */
enum class Stand(val label: String) {
    LOW("短"),
    MID("中"),
    HIGH("长"),
    VERY_HIGH("很久"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/**
 * 第一期④作息细档（三班／长白／季节）。
 * 与六维⑤ [Sched] 的分工：`Sched` 答「算不算倒班」，`Shift` 答「怎么个倒法」。
 */
enum class Shift(val label: String) {
    FIXED_DAY("固定白班"),
    THREE_SHIFT("三班"),
    LONG_DAY("长白"),
    SEASON("季节"),
    IRREGULAR("不固定"),
    ;

    companion object {
        const val COUNT = 5
    }
}
