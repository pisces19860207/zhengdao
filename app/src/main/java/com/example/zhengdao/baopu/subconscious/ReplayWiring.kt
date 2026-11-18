// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百七十八批 · 海马重放的**生产装配**（判据一行不落在这里）。
//
// 四条纪律（照 676 SubconsciousWiring／677 控制器装配的房型）：
// 1. **对被读的东西只读**：L2 记忆（[com.example.zhengdao.baopu.MemoryStore.loadAll]）
//    与项目账（`LedgerStore.items`）只读当输入；自身产出只写自己的行槽＋账＋状态三样
//    （见 [ReplayStores]）。记忆层没有任何写调用——判据③由源码门禁钉死。
// 2. **694 未合入的键适配**：海马·情景键按 694 派工单约定形状（{sessionId, seq, hint}）
//    从项目账行适配（[keysOfLedger]：账行 sourceMsgId → 消息库只读定位 → 键）；
//    694 合入后整缝交接给其 Hippocampus 件（回归项，见交付报告）。
// 3. **崩了只记一行**：装配层任何失败不抛出调用方（触发点在收工路径/广播上，
//    65/89 口径——不许把别人的路径带下去）。
// 4. **零调度**：本文件不建线程/协程/定时器——触发线程由调用方给（页签打开在
//    控制器协程上；灭屏在 ZhengdaoApp 的接收器线程上）。
package com.example.zhengdao.baopu.subconscious

import android.content.Context
import android.os.PowerManager
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.MemoryEntry
import com.example.zhengdao.baopu.MemoryRules
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.ledger.LedgerItem
import com.example.zhengdao.baopu.ledger.LedgerStore
import com.example.zhengdao.core.RunLog
import java.time.Instant
import java.time.ZoneId

/** 引擎「在跑」的四种状态（收工路径之外的都算闲；[com.example.zhengdao.baopu.agent.RunState] 闭集）。 */
private val ENGINE_BUSY_STATES = setOf("THINKING", "ACTING", "AWAIT_CONFIRM", "COMPRESSING")

/** 本轮的 id（账行认领用；时刻派生，同轮内稳定）。 */
internal fun replayRoundIdOf(nowMs: Long): String = "r$nowMs"

/** 钟点换算（本地时区；676 `echoHourOfDay` 同一套日历口径——给 [IdleSnapshot] 的）。 */
internal fun replayHourOfDay(nowMs: Long): Int =
    Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).hour

/** 屏幕亮着吗（只读取数；读不到 ⇒ null——判据②口径：读不到不据此放行）。 */
internal fun screenOnNow(ctx: Context): Boolean? = runCatching {
    val pm = ctx.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return null
    pm.isInteractive
}.getOrNull()

/** 有任务在跑吗（进程内引擎状态；引擎没建 ＝ 没任务——真空闲）。 */
internal fun engineBusyNow(): Boolean {
    val engine = BaopuRuntime.engineIfBuilt() ?: return false
    return engine.state.value.name in ENGINE_BUSY_STATES
}

/**
 * 694 键适配（约定形状；694 合入前生产走这里，合入后整缝交接）：
 * 项目账行带 `sourceMsgId` 的 → 消息库只读定位 (session_id, seq) → 情景键。
 * [resolveMessage] 注入（纯映射，JVM 直测）；定位不到的行跳过（键缺 ＝ 不锚）。
 */
internal fun keysOfLedger(
    items: List<LedgerItem>,
    resolveMessage: (String) -> Pair<Long, Long>?,
): List<HippocampusKey> = items.asSequence()
    .filter { it.sourceMsgId.isNotBlank() }
    .mapNotNull { item ->
        val (sid, seq) = resolveMessage(item.sourceMsgId) ?: return@mapNotNull null
        HippocampusKey(sessionId = sid, seq = seq, hint = item.title)
    }
    .toList()

/** 消息定位（账行 sourceMsgId → (session_id, seq)；只读，查不到 ⇒ null）。 */
internal fun messageLocateOf(db: BaopuDb, messageId: String): Pair<Long, Long>? = runCatching {
    db.read { d ->
        d.rawQuery("SELECT session_id, seq FROM message WHERE id = ?", arrayOf(messageId)).use { c ->
            if (c.moveToFirst()) c.getLong(0) to c.getLong(1) else null
        }
    }
}.getOrNull()

/** L0 原话引用（按指针只读回原消息正文，截到 [ReplayLimits.QUOTE_CHARS]；没有 ⇒ null）。 */
internal fun messageQuoteOf(db: BaopuDb, key: HippocampusKey): String? = runCatching {
    db.read { d ->
        d.rawQuery(
            "SELECT p.payload FROM message m JOIN part p ON p.message_id = m.id " +
                "WHERE m.session_id = ? AND m.seq = ? AND (p.kind IS NULL OR p.kind = 'text') " +
                "ORDER BY p.seq ASC LIMIT 1",
            arrayOf(key.sessionId.toString(), key.seq.toString()),
        ).use { c ->
            if (c.moveToFirst()) c.getString(0)?.take(ReplayLimits.QUOTE_CHARS) else null
        }
    }
}.getOrNull()

