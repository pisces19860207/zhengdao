// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十五批 · 许愿灰度「真实使用」的装配层（IO 壳 + 认领 + 计数 + 落账）。
//
// 分工（照 283/296 的房型）：
//   · 形状与纯判据 → `wish/WishUsage`（零 IO，测试直接喂）；
//   · 落盘 → `wish/WishUsageLedger`（append-only，一次受理一行）；
//   · 本件只做三件：**认领**（归属键 ⇒ 灰度中的 wishId）、**去重**（读回账上的 COUNTED 键）、
//     **计数**（调 `WishPipeline.recordUse`，转正/回滚判定仍归流水线，本件不改判据）。
//
// 为什么在 `agent/` 不在 `wish/`：510 的 `WishSideEffectGateTest` 按源码钉着"许愿包里除技能写口
// 外没有生产写口"，本件是又一处新增写口——与 516 的 WishIOWiring 同一条界线：碰盘的活儿留在被扫
// 的域（`agent/`）里，`wish/` 只留纯判据。
//
// 手动口（派工单）：`manual(...)` 打 `manual=true` ⇒ **只留痕、不计数**。
// 由头是灰度读数可信：有人为了"凑满三次"手点一下，那不叫被用过。
package com.example.zhengdao.baopu.agent

import android.content.Context
import com.example.zhengdao.baopu.BaopuRuntime
import com.example.zhengdao.baopu.wish.WishFlow
import com.example.zhengdao.baopu.wish.WishPipeline
import com.example.zhengdao.baopu.wish.WishUsageEntry
import com.example.zhengdao.baopu.wish.WishUsageLedger
import com.example.zhengdao.baopu.wish.WishUsageOutcome
import com.example.zhengdao.baopu.wish.WishUsageRules
import com.example.zhengdao.baopu.wish.WishUseEvent
import com.example.zhengdao.baopu.wish.WishUseSource
import com.example.zhengdao.baopu.wish.WishUseVerdict
import java.io.File

/** 灰度真实使用的装配（三类来源 + 手动口；全部过同一道去重与计数）。 */
internal object WishUsageWiring {

    /** 最近一次绑定的流水线（给没有 Context 的调用点用，见 [onContentLedger]）。 */
    @Volatile private var bound: Pair<File, WishPipeline>? = null

    /** 工具命中（技能被工具调用真跑成一次）。 */
    fun onToolHit(context: Context, skillName: String, detail: String = ""): WishUsageOutcome =
        recordVia(context, WishUseEvent(WishUseSource.TOOL_HIT, skillName, detail = detail))

    /** 卡片采纳（用户采纳了候选卡；采纳 id 就是幂等键）。 */
    fun onCardAdopted(context: Context, candidateId: String, detail: String = ""): WishUsageOutcome =
        recordVia(context, WishUseEvent(WishUseSource.CARD_ADOPTED, candidateId, detail = detail))

    /** 手动补记（**不计真实**；只落一行痕，供核对"为什么读数没涨"）。 */
    fun manual(context: Context, wishId: String, detail: String = "手动补记"): WishUsageOutcome =
        recordVia(context, WishUseEvent(WishUseSource.TOOL_HIT, wishId, wishId = wishId, manual = true, detail = detail))

    /**
     * 内容进台账（**没有 Context 的那条路**：台账写入在 UI store 里）。
     *
     * 绑定过流水线（同一工作区）⇒ 照常认领＋计数；没绑过 ⇒ 落一行 `UNWIRED`（只留痕不计数）。
     * 为什么不在这里现造流水线：`WishFlow.pipeline` 要 Context，本层拿不到；硬传假 Context
     * 就是给测试开后门，宁可如实记"未装配"。
     */
    fun onContentLedger(workspace: File, fileName: String, detail: String = ""): WishUsageOutcome {
        val pipeline = bound?.takeIf { it.first == workspace }?.second
        return handle(workspace, pipeline, WishUseEvent(WishUseSource.CONTENT_LEDGER, fileName, detail = detail), install = false)
    }

