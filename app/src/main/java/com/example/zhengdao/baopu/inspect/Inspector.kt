// 独立开发声明：本文件为本项目原创，不含第三方源码拷贝。
//
// 第二百三十九批 §3：报告形态（三行，人能 3 秒看完）。
//
// 这里是**聚合口**：六项勘验各出一条 [InspectFinding]，本文件只决定
// 「三行怎么摆、哪两行进正文、一句给模型」。零 IO、零时钟（时刻由调用方给）。
//
// 三条形制纪律（都是负例的靶心）：
// - **超过三行即红**（§5②）：[PatrolReport.lines] 的上限在这里钳，不在界面钳；
// - **明细永不进模型**（§5③／§9）：给模型的只有 [PatrolReport.modelLine] 一句，
//   `findings`/`evidence` 只进巡按栏，界面之外没有第二个出口；
// - **查不了要露字**：[InspectVerdict.NO_DATA] 不进命中数，但进标题那一句——
//   藏掉「没查成」是本批立批要纠的弊。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.core.TimeFormats

/** 一次巡按的报告（三行形制；巡按台账存的就是它）。 */
internal data class PatrolReport(
    /** 巡按 id（`patrol:<atMs>`；额度变动拿它当依据，§7.2「依据哪次勘验」）。 */
    val id: String,
    val atMs: Long,
    /** 第一行：`巡按 10-27：2 项`（＋「查不了 N 项」若有）。 */
    val headline: String,
    /** 后面最多两行（各 ≤40 字；只放命中项，[InspectVerdict.NO_DATA] 不上正文）。 */
    val lines: List<String>,
    /** 全部六项的明细（**只给巡按栏渲染**；模型侧一个字节都拿不到它）。 */
    val findings: List<InspectFinding> = emptyList(),
    /** 命中项数（标题里那个数）。 */
    val hits: Int = 0,
    /** 查不了的项数。 */
    val noData: Int = 0,
    /** §7.2：这次巡按算出的通过率读数（没账 ⇒ null，不假装知道）。 */
    val passRate: PassRate? = null,
) {
    /** 给模型/通知栏的**唯一一句**（＝标题；明细不在里面）。 */
    val modelLine: String get() = headline

    /** 形制自检（门禁与用例都拿它断言，而不是各自数一遍行数）。 */
    val fitsForm: Boolean
        get() = headline.length <= InspectLimits.MAX_LINE_CHARS &&
            lines.size <= InspectLimits.MAX_REPORT_LINES - 1 &&
            lines.all { it.length <= InspectLimits.MAX_LINE_CHARS }
}

internal object Inspector {

    /** 报告的固定顺序＝检查号（I1→I6），**不是严重度**。 */
    private val ORDER: List<InspectCheck> = InspectCheck.entries.toList()

    /**
     * 为什么按检查号而不是按「有多严重」排：两次巡按的同一批命中要落在同样的行上，
     * 人才能拿眼睛比对。按严重度排就得先定义严重度——那是御批的活，纠察不替人定。
     */
    fun ordered(findings: List<InspectFinding>): List<InspectFinding> =
        findings.sortedBy { f -> ORDER.indexOf(f.check).let { if (it < 0) ORDER.size else it } }

    /**
     * 聚合（[findings] 最多六条，一项一条；多余的按检查号去重保最后一条——
     * 同一项出两条＝编排写错了，这里兜住并让它显形，而不是渲染成四行）。
     */
    fun report(
        findings: List<InspectFinding>,
        nowMs: Long,
        passRate: PassRate? = null,
    ): PatrolReport {
        val byCheck = LinkedHashMap<InspectCheck, InspectFinding>()
        findings.forEach { byCheck[it.check] = it }
        val all = ordered(byCheck.values.toList())
        val hits = all.filter { it.verdict == InspectVerdict.HIT }
        val noData = all.count { it.verdict == InspectVerdict.NO_DATA }

        val bodyLimit = InspectLimits.MAX_REPORT_LINES - 1
        val listed = hits.take(bodyLimit)
        val rest = hits.size - listed.size
        val lines = listed.map { InspectText.line("· ${it.oneLine}") }

        val title = buildString {
            append("巡按 ${TimeFormats.monthDay(nowMs)}：${hits.size} 项")
            if (noData > 0) append("／查不了 $noData")
            // 「列前 N」而不是「列最重 N」：顺序按检查号，纠察不替人定严重度。
            if (rest > 0) append("（列前 $bodyLimit，余 $rest）")
        }
        return PatrolReport(
            id = patrolIdOf(nowMs),
            atMs = nowMs,
            headline = InspectText.line(title),
            lines = lines,
            findings = all,
            hits = hits.size,
            noData = noData,
            passRate = passRate,
        )
    }

    /** 巡按 id（时刻就是身份：一次巡按只跑一遍，同毫秒的两巡按本就同一次）。 */
    fun patrolIdOf(nowMs: Long): String = "patrol:$nowMs"
}
