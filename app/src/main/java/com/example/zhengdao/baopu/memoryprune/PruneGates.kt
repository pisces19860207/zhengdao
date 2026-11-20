// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百五十八批 · 修剪界面的**门槛与动作装配**（757 图纸 §2.1.4/§2.2 施工）：
//   · 接收器前置门（总开关）＋装配层频率门（周期 off/daily/weekly）——757 §三映射表
//     行 1/2 的生效层。频率门**不是定时器**：复用 752 同一把天序号尺
//     （[PatrolScheduler.dayBucket]＋[PruneDayGate.lastRunDayKey]），比较「已跑天数」；
//     零调度构造（PruneSourceGateTest 原样复跑扫全包）。
//   · 永久保留豁免清单（757 §2.2：`产物/记忆修剪-保留.json`，只加豁免 id——
//     不碰 PRUNE_ELIGIBLE_KINDS，不动物理文件）；装配层喂 [runPruneRound] 前按它剔除 id。
//   · 「恢复／永久保留」两动作的装配核（JVM 直测口）与 Android 壳。
//
// 与 752 PruneWiring 的边界（757 §五.1：Wiring 本体一字不动）：[runPruneRoundCore] 与
// [undoLatestPruneCore] 原样保留；本件按**同一判据次序**重述装配（写前原像 → 落档 →
// 应用 → 索引随文件走 → 账＋状态），账/回滚/状态全部复用 752 的 PruneLedger/
// PruneRollback/PruneDayGate——撤销口 [undoLatestPruneCore] 对本件跑的轮次照常可撤销。
//
// 恢复的已知边界（757 §六 原话）：只按**当前状态文件的 roundId** 找回滚档；条目来自
// 更早轮次而 roundId 已翻篇 ⇒ 如实回「本轮回滚档无此条，无法恢复」，不许装成功。
package com.example.zhengdao.baopu.memoryprune

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryLayout
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.inspect.PatrolScheduler
import com.example.zhengdao.baopu.kernel.BaopuKernel
import com.example.zhengdao.baopu.subconscious.IdleSnapshot
import com.example.zhengdao.baopu.subconscious.engineBusyNow
import com.example.zhengdao.baopu.subconscious.replayHourOfDay
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

/**
 * 装配层频率门（757 §2.1.4 判据本体；纯函数，JVM 直测）：
 *   · `off` ⇒ 恒不满足（同总开关一样前置拦住）；
 *   · 从没跑过（[PruneDayGate.NEVER]）⇒ 满足；
 *   · daily ⇒ `dayKey - last >= 1`；weekly ⇒ `>= 7`；
 *   · 时钟回拨（差值为负）⇒ 判不满足——不跑、不写账、不耗天闸。
 * 满足后照旧调 [runPruneRound]，其内部「同一天 ≤1 次」判据保留（叠加，不替代）。
 */
internal fun pruneScheduleDue(schedule: String, nowMs: Long, lastRunDayKey: Long): Boolean {
    if (schedule == PruneSettings.SCHEDULE_OFF) return false
    if (lastRunDayKey == PruneDayGate.NEVER) return true
    val gap = PatrolScheduler.dayBucket(nowMs) - lastRunDayKey
    if (gap < 0) return false
    val required = if (schedule == PruneSettings.SCHEDULE_DAILY) 1L else 7L
    return gap >= required
}

/**
 * 永久保留豁免清单（757 §2.2；`<工作区>/产物/记忆修剪-保留.json`，id 数组可覆盖写）。
 * 自身产出只写自己的这一样（产物目录内、BaopuKernel.Data 单一真相源派生）。
 */
internal object PruneKeepList {

    const val FILE_NAME = "记忆修剪-保留.json"

    fun fileOf(workspace: File): File = File(File(workspace, BaopuKernel.Data.DIR_PRODUCTS), FILE_NAME)

    /** 读豁免 id（坏档/不存在 ⇒ 空集——少豁免只是多修剪一轮，账上看得见）。 */
    fun ids(workspace: File): Set<String> = runCatching {
        val f = fileOf(workspace)
        if (!f.isFile) return emptySet()
        val arr = JSONObject(f.readText(Charsets.UTF_8)).optJSONArray("keep") ?: return emptySet()
        (0 until arr.length()).mapNotNull { i -> arr.optString(i).takeIf { it.isNotBlank() } }.toSet()
    }.getOrDefault(emptySet())

