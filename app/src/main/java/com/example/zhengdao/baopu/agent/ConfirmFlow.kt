// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 2026-10-17（抱朴刀 3 · A 段）：从 `RunEngine.kt` 拆出——派工单 §9 行数纪律
// 「单文件新增 ≤ 400 行；超了拆文件」。内核（无 Android/Compose/SQLite，
// [PendingStore] 是注入的接口）与引擎壳（[RunEngine] 只做接线）由此彻底分离。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.ConfirmAuditEntry
import com.example.zhengdao.baopu.ConfirmGrants
import com.example.zhengdao.baopu.ConfirmKeys
import com.example.zhengdao.baopu.ConfirmRisk
import com.example.zhengdao.baopu.ConfirmScope
import com.example.zhengdao.baopu.ConfirmScopeChoice
import com.example.zhengdao.baopu.PendingRow
import com.example.zhengdao.baopu.PendingStore
import com.example.zhengdao.core.RunLog
import java.io.File
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
 *
 * 第一百七十一批新增三格（全带缺省 ⇒ 存量构造点含恢复卡一个字不改）：
 * - [mergeCount]：同 key 等待中又来的相同请求数（1 = 首张；>1 时卡面显示
 *   「已合并 N 次」，不重复弹卡——派工单 §1 第 37 行）；
 * - [scopeOptions]：这张卡能摆的范围（高风险 ⇒ 只剩 [ConfirmScope.ONLY_ONCE]）；
 * - [highRisk]：写盘/删除/终端/安装/扣费类 ⇒ 卡面明示「每次都会询问」。
 */
