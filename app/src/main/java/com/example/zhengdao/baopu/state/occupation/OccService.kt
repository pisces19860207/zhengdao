// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · ERRATA 补充段：军／警／消必须分细类，并带三条行业不填的特有字段。
//
// 只给**类别**：不写番号、不写装备参数、不写行动细节（涉密口径，编造即红＝C12 机检）。
// 也不写性格：「铁血／服从／粗鲁」这类一个都不许出现（C4 禁词表）——本段只登记作息、装备、负荷、痕迹。
//
// 【与 883 的唯一合法连线】[DutyCycle] 与 [ReturnGap] 答的是**能不能回到有热水、有独处空间的地方**，
// 属客观时段条件；它**不答**想不想洗、勤不勤洗，更不产出任何清洁档（判据④＋2026-11-24 补充更正）。
// 值勤／驻训／专案的人同样在意自身清洁——这与任何职业一样，只能由个体档（883）表达。
package com.example.zhengdao.baopu.state.occupation

/** 三臂：军／警／消。派工单原文「军警消防」只到行业级，细类从这里起。 */
enum class Arm(val label: String) {
    ARMY("军"),
    POLICE("警"),
    FIRE("消"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 军：义务兵／士官／军官。 */
enum class ArmyRank(val label: String) {
    CONSCRIPT("义务兵"),
    NCO("士官"),
    OFFICER("军官"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/** 警：六个岗位类别，只给类别不给单位与序列。 */
enum class PolicePost(val label: String) {
    PATROL("巡警"),
    TRAFFIC("交警"),
    SWAT("特警"),
    DETECTIVE("刑警"),
    PRISON("狱警"),
    AUXILIARY("辅警"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/** 消：四个岗位类别。 */
enum class FirePost(val label: String) {
    FIGHTER("战斗员"),
    DRIVER("驾驶员"),
    COMMS("通讯员"),
    PREVENTION("防火监督"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 特有字段①值班形态：三臂共用的八种形态（24h 驻勤／轮值／常白／驻训／备勤／专案／轮班／出警）。 */
enum class DutyCycle(val label: String) {
    R24_STATIONED("24h 驻勤"),
    ROTATING_24H("24 小时轮值"),
    DAY_ONLY("常白／白班"),
    FIELD_TRAINING("驻训"),
    STANDBY("备勤"),
    CASE_TASK("专案"),
    ROSTER_SHIFT("轮班"),
    DISPATCH("出警"),
    ;

    companion object {
        const val COUNT = 8
    }
}

/** 军的使命期形态（日常／演训／值班／出勤）——只作作息类别。 */
enum class DutyPhase(val label: String) {
    DAILY("日常"),
    TRAINING("演训"),
    WATCH("值班"),
    DISPATCH("出勤"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 军的场景类别（派工单点名的六项）。 */
enum class ServiceScene(val label: String) {
    CAMP_TRAINING("营区驻训"),
    BORDER_POST("边防哨所"),
    VESSEL("舰艇"),
    AIRCRAFT_MAINT("机务"),
    COMM("通信"),
    SERVICE_MEDICAL("医疗"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/**
 * 能否回到有热水／独处空间之处的**时段档**：883 条件档的一个客观输入。
 * 只有「能不能」，没有「想不想」；同一驻勤形态下，个人选择照样各走各的。
 */
enum class ReturnGap(val label: String) {
    SAME_DAY("当日可回"),
    NEXT_DAY("隔日可回"),
    MULTI_DAY("连续多日在外"),
    IRREGULAR("随任务不定"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/** 特有字段②装备类别（派工单点名的八件；型号、参数一律不写）。 */
enum class Gear(val label: String) {
    CAMO("迷彩"),
    DRESS_SUIT("常服"),
    TRAINING_SUIT("作训服"),
    TACTICAL_VEST("战术背心"),
    BALLISTIC_VEST("防弹衣"),
    HELMET("头盔"),
    GLOVES("手套"),
    BOOTS("靴"),
    ;

    companion object {
        const val COUNT = 8
    }
}

/** 特有字段③环境附加暴露：烟熏高温／水域潮湿／高寒／粉尘／噪声。 */
enum class EnvExtra(val label: String) {
    SMOKED_HEAT("烟熏与高温"),
    WATER_WET("水域与潮湿"),
    ALPINE_COLD("高寒"),
    DUST("粉尘"),
    NOISE("噪声"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/**
 * 特有字段④身体负荷类别：负重行军／攀爬／长时间站立／突发冲刺。
 * 「力」的档归 727，「温湿」的档归 730，本包只登记这一身一班会遇到哪几种负荷类别。
 */
enum class BodyLoad(val label: String) {
    RUCK_MARCH("负重行军"),
    CLIMB("攀爬"),
    LONG_STAND("长时间站立"),
    SPRINT("突发冲刺"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/**
 * 军警消特有槽。三臂共用五个必填字段（值班形态／时段档／装备／环境附加／负荷），
 * 岗位类别按臂填，军的场景与使命期另算必填。
 *
 * 字段**全部可空**，负例才能把「填错臂」「漏填 dutyCycle」「非军警消却填了这一槽」各判一条。
 */
data class ServiceSlot(
    val arm: Arm?,
    val armyRank: ArmyRank?,
    val policePost: PolicePost?,
    val firePost: FirePost?,
    val scene: ServiceScene?,
    val phase: DutyPhase?,
    val dutyCycle: DutyCycle?,
    val returnGap: ReturnGap?,
    val gear: List<Gear>?,
    val envExtra: List<EnvExtra>?,
    val bodyLoad: List<BodyLoad>?,
) {
    /** 本臂该填的岗位类别（填了别臂的＝串臂，C6 判红）。 */
    fun wrongArmPosts(): List<String> {
        val out = mutableListOf<String>()
        if (arm == Arm.ARMY && (policePost != null || firePost != null)) out.add("军填了警或消的岗位")
        if (arm == Arm.POLICE && (armyRank != null || firePost != null)) out.add("警填了军或消的岗位")
        if (arm == Arm.FIRE && (armyRank != null || policePost != null)) out.add("消填了军或警的岗位")
        return out
    }

    /** 该臂的岗位类别有没有落。 */
    fun postMissing(): Boolean = when (arm) {
        Arm.ARMY -> armyRank == null
        Arm.POLICE -> policePost == null
        Arm.FIRE -> firePost == null
        null -> true
    }

    /** 三臂共用的五个必填＋军的场景与使命期。 */
    fun missingNames(): List<String> {
        val slots: List<Pair<String, Any?>> = listOf(
            "值班形态" to dutyCycle, "时段档" to returnGap, "装备类别" to gear,
            "环境附加" to envExtra, "身体负荷" to bodyLoad,
        )
        val out = slots.filter { it.second == null }.map { it.first }.toMutableList()
        if (arm == Arm.ARMY) {
            if (scene == null) out.add("场景")
            if (phase == null) out.add("使命期")
        }
        if (arm == null) out.add("臂")
        if (postMissing()) out.add("岗位类别")
        return out
    }
}
