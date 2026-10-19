// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 2026-10-17（抱朴刀 3 · A 段）：从 `RunEngine.kt` 拆出——派工单 §9 行数纪律
// 「单文件新增 ≤ 400 行；超了拆文件」。内核（无 Android/Compose/SQLite，
// [PendingStore] 是注入的接口）与引擎壳（[RunEngine] 只做接线）由此彻底分离。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PendingRow
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.rootfs.RunLog
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import org.json.JSONObject

/**
 * 要动文件了，请人点一下（刀 3 A 段：确认制的唯一事件；派工单 §3.1 原样）。
 *
 * [callId] = 工具调用 id（= [ToolCall.id]；陈旧点击判别的锚——不匹配就丢弃）；
 * [summary] = 一句话人话摘要（[RunRules.confirmSummary] 生成，重启后同函数重算）；
 * [argsJson] = 原样参数（落库、恢复时重放；**UI 只取 path 做标题展示，
 * 不解析它执行任何动作**——执行只走引擎的 [GuardPipeline.executeApproved]）。
 */
data class ConfirmRequest(
    val callId: String,
    val tool: String,
    val summary: String,
    val argsJson: String,
) : EngineEvent

/**
 * 确认制的**内核**（刀 3 A 段）——挂起等待 ＋ 批准执行 ＋ 落库清库，
 * 无 Android/Compose/SQLite（[PendingStore] 是注入的接口），JVM 直测；
 * 引擎壳（[RunEngine]）只做接线：事务落库、回灌历史、发事件。
 *
 * 生命周期（派工单 §3.2/§3.3）：
 * 1. [enter]：落 pending（回调）→ 出卡（回调）→ 挂起等批准；
 * 2. 批准 ⇒ [GuardPipeline.executeApproved]（只跳权限段，其余四段原样）；
 * 3. 拒绝 ⇒ 不执行，回灌「用户拒绝：<summary>」，对话继续；
 * 4. 超时（**仅前台计时**）⇒ 回灌人话、清 pending、交引擎收尾 ABORTED；
 * 5. 进程被杀 ⇒ pending 三列还在库里 ⇒ 控制器重建卡、[restoredExecute] 续上（T3）。
 *
 * 为什么等待是**轮询**而不是派工单 §3.2 写的 `withTimeoutOrNull`：超时口径是
 * 「仅前台计时」（[RunRules.confirmExpired] 的 KDoc），withTimeoutOrNull 只认
 * 墙钟、剔不掉后台时间；轮询里每次现问 [foregroundElapsedMs] 才做得到。
 * 两者不可兼得，以「仅前台计时」为准（§2 补录原文）。
 */
