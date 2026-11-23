// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第八百三十五批 · 因果链骨架：投影的唯一渲染出口。
//
// 四行固定次序（部位 / 姿态 / 感觉 / 环境）＋一行滞后未到期清单。固定次序不是洁癖：
// 回放结果要能被「逐字节比较」当断言用，任何一处 HashMap 顺序漏进来，确定性就假了。
//
// 「未给」是一个**值**，不是空：没人写过这一维就印「未给」，绝不印「无」——
// 「无汗」和「没人算过汗」在文本上必须分得开（825 §二 的缺项口径）。
package com.example.zhengdao.baopu.state.causal

/** 四行的分组表：唯一真相，渲染与测试都从这里取。 */
internal object CausalGroups {
    val SITE: List<CausalDim> = listOf(CausalDim.SITE_INJURY)
    val POSE: List<CausalDim> = listOf(CausalDim.POSE_JOINT, CausalDim.POSE_COM)
    val SENSE: List<CausalDim> = listOf(CausalDim.SENSE_PAIN, CausalDim.SENSE_FEEL, CausalDim.SENSE_FATIGUE)
    val ENV: List<CausalDim> = listOf(CausalDim.SKIN_WET, CausalDim.SKIN_TEMP, CausalDim.LIGHT)

    /** 行的固定次序。 */
    val ROWS: List<Pair<String, List<CausalDim>>> = listOf(
        "部位" to SITE,
        "姿态" to POSE,
        "感觉" to SENSE,
        "环境" to ENV,
    )
}

internal object CausalRenderer {

    /** 没有被写过的维度印这个，不印「无」。 */
    const val ABSENT = "未给"

    /** 唯一出口：一次投影 ⇒ 一段多行文本。 */
    fun render(state: CausalState): String {
        val sb = StringBuilder()
        sb.append("因果投影｜已过去 ").append(state.elapsed.label)
            .append("（825）｜").append(state.site.text)
        CausalGroups.ROWS.forEach { (name, dims) ->
            sb.append('\n').append(name).append(' ').append(cells(state, dims))
        }
        sb.append('\n').append("滞后未到 ").append(pendingText(state))
        if (state.levels.containsKey(CausalDim.LIGHT)) {
            sb.append('\n').append("光影缺省 ").append(Pending824)
        }
        return sb.toString()
    }

    /** 一行里的若干格，用「｜」隔开；空格数固定，便于逐字比对。 */
    private fun cells(state: CausalState, dims: List<CausalDim>): String =
        dims.joinToString("｜") { dim ->
            val lv = state.level(dim)
            if (lv == null) "${dim.label}：$ABSENT" else "${dim.label}：${dim.short(lv)}"
        }

    private fun pendingText(state: CausalState): String =
        if (state.pending.isEmpty()) {
            "无"
        } else {
            state.pending.joinToString("；") { "${it.dim.label} ${it.span.label}（${it.eventId}）" }
        }

    /** 溯源行：这一维最后是被哪条事件写下的。断言用，不进主投影文本。 */
    fun originOf(state: CausalState, dim: CausalDim): String = state.origin[dim] ?: ABSENT
}