/** L2 有效条目 ⇒ 候选投影（kind/scope 标签复用 [MemoryRules]，不另造第二套叫法）。 */
internal fun replayCandidateOf(e: MemoryEntry): ReplayCandidate = ReplayCandidate(
    memoryId = e.id,
    kind = e.kind.name,
    kindLabel = MemoryRules.kindLabel(e.kind),
    scope = e.scope.name,
    scopeLabel = MemoryRules.scopeLabel(e.scope),
    title = e.title,
    body = e.body,
    sourceSession = e.sourceSession,
    updatedAtMs = e.updatedAt,
)

/**
 * 跑一轮重放装配（判据本体全在 [runHippocampalReplay]；这里只读数、喂入、落自己的三样）。
 *
 * @param nowMs 本轮时刻（装配层读钟——纯层不读）
 * @param screenOn 屏幕亮吗（灭屏触发传 false；页签触发传读数）
 * @return 这轮的产物（没跑/空轮也返回——note 说了为什么；调用方按 [ReplayRound.ran] 落账）
 */
internal fun runReplayRound(ctx: Context, nowMs: Long, screenOn: Boolean?): ReplayRound {
    val workspace = BaopuRuntime.workspaceRoot(ctx)
    val db = BaopuRuntime.db(ctx)
    val keys = keysOfLedger(LedgerStore.items(workspace)) { messageId ->
        messageLocateOf(db, messageId)
    }
    val lastRun = ReplayDayGate.lastRunDayKey(workspace)
    val candidates = BaopuRuntime.memoryStore(ctx).loadAll()
        .filter { it.status == MemoryStatus.CONFIRMED }
        .sortedByDescending { it.updatedAt }
        .take(ReplayLimits.SCAN_CAP)
        .map { base ->
            val anchor = replayAnchorOf(replayCandidateOf(base), keys)
            val quote = anchor?.let { messageQuoteOf(db, it) }
            replayCandidateOf(base).copy(
                // 指针解析不到原消息 ＝ 点不回去 ⇒ 按缺指针丢弃（判据①「能点回原消息」）。
                l0Value = if (quote != null) anchor?.pointer else null,
                quote = quote.orEmpty(),
            )
        }
    val round = runHippocampalReplay(
        candidates = candidates,
        idle = IdleSnapshot(
            nowMs = nowMs,
            hourOfDay = replayHourOfDay(nowMs),
            screenOn = screenOn,
            engineBusy = engineBusyNow(),
        ),
        lastRunDayKey = lastRun,
        roundId = replayRoundIdOf(nowMs),
    )
    if (!round.ran) {
        RunLog.log("重放：这轮没跑（${round.note}）")
        return round
    }
    // 自身产出三件：行槽（建议区）＋本账（浮 N ⇒ N 行）＋状态（天闸桶＋prev）。
    ReplayRowStore.publishForRound(round.rows)
    val ledger = ReplayLedger.fileOf(workspace)
    round.rows.forEach { row ->
        ReplayLedger.append(ledger, ReplayLedger.floatedEvent(row.atMs, row.id, row.l0.value, row.title))
    }
    ReplayDayGate.writeAfterRound(workspace, round.dayKey, lastRun, nowMs, round.roundId)
    RunLog.log("重放：${round.note}")
    return round
}

/**
 * 一键撤销（判据④）：建议区行槽回重放前快照＋状态桶回 prev＋账面补一行撤销。
 * 记忆层零改动（本批从头到尾就没有记忆写口）。返回给人看的一句话。
 */
internal fun undoLatestReplay(ctx: Context, nowMs: Long): String {
    val workspace = BaopuRuntime.workspaceRoot(ctx)
    if (ReplayDayGate.lastRunDayKey(workspace) == ReplayDayGate.NEVER) {
        return "没有可撤销的重放（状态＝没跑过）"
    }
    val restored = ReplayRowStore.undoPublish()
    val ok = ReplayDayGate.restoreToPrev(workspace, nowMs, "u$nowMs")
    ReplayLedger.append(
        ReplayLedger.fileOf(workspace),
        ReplayLedger.undoneEvent(nowMs, "u$nowMs", restored.size),
    )
    RunLog.log("重放撤销：行 ${restored.size} 回快照；状态 ${if (ok) "已回重放前" else "写回失败"}")
    return if (ok) "已撤销：建议区与状态回到重放前；记忆层零改动" else "行区已回快照，但状态写回失败（要重试）"
}

/** 灭屏触发的装配入口（ZhengdaoApp 的接收器线程调；刚灭屏 ⇒ screenOn = false）。 */
internal fun replayOnScreenOff(ctx: Context) {
    runCatching { runReplayRound(ctx, System.currentTimeMillis(), screenOn = false) }
        .onFailure { RunLog.log("重放灭屏触发没跑成：${it.javaClass.simpleName}") }
}
