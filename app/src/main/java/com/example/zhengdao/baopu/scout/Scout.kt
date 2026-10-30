// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候的**装配层**（Context 到这一层为止）：何时探、探完怎么写账、怎么出两样东西。
//
// 派工单三条：
//   ① 何时探：首启、"再探一遍"、权限变更、App 或系统版本变化；
//   ② **首启 ≤2 秒、后台跑、不弹窗**——所以 [install] 只起一个 IO 协程，不碰主线程、不申请权限；
//   ③ 产出两样：`Opinion(source=SCOUT)` 给四器（[Report.opinions]）＋ 一页能力清单（[Report.sheet]，
//      落 `<工作区>/记忆/能力清单.md` 一份镜像 ＋ App 私有一份，供首条消息回灌）。
//
// **权限变更怎么发现**：不注册广播（那要为一条探测加一个 exported=false 的 receiver，还得赌厂商
// 不掐）。改成**每次回前台重探一遍**——11 项全是本地读数、没有一次用户内容读取，代价是一次
// 毫秒级比对；事实没变就一行都不写（`sameFact`），所以账本不会被前台切换刷成一堵墙。
//
// 本文件**不做文件 I/O、不拼 JSON**：落盘全在 [ScoutLedger]/[UnfinishedLedger]，
// 判态全在 [ScoutRules]，读数全在 [AndroidScoutProbes]。这里是唯一需要 Context 的地方。
package com.example.zhengdao.baopu.scout

