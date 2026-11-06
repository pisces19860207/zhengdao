// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.trigger

import android.content.Context
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.agent.SendAcceptance
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.schedule.ScheduleOutcome
import java.io.File

/**
 * 到点/日程触发的装配层（第三百九十九批）——把纯账接上真引擎、真日历、真调度。
 *
 * **红线与 167 的 ScheduleRunner 同款，一字不松**：
 * - 只调 `RunEngine.createSession` / `RunEngine.send`——与用户在输入框敲一句话走的是
 *   **同一个入口**，确认制、决策卡、工具权限一概照旧（派工单 §二②「只做可回退的事」的
 *   结构保证：本层没有任何工具执行器/审批/改库的路）；
 * - 不读日历的正文备注，事件标题只进会话标题（见 [TriggerCalendarProbe]）；
 * - 不碰 `BaopuDb` 的表结构（工作区那列的补写照抄 167 的后台会话口径）。
 *
 * 引擎发送这段与 `ScheduleRunner.engineSender` 同房型而非共用件：那是 167 的私有实现，
 * 抽公共件是另一单——这里逐行照抄语义（session 复用判定、发送分流），不抄行号。
 */
object TriggerRunner {

    /** 同一进程里闹钟广播与 Worker 兜底可能几乎同时到：整趟串行，读到的是同一份表。 */
    private val LOCK = TriggerRunner::class.java

    /** 跑一趟「该叫醒的叫醒」。@return 这趟发了几条、还有没有剩下的（Worker 据此 retry）。 */
    fun runDue(context: Context, nowMs: Long = System.currentTimeMillis()): TriggerPassResult {
        // 第五百零四单（471③）：停机到人工解除前，事件触发一律不启动（拦下落 BLOCK 一行）。
        if (com.example.zhengdao.baopu.safety.SafetyWiring.blockAutoStartIfHalted("事件触发")) {
            return TriggerPassResult(sent = false, moreRemain = false, handled = 0)
        }
        val app = context.applicationContext
        val result = synchronized(LOCK) {
            val pass = TriggerPass(
                store = store(app),
                ledger = ledger(app),
                sender = engineSender(app),
                switchOn = { source -> TriggerSwitch.of(app, source) },
            )
            val events = dueCalendarEvents(app, nowMs)
            val anyOn = TriggerSwitch.of(app, TriggerSource.SCHED) || TriggerSwitch.of(app, TriggerSource.CALENDAR)
            if (anyOn) {
                RunLog.log("事件触发：趟开始 日历窗口内事件 ${events.size} 条（诊断行：开关开着才记）")
            }
            pass.run(nowMs, events)
        }
        if (result.handled > 0) refreshAnchors(app, nowMs) // 锚点动了 ⇒ 重排下一次
        return result
    }

    /** 锚点重算（触发完/建删改/页面打开都走它）：日历规则找「下一次该叫醒的事件」，
     * 定时规则的锚点由 pass 自己推。调度器随后只认这些锚点排系统闹钟。 */
    fun refreshAnchors(context: Context, nowMs: Long = System.currentTimeMillis()) {
        val app = context.applicationContext
        val store = store(app)
        val rules = store.load()
        if (rules.isEmpty()) return
        var changed = false
        val next = rules.map { rule ->
            if (rule.source != TriggerSource.CALENDAR || !rule.enabled || !TriggerSwitch.of(app, rule.source)) {
                return@map rule
            }
            val computed = nextCalendarWake(app, rule, nowMs)
            if (computed == rule.nextWakeAtMs) rule else { changed = true; rule.copy(nextWakeAtMs = computed) }
        }
        if (changed && !store.save(next)) RunLog.log("事件触发：锚点重算没写上（下一趟重试）")
        TriggerScheduler.rescheduleAll(app, store, nowMs)
    }

    /** 打开设置/建删规则之后的重排（判据只有一处）。 */
    fun rescheduleAll(context: Context, nowMs: Long = System.currentTimeMillis()) {
        refreshAnchors(context, nowMs)
    }

    // ── 内部 ─────────────────────────────────────────────────────────────

    /** 落盘工厂（Context 只在这一层出现；纯账层构造只吃 File）。工具宿主注入也走这里，工厂只有这一份。 */
    internal fun ruleStore(app: Context) = TriggerStore(File(app.applicationContext.filesDir, TriggerStore.FILE_NAME))

    internal fun eventLedger(app: Context) = TriggerLedger(File(app.applicationContext.filesDir, "trigger/${TriggerLedger.FILE_NAME}"))

