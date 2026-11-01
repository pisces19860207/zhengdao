// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第三百四十八批 · 读数层的**接线壳**。281 把"怎么算"定稿了（[Metrics] 纯函数、
// [MetricsSources] 只读取数），但全仓零调用——算了没人看，等于没算。本文件只做两件事：
// 1. **调**：一次快照（取数）＋一次计算（[Metrics.compute]），不自己算任何比例；
// 2. **说**：把 [Ratio] 翻成人话，给体检单一行、给治理面板四行。
//
// 三条纪律（派工单 348 §二，逐条落在代码上）：
// 1. **只读**：本文件不写盘、不改任何业务状态、**不因读数触发动作**——
//    "四率低就自动降权"是政策，不是读数；读数只有"看"这一个用处。
// 2. **不重算**：分子分母与百分比一律来自 281 的 `Ratio`／`Metrics.decimals`／`InspectText.pct`。
//    这里再写一遍 `/ denom * 100` 就是"同一份数据两套口径"，迟早打架。
// 3. **空态不许写 0%**：分母不够时只写「样本不足」并把差多少条说出来。
//    0% 是"查过、一次都没错"；没样本是"还不知道"——两者写成一样，读数就变成了安慰。
//
// **三态（2026-11-01 用户定的统一口径，不许混成一种）**：
// `有数`（样本 ≥10，印得出比例）／`现算`（跑了，但样本不足 10，只印条数、不落账）／
// `无数据`（压根没跑）。三句各说各的，谁都不许冒充谁——"没跑"最容易被读成"零发生"，
// 所以它必须自己占一行，而不是干脆不出现。
//
// 对外文案的用词纪律：不出现内部字段名（`MetricsSources`／`snapshot`／`E1`／`wire` 值）。
// 用户看到的是「违规率 3.1%（1/32）」，不是「violation=0.03125」。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.BaopuDb
import java.io.File

/**
 * 读数三态（统一口径；`cn` 是单子上印的那个词，改字面量＝老单子对不上）。
 *
 * - [MEASURED] 有数：至少一项样本 ≥[Metrics.MIN_DENOM]，印得出比例；
 * - [COMPUTED] 现算：这一趟真算了，但每项样本都不够，只印条数（数在内存里，不落账）；
 * - [NO_DATA] 无数据：压根没跑（取数或计算失败）——**不是零，是不知道**。
 */
internal enum class ReadingState(val cn: String) {
    MEASURED("有数"),
    COMPUTED("现算"),
    NO_DATA("无数据"),
}

/**
 * 一次读数的成品（体检单与治理面板共用**同一次**快照，不各算一遍）。
 *
 * @param state 三态（见 [ReadingState]）
 * @param sheetLines 体检单要渲染的行（现在只有四率一行；将来加行也走这里）
 * @param centerLines 治理面板（322 第四区）要的行：四率一行＋三项最有用的单项
 * @param lines 算好的原始行（点得开明细用；322 接线时按 [MetricKey] 取）
 */
internal data class MetricsReading(
    val atMs: Long,
    val state: ReadingState,
    val sheetLines: List<String>,
    val centerLines: List<String>,
    val lines: List<MetricLine>,
)

/**
 * 读数层的接线口（**唯一入口**：体检单现在就走这里；322 的治理面板将来也走这里）。
 *
 * 为什么不给治理面板单开一个方法：多一个入口就多一处要维持的调用点，
 * 而这一批要治的恰恰是"有实现、零调用"。面板接线时直接调 [read]，取 [MetricsReading.centerLines]。
 */
internal object MetricsWiring {

    /** 体检单那行的四项（顺序＝口径文档 §1 的顺序，不另排）。 */
    private val FOUR_RATES = listOf(
        MetricKey.VIOLATION,
        MetricKey.MISJUDGE,
        MetricKey.INTERVENTION,
        MetricKey.SKIP,
    )

    /**
     * 治理面板的三项（派工单 348 §一：四率之外挑三个最有用的，别把 13 项全塞）。
     *
     * 挑法：任务成功率（整体干得成不成）、技能成功率（技能还能不能用）、
     * 不可逆动作占比（动了多少收不回来的手）——一条管结果、一条管资产、一条管风险。
     */
    private val CENTER_KEYS = listOf(
        MetricKey.E1_TASK_SUCCESS,
        MetricKey.SKILL_SUCCESS,
        MetricKey.E1_IRREVERSIBLE,
    )

    /** 读数那一行的抬头（体检单与面板共用；改字面量＝两处一起改）。 */
    const val FOUR_RATE_HEAD = "读数（四率）"

