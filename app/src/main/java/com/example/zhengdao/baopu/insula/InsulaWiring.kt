// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十一批 · 岛叶消费层的**生产接线**（判据一行不落在这里，判据在 BioTuning 纯层）。
//
// 三条纪律（照 676 `SubconsciousWiring` 的房型）：
// 1. **读数全走既有采集**（§二①）：电量/充电/发烫走 `AutoMaintenance`（479 唯一读数源），
//    内存走 `KeepaliveWatcher` 同口径（堆 used/max），存储走 `StatFs`（WishIOWiring 既定尺），
//    延迟走 `run_trace` 的 durationMs 列（既有轨迹，不新量），预算余量走 `AskDailyBudget`
//    （dayKey 用 `PatrolScheduler.dayBucket` 同一把日历尺）。本文件不摸系统服务第二遍。
// 2. **只调四参数**（§二③）：本文件对授权档/用户设置/判据阈值/工具白名单没有任何写路径
//    ——源码门禁 `InsulaSourceGateTest` 盯着全包，引用即红。
// 3. **崩了只记一行**（65/89 口径）：这一轮失败记 `lastFailAt`，重试间隔由四参数里的
//    重试间隔自己管（读数调出来的参数反过来管自己，闭环自洽）。
package com.example.zhengdao.baopu.insula

import android.content.Context
import android.os.StatFs
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.agent.AskDailyBudget
import com.example.zhengdao.baopu.bio.Insula
import com.example.zhengdao.baopu.biogate.BioAnalogGate
import com.example.zhengdao.baopu.biogate.BioIntent
import com.example.zhengdao.baopu.biogate.GateVerdict
import com.example.zhengdao.baopu.biogate.ThreeQuestionGate
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.baopu.maintenance.AutoMaintenance
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

/** 岛叶一轮状态文件（lastFailAt 一行；坏文件当从没失败过——重跑一轮比丢一轮便宜）。 */
internal const val INSULA_STATE_FILE = "岛叶-轮次.json"

/** p95 的样本窗（run_trace 最近这么多条 ACT 边界；轨迹是既有数据，只读）。 */
internal const val ACT_P95_SAMPLE = 200

/** 收工触发的那一行（`TriggerWiring` 调；调用方在 IO 上串行驱动）。 */
internal fun insulaOnRunSettled(ctx: Context, nowMs: Long): List<TuningMove>? {
    val root = DecisionLedger.installedRoot() ?: run {
        RunLog.log("岛叶：账根没装配，这轮不跑（不是没读数，是没地方落账）")
        return null
    }
    return runCatching { runInsulaRound(root, ctx, nowMs, ThreeQuestionGate) }
        .onFailure {
            RunLog.log("岛叶：这轮没跑成——${it.javaClass.simpleName}")
            noteRoundFailure(root, nowMs)
        }
        .getOrNull()
}

/**
 * 岛叶一轮：读数 → 调参判定 → 过门 → 落账 → 浮卡。
 * @return 真正生效的调节动作（null ＝ 这轮什么都没发生）
 */
internal fun runInsulaRound(root: File, ctx: Context, nowMs: Long, gate: BioAnalogGate): List<TuningMove>? {
    if (coolingDown(root, nowMs)) return null
    val vitals = gatherVitals(ctx, nowMs) ?: return null
    val basisLine = insulaBasisLine(vitals)
    val moves = insulaAdjust(readTuning(root), vitals)
    val applied = applyTuningMoves(root, moves, nowMs, gate)
    appendObserved(root, vitals, basisLine, nowMs)
    if (applied.isNotEmpty()) {
        val admitted = gate.admit(resetIntent())
        val card = InsulaTuningCard(
            id = InsulaTuningCard.idOf(nowMs),
            moves = applied,
            basis = basisLine,
            atMs = nowMs,
        )
        InsulaCardStore.publish(listOf(card))
        RunLog.log("岛叶：调了 ${applied.joinToString { "${it.param.cn} ${it.from}→${it.to}" }}（回默认钮${if (admitted == GateVerdict.Admit) "在" else "缺"}）")
        return applied
    }
    RunLog.log("岛叶：体征都在带内，不动参数")
    return emptyList()
}

