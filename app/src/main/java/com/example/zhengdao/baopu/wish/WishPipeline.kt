// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线：六步编排器（IO 壳）。
// 判据全在纯函数件（WishSpecBuilder/WishRedLine/WishSelfCheck/WishGrayRelease），
// 本件只做状态推进＋落账＋调既有零件。不新造技能引擎/沙盒。
// 试跑失败回③改草稿，连续 3 次停并报人（复用 GapFeedback.HAND_OVER_ATTEMPTS 同口径）。
package com.example.zhengdao.baopu.wish

import com.example.zhengdao.baopu.verify.GapFeedback
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** 试跑读数（从 RehearsalReport 提取；IO 壳填，纯判据消费）。 */
data class RehearsalReading(val ok: Boolean, val realUntouched: Boolean, val note: String)

/** 注入的 IO 操作（生产接既有零件，测试接假件）。 */
data class WishPorts(
    val generateBody: (WishContext) -> String,
    val writeDraft: (WishContext, String) -> Boolean,
    val runRehearsal: (WishContext) -> RehearsalReading,
    val collectCheck: (WishContext) -> SelfCheckVerdict,
    val doPromote: (WishContext) -> Boolean,
    val doRollback: (WishContext, String) -> Boolean,
)

/** 许愿流水线编排器（六步，缺一不算）。 */
internal class WishPipeline(
    private val workspaceRoot: File,
    private val ports: WishPorts,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    companion object {
        /** 交人线：不自定，引既有那把（同 `EvolutionLoop` 触发 c 的口径）。 */
        const val MAX_FAILURES = GapFeedback.HAND_OVER_ATTEMPTS
    }

    private val seq = AtomicLong(0)
    private val contexts = mutableMapOf<String, WishContext>()
    private val grays = mutableMapOf<String, GrayRecord>()

    fun contextOf(wishId: String): WishContext? = contexts[wishId]

    /** ① 许愿 → ② 规格（含候选验收）→ 等确认。红线在生成期就拦。 */
    fun submit(wishText: String, product: WishProduct): WishOutcome {
        val wishId = "wish-${seq.incrementAndGet()}-${clock()}"
        val danger = WishRedLine.refuseReason(wishText)
        if (danger != null) {
            log(wishId, product, WishStage.REJECTED, danger, wishText)
            return WishOutcome.Rejected(danger)
        }
        val spec = WishSpecBuilder.build(wishText, product)
        val ctx = WishContext(wishId, wishText, product, spec, WishStage.SPEC_PENDING)
        contexts[wishId] = ctx
        log(wishId, product, WishStage.SPEC_PENDING, "规格已生成，${spec.candidates.size} 条候选验收", wishText)
        return WishOutcome.AwaitingConfirmation(wishId, spec)
    }

    /** 用户确认规格 → ③草稿 → ④试跑 → ⑤自检 → ⑥灰度。 */
    fun confirm(wishId: String, chosenIds: List<String>): WishOutcome {
        val ctx = contexts[wishId] ?: return WishOutcome.Rejected("未知的许愿 ID：$wishId")
        val spec = ctx.spec ?: return WishOutcome.Rejected("规格丢失")
        val confirmed = WishSpecBuilder.confirm(spec, chosenIds)
        if (!confirmed.confirmed) return WishOutcome.Rejected("未选择验收判据")
        val updated = ctx.copy(spec = confirmed, stage = WishStage.DRAFTING)
        contexts[wishId] = updated
        log(wishId, ctx.product, WishStage.DRAFTING, "用户确认规格，选了 ${chosenIds.size} 条判据", confirmed.acceptance.joinToString { it.id })
        return runSteps(updated)
    }

    /** 灰度期记一次使用；达阈值自动转正，命中回滚条件自动回滚。 */
    fun recordUse(wishId: String): WishOutcome {
        val ctx = contexts[wishId] ?: return WishOutcome.Rejected("未知的许愿 ID")
        val gray = grays[wishId] ?: return WishOutcome.Rejected("不在灰度期")
        val evaluated = WishGrayRelease.evaluate(WishGrayRelease.recordUse(gray), selfCheckPassed = true)
        grays[wishId] = evaluated
        return resolveGray(ctx, evaluated)
    }

    /** ③④ 重试循环：试跑失败回③，连续 MAX_FAILURES 次停并报人。 */
    private fun runSteps(ctx: WishContext): WishOutcome {
        var current = ctx
        repeat(MAX_FAILURES) {
            val body = ports.generateBody(current)
            val danger = WishRedLine.refuseReason(body)
            if (danger != null) {
                log(current, WishStage.REJECTED, danger, body.take(80))
                return WishOutcome.Rejected(danger)
            }
            if (!ports.writeDraft(current, body)) {
                current = current.copy(consecutiveFailures = current.consecutiveFailures + 1)
                return@repeat
            }
            current = current.copy(draftVersion = current.draftVersion + 1, stage = WishStage.REHEARSAL)
            log(current, WishStage.REHEARSAL, "草稿 v${current.draftVersion} 已落，开始试跑", body.take(80))
            val reading = ports.runRehearsal(current)
            log(current, WishStage.REHEARSAL, "试跑 ok=${reading.ok} 真文件不变=${reading.realUntouched}", reading.note)
            if (reading.ok && reading.realUntouched) {
                current = current.copy(rehearsalOk = true, consecutiveFailures = 0, stage = WishStage.SELF_CHECK)
                return afterRehearsal(current)
            }
            current = current.copy(consecutiveFailures = current.consecutiveFailures + 1)
        }
        log(current, WishStage.HALTED, "连续 ${current.consecutiveFailures} 次失败，停并报人", "max=$MAX_FAILURES")
        contexts[current.wishId] = current.copy(stage = WishStage.HALTED)
        return WishOutcome.Halted(current.wishId, current.consecutiveFailures)
    }

    /** ⑤ 自检 → ⑥ 灰度入口。模型给自己发票 ⇒ 拦并记账。 */
    private fun afterRehearsal(ctx: WishContext): WishOutcome {
        val verdict = ports.collectCheck(ctx)
        log(ctx, WishStage.SELF_CHECK, verdictLabel(verdict), verdictDetail(verdict))
        if (verdict is SelfCheckVerdict.ModelPassBlocked) {
            return doRollback(ctx, "模型自评被拦：${verdict.modelClaim}")
        }
        if (verdict is SelfCheckVerdict.Fail) {
            return doRollback(ctx, verdict.reason)
        }
        val gray = GrayRecord(ctx.wishId, ctx.product, uses = 1)
        val evaluated = WishGrayRelease.evaluate(gray, selfCheckPassed = true)
        grays[ctx.wishId] = evaluated
        val updated = ctx.copy(selfCheckOk = true, stage = WishStage.GRAY, grayUses = evaluated.uses)
        contexts[ctx.wishId] = updated
        log(updated, WishStage.GRAY, "自检通过，进灰度（${evaluated.uses}/${WishGrayRelease.PROMOTE_THRESHOLD}）", verdictDetail(verdict))
        return resolveGray(updated, evaluated)
    }

    private fun resolveGray(ctx: WishContext, gray: GrayRecord): WishOutcome = when (gray.state) {
        GrayState.PROMOTED -> {
            ports.doPromote(ctx)
            contexts[ctx.wishId] = ctx.copy(stage = WishStage.PROMOTED)
            log(ctx, WishStage.PROMOTED, "灰度达标，自动转正", "uses=${gray.uses}")
            WishOutcome.Promoted(ctx.wishId, ctx.product)
        }
        GrayState.ROLLED_BACK -> doRollback(ctx, gray.rollbackReason ?: "回滚条件命中")
        GrayState.GRAY -> WishOutcome.InGray(ctx.wishId, ctx.product, gray.uses)
    }

    private fun doRollback(ctx: WishContext, reason: String): WishOutcome {
        ports.doRollback(ctx, reason)
        contexts[ctx.wishId] = ctx.copy(stage = WishStage.ROLLED_BACK)
        log(ctx, WishStage.ROLLED_BACK, "自动回滚：$reason", reason)
        return WishOutcome.RolledBack(ctx.wishId, reason)
    }

    private fun log(ctx: WishContext, stage: WishStage, detail: String, evidence: String) {
        log(ctx.wishId, ctx.product, stage, detail, evidence)
    }

    private fun log(wishId: String, product: WishProduct, stage: WishStage, detail: String, evidence: String) {
        WishLedger.record(workspaceRoot, WishLedgerEntry(wishId, stage, product, detail, evidence, clock()))
    }

    private fun verdictLabel(v: SelfCheckVerdict): String = when (v) {
        is SelfCheckVerdict.Pass -> "自检通过"
        is SelfCheckVerdict.Fail -> "自检不通过"
        is SelfCheckVerdict.ModelPassBlocked -> "模型自评被拦"
    }

    private fun verdictDetail(v: SelfCheckVerdict): String = when (v) {
        is SelfCheckVerdict.Pass -> v.detail
        is SelfCheckVerdict.Fail -> v.reason
        is SelfCheckVerdict.ModelPassBlocked -> v.modelClaim
    }
}