import android.content.Context
import android.os.SystemClock
import com.example.zhengdao.AppForeground
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.core.RunLog
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal object Scout {

    private val installed = AtomicBoolean(false)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /** 同一时刻只允许一趟探测（首启与回前台边沿会撞车；撞了排队而不是并行写账）。 */
    private val gate = Mutex()

    @Volatile
    private var lastReport: Report? = null

    /**
     * 一次探测的完整结果（**给后一批接线留的把手**：四器取 [opinions]，首条消息回灌取 [sheet]，
     * 起居注取 [records] 的"没开/做不了"那几项）。
     */
    internal data class Report(
        val trigger: ScoutTrigger,
        /** 这一趟探测的全部原因（首启可能同时命中版本类触发）。 */
        val reasons: List<ScoutTrigger>,
        val atMs: Long,
        /** 本次探测耗时（`elapsedRealtime`，毫秒）。 */
        val elapsedMs: Long,
        /** 是否在 [ScoutLimits.MAX_PROBE_MS] 预算内（判据①；超时不是失败，账照落）。 */
        val withinBudget: Boolean,
        val device: ScoutDevice,
        /** 探测后的当前态（每项一条，含刚记过"已提一次"的那几项）。 */
        val records: List<ScoutRecord>,
        /** 给四器的意见（[ScoutOpinions.forVessels]；granted 不产意见）。 */
        val opinions: List<Opinion>,
        /** 这次**事实有变化**、真正写进账的能力（没变的项不重写，账本才读得下去）。 */
        val changed: List<Capability>,
        val sheet: ScoutSheet.Sheet,
        /** 一页清单是否落盘成功（权威那份写成了就算；落不了不挡探测，但要能在报告里看见）。 */
        val sheetSaved: Boolean,
        /** 账本落点的绝对路径（权威那份在前，工作区镜像在后）。 */
        val ledgerPaths: List<String>,
        /** 共享存储没授权 ⇒ 工作区那本镜像落不下去（**重装会丢**，只剩 App 私有的权威本）——
         *  如实暴露，不许让报告写成"重装不丢"。 */
        val privateFallback: Boolean,
    )

    /**
     * 进程级安装（**唯一入口**，`ZhengdaoApp.onCreate` 调用）。
     *
     * 幂等：重复调用只记一次日志。不做任何主线程 I/O：首探丢 IO 协程。
     */
    fun install(context: Context) {
        if (!installed.compareAndSet(false, true)) return
        val ctx = context.applicationContext
        // 回前台 = 用户刚可能动过系统设置（开/关权限、拔摄像头……）⇒ 重探；事实没变不写账。
        AppForeground.addListener { front ->
            if (front) scope.launch { probe(ctx, ScoutTrigger.PERMISSION_CHANGED) }
        }
        scope.launch { probe(ctx, ScoutTrigger.FIRST_RUN) }
        RunLog.log("斥候：已安装（首启后台探测，预算 ${ScoutLimits.MAX_PROBE_MS}ms；不弹窗、不申请权限）")
    }

    /** 最近一次探测结果（null ＝ 还没探过/被跳过）。 */
    fun lastReport(): Report? = lastReport

    /** 「再探一遍」（设置页/决策中心的按钮后一批接；现在由 [install] 与回前台边沿驱动）。 */
    fun refresh(context: Context): Job = scope.launch {
        probe(context.applicationContext, ScoutTrigger.MANUAL)
    }

    /** 一页能力清单（首条消息回灌口；落盘那份的原文，可能为 null ＝ 还没探过）。 */
    fun sheetText(context: Context): String? = runCatching {
        ledgerFor(context).readSheet()
    }.onFailure {
        RunLog.log("斥候：读能力清单失败 ${it.javaClass.simpleName}: ${it.message}")
    }.getOrNull()

    /**
     * 记一次**用户驳回**（驳回记忆：权限名＋次数＋最近时间＋当时想做的事）。
     *
     * 同时写「未办成单」一行（原因＝被驳回，引用 [cardRef]，**可点回原卡改判**）。
     * 次数是**账本事实**：不随进程/会话清零，所以"3 次后不再主动提"才挡得住反复弹。
     *
     * 未接线：调用点归 UI 批次（弹卡组件），本批只把账与入口备好。
     *
     * @param cardRef 那张卡的引用，形如 `ask:<卡 id>`（[UnfinishedItem.reasonRef] 的口径）
     */
    fun recordDenial(context: Context, capability: Capability, intent: String, cardRef: String): Boolean {
        if (intent.isBlank() || !cardRef.contains(':')) {
            RunLog.log("斥候：驳回记账参数不全（intent/cardRef），已跳过")
            return false
        }
        val ctx = context.applicationContext
        return runCatching {
            val atMs = System.currentTimeMillis()
            val device = AndroidScoutProbes.device(ctx)
            val ledger = ledgerFor(ctx)
            val prior = ledger.load().records[capability]
                ?: ScoutRecord(capability, ScoutState.DENIED, lastAtMs = atMs, device = device.tag())
            val denied = ScoutRules.withDenial(prior, intent, atMs)
            ledger.append(denied)
            UnfinishedRules.fromRejected(denied, intent.trim(), atMs, cardRef).let { item ->
                UnfinishedLedger(ScoutDirs.ledgerDirs(ctx)).record(item)
            }
            true
        }.onFailure {
            RunLog.log("斥候：驳回记账失败 ${it.javaClass.simpleName}: ${it.message}")
        }.getOrDefault(false)
    }

    // ── 探测流水线 ────────────────────────────────────────────────────────────

    private suspend fun probe(ctx: Context, trigger: ScoutTrigger): Report? = gate.withLock {
        withContext(Dispatchers.IO) { runProbe(ctx, trigger) }
    }

    private fun runProbe(ctx: Context, trigger: ScoutTrigger): Report? {
        val startedAt = SystemClock.elapsedRealtime()
        val atMs = System.currentTimeMillis()
        val device = AndroidScoutProbes.device(ctx)
        val ledger = ledgerFor(ctx)
        val loaded = ledger.load()

        // 普通启动（非首启/非手动/非权限变更）只有在"App 版本或系统版本变了"时才重探。
        if (!ScoutFreshness.shouldProbe(trigger, loaded.device, device)) {
            RunLog.log("斥候：${trigger.wire} 跳过（版本与系统都没变，读账即当前态）")
            return null
        }

        val c = collect(ctx, trigger, ledger, loaded, device, atMs)

        val elapsed = SystemClock.elapsedRealtime() - startedAt
        val withinBudget = ScoutFreshness.withinBudget(elapsed)
        val report = Report(
            trigger = trigger,
            reasons = c.reasons,
            atMs = atMs,
            elapsedMs = elapsed,
            withinBudget = withinBudget,
            device = device,
            records = c.records,
            opinions = ScoutOpinions.forVessels(c.records),
            changed = c.changed.map { it.capability },
            sheet = c.sheet,
            sheetSaved = c.sheetSaved,
            ledgerPaths = ledger.paths(),
            privateFallback = BaopuWorkspace.isPrivate(ctx),
        )
        lastReport = report

        RunLog.log(
            "斥候：${c.reasons.joinToString("+") { it.wire }} 探 ${Capability.entries.size} 项，用时 ${elapsed}ms" +
                (if (withinBudget) "" else "（超 ${ScoutLimits.MAX_PROBE_MS}ms 预算）") +
                "，变化 ${c.changed.size} 项，清单 ${c.sheet.totalLines} 行，意见 ${report.opinions.size} 条" +
                (if (c.sheetSaved) "" else "（清单落盘失败）"),
        )
        return report
    }


    /**
     * 一趟探测的**采集半段**：算出理由、逐项定态、落账、出清单。
     *
     * 为什么单独一个函数（2026-10-30，detekt 硬墙红了 2 条）：`runProbe` 到 64 行、
     * 混了「决策（要不要探）」「采集（探什么、记什么）」「汇报（Report ＋ 日志）」三件事。
     * 拆出来的是**职责**，不是行数——采集本身要读写账本、要出清单，是完整的一步。
     */
    private data class Collected(
        val reasons: List<ScoutTrigger>,
        val records: List<ScoutRecord>,
        val changed: List<ScoutRecord>,
        val sheet: ScoutSheet.Sheet,
        val sheetSaved: Boolean,
    )

    private fun collect(
        ctx: Context,
        trigger: ScoutTrigger,
        ledger: ScoutLedger,
        loaded: ScoutLedger.Loaded,
        device: ScoutDevice,
        atMs: Long,
    ): Collected {
        // 这一趟到底**因为什么**探的：首启可能同时是"装了新包"或"升了系统"，
        // 日志与报告都要说得出原因，不然"为什么又探了"只能靠猜。
        val reasons = if (trigger == ScoutTrigger.FIRST_RUN) {
            ScoutFreshness.autoTriggers(loaded.device, device)
        } else {
            listOf(trigger)
        }

        val signals = AndroidScoutProbes.probeAll(ctx)
        val records = Capability.entries.map { cap ->
            val fresh = ScoutRules.resolve(
                capability = cap,
                signal = signals.getValue(cap),
                prior = loaded.records[cap],
                atMs = atMs,
                device = device.tag(),
            )
            // 事实没变就沿用旧快照：时间戳不空转，账本也不会被每次回前台刷成 11 行噪声。
            if (ScoutRules.sameFact(loaded.records[cap], fresh)) loaded.records.getValue(cap) else fresh
        }

        val changed = records.filter { it != loaded.records[it.capability] }
        changed.forEach { ledger.append(it) }
        if (loaded.device != device) ledger.appendDevice(device, atMs)

        val sheet = ScoutSheet.compose(records, atMs)
        val sheetSaved = ledger.saveSheet(sheet)

        // 「提一次」的额度在这里用掉：清单落页就是"提过的那一次"（人和模型读的是同一页）。
        val mentioned = sheet.revokedToMention.map { cap ->
            ScoutRules.withMention(records.first { it.capability == cap }, atMs)
        }
        mentioned.forEach { ledger.append(it) }
        val finalRecords = records.map { r ->
            mentioned.firstOrNull { it.capability == r.capability } ?: r
        }
        return Collected(reasons, finalRecords, changed, sheet, sheetSaved)
    }

    private fun ledgerFor(ctx: Context) = ScoutLedger(ScoutDirs.ledgerDirs(ctx))
}
