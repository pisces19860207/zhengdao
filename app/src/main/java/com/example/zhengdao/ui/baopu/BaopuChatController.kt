// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第九批 §4.3（B 段抱朴页）+ 第⑮刀经验（可观察性 bug 改状态载体：
// 引擎状态用 mutableStateOf + State 暴露）+ 设计 §9 施工细节第 1/8 条
// （流式期间内存累积、记忆写入要在会话里可见）。
// 本文件是派工单 §4.3 表格之外的第 6 个 UI 文件（偏差清单报备）：
// 没有它，装配逻辑只能散在 Composable 里，进一次页面建一次线程池。
package com.example.zhengdao.ui.baopu

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.MemoryScope
import com.example.zhengdao.baopu.MemoryStatus
import com.example.zhengdao.baopu.MemoryStore
import com.example.zhengdao.baopu.agent.EngineEvent
import com.example.zhengdao.baopu.agent.RunState
import org.json.JSONObject

/** 一条消息的 UI 形状（role 分组由列表层做）。 */
data class BaopuUiMessage(
    val id: Long,
    val role: String,
    val parts: List<BaopuUiPart>,
)

/** 一个 part 的 UI 形状（text 直显；tool 显示调用名 + 结果摘要，可展开全文）。 */
data class BaopuUiPart(
    val kind: String, // 库里 part.kind 原值："text" | "tool" | （将来的）"compressed" 等
    val text: String,
    val tool: String = "",
)

/**
 * 抱朴页的胶水（进程级单例挂在 [BaopuRuntime]；本类只持 UI 可观察状态）。
 *
 * 数据流：引擎落库（先落库再渲染）⇒ onEvent ⇒ [refresh] 重读 db ⇒ Compose 状态更新。
 * UI 不回写引擎数据，只调动作（send/stop/resume/记忆确认）。
 */
internal class BaopuChatController(context: Context) {

    private val appCtx = context.applicationContext
    private val db = BaopuRuntime.db(appCtx)
    private val engine = BaopuRuntime.engine(appCtx)
    private val store: MemoryStore = BaopuRuntime.memoryStore(appCtx)

    /** UI 线程 Handler：引擎事件从后台线程来，统一 post 回主线程再刷（见 [onEngineEvent]）。 */
    private val main = android.os.Handler(android.os.Looper.getMainLooper())

    /** 当前会话（null = 还没有；首次发送时建）。 */
    var sessionId by mutableStateOf(BaopuRuntime.latestSessionId(db))
        private set

    /** 会话里落库的 interrupted_step（-1 = 未中断）。 */
    var interruptedStep by mutableStateOf(-1)
        private set

    /** 消息列表（从 db 读——先落库再渲染，UI 永远不比库新）。 */
    var messages by mutableStateOf(listOf<BaopuUiMessage>())
        private set

    /** 记忆候选（status=CANDIDATE 的条目；确认/不记都在这里操作）。 */
    var memoryCandidates by mutableStateOf(listOf<com.example.zhengdao.baopu.MemoryEntry>())
        private set

    /** 引擎可观察状态（直接透出 Compose State）。 */
    val engineState: androidx.compose.runtime.State<RunState> get() = engine.state
    val engineStep: androidx.compose.runtime.State<Int> get() = engine.step
    val engineNotice: androidx.compose.runtime.State<String?> get() = engine.notice

    /** 会话中（可停止）。 */
    val busy: Boolean
        get() = engineState.value in setOf(
            RunState.THINKING, RunState.ACTING, RunState.COMPRESSING, RunState.AWAIT_CONFIRM,
        )

    init {
        engine.onEvent = { ev -> onEngineEvent(ev) }
        refresh()
    }

    /** 发送（首次自动建会话）。 */
    fun send(text: String) {
        if (text.isBlank() || busy) return
        val sid = sessionId ?: engine.createSession(
            title = text.take(40),
            now = System.currentTimeMillis(),
        ).also { sessionId = it }
        engine.send(sid, text)
    }

    /** 停止（半成品保留；本刀只读无写半成品）。 */
    fun stop() = engine.stop()

    /** 一键续跑（中断于第 N 步）。 */
    fun resume() {
        val sid = sessionId ?: return
        engine.resume(sid, interruptedStep)
    }

    /** 新会话：释放旧视图状态（库里的会话不删——账本只增）。 */
    fun newSession() {
        sessionId = null
        interruptedStep = -1
        messages = emptyList()
    }

    /** 记忆候选：确认（落盘 CONFIRMED）。 */
    fun confirmMemory(id: String) {
        val e = memoryCandidates.firstOrNull { it.id == id } ?: return
        val confirmed = e.copy(status = MemoryStatus.CONFIRMED, updatedAt = System.currentTimeMillis())
        store.update(confirmed)
        syncIndex(confirmed)
        refreshMemory()
    }

    /** 记忆候选：不记（文件与索引都删——候选没资格占地方）。 */
    fun forgetMemory(id: String) {
        store.delete(id)
        db.write { d ->
            val stmt = com.example.zhengdao.baopu.BaopuSql.deleteMemory(id)
            d.execSQL(stmt.sql, stmt.args)
        }
        refreshMemory()
    }

