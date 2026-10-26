// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.schedule

import android.content.Context
import com.example.zhengdao.R
import com.example.zhengdao.baopu.BaopuDb
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.BaopuSql
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.agent.ActiveSessionGate
import com.example.zhengdao.baopu.agent.SendAcceptance
import com.example.zhengdao.rootfs.RunLog

/**
 * 到点触发的装配层（第一百六十七批 §1「到点：开一个抱朴会话/轮次发送 prompt，结果落会话＋系统通知」）。
 *
 * 这一层只做三件事：把 [SchedulePass] 那套纯账接上真引擎、接上真通知、触发完把下一次排回去。
 * 账本身（去重、补跑、翻篇）不在这里，所以那部分能在 JVM 上测（见 `SchedulePassTest`）。
 *
 * **红线（派工单 §红线）在这层怎么守**：
 * - 只调 `RunEngine.createSession` / `RunEngine.send` —— 与用户在输入框敲一句话走的是**同一个入口**，
 *   确认制、决策卡、工具权限一概照旧；
 * - 不调 `resumeWithApproval` / `approveRestoredPending` / `answerChoice`（不替用户点确认），
 *   也不直接调任何工具执行器（不自动调工具、不写产物）；
 * - 不碰 `BaopuDb` 的表结构（`§0.5` 约束 1：172-L4 正在做 V19，本批不抬库版本）。
 *   下面那条 `sessionAlive` 是**只读查询**；之所以写在本地而不是进 `BaopuSql`，
 *   正是因为那条 SQL 的归属文件本批禁改（并表时再搬回去，另单）。
 */
object ScheduleRunner {

    /** 同一进程里闹钟广播与 Worker 兜底可能几乎同时到：整趟串行，读到的是同一份表。 */
    private val LOCK = ScheduleRunner::class.java

    /**
     * 跑一趟「到点处理」。
     * @return 这趟发了几条、还有没有剩下的（Worker 据此 success / retry）
     */
    fun runDueTasks(context: Context, nowMs: Long = System.currentTimeMillis()): PassResult {
        val app = context.applicationContext
        val result = synchronized(LOCK) {
            SchedulePass(ScheduleStore.of(app), engineSender(app), notifier(app)).run(nowMs)
        }
        if (result.handled > 0) {
            // 锚点已经推进 ⇒ 必须把下一次排回去，否则这一趟之后就没有后来了。
            rescheduleQuietly(app, System.currentTimeMillis())
        }
        return result
    }

    /**
     * 打开「定时任务」页时的补跑（派工单 §1「错过：下次打开补跑一次」）。
     *
     * 为什么先看单活跃门：`send` 被别的会话挡住时，引擎会往**那个会话**的界面上贴一条
     * 「已有另一个会话在跑（全局单活跃）」。用户在定时页点个新建结果，
     * 正在跑的那个会话里莫名多出一行提示 —— 那不是他要的反馈。
     * 门被占着就这一趟不补，留给闹钟/兜底作业的下一次（错过检测仍然有效：锚点没动）。
     *
     * @return null = 门被占着，这一趟没跑
     */
    fun catchUpOnOpen(context: Context, nowMs: Long = System.currentTimeMillis()): PassResult? {
        if (ActiveSessionGate.current() != null) {
            log("有会话正在跑，本次打开不补跑（留给下一次触发）")
            return null
        }
        return runDueTasks(context, nowMs)
    }

    /** 打开/编辑/删除之后重排全部（UI 与开机恢复都走这里，判据只有一处）。 */
    fun rescheduleAll(context: Context, nowMs: Long = System.currentTimeMillis()) {
        ScheduleScheduler.rescheduleAll(context.applicationContext, ScheduleStore.of(context), nowMs)
    }

    private fun rescheduleQuietly(context: Context, nowMs: Long) {
        runCatching { rescheduleAll(context, nowMs) }
            .onFailure { log("重排下一次没成 ${it.javaClass.simpleName}: ${it.message}") }
    }

