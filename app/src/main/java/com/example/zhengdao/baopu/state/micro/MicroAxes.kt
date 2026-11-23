// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 微观变化层（速率档）：给既有动态轴加一层「变化有多快 / 什么形状」的旁路元数据。
// 依据：派工单 821 §一①；速率挂 731/810，联动挂 658/730，词表挂 669（见 MicroAnchors）。
// **零改既有档位定义**：轴的档位本身仍由各自包（form/turgor、stimulus/敏感、fluid/湿润、
// physiology/疲劳、readback/饱胀、physiology/体温）持有；本包只加 rate/edge/event 三类旁路字段，
// 不重定义档、不给点值、不下判定、不装配、不进闸。
package com.example.zhengdao.baopu.state.micro

/** 严重度：error＝判据破（红）；warn＝只贴了标签的提示（不算红，但要能被读出来）。 */
enum class MicroSeverity(val label: String) { ERROR("error"), WARN("warn") }

/** 一条发现。rule＝判据号（M1/M2…），axis 可空（表级发现为空）。 */
data class MicroFinding(
    val rule: String,
    val severity: MicroSeverity,
    val axis: DynamicAxis?,
    val message: String,
)

/** 变化速率三档：缓 / 中 / 急。只给档，不给秒数或每分钟次数。 */
enum class ChangeRate(val level: Int, val label: String) {
    SLOW(0, "缓"),
    MEDIUM(1, "中"),
    RAPID(2, "急"),
    ;

    companion object {
        const val COUNT = 3
    }
}

/**
 * 变化形状四档：线性 / 先快后慢 / 先慢后快 / 骤变。
 * [SUDDEN] 必须带一个事件名（[AxisRateProfile.event] 非空），否则判红——无事件的「骤变」是凭空跳档。
 */
enum class ChangeEdge(val label: String) {
    LINEAR("线性"),
    FAST_THEN_SLOW("先快后慢"),
    SLOW_THEN_FAST("先慢后快"),
    SUDDEN("骤变"),
    ;

    companion object {
        const val COUNT = 4
    }
}

/**
 * 六条动态轴。名称照既有包的落账口取：
 * TURGOR→810/form、SENSITIVITY→781/stimulus、WETNESS→732+806/fluid、
 * FATIGUE→658/physiology、FULLNESS→readback、BODY_TEMP→658/physiology。
 * 本枚举只作旁路索引，不改上述任何一处的档位定义。
 */
enum class DynamicAxis(val key: String, val label: String, val home: String) {
    TURGOR("turgor", "充血胀大", "810/form"),
    SENSITIVITY("sensitivity", "敏感", "781/stimulus"),
    WETNESS("wetness", "湿润", "732+806/fluid"),
    FATIGUE("fatigue", "疲劳", "658/physiology"),
    FULLNESS("fullness", "饱胀", "readback"),
    BODY_TEMP("bodyTemp", "体温偏移", "658/physiology"),
    ;

    companion object {
        const val COUNT = 6
    }
}

/**
 * 一条轴的速率配置。rate/edge 刻意声明成可空：生产表 [AxisRateTable.PROFILES] 全填，
 * 但「改一半」（把若干条置空）必须能被 [checkAxisRates] 判红——这是判据①的负例抓手。
 */
data class AxisRateProfile(
    val axis: DynamicAxis,
    val rate: ChangeRate?,
    val edge: ChangeEdge?,
    val event: String?,
)

/** 六轴速率表：唯一真相，检查器与变异都从这里取，不许另写一份。 */
object AxisRateTable {
    val PROFILES: List<AxisRateProfile> = listOf(
        AxisRateProfile(DynamicAxis.TURGOR, ChangeRate.MEDIUM, ChangeEdge.FAST_THEN_SLOW, null),
        AxisRateProfile(DynamicAxis.SENSITIVITY, ChangeRate.SLOW, ChangeEdge.LINEAR, null),
        AxisRateProfile(DynamicAxis.WETNESS, ChangeRate.SLOW, ChangeEdge.SLOW_THEN_FAST, null),
        AxisRateProfile(DynamicAxis.FATIGUE, ChangeRate.SLOW, ChangeEdge.LINEAR, null),
        AxisRateProfile(DynamicAxis.FULLNESS, ChangeRate.RAPID, ChangeEdge.SUDDEN, "一次进食"),
        AxisRateProfile(DynamicAxis.BODY_TEMP, ChangeRate.SLOW, ChangeEdge.FAST_THEN_SLOW, null),
    )

    /** 判据①：每轴恰一条、rate 与 edge 都在、骤变必须有事件。任一条不满足 ⇒ 红。 */
    fun check(profiles: List<AxisRateProfile> = PROFILES): List<MicroFinding> {
        val out = mutableListOf<MicroFinding>()
        val seen = LinkedHashSet<DynamicAxis>()
        for (p in profiles) {
            if (!seen.add(p.axis)) {
                out += MicroFinding(
                    "M1", MicroSeverity.ERROR, p.axis, "动态轴 ${p.axis.label} 重复登记",
                )
            }
            if (p.rate == null) {
                out += MicroFinding("M1", MicroSeverity.ERROR, p.axis, "${p.axis.label} 缺 rate")
            }
            if (p.edge == null) {
                out += MicroFinding("M1", MicroSeverity.ERROR, p.axis, "${p.axis.label} 缺 edge")
            }
            if (p.edge == ChangeEdge.SUDDEN && p.event.isNullOrBlank()) {
                out += MicroFinding(
                    "M1", MicroSeverity.ERROR, p.axis, "${p.axis.label} 骤变缺事件",
                )
            }
        }
        for (axis in DynamicAxis.values()) {
            if (axis !in seen) {
                out += MicroFinding("M1", MicroSeverity.ERROR, axis, "动态轴 ${axis.label} 未登记")
            }
        }
        return out
    }
}
