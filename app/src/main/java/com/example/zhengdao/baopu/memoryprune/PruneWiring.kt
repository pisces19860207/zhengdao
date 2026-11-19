// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十二批 · 记忆修剪的**生产装配**（判据本体全在 [runPruneRound] 纯层）。
//
// 四条纪律（照 678 ReplayWiring 的房型）：
// 1. **对记忆条目只有两种写**：① [com.example.zhengdao.baopu.MemoryStore.update]
//    （标 `RETIRED`／写合并视图——id 不变、文件原地，物理文件一个不删一个不挪）；
//    ② 撤销的**原像写回**（[restoreOne]：把写前存的文件原文逐字节写回去——判据④
//    「回滚后索引与状态回到修剪前」的字节级实现）。除此之外零写口。
// 2. **写前原像**（判据④）：任何状态变更之前，先把要动的每条文件全文存进回滚档；
//    预读失败或落档失败 ⇒ 整轮中止、一条不动、账上一行中止——绝不半途改一半。
// 3. **崩了只记一行**：装配层任何失败不抛调用方（触发点在广播路径上，
//    65/89 口径——不许把别人的路径带下去）。
// 4. **零调度**：本文件不建线程/协程/定时器——触发线程由调用方给（灭屏在
//    ZhengdaoApp 的接收器线程上）；空闲/天闸判据全在纯层（沿用 676/678）。
package com.example.zhengdao.baopu.memoryprune

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.subconscious.IdleSnapshot
import com.example.zhengdao.baopu.subconscious.engineBusyNow
import com.example.zhengdao.baopu.subconscious.replayHourOfDay
import com.example.zhengdao.baopu.subconscious.screenOnNow
import com.example.zhengdao.core.RunLog
import java.io.File

/** 本轮的 id（账行/回滚档认领用；时刻派生，同轮内稳定）。 */
internal fun pruneRoundIdOf(nowMs: Long): String = "p$nowMs"

/** 一次装配的结果（[round] 是纯层产物；aborted ＝ 装配层中止，一条未动）。 */
internal data class PruneRoundResult(
    val round: PruneRound,
    val appliedMerges: Int,
    val appliedRetires: Int,
    val aborted: Boolean,
    val abortWhy: String,
)

/** 一次撤销的结果（判据④的读数；[restoredIds] 是逐条写回原像的条目）。 */
internal data class PruneUndoResult(
    val ok: Boolean,
    val message: String,
    val restoredIds: List<String>,
    val failedIds: List<String>,
)

/**
 * 跑一轮修剪装配（JVM 直测入口：[MemoryStore]/目录全注入，零 Android）。
 *
 * 次序（判据的次序，不是实现细节）：① 纯层判据（空闲窗口＋天闸＋白名单＋计划）；
 * ② 有动作才走 [applyPruneRound]：写前原像 → 原像落档 → 应用 → 索引随文件走 →
 * 账（总账「合并 N / 退役 M / 跳过 K」＋逐条行）＋状态（天闸桶＋prev＋撤销指针）。
 *
 * @param retireAfterDays 退役周期（可配；默认 [PruneLimits.RETIRE_AFTER_DAYS]）
 * @param indexSync 应用后的索引同步钩子（null ＝ 不同步——测试常用；生产给 SQLite upsert）
 */