    /** 追加一个豁免 id（幂等；数组整体覆盖写；写失败返回 false 要说）。 */
    fun add(workspace: File, id: String): Boolean = runCatching {
        val arr = JSONArray()
        (ids(workspace) + id).sorted().forEach { arr.put(it) }
        val f = fileOf(workspace)
        f.parentFile?.mkdirs()
        f.writeText(JSONObject().put("v", 1).put("keep", arr).toString(), Charsets.UTF_8)
        true
    }.getOrDefault(false)
}

/** 一次带门的装配结果：[blocked] ⇒ 门槛拦下（result 为 null，零写零账零耗天闸）。 */
internal data class GatedPruneOutcome(
    val blocked: Boolean,
    val why: String,
    val result: PruneRoundResult?,
)

/**
 * 带门的装配核（JVM 直测入口；[enabled]/[schedule]/[retireAfterDays] 由 [PruneSettings]
 * 读出后注入，[keepIds] 由 [PruneKeepList.ids] 读出后注入）：
 *   ① 总开关前置门（757 §三 行1：false ⇒ 直接 return，零写零账零耗天闸）；
 *   ② 周期＝off 同拦；③ 频率门（[pruneScheduleDue]）；
 *   ④ 豁免剔除（757 §2.2：喂 [runPruneRound] 前按保留清单剔除 id）；
 *   ⑤ 752 同序装配（写前原像 → 落档 → 应用 → 账＋状态，[gatedApply]）。
 */
internal fun runGatedPruneRoundCore(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    nowMs: Long,
    hourOfDay: Int,
    screenOn: Boolean?,
    engineBusy: Boolean,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
    enabled: Boolean,
    schedule: String,
    retireAfterDays: Long,
    keepIds: Set<String>,
): GatedPruneOutcome {
    if (!enabled) return GatedPruneOutcome(true, "总开关已关", null)
    if (schedule == PruneSettings.SCHEDULE_OFF) return GatedPruneOutcome(true, "周期＝off", null)
    val lastRunDayKey = PruneDayGate.lastRunDayKey(workspace)
    if (!pruneScheduleDue(schedule, nowMs, lastRunDayKey)) {
        return GatedPruneOutcome(true, "频率门没到（schedule=$schedule）", null)
    }
    val entries = store.loadAll().filter { it.id !in keepIds }
    val result = gatedApply(
        store, workspace, rollbackRoot, nowMs, hourOfDay, screenOn, engineBusy, indexSync,
        entries = entries, lastRunDayKey = lastRunDayKey, retireAfterDays = retireAfterDays,
    )
    return GatedPruneOutcome(false, "", result)
}

/** 752 装配同序（判据的次序，不是实现细节）：纯层判据 → 有动作才走写前原像 → 应用 → 账＋状态。 */
private fun gatedApply(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    nowMs: Long,
    hourOfDay: Int,
    screenOn: Boolean?,
    engineBusy: Boolean,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
    entries: List<MemoryEntry>,
    lastRunDayKey: Long,
    retireAfterDays: Long,
): PruneRoundResult {
    val round = runPruneRound(
        entries = entries,
        idle = IdleSnapshot(nowMs, hourOfDay, screenOn, engineBusy),
        lastRunDayKey = lastRunDayKey,
        roundId = pruneRoundIdOf(nowMs),
        retireAfterDays = retireAfterDays,
    )
    if (!round.ran) {
        RunLog.log("记忆修剪：这轮没跑（${round.note}）")
        return PruneRoundResult(round, 0, 0, aborted = false, abortWhy = "")
    }
    val ledger = PruneLedger.fileOf(workspace)
    if (round.merges.isEmpty() && round.retires.isEmpty()) {
        // 空轮（真跑了但没的可剪）：总账＋状态照写（天闸口径：今天跑过）。
        PruneLedger.append(ledger, PruneLedger.summaryEvent(nowMs, round.roundId, round.note, round.skipDetail))
        PruneDayGate.writeAfterRound(
            workspace, round.dayKey, lastRunDayKey, round.roundId,
            rollbackName = "", merged = 0, retired = 0, skipped = round.skipped,
            note = round.note, skipDetail = round.skipDetail, nowMs = nowMs,
        )
        RunLog.log("记忆修剪：${round.note}")
        return PruneRoundResult(round, 0, 0, aborted = false, abortWhy = "")
    }
    return gatedApplyEntries(store, workspace, rollbackRoot, ledger, round, lastRunDayKey, nowMs, indexSync)
}

