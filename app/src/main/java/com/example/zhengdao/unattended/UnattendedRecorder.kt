// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百八十八单 · 值守的**装配层**：状态（UnattendedStatus）×账（UnattendedLedger）×
// 通知（UnattendedNotifier）的落笔处。判定层（Circle/Env/Cadence/Report）全是纯函数，
// 谁来落账、谁来弹通知、谁来落盘——都收在这一口，别处不摸 IO。
//
// 两条纪律：
// 1. **每一笔都三件套**：改状态、记账、（该响的）响一声——三件同源，调用方没有机会只取一半
//    （照 QuietCardOutcome「裁决＋账行＋入队件」同源的老规矩）。
// 2. **报告先落盘再发通知**：通知说"报告好了"，点开必须有报告在——顺序反了就是"点了没东西"。
package com.example.zhengdao.unattended

import android.content.Context
import com.example.zhengdao.core.RunLog
import java.io.File

internal object UnattendedRecorder {

    private const val DIR = "unattended"

    /**
     * 开值守：状态＋账＋起拍（心跳从这一刻起算；通知不必响——开关就在值守页上，人刚按过）。
     *
     * 441 加法：[autoDisarmAtMs] 是"到点自动收"的时刻（一句话值守带时长时接线层算好传入；
     * null ＝ 不自动收）。判到期不在这里（归服务的节拍），这里只落值。
     */
    fun arm(ctx: Context, scope: String, nowMs: Long, autoDisarmAtMs: Long? = null) {
        UnattendedStatus.arm(scope, nowMs, autoDisarmAtMs)
        UnattendedStatus.beat(nowMs)
        UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.ARM, nowMs, scope.trim().ifEmpty { "范围未写" }))
    }

    /**
     * 收值守并出报告（落盘 → 记账 → 通知，顺序见文件头纪律 2）。
     *
     * 441 加法：[closeReasonCn] 是收场理由的人话（如"因用户回话而解除，值守了 X 分钟"）——
     * **先落账再出报告**：理由先进事件账（账上可查），再进报告头（回场第一眼可见）；
     * null ＝ 388 老口径，报告头逐字节不变（值守页手动收走的走 null）。
     */
    fun disarmAndReport(
        ctx: Context,
        nowMs: Long,
        queueLines: List<String>,
        closeReasonCn: String? = null,
    ): UnattendedReportDoc {
        val armedAt = UnattendedStatus.armedAtMs() ?: nowMs
        if (closeReasonCn != null) {
            UnattendedLedger.append(
                ctx,
                UnattendedEvent(UnattendedEventKind.DISARM, nowMs, closeReasonCn),
            )
        }
        UnattendedStatus.disarm()
        val events = UnattendedLedger.readSince(ctx, armedAt)
        val doc = UnattendedReport.build(
            UnattendedReportInput(
                armedAtMs = armedAt,
                closedAtMs = nowMs,
                events = events,
                queueLines = queueLines,
                closedReasonCn = closeReasonCn,
            ),
        )
        saveReport(ctx, doc, nowMs)
        UnattendedLedger.append(
            ctx,
            UnattendedEvent(UnattendedEventKind.REPORT, nowMs, closeReasonCn ?: "收值守出报告"),
        )
        UnattendedNotifier.reportReady(ctx)
        return doc
    }

    /** 期中报告（值守中手动要一份；不收值守、closedAt 为空）。 */
    fun midtermReport(ctx: Context, nowMs: Long, queueLines: List<String>): UnattendedReportDoc {
        val armedAt = UnattendedStatus.armedAtMs() ?: nowMs
        val doc = UnattendedReport.build(
            UnattendedReportInput(armedAtMs = armedAt, closedAtMs = null, events = UnattendedLedger.readSince(ctx, armedAt), queueLines = queueLines),
        )
        saveReport(ctx, doc, nowMs)
        UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.REPORT, nowMs, "期中报告"))
        UnattendedNotifier.reportReady(ctx)
        return doc
    }

    /** 报告落盘（filesDir/unattended/离场报告-<时刻>.md；返回文件，供值守页直读）。 */
    fun saveReport(ctx: Context, doc: UnattendedReportDoc, nowMs: Long): File? = runCatching {
        val dir = File(ctx.filesDir, DIR).apply { mkdirs() }
        File(dir, "离场报告-${nowMs / 1000}.md").apply { writeText(doc.text()) }
    }.getOrNull()

    /** 最近一份报告的全文（值守页显示；读盘归调用方线程，本层不挑线程）。 */
    fun latestReport(ctx: Context): String? = runCatching {
        val dir = File(ctx.filesDir, DIR)
        dir.listFiles { f -> f.name.startsWith("离场报告-") }
            ?.maxByOrNull { it.name }
            ?.readText()
    }.getOrNull()

    /** 引擎弹卡闸压下卡的那一刻（388 接线：离场＋锁屏 ⇒ 进队列＋高优先一声）。 */
    fun onDefer(ctx: Context, headline: String, nowMs: Long) {
        UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.QUEUE, nowMs, headline))
        UnattendedNotifier.queued(ctx, headline)
    }

    /** 一次圈裁决的落笔（判定层唯一的生产消费口；391 接线批的工具缝也从这里走）。 */
    fun ruling(ctx: Context, ruling: UnattendedRuling, headline: String, nowMs: Long) {
        when (ruling.verdict) {
            UnattendedVerdict.QUEUE -> onDefer(ctx, headline, nowMs)
            UnattendedVerdict.STOP -> {
                UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.STOP, nowMs, "${ruling.line}｜${headline}"))
                UnattendedNotifier.stopFired(ctx, headline)
            }
            UnattendedVerdict.AUTO -> Unit // 自动执行不是要响的事，账都不记（执行账归动作账本）
        }
    }

    /** 环境一拍转移：进/退的格各自记一笔＋普通通知（388：暂停＋普通通知）。 */
    fun pauseTransition(ctx: Context, prev: Set<UnattendedEnvReason>, next: Set<UnattendedEnvReason>, nowMs: Long) {
        if (prev == next) return
        UnattendedStatus.setPausedReasons(next)
        (next - prev).forEach { reason ->
            UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.PAUSE, nowMs, reason.cn))
        }
        (prev - next).forEach { reason ->
            UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.RESUME, nowMs, reason.cn))
        }
        val entered = next - prev
        if (entered.isEmpty()) {
            if ((prev - next).isNotEmpty()) UnattendedNotifier.envResumed(ctx)
        } else {
            UnattendedNotifier.envPaused(ctx, entered.joinToString("、") { it.cn })
        }
    }

    /** 降级停手（B级汇总 2h 没出）：单行道，复工＝收值守再开。 */
    fun degrade(ctx: Context, nowMs: Long) {
        if (UnattendedStatus.degraded()) return
        UnattendedStatus.setDegraded(true)
        UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.DEGRADE, nowMs, "B级汇总 2h 没出，自动权收回（排队的事不丢，等她回场）"))
        UnattendedNotifier.degraded(ctx)
    }

    /** B 级汇总出账：从事件账数一笔（每小时一条；最后汇总时刻随笔画下）。 */
    fun summary(ctx: Context, armedAtMs: Long, lastSummaryAtMs: Long?, nowMs: Long) {
        val since = maxOf(armedAtMs, lastSummaryAtMs ?: 0L)
        val rows = UnattendedLedger.readSince(ctx, since)
        val queued = rows.count { it.kind == UnattendedEventKind.QUEUE }
        val stopped = rows.count { it.kind == UnattendedEventKind.STOP }
        val paused = rows.count { it.kind == UnattendedEventKind.PAUSE }
        val text = "B级汇总｜此段事件 ${rows.size} 条（入队 $queued · 停手 $stopped · 暂停 $paused）"
        UnattendedStatus.setLastSummaryAtMs(nowMs)
        UnattendedLedger.append(ctx, UnattendedEvent(UnattendedEventKind.SUMMARY, nowMs, text))
        RunLog.log("离场值守：$text")
    }

    /** 中断补报（看门狗发现服务断了/进程死了 ⇒ 记一笔；报告里逐条列）。 */
    fun interrupt(ctx: Context, nowMs: Long, gapMs: Long) {
        UnattendedLedger.append(
            ctx,
            UnattendedEvent(UnattendedEventKind.INTERRUPT, nowMs, "服务中断，断档 ${UnattendedReport.formatSpan(gapMs)}（本笔为复工补报）"),
        )
        RunLog.log("离场值守：中断补报（断档 ${gapMs / 60_000L} 分钟）")
    }
}