/** 回默认的过门意图（卡上那枚钮能不能落，门说了算；白名单里有 RESET_TUNING 这一格）。 */
private fun resetIntent(): BioIntent = BioIntent(
    organ = ThreeQuestionGate.ORGAN_INSULA,
    action = ThreeQuestionGate.ACTION_RESET_TUNING,
    subject = "参数.json",
    observable = "一键回默认后 readTuning 读回出厂档且调节账多一行 reset",
)

/** 重试冷却：上一轮失败还没到重试间隔 ⇒ 这轮让开（参数自己管自己）。 */
internal fun coolingDown(root: File, nowMs: Long): Boolean {
    val lastFail = runCatching {
        val f = File(insulaDir(root), INSULA_STATE_FILE)
        if (!f.isFile) return false
        JSONObject(f.readText(Charsets.UTF_8)).optLong("lastFailAt", -1L)
    }.getOrDefault(-1L)
    if (lastFail <= 0) return false
    val backoff = readTuning(root).retryBackoffMs
    return nowMs - lastFail < backoff
}

/** 记一轮失败（重试冷却的输入；写失败只记日志）。 */
internal fun noteRoundFailure(root: File, nowMs: Long) {
    runCatching {
        val f = File(insulaDir(root), INSULA_STATE_FILE)
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("v", 1).put("lastFailAt", nowMs).toString(), Charsets.UTF_8)
    }.onFailure { RunLog.log("岛叶：轮次状态没落上——${it.javaClass.simpleName}") }
}

/** 体征装配（只喂既有采集；哪格读不出就是 null/-1，不编数——兜底＝读不到不动参数）。 */
internal fun gatherVitals(ctx: Context, nowMs: Long): BioVitals? {
    val workspace = runCatching { BaopuRuntime.workspaceRoot(ctx) }.getOrNull() ?: return null
    val batteryPct = AutoMaintenance.batteryPct(ctx)
    val storage = storageRatio(workspace)
    return BioVitals(
        batteryPct = batteryPct,
        charging = AutoMaintenance.charging(ctx),
        hot = AutoMaintenance.hot(ctx),
        heapPressure = heapPressure(),
        storageFreeRatio = storage?.let { (free, total) -> if (total > 0) free.toDouble() / total else null },
        actP95Ms = actP95Ms(BaopuRuntime.db(ctx)),
        budgetLeft = AskDailyBudget.LIMIT -
            AskDailyBudget.usedToday(PatrolScheduler.dayBucket(nowMs).toInt()),
    )
}

/** 岛叶快照一句话（消费 675 岛叶器官：同一路读数喂 [Insula.Sources]，拿它的只读快照当依据行）。 */
internal fun insulaBasisLine(vitals: BioVitals): String {
    val snapshot = Insula.snapshot(
        Insula.Sources(
            batteryPct = { vitals.batteryPct.takeIf { it >= 0 } },
            charging = { vitals.charging },
        ),
    )
    return "岛叶快照｜电量：${snapshot.battery}·本批调节只看四参数"
}

/** 存储余量/总量（StatFs：WishIOWiring 同一把尺；读不出 ⇒ null）。 */
internal fun storageRatio(workspace: File): Pair<Long, Long>? = runCatching {
    val stat = StatFs(workspace.absolutePath)
    stat.availableBytes to stat.totalBytes
}.getOrNull()

/** 堆内存压力（KeepaliveWatcher.memoryText 同口径：used/max；读不出 ⇒ null）。 */
internal fun heapPressure(): Double? = runCatching {
    val heap = Runtime.getRuntime()
    val max = heap.maxMemory()
    if (max <= 0) null else (heap.totalMemory() - heap.freeMemory()).toDouble() / max
}.getOrNull()

/** ACT 边界 p95 耗时（run_trace 既有列只读；没样本 ⇒ null）。 */
internal fun actP95Ms(db: com.example.zhengdao.baopu.BaopuDb): Long? = runCatching {
    val rows = mutableListOf<Long>()
    db.read { d ->
        d.rawQuery(
            "SELECT duration_ms FROM run_trace WHERE phase = 'ACT' AND duration_ms >= 0 " +
                "ORDER BY id DESC LIMIT $ACT_P95_SAMPLE",
            null,
        ).use { c ->
            while (c.moveToNext()) rows.add(c.getLong(0))
        }
    }
    if (rows.isEmpty()) null else rows.sorted()[(rows.size - 1) * 95 / 100]
}.getOrNull()
