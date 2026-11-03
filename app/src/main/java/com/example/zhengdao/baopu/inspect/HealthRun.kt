// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十一批（第二刀）· 御医体检的**编排壳**：取数（普查＋轨迹＋自己的账）→ 判据
// （[HealthCheck]）→ 落账（[HealthLedger]）→ 递一条意见进延后队列（**不弹卡**）。
//
// 为什么"递意见"走 `OpinionWiring.enqueueOnly` 而不是 `onOpinion`：
// `onOpinion` 是**分类器**那条道，预算内会判成"该弹"并吃掉当日御案额度（每日 ≤3）。
// 体检是月度维护，抢掉一张御案额度等于让真正的确认卡往后排——所以只进队列、不占额度。
//
// 三条纪律：
// 1. **只读业务**：不写技能、不改账本、不动工作区文件；只追加自己那本 `体检记录.jsonl`；
// 2. **不弹卡**：不碰 UI、不 Toast、不 Dialog（源码门禁见 `HealthWiringGateTest`）；
// 3. **失败了要说出来**：库打不开／账落不上 ⇒ [Result.failReason]，由调度器决定要不要重试。
package com.example.zhengdao.baopu.inspect

import android.content.Context
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.OpinionWiring
import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.core.RunLog
import java.io.File

/** 一次体检的产物（界面/日志只消费 [entry]；[enqueued] 说明有没有递到御案）。 */
internal data class HealthResult(
    val entry: HealthEntry?,
    val enqueued: Boolean,
    val failReason: String?,
)

internal object HealthRun {

    /**
     * 部位 → 产出落点文件。
     *
     * **只收"单一写者"的账**：一本账只有一个部位往里写，行数才等于它的产出数。
     * 有歧义的（`决策记录.jsonl` 有十几个写者、`进化档案-承诺.md` 反思与学习都写）
     * 一律**不猜**——留空 ⇒ 那一路记"未取样"，比编一个数强。
     *
     * 409：影子／不良人／金吾卫三个抽检件**退出这张表**——它们的"账本行数"从此不进
     * 判据（行数≠产出：一行一次运行，零差异也是一行），改由 [samplingFacts] 按运行记录
     * 判「有没有跑」与「跑出了什么」两个维度。账还在，读法换了。
     */
    private val SINK_FILES: Map<String, String> = mapOf(
        "勘验" to PatrolLedger.FILE_NAME,
    )

    /** 抽检件（409）：按运行记录判的三个部位（名字与 [OrganCensus.HEALTH_PARTS] 同一把键）。 */
    private val SAMPLE_ORGANS: Set<String> = setOf("影子", "不良人", "金吾卫")

    /** 208 装配的那棵目录（体检账与 208 同目录，不同文件）。 */
    fun memoryRoot(): File? = DecisionLedger.installedRoot()