internal fun runPruneRoundCore(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    nowMs: Long,
    hourOfDay: Int,
    screenOn: Boolean?,
    engineBusy: Boolean,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
    retireAfterDays: Long = PruneLimits.RETIRE_AFTER_DAYS,
): PruneRoundResult {
    val round = runPruneRound(
        entries = store.loadAll(),
        idle = IdleSnapshot(nowMs, hourOfDay, screenOn, engineBusy),
        lastRunDayKey = PruneDayGate.lastRunDayKey(workspace),
        roundId = pruneRoundIdOf(nowMs),
        retireAfterDays = retireAfterDays,
    )
    if (!round.ran) {
        RunLog.log("记忆修剪：这轮没跑（${round.note}）")
        return PruneRoundResult(round, 0, 0, aborted = false, abortWhy = "")
    }
    val prev = PruneDayGate.lastRunDayKey(workspace)
    val ledger = PruneLedger.fileOf(workspace)
    val hasActions = round.merges.isNotEmpty() || round.retires.isNotEmpty()
    if (!hasActions) {
        // 空轮（真跑了但没的可剪）：不建回滚档，总账＋状态照写（天闸口径：今天跑过）。
        PruneLedger.append(ledger, PruneLedger.summaryEvent(nowMs, round.roundId, round.note, round.skipDetail))
        PruneDayGate.writeAfterRound(
            workspace, round.dayKey, prev, round.roundId,
            rollbackName = "", merged = 0, retired = 0, skipped = round.skipped,
            note = round.note, skipDetail = round.skipDetail, nowMs = nowMs,
        )
        RunLog.log("记忆修剪：${round.note}")
        return PruneRoundResult(round, 0, 0, aborted = false, abortWhy = "")
    }
    return applyPruneRound(store, workspace, rollbackRoot, ledger, round, prev, nowMs, indexSync)
}

/**
 * 应用一轮修剪（判据④「写前原像 → 落档 → 应用」的次序在这里；中止＝零改动）。
 * 三段：[collectPreImages]（写前原像）→ [PruneRollback.write]（落档）→
 * [applyEntries]（逐条 [MemoryStore.update]，id 不变 ⇒ 文件原地重写）；
 * 账与状态走 [writeRoundBookkeeping]。索引同步只喂**真的应用上了**的条目。
 */
private fun applyPruneRound(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    ledger: File,
    round: PruneRound,
    prevDayKey: Long,
    nowMs: Long,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
): PruneRoundResult {
    val plan = collectPreImages(store, round)
    val items = plan.items
    if (items == null) {
        return abortRound(ledger, round, nowMs, plan.why)
    }
    val rollbackFile = PruneRollback.fileOf(rollbackRoot, round.roundId)
    if (!PruneRollback.write(rollbackFile, items)) {
        return abortRound(ledger, round, nowMs, "回滚档没写上（${rollbackFile.name}）——半份计划比没有更坏")
    }
    val (applied, failedIds) = applyEntries(store, ledger, round, nowMs)
    if (applied.isNotEmpty()) indexSync?.invoke(applied)
    val appliedMerges = round.merges.count { plan ->
        plan.survivor.id !in failedIds && plan.absorbed.none { it.id in failedIds }
    }
    val appliedRetires = round.retires.count { it.id !in failedIds }
    writeRoundBookkeeping(workspace, ledger, rollbackFile, round, prevDayKey, nowMs, appliedMerges, appliedRetires, failedIds)
    RunLog.log("记忆修剪：${round.note}（应用 合并 $appliedMerges／退役 $appliedRetires；失败 ${failedIds.size}）")
    return PruneRoundResult(round, appliedMerges, appliedRetires, aborted = false, abortWhy = "")
}

/** 写前原像的收集结果：[items] 为 null ＝ 收集失败（[why] 给中止账用）。 */
private data class PreImagePlan(val items: List<PruneRollback.Item>?, val why: String)

/**
 * 写前原像（判据④第 1 步）：要动的每条，先读出文件全文；缺一条/读不动一条 ⇒
 * [PreImagePlan.items] 为 null（调用方整轮中止、一条不动——半份计划比没有更坏）。
 */