    /**
     * 读一次：取数 → 计算 → 翻人话。**纯读**，跑一趟只多一份内存里的快照。
     *
     * M9 的判词没有持久账（281 的实话），由调用方递；空表就是"这周没判"，读数会如实 unknown。
     */
    fun read(
        db: BaopuDb,
        projectRoot: File,
        memoryRoot: File,
        nowMs: Long,
        m9: List<M9Judgment> = emptyList(),
    ): MetricsReading = of(nowMs, Metrics.compute(MetricsSources.snapshot(db, projectRoot, memoryRoot, nowMs, m9)))

    /**
     * 这一趟**没跑起来**（取数或计算抛了）：照样出一个读数，但三态记 [ReadingState.NO_DATA]。
     *
     * 为什么不留白：单子上"没有那一行"与"有那一行、写着无数据"读起来天差地别——
     * 前者像"没这回事"，后者才是"这趟没跑"。三态里最容易被误读的就是这一态，必须自己占一行。
     */
    fun noData(atMs: Long): MetricsReading = of(atMs, emptyList())

    /** 从算好的行拼出成品（纯函数；用例直接调它，与生产逐字一致）。 */
    fun of(atMs: Long, lines: List<MetricLine>): MetricsReading {
        val state = stateOf(lines)
        // 四率那一行体检单与面板共用同一句（同一个对象，不各排一遍）。
        val four = readingLine(state, FOUR_RATES, lines)
        return MetricsReading(
            atMs = atMs,
            state = state,
            sheetLines = listOf(four),
            centerLines = listOf(four) + CENTER_KEYS.mapNotNull { key ->
                lines.firstOrNull { it.key == key }?.let { human(it) }
            },
            lines = lines,
        )
    }

    /** 三态判据（只看"有没有一项样本够"，不看数值大小——数值低是结论，不是空态）。 */
    fun stateOf(lines: List<MetricLine>): ReadingState {
        val picked = FOUR_RATES.mapNotNull { key -> lines.firstOrNull { it.key == key } }
        return when {
            picked.isEmpty() -> ReadingState.NO_DATA
            picked.any { it.ratio.known } -> ReadingState.MEASURED
            else -> ReadingState.COMPUTED
        }
    }

    /**
     * 读数那一行：**先报态，再报数**。三句互不冒充。
     *
     * - 有数：`读数（四率）·有数（样本 ≥10）：违规率 3.1%（1/32）｜…`
     * - 现算：`读数（四率）·现算（不落账，样本不足 10 条）：违规率 32 条样本｜…`（**不印 %**）
     * - 无数据：`读数（四率）·无数据（这一趟没跑起来）`（不印数、不印条数）
     */
    fun readingLine(state: ReadingState, keys: List<MetricKey>, lines: List<MetricLine>): String {
        val head = "$FOUR_RATE_HEAD·${state.cn}"
        val picked = keys.mapNotNull { key -> lines.firstOrNull { it.key == key } }
        return when (state) {
            ReadingState.MEASURED ->
                "$head（样本 ≥${Metrics.MIN_DENOM}）：" + picked.joinToString("｜") { human(it) }
            ReadingState.COMPUTED ->
                "$head（不落账，样本不足 ${Metrics.MIN_DENOM} 条）：" +
                    picked.joinToString("｜") { "${it.key.cn} ${it.ratio.denom} 条样本" }
            ReadingState.NO_DATA -> "$head（这一趟没跑起来）"
        }
    }

    /**
     * 一项读数翻成人话。
     *
     * 算得出：`违规率 3.1%（1/32）`——分子分母都亮出来，点得开明细。
     * 算不出：`违规率 样本不足（只有 4 条，不到 10 条）`——**一个字都不出现 %**。
     */
    fun human(line: MetricLine): String {
        val r = line.ratio
        val name = line.key.cn
        if (!r.known) return "$name 样本不足（${shortfall(r.denom)}）"
        val v = r.value ?: return "$name 样本不足（${shortfall(r.denom)}）"
        return if (line.key.perTask) {
            "$name ${Metrics.decimals(v)} 次/任务（${r.num}/${r.denom}）"
        } else {
            "$name ${InspectText.pct(v)}%（${r.num}/${r.denom}）"
        }
    }

    /** 差多少条才够算（分母线只有一处：`Metrics.MIN_DENOM`，不在这里再写一个 10）。 */
    private fun shortfall(denom: Int): String =
        if (denom <= 0) "这窗内没有可算的记录" else "只有 $denom 条，不到 ${Metrics.MIN_DENOM} 条"
}