    private fun notifier(app: Context) = object : PassNotifier {
        override fun starting(task: ScheduleTask) {
            // 第二百二十九批：到点先说「要开始了」。开关关 ⇒ 只发进会话不通知；
            // 通知权限没给 ⇒ ScheduleNotifier.postStarting 里落 RunLog（不许静默），
            // 且任务页顶部有「通知权限未开」的提示。手建与抱朴自建的任务同一处理。
            val on = runCatching { SchedulePreNotify.enabled(app) }.getOrDefault(true)
            if (!SchedulePreNotifyRules.shouldPreNotify(on)) {
                log("开始前提醒已关：不响，只把 prompt 发进会话")
                return
            }
            ScheduleNotifier.postStarting(app, task)
        }

        override fun fired(task: ScheduleTask, sessionId: Long, late: Boolean) {
            ScheduleNotifier.postFired(app, task, sessionId, late)
        }

        override fun failed(task: ScheduleTask, outcome: ScheduleOutcome, detail: String) {
            ScheduleNotifier.postFailed(app, task, outcome, detail)
        }

        override fun pausedByFailStreak(task: ScheduleTask, outcome: ScheduleOutcome, detail: String) {
            ScheduleNotifier.pausedByFailStreak(app, task, outcome, detail)
        }
    }

    /** 真发送出口：抱朴引擎是进程级单例，后台 Worker 里没有页面也能用（不依赖 Activity / Compose）。 */
    private fun engineSender(app: Context) = TaskSender { task, nowMs ->
        runCatching {
            val db = BaopuRuntime.db(app)
            val engine = BaopuRuntime.engine(app)
            val sid = if (task.sessionId > 0L && sessionAlive(db, task.sessionId)) {
                task.sessionId
            } else {
                createSession(app, db, engine, task, nowMs)
            }
            when (engine.send(sid, task.prompt)) {
                SendAcceptance.STARTED -> SendResult(ScheduleOutcome.FIRED, sid, "started")
                SendAcceptance.QUEUED -> SendResult(ScheduleOutcome.QUEUED, sid, "queued")
                SendAcceptance.REFUSED_BUSY_OTHER_SESSION ->
                    SendResult(ScheduleOutcome.REFUSED_BUSY, sid, "other_session_busy")

                SendAcceptance.REFUSED_QUEUE_FULL ->
                    SendResult(ScheduleOutcome.REFUSED_BUSY, sid, "queue_full")

                SendAcceptance.REFUSED_EMPTY -> SendResult(ScheduleOutcome.FAILED, sid, "empty_prompt")
            }
        }.getOrElse {
            SendResult(ScheduleOutcome.FAILED, -1L, "${it.javaClass.simpleName}:${it.message}")
        }
    }

    /**
     * 会话标题带上出处，用户回列表里一眼看出这条不是自己打的（派工单 §1「结果落会话」的可辨认要求）。
     *
     * 文案走 `R.string.sched_session_title`：这是**用户看得见**的一行字，写死中文的话
     * 切英文时会话列表里会冒出一句中文（本仓 i18n 口径，见 ui/baopu 各页与 `strings.xml`）。
     */
    private fun sessionTitle(app: Context, task: ScheduleTask): String =
        app.getString(R.string.sched_session_title, task.title).take(40)

    private fun createSession(app: Context, db: BaopuDb, engine: com.example.zhengdao.baopu.agent.RunEngine, task: ScheduleTask, nowMs: Long): Long {
        val created = engine.createSession(title = sessionTitle(app, task), now = nowMs)
        // 工作区那一列是控制器建会话时一并写的（ui/baopu/BaopuChatController.recordSessionWorkspace）；
        // 后台这条路径不写就会与页面建的会话口径不一致，这里照搬同一句。
        val path = BaopuWorkspace.hostDir(app).absolutePath
        runCatching {
            val stmt = BaopuSql.setSessionWorkspace(created, path)
            db.write { d -> d.execSQL(stmt.sql, stmt.args) }.get()
        }.onFailure { log("新建会话后记工作区失败 ${it.javaClass.simpleName}: ${it.message}") }
        return created
    }

    /** 绑定的会话还在不在（用户可能把它删了）：不在就重新开一个，不许把消息发进一个已经不存在的会话。 */
    private fun sessionAlive(db: BaopuDb, sessionId: Long): Boolean = runCatching {
        db.read { d ->
            d.rawQuery(
                "SELECT 1 FROM session WHERE id = ? AND deleted_at IS NULL",
                arrayOf(sessionId.toString()),
            ).use { c -> c.moveToFirst() }
        }
    }.getOrDefault(false)

    private fun log(line: String) = RunLog.log("定时任务：$line")
}
