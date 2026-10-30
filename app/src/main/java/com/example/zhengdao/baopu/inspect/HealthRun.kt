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
     */
    private val SINK_FILES: Map<String, String> = mapOf(
        "勘验" to PatrolLedger.FILE_NAME,
        "影子" to ShadowLedger.FILE_NAME,
        "不良人" to NocturnalLedger.FILE_NAME,
        "金吾卫" to GuardLedger.FILE_NAME,
    )

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
        val tools = HealthSources.probes(
            stats = stats,
            visible = visibleTools(),
            groupOf = ToolGroupTable.GROUP_OF.mapValues { it.value.key },
            allTools = BaopuToolCatalog.ENTRIES.map { it.name },
        )
        val prev = HealthLedger.previousStates(root)
        val organs = OrganCensus.PROBES.map { p ->
            HealthCheck.organ(
                p.copy(
                    outputs = SINK_FILES[p.name]?.let { HealthSources.outputLines(root, it) },
                    previous = prev[p.name]?.let(OrganState::ofWire),
                ),
            )
        }
        val rows = tools.map(HealthCheck::tool)
        val signals = HealthCheck.signals(SignalInput(toolRows = rows))
        val blockers = HealthCheck.blockers(organs, rows, SignalInput(toolRows = rows))
        val sheet = HealthCheck.sheet(nowMs, organs, rows, signals, blockers)

        val entry = entryOf(sheet, nowMs)
        val saved = HealthLedger.write(root, entry)
        val enqueued = enqueue(sheet, entry, nowMs)
        RunLog.log(
            "御医体检：${entry.headline}｜${if (manual) "手动" else "月位"}｜" +
                "${if (saved) "已落账" else "账没落上"}｜${if (enqueued) "已进队列" else "不进队列"}",
        )
        return HealthResult(entry, enqueued, if (saved) null else "体检账没落上")
    }

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