    /**
     * 跑一趟。
     *
     * @param manual true ＝ 用户在设置页点的（不看月位、不看充电）
     */
    fun run(ctx: Context, manual: Boolean, nowMs: Long): HealthResult {
        val root = memoryRoot()
            ?: return fail("208 没装配落点（DecisionLedger.install），体检没处落账")
        val db = runCatching { BaopuRuntime.db(ctx) }.getOrNull()
            ?: return fail("库打不开，体检取不到轨迹")

        val from = HealthLimits.fromOf(nowMs)
        val stats = HealthSources.traceStats(db, from)
        // 第三百四十八批：读数层接进来（281 交付时全仓零调用 ⇒ "账在、没人算"）。
        // 只取一行文字给单子看；**不拿读数做任何判断**（"四率低就降权"是政策，不是读数）。
        // 没跑起来也要留一行（三态里的「无数据」）——留白会被读成"没这回事"。
        val projectRoot: File = runCatching { BaopuRuntime.workspaceRoot(ctx) }.getOrDefault(root)
        val reading = runCatching { MetricsWiring.read(db, projectRoot, root, nowMs) }
            .onFailure { RunLog.log("御医体检：读数没算出来——${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse { MetricsWiring.noData(nowMs) }
        val tools = HealthSources.probes(
            stats = stats,
            visible = visibleTools(),
            groupOf = ToolGroupTable.GROUP_OF.mapValues { it.value.key },
            allTools = BaopuToolCatalog.ENTRIES.map { it.name },
        )
        val prev = HealthLedger.previousStates(root)
        // 409：三个抽检件的运行记录读数（有没有跑／跑出了什么），读不出来的那一路不附。
        val sampling = runCatching { samplingFacts(root, db, from, nowMs) }
            .onFailure { RunLog.log("御医体检：抽检运行记录没读出来——${it.javaClass.simpleName}: ${it.message}") }
            .getOrElse { emptyMap<String, SampleFacts>() }
        val organs = OrganCensus.HEALTH_PARTS.map { p ->
            HealthCheck.organ(
                p.copy(
                    outputs = if (p.name in SAMPLE_ORGANS) null else organOutputs(p, root, reading),
                    sampling = sampling[p.name],
                    previous = prev[p.name]?.let(OrganState::ofWire),
                ),
            )
        }
        val rows = tools.map(HealthCheck::tool)
        val signals = HealthCheck.signals(SignalInput(toolRows = rows))
        val blockers = HealthCheck.blockers(organs, rows, SignalInput(toolRows = rows))
        val sheet = HealthCheck.sheet(
            nowMs,
            organs,
            rows,
            signals,
            blockers,
            readings = reading.sheetLines,
            // 抽检读数块：上次抽检时间＋假 PASS 数，一行一件（判据层的行文，自带部位名；编排层只排版）。
            sampling = SAMPLE_ORGANS
                .filter { sampling.containsKey(it) }
                .map { name -> HealthCheck.samplingLine(name, sampling.getValue(name)) },
        )

        val entry = entryOf(sheet, nowMs)
        val saved = HealthLedger.write(root, entry)
        val enqueued = enqueue(sheet, entry, nowMs)
        RunLog.log(
            "御医体检：${entry.headline}｜${if (manual) "手动" else "月位"}｜" +
                "${if (saved) "已落账" else "账没落上"}｜${if (enqueued) "已进队列" else "不进队列"}",
        )
        return HealthResult(entry, enqueued, if (saved) null else "体检账没落上")
    }

    /**
     * 部位产出数（403 修正：读数层不再走 SINK_FILES）。
     *
     * 病根：读数层的产出在**内存**里（`Metrics 四率，无落盘`），而 SINK_FILES 只收
     * "单一写者的账本文件"——它没份 ⇒ outputs=null ⇒ 判据层只能如实记"未取样"、
     * 态线落成空串。可读数明明每次体检都算出来了（348 接线，[MetricsWiring.read]
     * 就在本文件 run() 里）：**有产出而部位档没读**，就是这处断线。
     *
     * 修法：读数层直接吃这一趟读数的产出计数（算出了几行指标，见 [readingOutputs]）；
     * 其余部位照旧走 SINK_FILES（那一路的口径一字不动）。
     */
    private fun organOutputs(p: OrganProbe, root: File, reading: MetricsReading): Int? =
        if (p.name == OrganCensus.READING_ORGAN) {
            readingOutputs(reading)
        } else {
            SINK_FILES[p.name]?.let { HealthSources.outputLines(root, it) }
        }

    /**
     * 409：三个抽检件的**运行记录读数**——判据的两个维度从这里来：
     *
     * - **有没有跑**（[SampleFacts.runs]／[SampleFacts.lastRunMs]）：影子＝影子执行.jsonl 一行
     *   一次预演（零差异也是一行）；不良人＝暗卫记录.jsonl 一行一轮；金吾卫＝金吾卫报告.jsonl
     *   一行一份。**没跑 ＝ 未取样**（不是零产出）；
     * - **跑出了什么**（[SampleFacts.problems]）：影子＝差异数（MISMATCH/MISWIRED 条数）；
     *   暗卫／金吾卫＝抽到假 PASS 数（命中数）。跑了零差异／零假 PASS ＝ 正常。
     *
     * 影子的「该跑没跑」借 I6 的同一把判据（[ShadowCheck.check]：窗内该预演的动作 × 影子账）
     * ——巡按怎么算，体检就怎么算，不开第二套口径。
     *
     * 三本账的读口**读不动就回空表**（与各自 `read` 的既有口径一致）⇒ 按真没跑收；
     * 「测不出来」那一路留给判据层（sampling 不附 ⇒ 走旧的未取样路）。
     */
    private fun samplingFacts(root: File, db: BaopuDb, from: Long, nowMs: Long): Map<String, SampleFacts> {
        val facts = LinkedHashMap<String, SampleFacts>()
        val shadowRows = ShadowLedger.read(root)
        val shadowWin = shadowRows.filter { it.atMs >= from }
        // 「该跑没跑」用 [ShadowCheck.compare] 的结构化缺口（I6 的判据本体）；
        // `check()` 那个口回的是措辞级 finding，缺口数在这里拿不到。
        val demand = runCatching { ShadowCheck.compare(PatrolSources.shadowTraces(db, from), shadowRows) }.getOrNull()
        facts["影子"] = SampleFacts(
            kind = SampleKind.SHADOW,
            // 短路行（工具没接／没对象／没沙盒）不是一次预演——不进「有没有跑」，
            // 单独记进 unverifiable（不许拿"跑不出结论"冒充"跑了零差异"）。
            runs = shadowWin.count { it.verdict != ShadowVerdict.UNVERIFIABLE },
            lastRunMs = shadowRows.maxOfOrNull { it.atMs },
            problems = shadowWin.count { it.violated },
            unverifiable = shadowWin.count { it.verdict == ShadowVerdict.UNVERIFIABLE },
            shouldHaveRun = demand?.missing?.isNotEmpty() == true,
        )
        val nightRows = NocturnalLedger.read(root)
        val nightWin = nightRows.filter { it.atMs >= from }
        facts["不良人"] = SampleFacts(
            kind = SampleKind.SAMPLER,
            runs = nightWin.size,
            lastRunMs = nightRows.maxOfOrNull { it.atMs },
            problems = nightWin.sumOf { it.hits },
            // 周期件（夜巡按晚抽）：整窗没抽检即超期 ⇒ 未取样（判据：暗卫超期 ⇒ 未取样）。
            overdue = nightWin.isEmpty(),
            overdueDays = nightRows.maxOfOrNull { it.atMs }?.let { (nowMs - it) / HealthLimits.DAY_MS },
        )
        val guardRows = GuardLedger.read(root)
        val guardWin = guardRows.filter { it.atMs >= from }
        facts["金吾卫"] = SampleFacts(
            kind = SampleKind.SAMPLER,
            runs = guardWin.size,
            lastRunMs = guardRows.maxOfOrNull { it.atMs },
            // 假 PASS 数吃结构化那一格（409 起新行才带；老行没有 ⇒ 0，金吾卫报告无存量行）。
            problems = guardWin.sumOf { it.hits },
            // 用户点名件，没有周期承诺 ⇒ 不判超期，没巡就照实说「窗内没跑」。
            overdue = false,
            overdueDays = guardRows.maxOfOrNull { it.atMs }?.let { (nowMs - it) / HealthLimits.DAY_MS },
        )
        return facts
    }

    /**
     * 读数层的产出数（纯函数，用例直测；与直读同一个 [MetricsReading]，天然一致）。
     *
     * 算出来了（有数/现算）＝指标行数（13 项都算出了行，unknown 也是产出）；
     * 没跑起来（无数据）＝0——**真零产出，不是未取样**：接线在、活派了、这趟没算出来。
     */
    internal fun readingOutputs(reading: MetricsReading): Int =
        if (reading.state == ReadingState.NO_DATA) 0 else reading.lines.size

    /** 把单子压成一行账（计数＋各部位态＋命中键＋全文）。 */
    private fun entryOf(sheet: HealthSheet, nowMs: Long): HealthEntry {
        val counts = LinkedHashMap<String, Int>()
        OrganState.entries.forEach { counts[it.wire] = sheet.count(it) }
        counts[COUNT_UNMEASURED] = sheet.unmeasured()
        val organs = LinkedHashMap<String, String>()
        sheet.organs.forEach { organs[it.name] = it.state?.wire ?: "" }
        val hits = ArrayList<String>()
        sheet.signals.filter { it.outcome == ScanOutcome.HIT }.forEach { hits += "signal:${it.signal.wire}" }
        sheet.blockers.filter { it.outcome == ScanOutcome.HIT }.forEach { hits += "blocker:${it.blocker.wire}" }
        return HealthEntry(
            id = "health:$nowMs",
            atMs = nowMs,
            windowDays = HealthLimits.SPAN_DAYS,
            counts = counts,
            organs = organs,
            hits = hits,
            headline = headline(sheet),
            lines = sheet.lines,
        )
    }

    /** 一句话结论（≤8 字的那句给意见用，长的那句给日志与账本用）。 */
    fun headline(sheet: HealthSheet): String {
        val notWired = sheet.organs.count {
            it.state == OrganState.NOT_WIRED && it.disposition == Disposition.DEFECT
        }
        val unmeasured = sheet.unmeasured()
        return "体检：$notWired 处没接线、$unmeasured 处未取样"
    }

    /**
     * 递一条意见进延后队列（**不进预算、不弹卡**）。
     *
     * 只在"真有要人看的东西"时递：没接线的地方 >0，或任一卡点命中。
     * 全都干净就不递——每月一条"没事"本身就是打扰。
     */
    private fun enqueue(sheet: HealthSheet, entry: HealthEntry, nowMs: Long): Boolean {
        val notWired = sheet.organs.count {
            it.state == OrganState.NOT_WIRED && it.disposition == Disposition.DEFECT
        }
        val anyBlocker = sheet.blockers.any { it.outcome == ScanOutcome.HIT }
        if (notWired == 0 && !anyBlocker) return false
        val gist = if (notWired > 0) "体检${notWired}处未接" else "体检有卡点"
        val opinion = Opinion(
            source = Source.PATROL,
            severity = Severity.MINOR,
            gist = gist,
            action = ActionCode.REVIEW,
            lawRef = LawRef.L14,
            evidence = listOf("health:${entry.id}"),
        )
        return runCatching {
            OpinionWiring.enqueueOnly(opinion, tool = "", now = nowMs)
            true
        }.onFailure {
            RunLog.log("御医体检：意见没递上——${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    /**
     * 默认可见的工具（组级 + 工具级两条口径，与 `ToolRegistry` 的可见性判定同源）。
     *
     * 两条名单都走 `ToolGroup` 那两个口——它们内部读策略，但**代码住在 `ToolRegistry.kt`**。
     * 本文件不碰 `PolicyRuntime.current`：`PolicySourceGateTest` 钉着"政策只被那三个执行点读，
     * 多一处就是新权力"，体检不是执行点（实测：直接读会让那条门禁红）。
     */
    private fun visibleTools(): Set<String> {
        val groups = ToolGroup.DEFAULT_VISIBLE
        val byGroup = ToolGroupTable.GROUP_OF.filterValues { it in groups }.keys
        return byGroup + ToolGroup.DEFAULT_VISIBLE_TOOLS
    }

    private fun fail(why: String): HealthResult {
        RunLog.log("御医体检：这趟没跑成（$why）")
        return HealthResult(null, false, why)
    }

    /** 计数表里"未取样"那一格的键（不是第五态；四态只有四个）。 */
    const val COUNT_UNMEASURED: String = "unmeasured"
}
