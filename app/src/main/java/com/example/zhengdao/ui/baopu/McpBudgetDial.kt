// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百四十单 · 「外部工具占用上限」拨盘的纯判据（零 Android 依赖，JVM 直测）。
//
// 为什么单独一份：630 已把预算算法（McpToolBudget / McpBudgetStore / McpBudgetWiring）
// 定死，本单是**纯 UI**。滑杆的档位、夹取、百分比↔比例换算、"一次改动只拨一次"这四条
// 仍是可判定的规则，抽到这里，页面只负责画 —— 与 BaopuSettingsRules.kt 同一房型。
//
// 唯一真相源：比例本身没有第二份配置。落盘仍是 630 的 `baopu_mcp` / `assembly` 键里
// 的 ratio 字段；本文件只是把「界面上的整数百分比」翻译成那个比例，别处不许再抄一份范围。
package com.example.zhengdao.ui.baopu

/** 拨盘上的整数百分比档位；[ratioOf] 之后才交给 630 的 [com.example.zhengdao.baopu.mcp.McpBudgetStore.setRatio]。 */
internal object McpBudgetDial {

    const val MIN_PERCENT = 4
    const val MAX_PERCENT = 20
    const val STEP_PERCENT = 1
    const val DEFAULT_PERCENT = 8

    /** Compose Slider 的 steps 不含两端：4..20 步长 1 ⇒ 17 个档位、15 个中间刻度。 */
    const val SLIDER_STEPS = (MAX_PERCENT - MIN_PERCENT) / STEP_PERCENT - 1

    val SLIDER_RANGE: ClosedFloatingPointRange<Float> =
        MIN_PERCENT.toFloat()..MAX_PERCENT.toFloat()

    // 文案中文（本单边界：不引多语言）——不新增任何 <string> 键，
    // 与 SafetyActivity 的「MCP 服务端（外部工具口）」卡同款口径。
    const val LABEL = "外部工具占用上限"
    const val SUBTITLE = "超出后按最近使用优先装载，未装载的会被告知，不会截断"

    /** 控件之外的坏值（旧配置、拖动浮点误差）一律夹回 4–20，界面上不可能出现越界档。 */
    fun clampPercent(raw: Int): Int = raw.coerceIn(MIN_PERCENT, MAX_PERCENT)

    /** 百分比 → 630 吃的比例（0.04–0.20，落在 clampRatio 的 0.01–0.50 之内，不动算法）。 */
    fun ratioOf(percent: Int): Double = clampPercent(percent) / 100.0

    /** 落盘比例 → 界面档位；null = 没配过，用默认 8%（与 630 的 DEFAULT_RATIO 同值）。 */
    fun percentOf(ratio: Double?): Int =
        if (ratio == null || ratio.isNaN()) DEFAULT_PERCENT
        else clampPercent(kotlin.math.round(ratio * 100.0).toInt())

    /** Slider 回调给的是 Float（已按 steps 吸附），仍取整夹取一次。 */
    fun percentOfSlider(value: Float): Int = clampPercent(kotlin.math.round(value).toInt())

    fun valueText(percent: Int): String = "${clampPercent(percent)}%"

    /**
     * 一次改动只拨一次：档位没变就不调 [setRatio]（拖动时 Compose 会重复回调同一个刻度，
     * 首帧也可能拿当前档再回调一次 —— 若每次都写，prefs 会被无意义地刷 N 遍）。
     *
     * @return 界面该显示的档位（夹取后）。
     */
    fun commitIfChanged(current: Int, next: Int, setRatio: (Double) -> Boolean): Int {
        val from = clampPercent(current)
        val to = clampPercent(next)
        if (to != from) setRatio(ratioOf(to))
        return to
    }
}
