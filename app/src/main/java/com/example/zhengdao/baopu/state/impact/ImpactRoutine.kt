// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第七百二十七批 · 日常动作豁免（§追加·日常动作不算作用力）。
// 七类力指有意图或非日常的施加；掏 / 拉链 / 解裤 / 坐下 / 起身 / 排尿不进矩阵，
// 默认无伤（见 748 豁免清单）。唯一边界：拉链夹到包皮，rare 档只到 1，且必须有触发。
package com.example.zhengdao.baopu.state.impact

/** 日常动作的默认后果：一律 0 档（无），不进 [ImpactMatrix] 的七类力。 */
internal val ROUTINE_DEFAULT_TIER: Int = 0

/** 唯一边界情形的后果上限：1（轻微），不许到 2 / 3。 */
internal val RARE_EDGE_MAX_TIER: Int = 1

/** 日常动作结果。 */
internal data class RoutineResult(
    val action: RoutineAction,
    val tier: Int,
    val triggered: Boolean,
    val basis: String,
) {
    fun line(): String = buildString {
        append(action.label).append('|').append(tier).append('|')
        append(if (triggered) "有触发" else "无触发").append('|').append(basis)
    }
}

internal object ImpactRoutine {

    /** 日常动作一律无后果；本表不是矩阵的一部分，判据⑤靠它把 routine 挡在矩阵之外。 */
    fun of(action: RoutineAction): RoutineResult = RoutineResult(
        action = action,
        tier = ROUTINE_DEFAULT_TIER,
        triggered = false,
        basis = "日常动作不进作用力矩阵，默认无伤（748 豁免清单）",
    )

    /**
     * 唯一边界：拉裤门拉链时夹到包皮。
     * 只有 [trigger] 非空才成立；后果封顶 [RARE_EDGE_MAX_TIER]，急 / 手滑 / 衣物变形三选一。
     */
    fun rareEdge(trigger: RareTrigger?): RoutineResult {
        require(trigger != null) { "拉链夹到包皮须有触发（急 / 手滑 / 衣物变形），无触发不算边界情形" }
        return RoutineResult(
            action = RoutineAction.ZIP,
            tier = RARE_EDGE_MAX_TIER,
            triggered = true,
            basis = "rare 边界：拉链夹到包皮（触发＝${trigger.label}），牵扯 / 挤压轻微档封顶",
        )
    }

    /** 判据⑤：矩阵里不许出现「日常动作 → 后果 ≥2」。 */
    fun routineClean(): Boolean =
        ROUTINE_DEFAULT_TIER < 2 && RARE_EDGE_MAX_TIER < 2 &&
            RoutineAction.entries.all { of(it).tier < 2 }
}
