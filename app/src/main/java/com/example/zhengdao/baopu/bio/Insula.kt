// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百七十五批 · 岛叶·体感（六体第②件，登记表第五行）。
//
// 机理：内感受——对身体自身状态的集合感知。只做：把本机体征（电量/温度档/
// 存储余量/构建时长/门禁红黄绿）汇成**一个只读快照**。不做：不以体征决定接不接
// 任务、不触发自我修复（修是御医的职，岛叶只递读数）。
//
// 三条硬纪律（派工单②＋判据⑥逐字落码）：
//   ① 快照是**值**不是账：本 object 无状态、无落盘，取多少次都不留痕；
//   ② 数据全走 [Insula.Sources] 只读缝——真机接电池/温度/存储/门禁的活归后续接线单，
//      本件不偷偷摸系统服务；哪个缝没接，对应字段就是"无读数"（兜底＝现状，登记表口径）；
//   ③ 形轨数值**现读现显**：走形轨只读 API（SilhouetteWire 一类纯函数）拿到什么显什么，
//      快照里只留一句话——本件没有任何存形轨数值的字段/文件/表，"不抄进自己的账"
//      在结构上成立，不靠自觉。
package com.example.zhengdao.baopu.bio

import java.util.Locale

/** 岛叶·体感快照（纯函数器官，零状态零落盘）。 */
@BioAnalog("岛叶")
internal object Insula {

    /**
     * 一次全身快照。六个体征字段＋形轨现读一句＋note；缺数据的字段固定写"无读数"，
     * 不造数、不补零——读数缺失即兜底（登记表"失败兜底"列的口径）。
     */
    data class Snapshot(
        val battery: String,
        val storage: String,
        val thermal: String,
        val build: String,
        val gates: String,
        val formTrack: String,
        val note: String,
    )

    /**
     * 只读数据缝：七个 supplier 全由调用方喂。默认全空 ⇒ 快照体征全"无读数"——
     * 这是合法快照（岛叶活着但没接探头），不是失败。
     */
    data class Sources(
        val batteryPct: (() -> Int?)? = null,
        val charging: (() -> Boolean?)? = null,
        val thermalC: (() -> Double?)? = null,
        val storageBytes: (() -> Pair<Long, Long>)? = null,
        val buildSeconds: (() -> Long?)? = null,
        val gatesLine: (() -> String?)? = null,
        val formTrackLine: (() -> String?)? = null,
    )

    /** 取一次快照。同一份 Sources 调两次，字段逐字一致（判据④；真机探头自带的抖动除外）。 */
    fun snapshot(sources: Sources = Sources()): Snapshot = Snapshot(
        battery = batteryLine(sources.batteryPct?.invoke(), sources.charging?.invoke()),
        storage = storageLine(sources.storageBytes?.invoke()),
        thermal = thermalLine(sources.thermalC?.invoke()),
        build = buildLine(sources.buildSeconds?.invoke()),
        gates = passThrough(sources.gatesLine?.invoke(), MISSING),
        formTrack = sources.formTrackLine
            ?.let { passThrough(it.invoke(), TRACK_MISSING) } ?: TRACK_UNWIRED,
        note = NOTE,
    )

    /** 电量：有读数显百分比＋充放电态；充电态未知就明说未知，不猜。 */
    private fun batteryLine(pct: Int?, charging: Boolean?): String {
        if (pct == null || pct !in 0..100) return MISSING
        val state = when (charging) {
            true -> "充电中"
            false -> "放电"
            null -> "充电态未知"
        }
        return "$pct%（$state）"
    }

    /** 存储：余量/总量两条 GB 数，一位小数，Locale.ROOT 固定小数点（逐字节一致）。 */
    private fun storageLine(bytes: Pair<Long, Long>?): String {
        if (bytes == null || bytes.second <= 0L) return MISSING
        val free = String.format(Locale.ROOT, "%.1f", bytes.first / GB_BYTES)
        val total = String.format(Locale.ROOT, "%.1f", bytes.second / GB_BYTES)
        return "余 ${free}GB / 共 ${total}GB"
    }

    /** 温度档（左闭右开，压线归高档）：<30 凉 / [30,37) 常 / [37,40) 温 / ≥40 热。 */
    private fun thermalLine(c: Double?): String {
        if (c == null || !c.isFinite()) return MISSING
        val band = when {
            c < 30.0 -> "凉"
            c < 37.0 -> "常温"
            c < 40.0 -> "温"
            else -> "热"
        }
        return "${band}档（${String.format(Locale.ROOT, "%.1f", c)}℃）"
    }

    /** 构建时长：不在构建就是"不在构建"，不拿旧数充数。 */
    private fun buildLine(seconds: Long?): String =
        if (seconds == null || seconds < 0L) "不在构建" else "构建中 ${seconds}s"

    /** 门禁红黄绿与形轨都是**转递不改写**：御医/形轨说什么就是什么，岛叶不判色不解读。 */
    private fun passThrough(line: String?, missing: String): String =
        line?.trim()?.takeIf { it.isNotEmpty() } ?: missing

    private const val MISSING = "无读数"

    /** 形轨两种"没有"分开说：缝没接（本批默认）与缝接了但那次没读数。 */
    private const val TRACK_UNWIRED = "未接形轨缝"
    private const val TRACK_MISSING = "形轨无读数"
    private const val GB_BYTES = 1024.0 * 1024.0 * 1024.0
    private const val NOTE = "只读快照·零落盘；体征不参与任何决定（判定权在既有裁决面）"
}