/** 写前原像 → 落档 → 逐条应用 → 索引随文件走 → 账＋状态（752 applyPruneRound 同判据）。 */
private fun gatedApplyEntries(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    ledger: File,
    round: PruneRound,
    prevDayKey: Long,
    nowMs: Long,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
): PruneRoundResult {
    val items = collectPreImages(store, round)
    if (items == null) return abortGatedRound(ledger, round, nowMs, "预读原像失败——整轮中止一条未动")
    val rollbackFile = PruneRollback.fileOf(rollbackRoot, round.roundId)
    if (!PruneRollback.write(rollbackFile, items)) {
        return abortGatedRound(ledger, round, nowMs, "回滚档没写上（${rollbackFile.name}）——半份计划比没有更坏")
    }
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
    if (applied.isNotEmpty()) indexSync?.invoke(applied)
    val appliedMerges = round.merges.count { plan ->
        plan.survivor.id !in failedIds && plan.absorbed.none { it.id in failedIds }
    }
    val appliedRetires = round.retires.count { it.id !in failedIds }
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
    RunLog.log("记忆修剪：${round.note}（应用 合并 $appliedMerges／退役 $appliedRetires；失败 ${failedIds.size}）")
    return PruneRoundResult(round, appliedMerges, appliedRetires, aborted = false, abortWhy = "")
}

/**
 * 写前原像（判据④地基的复用）：要动的每条先读文件全文；缺一条/读不动一条 ⇒ null
 * （调用方整轮中止、一条不动——半份计划比没有更坏）。
 */
private fun collectPreImages(store: MemoryStore, round: PruneRound): List<PruneRollback.Item>? {
    val targetIds = (round.merges.flatMap { it.allIds } + round.retires.map { it.id }).distinct()
    val items = mutableListOf<PruneRollback.Item>()
    for (id in targetIds) {
        val file = store.findFile(id)
        val text = file?.let { f -> runCatching { f.readText(Charsets.UTF_8) }.getOrNull() }
        if (file == null || text == null) return null
        items += PruneRollback.Item(id = id, path = file.absolutePath, before = text)
    }
    return items
}

/** 整轮中止（判据④的失败路径）：账一行中止、状态不写（天闸不耗）、一条未动。 */
private fun abortGatedRound(ledger: File, round: PruneRound, nowMs: Long, why: String): PruneRoundResult {
    PruneLedger.append(ledger, PruneLedger.abortEvent(nowMs, round.roundId, why))
    RunLog.log("记忆修剪：${round.roundId} 中止：$why")
    return PruneRoundResult(round.copy(note = "中止：$why"), 0, 0, aborted = true, abortWhy = why)
}

/** 一次恢复/保留动作的读数（[restoredId] 成功才有；[message] 一句话给人看）。 */
internal data class PruneRestoreResult(val ok: Boolean, val message: String, val restoredId: String?)

/**
 * 「恢复」装配核（757 §2.2 三动作之一；JVM 直测口）：按**当前状态文件的 roundId** 找
 * 回滚档，取该 id 的写前原像**逐字节写回**＋索引随文件同步。写回失败如实报
 * 「恢复失败」，不许装成功；roundId 已翻篇或档里无此条 ⇒ 757 §六 的边界文案。
 */
internal fun restoreRetiredCore(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    id: String,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
): PruneRestoreResult {
    val state = PruneDayGate.lastState(workspace)
    val items = state?.takeIf { it.undoable }?.let {
        PruneRollback.read(PruneRollback.fileOf(rollbackRoot, it.roundId))
    }
    val item = items?.firstOrNull { it.id == id }
    if (item == null) return PruneRestoreResult(false, "本轮回滚档无此条，无法恢复", null)
    val file = store.findFile(id)
    if (file == null || !restoreOneFile(file, item.before)) {
        return PruneRestoreResult(false, "恢复失败（写回没成）——条目仍是退役态，不装成功", null)
    }
    val rebuilt = store.loadAll().filter { it.id == id }
    if (rebuilt.isNotEmpty()) indexSync?.invoke(rebuilt)
    return PruneRestoreResult(true, "已恢复：$id 回到修剪前（索引随文件重建）", id)
}

