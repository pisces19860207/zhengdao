// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百二十一批 · 联动传导：一处变化牵动哪些可观察表现（五组，每组 ≥3 对）。
// 挂 658/730：敏感/疲劳/体温的牵动挂 658 轻生理，冷挂 730 昼夜温湿（尚未落地，本单只留来源锚）；
// 痛的来源归 781/stimulus·Discomfort，饱胀归 readback——只记「因→果」档位对，
// 不给力度值、不下判定、不装配，不改上述任一处的既有档位定义。
package com.example.zhengdao.baopu.state.micro

/**
 * 五组联动的独立来源。刻意不复用 [DynamicAxis]：痛、冷不是速率六轴里的轴，
 * 强行塞进去会污染 810/658 的档位语义。每组来源自带方向，查表只用这一把钥匙。
 */
enum class LinkageSource(val label: String, val home: String) {
    SENSITIVITY_UP("敏感↑", "781/stimulus"),
    PAIN_UP("痛↑", "781/stimulus·Discomfort"),
    COLD("冷", "730/昼夜温湿（锚，未落地）"),
    FATIGUE_UP("疲劳↑", "658/physiology"),
    FULLNESS_UP("饱胀↑", "readback"),
    ;

    companion object {
        const val COUNT = 5
    }
}

/** 一对传导：某来源变化时，target 上出现的可观察表现（cue）。 */
data class LinkagePair(
    val target: String,
    val cue: String,
)

/** 一组传导：一个来源牵动 ≥3 个表现。 */
data class LinkageGroup(
    val source: LinkageSource,
    val pairs: List<LinkagePair>,
) {
    val size: Int get() = pairs.size
}

/** 一条变化标记：某来源变了——判据②用它去查有没有联动。 */
data class ChangeMark(val source: LinkageSource)

/** 五组联动的唯一真相：敏感↑ / 痛↑ / 冷 / 疲劳↑ / 饱胀↑，每组 ≥3 对。 */
object LinkageTable {
    val GROUPS: List<LinkageGroup> = listOf(
        LinkageGroup(LinkageSource.SENSITIVITY_UP, listOf(
            LinkagePair("呼吸", "浅而快"),
            LinkagePair("喉结", "吞咽"),
            LinkagePair("手指", "抓握收紧"),
            LinkagePair("腹", "绷紧"),
        )),
        LinkageGroup(LinkageSource.PAIN_UP, listOf(
            LinkagePair("呼吸", "屏住"),
            LinkagePair("躯干", "僵住"),
            LinkagePair("局部", "收缩"),
        )),
        LinkageGroup(LinkageSource.COLD, listOf(
            LinkagePair("皮肤", "起鸡皮"),
            LinkagePair("阴囊", "提睾"),
            LinkagePair("双肩", "向内收"),
        )),
        LinkageGroup(LinkageSource.FATIGUE_UP, listOf(
            LinkagePair("眼睑", "眨得慢"),
            LinkagePair("下颌", "打哈欠"),
            LinkagePair("支撑腿", "发沉"),
        )),
        LinkageGroup(LinkageSource.FULLNESS_UP, listOf(
            LinkagePair("腹壁", "撑紧"),
            LinkagePair("呼吸", "变浅"),
            LinkagePair("动作", "放缓"),
        )),
    )

    const val GROUP_COUNT = 5
    const val MIN_PAIRS_PER_GROUP = 3

    /** 查某来源有没有联动组。 */
    fun groupFor(source: LinkageSource): LinkageGroup? = GROUPS.firstOrNull { it.source == source }

    /**
     * 判据①（联动侧）：组数恰为 5、每组 ≥3 对、无空 target/cue、来源不重复。
     * 判据②（提示侧）见 [checkLinkageCoverage]：写变化而零联动 ⇒ WARN。
     */
    fun checkStructure(groups: List<LinkageGroup> = GROUPS): List<MicroFinding> {
        val out = mutableListOf<MicroFinding>()
        if (groups.size != GROUP_COUNT) {
            out += MicroFinding(
                "L1", MicroSeverity.ERROR, null,
                "联动组数应为 $GROUP_COUNT，实测 ${groups.size}",
            )
        }
        val seen = LinkedHashSet<LinkageSource>()
        groups.forEachIndexed { idx, g ->
            if (!seen.add(g.source)) {
                out += MicroFinding("L1", MicroSeverity.ERROR, null, "联动来源 ${g.source.label} 重复")
            }
            if (g.size < MIN_PAIRS_PER_GROUP) {
                out += MicroFinding(
                    "L1", MicroSeverity.ERROR, null,
                    "第 ${idx + 1} 组（${g.source.label}）只有 ${g.size} 对，须 ≥$MIN_PAIRS_PER_GROUP",
                )
            }
            if (g.pairs.any { it.target.isBlank() || it.cue.isBlank() }) {
                out += MicroFinding("L1", MicroSeverity.ERROR, null, "第 ${idx + 1} 组存在空的 target 或 cue")
            }
        }
        for (src in LinkageSource.values()) {
            if (src !in seen) {
                out += MicroFinding("L1", MicroSeverity.ERROR, null, "联动来源 ${src.label} 未登记")
            }
        }
        return out
    }
}

/**
 * 判据②：一串变化里只要有一条查不到任何联动组，就给一条 WARN——说明它只贴了标签、
 * 没写牵动谁。WARN 不判红，但必须能被读出来（返回非空）。
 */
fun checkLinkageCoverage(
    changes: List<ChangeMark>,
    groups: List<LinkageGroup> = LinkageTable.GROUPS,
): List<MicroFinding> {
    val out = mutableListOf<MicroFinding>()
    for (mark in changes) {
        if (groups.none { it.source == mark.source }) {
            out += MicroFinding(
                "M2", MicroSeverity.WARN, null,
                "${mark.source.label} 只贴了标签，未写联动表现",
            )
        }
    }
    return out
}
