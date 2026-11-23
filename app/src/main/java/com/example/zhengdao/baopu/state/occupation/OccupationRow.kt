// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百八十二批 · 一行的形状：工种（行业下再一层）＋六维＋环境／着装／负荷三挂＋四面驱动＋交接＋两枚特有槽＋第二期四槽。
//
// 判据③就钉在这个构造上：具体工种必须能挂到「环境／着装／负荷」三样上，只列名字不算。
// 除 key／label 之外的格子**全部可空**，为的是负例能把任意一格抽掉，看检查器报不报——
// 「改一半」必须红，否则判据只是文档里的一句话。
//
// [texts] 分成四段取（六维与三挂／军警消／宗教／第二期），不是为了好看：
// 判据词表扫描的唯一取材口一旦写成一个大函数，漏一格就永远扫不到，也没法说清「扫的是哪些字」。
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
     * 本行能读到的全部中文文本——判据扫描的唯一取材口（C4 刻板词、C10 病名、C12 涉密都只从这里取）。
     * 未设定的格子贡献 null，最后一起滤掉：空值不该参与词表比对。
     */
    fun texts(): List<String> = (trunkTexts() + serviceTexts() + faithTexts() + phase2Texts()).filterNotNull()

    /** 六维＋环境＋着装＋负荷＋驱动＋交接：民用行与军警消、宗教行都要扫的一段。 */
    private fun trunkTexts(): List<String?> {
        val s = six
        val e = env
        val a = attire
        val b = burden
        val d = drives
        val out = mutableListOf<String?>(label, industry?.label)
        out += listOf(s?.posture?.label, s?.dirt?.label, s?.dress?.label, s?.sched?.label, s?.risk?.label)
        out += listOf(e?.place?.label, e?.noise?.label, e?.dust?.label, e?.temp?.label, e?.moist?.label, e?.chem?.label)
        out += listOf(b?.carry?.label, b?.standing?.label, b?.shift?.label)
        out += listOf(a?.closure?.label, a?.closure?.reach?.label)
        out += a?.items?.map { it.label } ?: emptyList()
        out += b?.repeats?.map { it.label } ?: emptyList()
        out += DriveAxis.entries.map { it.label }
        out += DriveAxis.entries.map { d?.dirOf(it)?.label }
        out += d?.mechanisms?.map { it.label } ?: emptyList()
        out += listOf(d?.spread?.label)
        out += hands?.map { it.target } ?: emptyList()
        return out
    }

    /** 军警消特有槽十一格＋三张清单。 */
    private fun serviceTexts(): List<String?> {
        val s = service ?: return emptyList()
        val out = mutableListOf<String?>(
            s.arm?.label, s.armyRank?.label, s.policePost?.label, s.firePost?.label,
            s.scene?.label, s.phase?.label, s.dutyCycle?.label, s.returnGap?.label,
        )
        out += s.gear?.map { it.label } ?: emptyList()
        out += s.envExtra?.map { it.label } ?: emptyList()
        out += s.bodyLoad?.map { it.label } ?: emptyList()
        return out
    }

    /** 宗教七件＋教派归属（tradition 一起扫，「道教」「佛教」这类归类词也不许被写成评价）。 */
    private fun faithTexts(): List<String?> {
        val f = faith ?: return emptyList()
        val out = mutableListOf<String?>(
            f.branch?.label, f.branch?.tradition, f.celibate?.label, f.diet?.label,
            f.hair?.label, f.dress?.label, f.residence?.label,
        )
        out += f.basis?.map { it.label } ?: emptyList()
        return out
    }

    /** 第二期四槽：状态位与已填内容都进文本（占位期只有状态位）。 */
    private fun phase2Texts(): List<String?> {
        val p = phase2 ?: return emptyList()
        val h = p.habitat
        val t = p.traces
        val o = p.odor
        val m = p.minority
        val out = mutableListOf<String?>(
            h?.status?.label, h?.habitat?.label, h?.bath?.label,
            t?.status?.label, t?.backAche?.label, t?.hearing?.label,
            o?.status?.label,
            m?.status?.label, m?.share?.label, m?.gaze?.label, m?.address?.label,
        )
        out += t?.kinds?.map { it.label } ?: emptyList()
        out += o?.sources?.map { it.label } ?: emptyList()
        out += m?.kinds?.map { it.label } ?: emptyList()
        return out
    }

    /** 三挂（环境／着装／负荷）缺哪几样——判据③，缺了给提示不是判红。 */
    fun missingHangs(): List<String> = listOf(
        "环境" to env, "着装" to attire, "负荷" to burden,
    ).filter { it.second == null }.map { it.first }
}
