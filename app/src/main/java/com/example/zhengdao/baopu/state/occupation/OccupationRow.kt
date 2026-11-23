// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 一行的形状：工种（行业下再一层）＋六维＋环境／着装／负荷三挂＋四面驱动＋交接＋两枚特有槽＋第二期四槽。
//
// 判据③就钉在这个构造上：具体工种必须能挂到「环境／着装／负荷」三样上，只列名字不算。
// 除 key／label 之外的格子**全部可空**，为的是负例能把任意一格抽掉，看检查器报不报——
// 「改一半」必须红，否则判据只是文档里的一句话。
package com.example.zhengdao.baopu.state.occupation

/** 一个工种一行。[service] 只有军警消防的行业填，[faith] 只有宗教职的行业填（C6／C7 双向机检）。 */
data class OccupationRow(
    val key: String,
    val label: String,
    val industry: Industry?,
    val six: SixDims?,
    val env: EnvProfile?,
    val attire: AttireProfile?,
    val burden: BurdenProfile?,
    val drives: DriveSet?,
    val hands: List<Handoff>?,
    val service: ServiceSlot? = null,
    val faith: FaithSlot? = null,
    val phase2: Phase2Slots? = null,
) {
    /**
     * 本行能读到的全部中文文本——判据扫描的唯一取材口。
     * 禁词表（C4）、涉密口径（C12）、病名（C10）都只从这里取，所以任何一格写出刻板话都跑得掉不了。
     */
    fun texts(): List<String> {
        val parts = mutableListOf(label)
        industry?.let { parts.add(it.label) }
        six?.let { s ->
            s.posture?.let { parts.add(it.label) }
            s.dirt?.let { parts.add(it.label) }
            s.dress?.let { parts.add(it.label) }
            s.sched?.let { parts.add(it.label) }
            s.risk?.let { parts.add(it.label) }
        }
        env?.let { e ->
            e.place?.let { parts.add(it.label) }
            e.noise?.let { parts.add(it.label) }
            e.dust?.let { parts.add(it.label) }
            e.temp?.let { parts.add(it.label) }
            e.moist?.let { parts.add(it.label) }
            e.chem?.let { parts.add(it.label) }
        }
        attire?.let { a ->
            a.items?.forEach { parts.add(it.label) }
            a.closure?.let { parts.add(it.label); parts.add(it.reach.label) }
        }
        burden?.let { b ->
            b.carry?.let { parts.add(it.label) }
            b.repeats?.forEach { parts.add(it.label) }
            b.standing?.let { parts.add(it.label) }
            b.shift?.let { parts.add(it.label) }
        }
        drives?.let { d ->
            DriveAxis.entries.forEach { axis ->
                parts.add(axis.label)
                d.dirOf(axis)?.let { parts.add(it.label) }
            }
            d.mechanisms?.forEach { parts.add(it.label) }
            d.spread?.let { parts.add(it.label) }
        }
        hands?.forEach { parts.add(it.target) }
        service?.let { s ->
            s.arm?.let { parts.add(it.label) }
            s.armyRank?.let { parts.add(it.label) }
            s.policePost?.let { parts.add(it.label) }
            s.firePost?.let { parts.add(it.label) }
            s.scene?.let { parts.add(it.label) }
            s.phase?.let { parts.add(it.label) }
            s.dutyCycle?.let { parts.add(it.label) }
            s.returnGap?.let { parts.add(it.label) }
            s.gear?.forEach { parts.add(it.label) }
            s.envExtra?.forEach { parts.add(it.label) }
            s.bodyLoad?.forEach { parts.add(it.label) }
        }
        faith?.let { f ->
            f.branch?.let { parts.add(it.label); parts.add(it.tradition) }
            f.celibate?.let { parts.add(it.label) }
            f.diet?.let { parts.add(it.label) }
            f.hair?.let { parts.add(it.label) }
            f.dress?.let { parts.add(it.label) }
            f.residence?.let { parts.add(it.label) }
            f.basis?.forEach { parts.add(it.label) }
        }
        phase2?.let { p ->
            p.habitat?.let { parts.add(it.status?.label ?: ""); parts.add(it.habitat?.label ?: ""); parts.add(it.bath?.label ?: "") }
            p.traces?.let { t ->
                parts.add(t.status?.label ?: "")
                t.kinds?.forEach { parts.add(it.label) }
                parts.add(t.backAche?.label ?: "")
                parts.add(t.hearing?.label ?: "")
            }
            p.odor?.let { o ->
                parts.add(o.status?.label ?: "")
                o.sources?.forEach { parts.add(it.label) }
            }
            p.minority?.let { m ->
                parts.add(m.status?.label ?: "")
                m.kinds?.forEach { parts.add(it.label) }
                parts.add(m.share?.label ?: "")
                parts.add(m.gaze?.label ?: "")
                parts.add(m.address?.label ?: "")
            }
        }
        return parts
    }

    /** 三挂（环境／着装／负荷）缺哪几样——判据③，缺了给提示不是判红。 */
    fun missingHangs(): List<String> = listOf(
        "环境" to env, "着装" to attire, "负荷" to burden,
    ).filter { it.second == null }.map { it.first }
}
