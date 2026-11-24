// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第八百九十六批 · 档位闭集。维度名即接口，档数冻结（源码门禁逐条钉住）。
// 定稿口径：日常身体场景如实写；场景级判红只有两条，其余只给参考档与提示。
package com.example.zhengdao.baopu.state.presence

/** 在场者。只作参考信息，不阻断；没写时用 null 表达，默认按「有他人」处理。 */
enum class PresenceKind(val label: String) {
    ALONE("独处"),
    SAME_SEX("同室同性"),
    OPPOSITE_SEX("同室异性"),
    PUBLIC("公共空间"),
    CHILD("有小孩在场"),
    UNDERAGE("主角未成年"),
    ;

    companion object {
        const val COUNT: Int = 6
    }
}

/** 「不适合」的默认应对就是穿条内裤；穿戴整齐只在明确有外人时出现。 */
enum class CanBeNude(val level: Int, val label: String) {
    NATURAL(0, "自然（裸）"),
    UNDERWEAR(1, "穿内裤"),
    WRAPPED(2, "披遮（浴巾/睡衣）"),
    FULLY_DRESSED(3, "穿戴整齐"),
    ;

    companion object {
        const val COUNT: Int = 4
        const val DEFAULT_LEVEL: Int = 1
    }
}

/** 可写程度。敏感层即性化层；有小孩或主角未成年的场景里它整层不写。 */
enum class CanDescribe(val label: String) {
    DAILY("生活层照写"),
    SENSITIVE_SKIP("敏感层不写"),
    ;

    companion object {
        const val COUNT: Int = 2
    }
}

/** 六类场所。全部属于日常身体场景（追加八），只给常态度参考，不作判据。 */
enum class Venue(val label: String, val routine: Boolean) {
    BATHHOUSE("大澡堂/公共浴室", true),
    HOME("家里卫浴", true),
    DORM("多人宿舍", true),
    SHARED_RENTAL("合租房卫浴", true),
    LOCKER_ROOM("公共更衣室/健身房淋浴", true),
    BARRACKS("军营式集体浴", true),
    ;

    companion object {
        const val COUNT: Int = 6
    }
}

/** 未成年维度三态，不许并成「有/无」两档。 */
enum class MinorPresence(val label: String) {
    NONE("无"),
    ACCOMPANIED("有小孩在场"),
    UNDERAGE_LEAD("主角本人是未成年"),
    ;

    companion object {
        const val COUNT: Int = 3
    }
}

/** 年龄只给档不给岁数；三岁前是一等档，不是「幼」的子档。 */
enum class AgeTier(val label: String, val order: Int) {
    UNDER_THREE("三岁前（零避讳）", 0),
    YOUNG("幼", 1),
    SCHOOL("学龄", 2),
    PRE_PUBERTY("青春期前", 3),
    OLDER("更大", 4),
    ;

    companion object {
        const val COUNT: Int = 5
        const val ZERO_AVOID_ORDER: Int = 0
    }
}

/** 异性亲子避讳档：习惯倾向，不是禁令，不拦截事件本身。 */
enum class KinAvoidance(val label: String) {
    NONE("不避"),
    YOUNG_OK_LATER("幼年不避·长大避"),
    ALWAYS("一直避"),
    ;

    companion object {
        const val COUNT: Int = 3
    }
}

/** 血缘：无血缘时倾向更避，同样只是习惯。 */
enum class BloodTie(val label: String) {
    YES("有血缘"),
    NO("无血缘（继父母/寄养/婚配新成员）"),
    ;

    companion object {
        const val COUNT: Int = 2
    }
}

/** 场景类别。只有「其他场景」才可能出没写在场者的提示。 */
enum class SceneKind(val label: String) {
    DAILY_BODY("日常身体场景"),
    OTHER("其他场景"),
    ;

    companion object {
        const val COUNT: Int = 2
    }
}

/** 照护动作。非照护之外六类全部属于零避讳范围（三岁前）。 */
enum class CareAction(val label: String) {
    NONE("非照护"),
    BATH("洗澡"),
    DRESS("穿衣"),
    NURSE("喂奶/哺乳"),
    DIAPER("换尿布"),
    WIPE("擦身"),
    SLEEP("哄睡"),
    ;

    companion object {
        const val COUNT: Int = 7
        val CARES: List<CareAction> = entries.filter { it != CareAction.NONE }
    }
}

/** 性化信号。只有「设定明写」与「仅因裸体推断」两种非无信号，均触发两条红线。 */
enum class SexualSignal(val label: String) {
    NONE("无性化描写"),
    EXPLICIT("设定或情境明写"),
    FROM_NAKED("仅因裸体推断"),
    ;

    companion object {
        const val COUNT: Int = 3
    }
}

/** 失真写法：为了避讳反而把常态写歪，只提示、不判红。 */
enum class DistortionFlag(val label: String) {
    NONE("无"),
    WRAPPED_EVERYONE("写成人人裹着毛巾"),
    NURSING_HIDDEN("把哺乳写成背过身遮掩"),
    ;

    companion object {
        const val COUNT: Int = 3
    }
}

/** 发现类型：红线与两种提示分开，便于断言「三岁前零避讳提示」。 */
enum class FindingKind(val label: String) {
    RED("红线"),
    AVOIDANCE("避讳提示"),
    DISTORTION("失真提示"),
    INFO("通过"),
    ;

    companion object {
        const val COUNT: Int = 4
    }
}

/** 判定三态。severity 只用来取最严，不依赖枚举声明次序。 */
enum class PresenceVerdict(val label: String, val severity: Int) {
    PASS("通过", 0),
    HINT("提示", 1),
    DENY("红", 2),
    ;

    companion object {
        const val COUNT: Int = 3

        fun worst(a: PresenceVerdict, b: PresenceVerdict): PresenceVerdict =
            if (a.severity >= b.severity) a else b
    }
}

/** 私密期待：能否关门、有无隔断、是否可能有人突然进来。 */
data class PrivacyExpect(
    val canCloseDoor: Boolean,
    val hasPartition: Boolean,
    val suddenEntry: Boolean,
) {
    fun line(): String =
        "可关门=" + yesNo(canCloseDoor) + "/隔断=" + yesNo(hasPartition) +
            "/可能有人进来=" + yesNo(suddenEntry)

    private fun yesNo(v: Boolean): String = if (v) "有" else "无"
}