private fun collectPreImages(store: MemoryStore, round: PruneRound): PreImagePlan {
    val targetIds = (round.merges.flatMap { it.allIds } + round.retires.map { it.id }).distinct()
    val items = mutableListOf<PruneRollback.Item>()
    for (id in targetIds) {
        val file = store.findFile(id)
        val text = file?.let { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
        if (file == null || text == null) {
            return PreImagePlan(null, "预读原像失败（id=$id 文件${if (file == null) "找不到" else "读不动"}）")
        }
        items += PruneRollback.Item(id = id, path = file.absolutePath, before = text)
    }
    return PreImagePlan(items, "")
}

/**
 * 逐条应用（判据②「只标状态不物理删」的全部写面：[MemoryStore.update]，id 不变 ⇒
 * 文件原地重写；返回 false 的逐条记失败账——文件原样，不静默）。
 * @return applied 真应用上的条目（索引同步吃这份）＋ failed 失败的 id
 */
private fun applyEntries(
    store: MemoryStore,
    ledger: File,
    round: PruneRound,
    nowMs: Long,
): Pair<List<MemoryEntry>, List<String>> {
    val applied = mutableListOf<MemoryEntry>()
    val failedIds = mutableListOf<String>()
    fun apply(e: MemoryEntry, kind: String) {
        if (store.update(e)) applied += e else {
            failedIds += e.id
            PruneLedger.append(ledger, PruneLedger.failedEvent(nowMs, e.id, "$kind 落盘没成（该条原样）"))
        }
    }
    round.merges.forEach { plan ->
        apply(plan.survivor, "合并主体")
        plan.absorbed.forEach { apply(it, "合并吸收") }
    }
    round.retires.forEach { apply(it.entry, "退役") }
    return applied to failedIds
}

/** 账与状态（判据⑥；counts 记实际应用的读数，不虚报；失败的条目不出合并/退役行）。 */
private fun writeRoundBookkeeping(
    workspace: File,
    ledger: File,
    rollbackFile: File,
    round: PruneRound,
    prevDayKey: Long,
    nowMs: Long,
    appliedMerges: Int,
    appliedRetires: Int,
    failedIds: List<String>,
) {
    PruneLedger.append(ledger, PruneLedger.summaryEvent(nowMs, round.roundId, round.note, round.skipDetail))
    round.merges.forEach { plan ->
        if (plan.survivor.id in failedIds) return@forEach
        PruneLedger.append(
            ledger,
            PruneLedger.mergedEvent(nowMs, plan.survivor.id, plan.absorbed.map { it.id }, plan.allIds),
        )
    }
    round.retires.forEach { mark ->
        if (mark.id in failedIds) return@forEach
        PruneLedger.append(ledger, PruneLedger.retiredEvent(nowMs, mark.id))
    }
    PruneDayGate.writeAfterRound(
        workspace, round.dayKey, prevDayKey, round.roundId,
        rollbackName = rollbackFile.name, merged = appliedMerges, retired = appliedRetires,
        skipped = round.skipped, note = round.note, skipDetail = round.skipDetail, nowMs = nowMs,
    )
}

/** 整轮中止（判据④的失败路径）：账一行中止、状态不写（天闸不耗）、一条未动。 */
private fun abortRound(ledger: File, round: PruneRound, nowMs: Long, why: String): PruneRoundResult {
    PruneLedger.append(ledger, PruneLedger.abortEvent(nowMs, round.roundId, why))
    RunLog.log("记忆修剪：${round.roundId} 中止：$why")
    return PruneRoundResult(
        round.copy(note = "中止：$why"), 0, 0, aborted = true, abortWhy = why,
    )
}

/**
 * 一键撤销最近一轮修剪（判据④）：回滚档原像逐条写回（字节级）＋索引随文件重建＋
 * 状态桶回 prev＋账面补一行撤销。失败逐条如实记（文件不在＝失败，不虚构恢复）。
 */
internal fun undoLatestPruneCore(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    nowMs: Long,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
): PruneUndoResult {
    val state = PruneDayGate.lastState(workspace)
    if (state == null || !state.undoable) {
        return PruneUndoResult(false, "没有可撤销的修剪（状态＝没跑过）", emptyList(), emptyList())
    }
    val items = PruneRollback.read(PruneRollback.fileOf(rollbackRoot, state.roundId))
    if (items == null) {
        val name = state.rollbackName.ifBlank { "缺失" }
        return PruneUndoResult(
            false,
            "回滚档读不回（$name）——不能装作恢复成功；条目仍是修剪后的状态",
            emptyList(),
            emptyList(),
        )
    }
    val restored = mutableListOf<String>()
    val failed = mutableListOf<String>()
    items.forEach { item ->
        val file = store.findFile(item.id)
        if (file == null || !restoreOne(file, item.before)) failed += item.id else restored += item.id
    }
    val rebuilt = store.loadAll().filter { it.id in restored.toSet() }
    if (restored.isNotEmpty()) indexSync?.invoke(rebuilt)
    val stateOk = PruneDayGate.restoreToPrev(workspace, nowMs)
    PruneLedger.append(
        PruneLedger.fileOf(workspace),
        PruneLedger.undoneEvent(nowMs, state.roundId, restored.size, failed.size),
    )
    val ok = failed.isEmpty() && stateOk
    val msg = if (ok) {
        "已撤销：${restored.size} 条回到修剪前（索引与状态一并回滚）"
    } else {
        buildString {
            append("撤销不完整：恢复 ${restored.size} 条，失败 ${failed.size} 条")
            if (!stateOk) append("；状态桶写回失败（要重试）")
            append("——失败的条目未动，账上有名字")
        }
    }
    RunLog.log("记忆修剪撤销：${state.roundId} ⇒ $msg")
    return PruneUndoResult(ok, msg, restored, failed)
}

/**
 * 原像写回（判据④的**唯一**直接记忆文件写口；[MemoryStore.update] 之外的例外，
 * 专用于撤销：update 会重渲染，做不到「字节级回到修剪前」——手改过的文件也要原样回去）。
 * 源码门禁钉死：本包 writeText 只许出现在 PruneStores（自身三样）与本函数。
 */
private fun restoreOne(file: File, before: String): Boolean = runCatching {
    file.writeText(before, Charsets.UTF_8)
    true
}.getOrDefault(false)

/** 生产索引同步（SQLite upsert；文件是真相，索引只是加速——0b 定的职责）。 */
private fun sqliteIndexSyncOf(ctx: Context): (List<MemoryEntry>) -> Unit = { entries ->
    val db = BaopuRuntime.db(ctx)
    entries.forEach { e ->
        db.write { d ->
            val stmt = BaopuSql.upsertMemory(e)
            d.execSQL(stmt.sql, stmt.args)
        }
    }
}

/** 灭屏触发的装配入口（ZhengdaoApp 的接收器线程调；刚灭屏 ⇒ screenOn = false）。 */
internal fun pruneOnScreenOff(ctx: Context) {
    runCatching {
        runPruneRoundCore(
            store = BaopuRuntime.memoryStore(ctx),
            workspace = BaopuRuntime.workspaceRoot(ctx),
            rollbackRoot = PruneRollback.dirOf(MemoryLayout.globalMemoryDir(ctx.filesDir)),
            nowMs = System.currentTimeMillis(),
            hourOfDay = replayHourOfDay(System.currentTimeMillis()),
            screenOn = false,
            engineBusy = engineBusyNow(),
            indexSync = sqliteIndexSyncOf(ctx),
        )
    }.onFailure { RunLog.log("记忆修剪灭屏触发没跑成：${it.javaClass.simpleName}") }
}

/** 一键撤销的 Android 壳（界面「撤销本次修剪」调；读数一句话给人看）。 */
internal fun undoLatestPrune(ctx: Context, nowMs: Long): String = runCatching {
    undoLatestPruneCore(
        store = BaopuRuntime.memoryStore(ctx),
        workspace = BaopuRuntime.workspaceRoot(ctx),
        rollbackRoot = PruneRollback.dirOf(MemoryLayout.globalMemoryDir(ctx.filesDir)),
        nowMs = nowMs,
        indexSync = sqliteIndexSyncOf(ctx),
    ).message
}.getOrElse { "撤销没跑成（${it.javaClass.simpleName}）——账与状态未动" }