    // ── 内部 ──────────────────────────────────────────────────────────────

    /**
     * 引擎事件一律**回到主线程**再刷新。
     *
     * 为什么（2026-10-17 真机两次实测对比）：引擎事件是从 `zd-baopu-run` 后台线程发的，
     * 在后台线程直接写 Compose 的 `mutableStateOf`（messages / interruptedStep）时，
     * 界面**有时**跟着变、有时不变——"续跑完了中断红字还挂着"就是这么来的。
     * 主线程里读库 + 写状态才稳定（同第㊳刀「View.post 对未 attach 的 View 无限期推迟」的教训：
     * 跨线程的界面更新要显式回到主线程，别指望别人替你切）。
     */
    private fun onEngineEvent(@Suppress("UNUSED_PARAMETER") ev: EngineEvent) {
        // `Settled` 是"本轮彻底结束"的权威信号（引擎在释放单活跃门之后才发）⇒ 强制重算中断标记，
        // 不再靠 `runningNow()` 去猜（真机上猜错过一次：正常完成的会话红字挂着不下来）。
        val force = ev is EngineEvent.Settled
        if (android.os.Looper.myLooper() == android.os.Looper.getMainLooper()) {
            refresh(force)
        } else {
            main.post { refresh(force) }
        }
    }

    /**
     * 重读消息列表 ＋ 中断标记 ＋ 记忆候选（一次 db 读，不逐事件逐表）。
     *
     * @param force true = 不看"在不在跑"，直接重算中断标记（[EngineEvent.Settled] 专用）
     */
    fun refresh(force: Boolean = false) {
        val sid = sessionId
        messages = if (sid == null) emptyList() else readMessages(sid)
        // 中断标记：**引擎在跑的时候不刷新**。真机实测（2026-10-17）两条教训：
        // ① 跑到第 N 步时库里最后一条 run_log 正是 Thinking/Acting，此刻重算会把
        //    "正在思考"误读成"被杀了"——所以运行中跳过；
        // ② 判"在不在跑"**不能看 `engine.state`**：那是后台线程写的 Compose State，
        //    主线程读到的可能是旧值（本次实测读到过 DONE），于是运行中照样重算并误报。
        //    改用 `ActiveSessionGate`（AtomicReference，跨线程立即可见）——它在整个
        //    循环期间被持有，收尾 finally 里才释放，正是"本轮还没结束"的真值。
        // 收尾那一下引擎会发 `EngineEvent.Settled`，带着 `force = true` 过来，因此
        // "跑完了红字还不下来"这种情况不可能再出现（不依赖上面那个判据的对错）。
        if (force || !runningNow()) {
            interruptedStep = if (sid == null) -1 else BaopuRuntime.interruptedStep(db, sid)
        }
        refreshMemory()
    }

    /** 本轮是否还在跑（单活跃门的持有即"在跑"）。 */
    private fun runningNow(): Boolean =
        com.example.zhengdao.baopu.agent.ActiveSessionGate.current() != null

    private fun refreshMemory() {
        memoryCandidates = store.loadAll().filter { it.status == MemoryStatus.CANDIDATE }
    }

    private fun readMessages(sessionId: Long): List<BaopuUiMessage> = db.read { d ->
        d.rawQuery(
            "SELECT message.id, message.role, part.kind, part.payload " +
                "FROM message LEFT JOIN part ON part.message_id = message.id " +
                "WHERE message.session_id = ? ORDER BY message.seq ASC, part.seq ASC",
            arrayOf(sessionId.toString()),
        ).use { c ->
            val out = mutableListOf<BaopuUiMessage>()
            var curId = -1L
            var curRole = ""
            var parts = mutableListOf<BaopuUiPart>()
            fun flush() {
                if (curId >= 0) out += BaopuUiMessage(curId, curRole, parts.toList())
            }
            while (c.moveToNext()) {
                val id = c.getLong(0)
                if (id != curId) {
                    flush()
                    curId = id
                    curRole = c.getString(1)
                    parts = mutableListOf()
                }
                val kind = if (c.isNull(2)) "text" else c.getString(2)
                val payload = if (c.isNull(3)) "" else c.getString(3)
                parts += if (kind == "tool") {
                    val json = runCatching { JSONObject(payload) }.getOrNull()
                    BaopuUiPart(
                        kind = "tool",
                        tool = json?.optString("tool") ?: "",
                        text = json?.optString("result") ?: payload,
                    )
                } else {
                    // 保留库里的原始 kind（不硬归一成 "text"）：part.kind 是自由文本，
                    // 未来新增 kind（如压缩标记）不该在列表层被静默吞掉。本刀实际只会有 text。
                    BaopuUiPart(kind = kind, text = payload)
                }
            }
            flush()
            out
        }
    }

    /** 索引同步（0b 定的职责：文件是真相，SQLite 只是索引）。 */
    private fun syncIndex(entry: com.example.zhengdao.baopu.MemoryEntry) {
        db.write { d ->
            val stmt = com.example.zhengdao.baopu.BaopuSql.upsertMemory(entry)
            d.execSQL(stmt.sql, stmt.args)
        }
    }
}