/**
 * 「永久保留」装配核（757 §2.2）：先按「恢复」写回活状态，再把该 id 追加进豁免清单
 * （[PruneKeepList.add]）。清单没写上 ⇒ 如实说不装成功——下轮仍可能退役。
 * 不碰 PRUNE_ELIGIBLE_KINDS、不改 MemoryQuality 标记闭集（757 §五.3）。
 */
internal fun keepRetiredCore(
    store: MemoryStore,
    workspace: File,
    rollbackRoot: File,
    id: String,
    indexSync: ((List<MemoryEntry>) -> Unit)?,
): PruneRestoreResult {
    val restored = restoreRetiredCore(store, workspace, rollbackRoot, id, indexSync)
    if (!restored.ok) return restored
    val listed = PruneKeepList.add(workspace, id)
    return if (listed) {
        PruneRestoreResult(true, "已永久保留：$id（以后不再自动退役）", id)
    } else {
        PruneRestoreResult(false, "恢复成了，但保留清单没写上——下轮仍可能退役（要重试）", id)
    }
}

/** 原像写回（752 restoreOne 同房型：update 会重渲染，做不到字节级；本文件唯一记忆文件直接写口）。 */
private fun restoreOneFile(file: File, before: String): Boolean = runCatching {
    file.writeText(before, Charsets.UTF_8)
    true
}.getOrDefault(false)

/** 回滚档根（与 752 装配壳同派生：App 私有 `filesDir/抱朴记忆/修剪回滚/`）。 */
private fun rollbackRootOf(ctx: Context): File = PruneRollback.dirOf(MemoryLayout.globalMemoryDir(ctx.filesDir))

/** 生产索引同步（752 同款：SQLite upsert；文件是真相，索引只是加速——0b 定的职责）。 */
private fun sqliteIndexSync(ctx: Context): (List<MemoryEntry>) -> Unit = { entries ->
    val db = BaopuRuntime.db(ctx)
    entries.forEach { e ->
        db.write { d ->
            val stmt = BaopuSql.upsertMemory(e)
            d.execSQL(stmt.sql, stmt.args)
        }
    }
}

/**
 * 灭屏触发的**带门**装配壳（ZhengdaoApp 接收器线程调；758 起取代 752 的直通口）：
 * 前置门（总开关／周期）与频率门全在 [runGatedPruneRoundCore]，门槛拦下＝零写零账。
 */
internal fun pruneOnScreenOffGated(ctx: Context) {
    runCatching {
        val settings = PruneSettings.get(ctx)
        val workspace = BaopuRuntime.workspaceRoot(ctx)
        val outcome = runGatedPruneRoundCore(
            store = BaopuRuntime.memoryStore(ctx),
            workspace = workspace,
            rollbackRoot = rollbackRootOf(ctx),
            nowMs = System.currentTimeMillis(),
            hourOfDay = replayHourOfDay(System.currentTimeMillis()),
            screenOn = false,
            engineBusy = engineBusyNow(),
            indexSync = sqliteIndexSync(ctx),
            enabled = settings.enabled,
            schedule = settings.schedule,
            retireAfterDays = settings.retireAfterDays.toLong(),
            keepIds = PruneKeepList.ids(workspace),
        )
        if (outcome.blocked) RunLog.log("记忆修剪：门槛拦下（${outcome.why}）——这轮没跑、零写零账")
    }.onFailure { RunLog.log("记忆修剪灭屏触发没跑成：${it.javaClass.simpleName}") }
}

/** 「恢复」的 Android 壳（已退役列表页调；读数一句话给人看）。 */
internal fun restoreRetired(ctx: Context, id: String): String = runCatching {
    restoreRetiredCore(
        store = BaopuRuntime.memoryStore(ctx),
        workspace = BaopuRuntime.workspaceRoot(ctx),
        rollbackRoot = rollbackRootOf(ctx),
        id = id,
        indexSync = sqliteIndexSync(ctx),
    ).message
}.getOrElse { "恢复没跑成（${it.javaClass.simpleName}）——账与状态未动" }

/** 「永久保留」的 Android 壳（已退役列表页调）。 */
internal fun keepRetired(ctx: Context, id: String): String = runCatching {
    keepRetiredCore(
        store = BaopuRuntime.memoryStore(ctx),
        workspace = BaopuRuntime.workspaceRoot(ctx),
        rollbackRoot = rollbackRootOf(ctx),
        id = id,
        indexSync = sqliteIndexSync(ctx),
    ).message
}.getOrElse { "保留没跑成（${it.javaClass.simpleName}）——账与状态未动" }