internal class ConfirmFlow(
    private val pipeline: GuardPipeline,
    private val store: PendingStore,
    private val foregroundElapsedMs: () -> Long = { 0L },
    private val onPersistPending: (sessionId: Long, step: Int, tool: String, argsJson: String, at: Long) -> Unit = { _, _, _, _, _ -> },
    private val onRequestCard: (ConfirmRequest) -> Unit = {},
    private val log: (String) -> Unit = { RunLog.log(it) },
) {

    sealed interface Outcome {
        /** 批准且已执行（[result] = 管线结果；引擎按在线路径同形处理）。 */
        data class Approved(val call: ToolCall, val result: GuardPipeline.Result) : Outcome

        /** 拒绝（[note] = 回灌文本；引擎落库并进历史，对话继续）。 */
        data class Rejected(val note: String) : Outcome

        /** 用户点「停止」导致的作废（不回灌人话；引擎的循环会随即收尾 ABORTED）。 */
        data object Abandoned : Outcome

        /** 超时（[note] = 回灌文本；pending 已清，引擎收尾 ABORTED）。 */
        data class TimedOut(val note: String) : Outcome

        /** 已有 pending 在等（单活跃门外的双保险；第二个丢弃）。 */
        data object Ignored : Outcome
    }

    private data class Pending(
        val sessionId: Long,
        val step: Int,
        val call: ToolCall,
        val summary: String,
        val argsJson: String,
    )

    // ── 跨线程字段（P2-10，第六十批：四个都必须 @Volatile）────────────────────
    //
    // 写：引擎线程（enter 里置 pending/deferred、清 note/abandoned、结束后整体清空）。
    // 读：UI 主线程（resumeWithApproval / abandon / liveCallId 判陈旧点击）。
    // 没有 @Volatile 时的故障形态是「点了批准，引擎还在等」——两个线程各自缓存了旧值，
    // 且在 x86 模拟器上几乎不复现（只有真机 ARM 的弱内存序才稳定出）。状态机语义一个字没改：
    // 仍是「写 pending → 等 deferred → 清 pending」这一个串行故事，只是让读写彼此可见。
    @Volatile
    private var pending: Pending? = null

    @Volatile
    private var deferred: CompletableDeferred<Boolean>? = null

    @Volatile
    private var note: String? = null

    @Volatile
    private var abandoned = false

    /** 当前挂起的 callId（没有 ⇒ null；陈旧点击判别与测试都看它）。 */
    fun liveCallId(): String? = pending?.call?.id

    /**
     * 进确认：落库 → 出卡 → 挂起等 → 按结局返回。
     *
     * @param spillWriter spill 落盘注入（引擎给：落工作区内 `.baopu-spill/`）
     */
    suspend fun enter(
        sessionId: Long,
        step: Int,
        call: ToolCall,
        spillWriter: (fileName: String, text: String) -> SpillResult?,
    ): Outcome {
        if (pending != null) {
            log("抱朴确认：已有 pending 在等（${pending?.call?.name}），丢弃第二个（${call.name}）")
            return Outcome.Ignored
        }
        val argsJson = RunRules.capArgsJson(call.arguments.toString())
        val summary = RunRules.confirmSummary(call.name, call.arguments)
        pending = Pending(sessionId, step, call, summary, argsJson)
        deferred = CompletableDeferred()
        note = null
        abandoned = false
        val at = System.currentTimeMillis()
        // ① 落库（引擎回调：run_log 的 AwaitConfirm 行 + session pending 三列**同事务**）
        onPersistPending(sessionId, step, call.name, argsJson, at)
        // ② 出卡（UI 事件；顺序在落库后——卡可见时库里必有行，refresh 才不会误销卡）
        onRequestCard(ConfirmRequest(call.id, call.name, summary, argsJson))
        // ③ 挂起等（仅前台计时；见类 KDoc 为什么不用 withTimeoutOrNull）
        val verdict = awaitApproval()
        val p = pending
        pending = null
        deferred = null
        return when (verdict) {
            null -> {
                store.clear(sessionId)
                Outcome.TimedOut(RunRules.CONFIRM_TIMEOUT_NOTE)
            }

            false -> {
                store.clear(sessionId)
                // 用户点「停止」导致的作废不回灌「用户拒绝」（那是误述）⇒ 单独结局
                if (abandoned) {
                    Outcome.Abandoned
                } else {
                    Outcome.Rejected(RunRules.rejectNote(p?.summary ?: summary, note))
                }
            }

            true -> {
                val result = pipeline.executeApproved(call, spillWriter)
                store.clear(sessionId) // 批准/拒绝/超时后清空（§3.3）；执行后立刻清，窗口毫秒级
                Outcome.Approved(p?.call ?: call, result)
            }
        }
    }

    /**
     * 用户点批准/拒绝（UI 线程调）。
     * callId 不匹配 = 陈旧点击（上个会话/上张卡的残留）⇒ **丢弃**，不动任何状态（J4）。
     */
    fun resumeWithApproval(callId: String, approved: Boolean, note: String?) {
        val p = pending
        if (p == null || p.call.id != callId) {
            log("抱朴确认：陈旧/无效点击丢弃（来路 $callId，当前 ${p?.call?.id ?: "无"}）")
            return
        }
        this.note = note
        deferred?.complete(approved)
    }

    /** 用户点「停止」：挂起的确认作废（不执行；循环随即收尾 ABORTED）。 */
    fun abandon() {
        if (pending == null) return
        abandoned = true
        deferred?.complete(false)
    }

    /**
     * 重启后批准落库的 pending（T3）：进程被杀过 ⇒ 内存里没有挂起可恢复，
     * 从库里取出参数**直接执行**（没有活人可等，但批准的意思还在库里）。
     *
     * callId 合成 `restored-<pending_at>`（稳定：同一行 pending 永远同一 id）。
     */
    fun restoredExecute(
        sessionId: Long,
        row: PendingRow,
        spillWriter: (fileName: String, text: String) -> SpillResult?,
    ): GuardPipeline.Result {
        val args = runCatching { JSONObject(row.argsJson) }.getOrNull() ?: JSONObject()
        val call = ToolCall("restored-${row.at}", row.tool, args)
        val result = pipeline.executeApproved(call, spillWriter)
        store.clear(sessionId)
        return result
    }

    /** 轮询等批准；@return true=批准 false=拒绝 null=超时（仅前台计时） */
    private suspend fun awaitApproval(): Boolean? {
        while (true) {
            val d = deferred ?: return null
            if (d.isCompleted) return d.await()
            if (RunRules.confirmExpired(foregroundElapsedMs())) return null
            delay(RunRules.CONFIRM_POLL_MS)
        }
    }
}