data class ConfirmRequest(
    val callId: String,
    val tool: String,
    val summary: String,
    val argsJson: String,
    val mergeCount: Int = 1,
    val scopeOptions: List<ConfirmScope> = ConfirmRisk.scopesFor(tool),
    val highRisk: Boolean = ConfirmRisk.isHighRisk(tool),
    /**
     * 第二百二十三批：**为什么问你**——风险矩阵主判据的一句人话（≤30 字单行）。
     *
     * 缺省 null ⇒ 存量构造点（含重启恢复卡）一个字不改；它占的是 220 影响提示那**一行**
     * 的位置（不新增卡片、不加按钮），合成在 `RiskTell.cardLine`。重启恢复卡没有判定
     * 可重放（pending 只存了参数），所以恢复出来的卡上这一行本来就是空——如实留空，
     * 不许现编一句。
     */
    val reasonLine: String? = null,
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
 *
 * 第一百七十一批（确认卡降噪 v2）在三处加语义，既有路径**逐字不变**：
 * 1. [enter] 开头：命中有效范围授权 ⇒ 不问、直接执行（五段一段不少）；
 * 2. [enter] 的单活跃门：同 key 等待中 ⇒ 合并计数、不重复弹卡；
 * 3. [resumeWithApproval]：批准时按用户选的范围记账（高风险强制「仅这一次」）。
 *
 * **第二百四十九批 §8：R2「逐次人批」口径改订**（依据
 * `_zd-private/docs/派工单-2026-10-27-第二百四十九批-先斩后奏与追认窗口.md` §8 ＋
 * `zhengdao/证道设计报告/任务级明确授权-完整方案-2026-10-28.md` §3.1）。
 *
 * 改订的就是下面这一条（原文在 [com.example.zhengdao.baopu.agent.GuardPipeline] 五段 KDoc 第 ③ 段）：
 * - **旧**：`R2` **必问**——没有任何例外，每一次都弹卡；
 * - **新**：`R2` 仍是人批制，但「人批」多了**任务级明确授权**这一种来源：它在
 *   [com.example.zhengdao.baopu.authz.TaskGrant] 的快照范围内（列得出的对象清单、
 *   新建/改正文两种动作、四个上限、R2 封顶、进程重启即失效）替用户沿用那一次批准。
 *   快照之外的对象、受保护对象、矩阵硬拒、R3、影子三态不成立、额度用尽——**一处都不覆盖**，
 *   逐次照问或直接停（`authz/TaskGrantGate` 的六步）。
 *
 * 为什么这条改订只动「逐次」这三个字：把 R2 整体降成 R1 是另一回事（那等于说「R2 不用问了」）；
 * 本批一个字都没动 R2 的分档与矩阵，变的只是「谁算人批的那一次」。
 * 判定不在本文件里做：六步在 [com.example.zhengdao.baopu.authz.TaskGrantGate]，
 * 本流程拿到的仍是「问／不问」这一个结论。
 */
internal class ConfirmFlow(
    private val pipeline: GuardPipeline,
    private val store: PendingStore,
    private val foregroundElapsedMs: () -> Long = { 0L },
    private val onPersistPending: (sessionId: Long, step: Int, tool: String, argsJson: String, at: Long) -> Unit = { _, _, _, _, _ -> },
    private val onRequestCard: (ConfirmRequest) -> Unit = {},
    private val log: (String) -> Unit = { RunLog.log(it) },
    /**
     * 工作区根（第一百四十九批 组5）：确认卡摘要把绝对路径剥成相对显示。
     * 注入函数而不是 File —— 引擎的根本身是 `() -> File`（换工作区时读数得跟着变）；
     * 排在参数表**最后**且默认 `{ null }` ⇒ 旧调用点（含测试）一个字不改、行为逐字节不变。
     */
    private val projectRoot: () -> File? = { null },
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
        /** 第一百七十一批：decision key（工具 + 规范化参数；作用域不进键，见 [ConfirmKeys]）。 */
        val key: String,
        /** 第一百七十一批：同 key 等待中合并进来的次数（1 = 首张）。 */
        val mergeCount: Int = 1,
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
     * 第一百七十一批在入口加两条**短路**（都在 pending 落库/出卡之前）：
     * 1. **范围授权命中**（同 key 且在有效作用域内）⇒ 不落库、不出卡、不挂起，
     *    直接走 [GuardPipeline.executeApproved] 执行——「同 key 同作用域只问一次」。
     *    高风险工具在这里永远进不来（[ConfirmGrants.covers] 纵深拒）；
     * 2. **同 key 已在等待** ⇒ 不重复弹卡：合并计数 +1、重发一次卡事件
     *    （UI 刷新「已合并 N 次」）、返回 [Outcome.Ignored]（引擎跳过这个重复调用，
     *    与单活跃门丢弃第二个的同款收场——同一个请求执行一次即够）。
     *
     * @param spillWriter spill 落盘注入（引擎给：落工作区内 `.baopu-spill/`）
     * @param reasonLine 第二百二十三批：矩阵主判据的一句人话（卡面上那一行；null ⇒ 不摆）
     */
    suspend fun enter(
        sessionId: Long,
        step: Int,
        call: ToolCall,
        reasonLine: String? = null,
        spillWriter: (fileName: String, text: String) -> SpillResult?,
    ): Outcome {
        val key = ConfirmKeys.of(call.name, call.arguments)
        // ① 范围授权命中 ⇒ 免卡直跑（批准执行的同一条路，一段不少）
        if (ConfirmGrants.covers(sessionId, key, call.name)) {
            log("抱朴确认：${call.name} 命中范围授权（key=$key），本作用域内不再询问，直接执行")
            val result = pipeline.executeApproved(call, spillWriter)
            return Outcome.Approved(call, result)
        }
        val waiting = pending
        if (waiting != null) {
            if (waiting.key == key) {
                // ② 同 key 等待中 ⇒ 合并显示次数，不弹第二张卡
                val merged = waiting.mergeCount + 1
                pending = waiting.copy(mergeCount = merged)
                onRequestCard(
                    ConfirmRequest(
                        callId = waiting.call.id,
                        tool = waiting.call.name,
                        summary = waiting.summary,
                        argsJson = waiting.argsJson,
                        mergeCount = merged,
                        scopeOptions = ConfirmRisk.scopesFor(waiting.call.name),
                        highRisk = ConfirmRisk.isHighRisk(waiting.call.name),
                        reasonLine = reasonLine,
                    ),
                )
                log("抱朴确认：同 key 等待中（${call.name}），合并显示第 $merged 次，不重复弹卡")
                return Outcome.Ignored
            }
            log("抱朴确认：已有 pending 在等（${waiting.call.name}），丢弃第二个（${call.name}）")
            return Outcome.Ignored
        }
        val argsJson = RunRules.capArgsJson(call.arguments.toString())
        val summary = RunRules.confirmSummary(call.name, call.arguments, projectRoot())
        pending = Pending(sessionId, step, call, summary, argsJson, key)
        deferred = CompletableDeferred()
        note = null
        abandoned = false
        val at = System.currentTimeMillis()
        // ① 落库（引擎回调：run_log 的 AwaitConfirm 行 + session pending 三列**同事务**）
        onPersistPending(sessionId, step, call.name, argsJson, at)
        // ② 出卡（UI 事件；顺序在落库后——卡可见时库里必有行，refresh 才不会误销卡）
        onRequestCard(
            ConfirmRequest(
                call.id,
                call.name,
                summary,
                argsJson,
                mergeCount = 1,
                scopeOptions = ConfirmRisk.scopesFor(call.name),
                highRisk = ConfirmRisk.isHighRisk(call.name),
                reasonLine = reasonLine,
            ),
        )
        // ③ 挂起等（仅前台计时；见类 KDoc 为什么不用 withTimeoutOrNull）
        val verdict = awaitApproval()
        val p = pending
        pending = null
        deferred = null
        return when (verdict) {
            null -> {
                store.clear(sessionId)
                ConfirmScopeChoice.discard(call.id) // 超时收走 ⇒ 没消费的范围选择作废
                Outcome.TimedOut(RunRules.CONFIRM_TIMEOUT_NOTE)
            }

            false -> {
                store.clear(sessionId)
                ConfirmScopeChoice.discard(call.id) // 拒绝同理（批准才会消费）
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
     *
     * 第一百七十一批：[scope] = 用户在卡面上选的范围（null = 没选 = 仅这一次）。
     * 记账三条纪律：
     * 1. 高风险/扣费类**强制**「仅这一次」——卡面不给范围按钮，这里再摁一次
     *    （负例「高风险被范围授权绕过」的两道闸，这是第一道），并留审计；
     * 2. 「仅这一次」**不入账**（没有授权可继承，下次照问）；
     * 3. 旁路（[ConfirmScopeChoice]）里同 callId 的选择优先于参数——生产走旁路，
     *    直调/测试走参数；两个都有时以参数为准（显式大于隐式）。
     */
    fun resumeWithApproval(callId: String, approved: Boolean, note: String?, scope: ConfirmScope? = null) {
        val p = pending
        if (p == null || p.call.id != callId) {
            log("抱朴确认：陈旧/无效点击丢弃（来路 $callId，当前 ${p?.call?.id ?: "无"}）")
            return
        }
        if (approved) {
            val picked = scope ?: ConfirmScopeChoice.consume(callId) ?: ConfirmScope.ONLY_ONCE
            val effective = if (ConfirmRisk.isHighRisk(p.call.name)) {
                if (picked != ConfirmScope.ONLY_ONCE) {
                    // 负例靶心：高风险要了范围 ⇒ 摁回仅这一次并留痕（不许静默）
                    ConfirmGrants.auditDowngraded(p.sessionId, p.key, p.call.name)
                    log("抱朴确认：${p.call.name} 是高风险动作，范围授权被摁回「仅这一次」")
                }
                ConfirmScope.ONLY_ONCE
            } else {
                picked
            }
            if (effective != ConfirmScope.ONLY_ONCE) {
                ConfirmGrants.grant(
                    sessionId = p.sessionId,
                    key = p.key,
                    tool = p.call.name,
                    scope = effective,
                )
                log("抱朴确认：用户授权「${effective.label}」（${p.call.name}），同 key 在本作用域内不再询问")
            }
        }
        this.note = note
        deferred?.complete(approved)
    }

    /** 用户点「停止」：挂起的确认作废（不执行；循环随即收尾 ABORTED）。 */
    fun abandon() {
        if (pending == null) return
        abandoned = true
        pending?.call?.id?.let { ConfirmScopeChoice.discard(it) }
        deferred?.complete(false)
    }

    // ── 第一百七十一批：授权账本的对外动作（接线见交付报告 §接线）─────────────

    /** 运行结束 ⇒ 清 THIS_RUN/THIS_SKILL 授权（引擎 run 循环收尾处调）。 */
    fun endRun(sessionId: Long) = ConfirmGrants.endRun(sessionId)

    /** 会话结束/切换 ⇒ 清这个会话的全部授权（控制器切会话处调）。 */
    fun endSession(sessionId: Long) = ConfirmGrants.endSession(sessionId)

    /** 撤销授权（[key] 为空 ⇒ 全撤）；返回撤掉条数（设置页一键撤销用）。 */
    fun revokeGrants(sessionId: Long, key: String? = null): Int = ConfirmGrants.revoke(sessionId, key)

    /** 当前生效的授权清单（设置页/测试读数）。 */
    fun activeGrants(sessionId: Long): List<ConfirmAuditEntry> =
        ConfirmGrants.activeGrants(sessionId)

    /** 授权审计流水（「谁授权/范围/次数」三件事都在这）。 */
    fun grantAudit(sessionId: Long): List<ConfirmAuditEntry> =
        ConfirmGrants.auditFor(sessionId)

    /**
     * 重启后批准落库的 pending（T3）：进程被杀过 ⇒ 内存里没有挂起可恢复，
     * 从库里取出参数**直接执行**（没有活人可等，但批准的意思还在库里）。
     *
     * callId 合成 `restored-<pending_at>`（稳定：同一行 pending 永远同一 id）。
     *
     * 第一百七十一批：恢复卡上选的范围同样记账（旁路按同一个 callId 取；
     * 高风险摁回「仅这一次」）——否则重启后的第一张卡选了「本会话内」会白选。
     */
    fun restoredExecute(
        sessionId: Long,
        row: PendingRow,
        spillWriter: (fileName: String, text: String) -> SpillResult?,
    ): GuardPipeline.Result {
        val args = runCatching { JSONObject(row.argsJson) }.getOrNull() ?: JSONObject()
        val call = ToolCall("restored-${row.at}", row.tool, args)
        val picked = ConfirmScopeChoice.consume(call.id) ?: ConfirmScope.ONLY_ONCE
        val effective = if (ConfirmRisk.isHighRisk(row.tool)) {
            if (picked != ConfirmScope.ONLY_ONCE) {
                ConfirmGrants.auditDowngraded(sessionId, ConfirmKeys.of(row.tool, args), row.tool)
                log("抱朴确认：${row.tool} 是高风险动作，恢复卡上的范围授权被摁回「仅这一次」")
            }
            ConfirmScope.ONLY_ONCE
        } else {
            picked
        }
        if (effective != ConfirmScope.ONLY_ONCE) {
            ConfirmGrants.grant(sessionId, ConfirmKeys.of(row.tool, args), row.tool, effective)
            log("抱朴确认：恢复卡授权「${effective.label}」（${row.tool}）")
        }
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
