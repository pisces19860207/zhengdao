// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第三百四十八批 · 读数层的**接线壳**。281 把"怎么算"定稿了（[Metrics] 纯函数、
// [MetricsSources] 只读取数），但全仓零调用——算了没人看，等于没算。本文件只做两件事：
// 1. **调**：一次快照（取数）＋一次计算（[Metrics.compute]），不自己算任何比例；
// 2. **说**：把 [Ratio] 翻成人话，给体检单一行、给治理面板三行。
//
// 三条纪律（派工单 348 §二，逐条落在代码上）：
// 1. **只读**：本文件不写盘、不改任何业务状态、**不因读数触发动作**——
//    "四率低就自动降权"是政策，不是读数；读数只有"看"这一个用处。
// 2. **不重算**：分子分母与百分比一律来自 281 的 `Ratio`／`Metrics.decimals`／`InspectText.pct`。
//    这里再写一遍 `/ denom * 100` 就是"同一份数据两套口径"，迟早打架。
// 3. **空态不许写 0%**：分母不够时只写「样本不足」并把差多少条说出来。
//    0% 是"查过、一次都没错"；没样本是"还不知道"——两者写成一样，读数就变成了安慰。
//
// 对外文案的用词纪律：不出现内部字段名（`MetricsSources`／`snapshot`／`E1`／`wire` 值）。
// 用户看到的是「违规率 3.1%（1/32）」，不是「violation=0.03125」。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.BaopuDb
import java.io.File

/**
 * 一次读数的成品（体检单与治理面板共用**同一次**快照，不各算一遍）。
 *
 * @param fourRateLine 四率那一行（体检单直接印）
 * @param sheetLines 体检单要渲染的行（现在只有四率一行；将来加行也走这里）
 * @param centerLines 治理面板（322 第四区）要的行：四率一行＋三项最有用的单项
 * @param lines 算好的原始行（点得开明细用；322 接线时按 [MetricKey] 取）
 */
internal data class MetricsReading(
    val atMs: Long,
    val fourRateLine: String,
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

    /** 四率那一行的抬头（体检单与面板共用；改字面量＝两处一起改）。 */
    const val FOUR_RATE_HEAD = "读数（四率）"

    /**
     * 体检单那一行的尾巴：说明四个数是**现算**的、没有落盘账。
     *
     * 为什么非要写这一句：单子上紧接着就有一行「未取样…读数层」——那是"产出条数测不出来"，
     * 不是"没算"。不写清楚，两行看着像打架。
     */
    const val NO_LEDGER_TAIL = "（现算，无落账）"

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
    ): MetricsReading {
        val lines = Metrics.compute(MetricsSources.snapshot(db, projectRoot, memoryRoot, nowMs, m9))
        val four = fourRateLine(lines)
        return MetricsReading(
            atMs = nowMs,
            fourRateLine = four,
            sheetLines = sheetLines(lines),
            centerLines = centerLines(lines),
            lines = lines,
        )
    }

    /** 体检单要印的行（纯函数：算好的行 → 单子文字；用例直接调它，与生产逐字一致）。 */
    fun sheetLines(lines: List<MetricLine>): List<String> = listOf(fourRateLine(lines) + NO_LEDGER_TAIL)

    /** 四率一行（人话；四项全算不出时只留一句"样本不足"，不铺四遍）。 */
    fun fourRateLine(lines: List<MetricLine>): String {
        val picked = FOUR_RATES.mapNotNull { key -> lines.firstOrNull { it.key == key } }
        if (picked.none { it.ratio.known }) {
            return "$FOUR_RATE_HEAD：样本不足（这窗内四项都没有可算的记录）"
        }
        return "$FOUR_RATE_HEAD：" + picked.joinToString("｜") { human(it) }
    }

    /** 治理面板三行＋四率一行（322 接线时直接用；本批不接界面）。 */
    fun centerLines(lines: List<MetricLine>): List<String> =
        listOf(fourRateLine(lines)) + CENTER_KEYS.mapNotNull { key ->
            lines.firstOrNull { it.key == key }?.let { human(it) }
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