    /** 有 Context 的入口：绑一次流水线（与 WishFlow 同一个单例），再走同一条受理。 */
    private fun recordVia(context: Context, event: WishUseEvent): WishUsageOutcome {
        val appCtx = context.applicationContext
        val workspace = BaopuRuntime.workspaceRoot(appCtx)
        val pipeline = WishFlow.pipeline(appCtx)
        bound = workspace to pipeline
        return handle(workspace, pipeline, event, install = true)
    }

    /**
     * 受理核心（**测试直接调这个**，不碰 Android Context）。
     *
     * @param install false ⇒ 不覆盖已绑定的流水线（无 Context 那条路用）
     */
    internal fun recordInto(workspace: File, pipeline: WishPipeline?, event: WishUseEvent): WishUsageOutcome {
        if (pipeline != null) bound = workspace to pipeline
        return handle(workspace, pipeline, event, install = true)
    }

    private fun handle(
        workspace: File,
        pipeline: WishPipeline?,
        event: WishUseEvent,
        install: Boolean,
    ): WishUsageOutcome {
        if (install && pipeline != null) bound = workspace to pipeline
        val seen = WishUsageLedger.countedKeys(workspace)
        val wishId = event.wishId ?: pipeline?.grayWishFor(event.target)
        val outcome = judge(pipeline, event, wishId, seen)
        WishUsageLedger.append(workspace, entryOf(event, wishId, outcome))
        return outcome
    }

    /** 五种结论的判定（顺序即优先级：手动 → 空键 → 幂等 → 未装配 → 无主 → 计数）。 */
    private fun judge(
        pipeline: WishPipeline?,
        event: WishUseEvent,
        wishId: String?,
        seen: Set<String>,
    ): WishUsageOutcome {
        val uses = wishId?.let { pipeline?.usesOf(it) } ?: 0
        return when {
            event.manual -> WishUsageOutcome(WishUseVerdict.MANUAL, wishId, uses, "手动补记：只留痕，不计真实")
            !WishUsageRules.accepts(event) ->
                WishUsageOutcome(WishUseVerdict.UNOWNED, wishId, uses, "归属键为空：不认（空键会把无主事件去重成一条）")
            WishUsageRules.alreadyCounted(event, seen) ->
                WishUsageOutcome(WishUseVerdict.DUPLICATE, wishId, uses, "同一件事已算过（幂等：${event.useKey}）")
            // 先判"有没有人算"：没有流水线时既认领不了也计不了，比"对不上归属"更接近真相
            pipeline == null ->
                WishUsageOutcome(WishUseVerdict.UNWIRED, wishId, 0, "流水线未装配：只留痕，不计数")
            wishId == null ->
                WishUsageOutcome(WishUseVerdict.UNOWNED, null, 0, "不在灰度期或归属键对不上（${event.target}）")
            else -> count(pipeline, wishId, event, uses)
        }
    }

    /** 真的算一次：转正/回滚判定仍归流水线（本件不碰判据）。 */
    private fun count(pipeline: WishPipeline, wishId: String, event: WishUseEvent, before: Int): WishUsageOutcome {
        val result = pipeline.recordUse(wishId)
        val after = pipeline.usesOf(wishId) ?: before
        val note = "计数 ${before}→${after}｜${event.source.cn}｜${result.javaClass.simpleName}"
        return WishUsageOutcome(WishUseVerdict.COUNTED, wishId, after, note)
    }

    private fun entryOf(event: WishUseEvent, wishId: String?, outcome: WishUsageOutcome): WishUsageEntry =
        WishUsageEntry(
            atMs = System.currentTimeMillis(),
            source = event.source,
            target = event.target.trim(),
            wishId = wishId,
            manual = event.manual,
            verdict = outcome.verdict,
            uses = outcome.uses,
            note = if (event.detail.isBlank()) outcome.note else "${outcome.note}｜${event.detail}",
        )
}