    private fun store(app: Context) = ruleStore(app)

    private fun ledger(app: Context) = eventLedger(app)

    /** 日历事件的原始行：源开关开着且权限给着才去读（权限 fail-closed 在探针里）。 */
    private fun dueCalendarEvents(app: Context, nowMs: Long): List<CalEventRow> {
        if (!TriggerSwitch.of(app, TriggerSource.CALENDAR)) return emptyList()
        // due 判定只需要「未来 lead 最远 12 小时内开始的事件」；多读没意义。
        return TriggerCalendarProbe.query(app, nowMs, nowMs + TriggerRules.LEAD_MAX * 60_000L)
    }

    /** 找这条日历规则下一次该叫醒的时刻（7 天内最近的一个「开始 − N」；已触发过的跳过）。 */
    private fun nextCalendarWake(app: Context, rule: TriggerRule, nowMs: Long): Long {
        val events = TriggerCalendarProbe.query(app, nowMs, nowMs + TriggerRules.CALENDAR_HORIZON_MS)
        return events
            .map { TriggerRules.calWakeAt(it.beginMs, rule.leadMinutes) }
            .filter { it > nowMs }
            .minOrNull() ?: TriggerRules.NO_WAKE
    }

    /** 真发送出口：与 ScheduleRunner.engineSender 同房型（类 KDoc 说了为什么不共用件）。 */
    private fun engineSender(app: Context) = WakeSender { rule, eventTitle, nowMs ->
        runCatching {
            val db = BaopuRuntime.db(app)
            val engine = BaopuRuntime.engine(app)
            val sid = if (rule.sessionId > 0L && sessionAlive(db, rule.sessionId)) {
                rule.sessionId
            } else {
                createSession(app, db, engine, rule, eventTitle, nowMs)
            }
            when (engine.send(sid, rule.prompt)) {
                SendAcceptance.STARTED -> WakeResult(ScheduleOutcome.FIRED, sid, "started")
                SendAcceptance.QUEUED -> WakeResult(ScheduleOutcome.QUEUED, sid, "queued")
                SendAcceptance.REFUSED_BUSY_OTHER_SESSION -> WakeResult(ScheduleOutcome.REFUSED_BUSY, sid, "other_session_busy")
                SendAcceptance.REFUSED_QUEUE_FULL -> WakeResult(ScheduleOutcome.REFUSED_BUSY, sid, "queue_full")
                SendAcceptance.REFUSED_EMPTY -> WakeResult(ScheduleOutcome.FAILED, sid, "empty_prompt")
            }
        }.getOrElse {
            WakeResult(ScheduleOutcome.FAILED, -1L, "${it.javaClass.simpleName}:${it.message}")
        }
    }

    /** 会话标题带上出处：用户在会话列表一眼看出这条不是自己打的（167 同款口径，i18n 走 strings）。 */
    private fun sessionTitle(app: Context, rule: TriggerRule, eventTitle: String): String {
        val base = app.getString(R.string.trigger_session_title, rule.title)
        return if (eventTitle.isBlank()) base else "$base · ${eventTitle.take(20)}".take(40)
    }

    private fun createSession(
        app: Context,
        db: BaopuDb,
        engine: com.example.zhengdao.baopu.agent.RunEngine,
        rule: TriggerRule,
        eventTitle: String,
        nowMs: Long,
    ): Long {
        val created = engine.createSession(title = sessionTitle(app, rule, eventTitle), now = nowMs)
        // 工作区那一列是控制器建会话时一并写的；后台这条路径不写会与页面建的口径不一致（167 同款补写）。
        val path = BaopuWorkspace.hostDir(app).absolutePath
        runCatching {
            val stmt = BaopuSql.setSessionWorkspace(created, path)
            db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get()
        }.onFailure { RunLog.log("事件触发：新建会话后记工作区失败 ${it.javaClass.simpleName}: ${it.message}") }
        return created
    }

    /** 绑定的会话还在不在：不在就重开一个，不许把消息发进不存在的会话（167 同款只读查询）。 */
    private fun sessionAlive(db: BaopuDb, sessionId: Long): Boolean = runCatching {
        db.read { d ->
            d.rawQuery(
                "SELECT 1 FROM session WHERE id = ? AND deleted_at IS NULL",
                arrayOf(sessionId.toString()),
            ).use { c -> c.moveToFirst() }
        }
    }.getOrDefault(false)
}
