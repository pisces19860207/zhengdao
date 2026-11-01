// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-11-01-第三百四十三批-军令状范围变更 §一（三种变更三条正路）＋
// §二（五条硬规矩）＋ §三（与 312/御案/账本的关系）＋ §判据 ①–⑥ ＋ §负例（各红）。
//
// 本文件是**判定层**：零 Android / 零 IO / 零时钟（atMs 一律由调用方传入），
// 房型照 `PlanPledge.kt`（312）与 `PlanContract.kt`（242），JVM 直测。
//
// 治的是**用户中途改需求时没有正当动作**：312 的军令状只有两态——照做，或漏项即
// 未完成。现实里她会说「再加一项」「这条改成这样」「这条不要了」，这三件事在 312
// 里都无路可走：要么硬做（计划被悄悄改），要么拒（她被卡住）。本层给每条路一个
// 显式动作，并把「悄悄」二字从制度里拿掉（判据①②③）。
//
// 三条正路（派工单 §一，各红一条负例）：
// 1. **追加** `add` ⇒ 问用户；预算内的追加可走自决单，超预算**必须问**（规矩④，
//    负例⑤「超预算自己认」）；新项进表、版本 +1、账上记「谁追加的」（by）。
// 2. **修改** `revise` ⇒ **必须重签**：判据变了等于重新立约，自决单无权改标准
//    （312 追加③「不许改标准」的同一条腿）；旧判据留在旧版快照里，不删（规矩①）。
// 3. **删除** `drop` ⇒ 问用户 ⇒ 该项记 `DROPPED(原因)`。DROPPED 与 ABANDONED
//    分列：前者是「不要了」，后者是「做不完」（负例②把它们混记 ⇒ 红）。
//
// 五条硬规矩落点（派工单 §二）：
// - ①版本化：变更只增版（v_n → v_n+1），旧版快照一律不删——[owedTexts] 的底册
//   正是「历版出现过的项」，删了旧版等于自己把把柄抹掉，本层不给这条路；
// - ②状态继承：未变项保留 DONE/ABANDONED（[AmendState.keepEndings]），被修改项
//   **重置为待做**（[AmendState.resetToTodo]，判据变了旧结论不算）；
// - ③禁止静默缩表：历版里有、现役里没有、既没 DROPPED 也没被 revise 顶替 ⇒ 收口
//   时按缺口报红（[shrinkGaps]）——这一条与 312 的结项门同轴，是它的下半张脸；
// - ④预算重算：追加要重算时间/打扰两条预算，超了必须问用户（[AmendBudget.overByAdd]）；
// - ⑤已完成项不许因变更作废：本层任何一路都不清空未变项的结局（负例④）。
//
// 两条边界：
// - 没立状 ⇒ [decide] 不回话（[AmendDecision.Ignored]），计划改动照 140 的确认卡走，
//   今天的执行路径逐字节不变（与 312「军令状是封底不是门槛」同口径）；
// - 本层只管**判定与账的形状**；弹卡与落账在 `PlanPledgeBoard` ＋ `PlanPledgeAmendCards`。
//
// 预算的两个刻度是**本层定的**（派工单没给数）：时间轴用项数（计划卡本身 12 条封顶，
// 立状批准的项数 + 2 项余量算「补票」），打扰轴用「一个状累计问几次」（3，与御案
// 每日 ≤3 张同档，见 `inspect/OpinionBudget.kt`）。两个数都在交付报告里请用户拍板。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndingDeclaration
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeEntry
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeSnapshot
import com.example.zhengdao.baopu.plan.PlanPledgeRules.ResignChannel
import com.example.zhengdao.baopu.plan.PlanPledgeRules.SettleGap
import org.json.JSONArray
import org.json.JSONObject

internal object PlanPledgeAmendRules {

    /** `update_plan` 参数里的第四件：范围变更（与 pledge/closeout/resign 同一层）。 */
    const val ARG_AMEND = "amend"

    /** 变更账行的事件键（稳定值，改了老账读不出来）。 */
    const val EVENT_AMEND = "amend"

    /** 变更账的账名——与业务账（未办成单／决策记录）分表（派工单 §三＋判据⑥）。 */
    const val LEDGER_FILE = "计划军令状变更.jsonl"

    /** 「不要了」的状态词。与 312 的 `abandoned`（做不完）分列，一个字不许混。 */
    const val STATE_DROPPED = "dropped"

    /** 时间预算的余量：立状之后两项以内的追加算「补票」，可以在预算内自决。 */
    const val SELF_ADD_HEADROOM = 2

    /** 打扰预算：一个状累计问她三次；用满之后再动计划就是超预算——超了不许自决，只能当面问。 */
    const val MAX_ASK_ROUNDS = 3

    /** 三种变更（派工单 §一的三行；闭集，别的写法不收）。 */
    enum class AmendKind(val wire: String, val cn: String) {
        ADD("add", "追加"),
        REVISE("revise", "修改"),
        DROP("drop", "删除"),
        ;

        companion object {
            fun ofWire(raw: String): AmendKind? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
        }
    }

    /** 谁提的这次变更（派工单 §一「记谁追加的」；批准走 [ResignChannel]，两码事）。 */
    enum class AmendBy(val wire: String, val cn: String) {
        USER("user", "用户"),
        MODEL("model", "模型"),
        ;

        companion object {
            fun ofWire(raw: String): AmendBy? = entries.firstOrNull { it.wire == raw.trim().lowercase() }
        }
    }

    /**
     * 一条变更操作。[text] 对三种意义不同：add＝新项原文，revise＝被改那项的原文，
     * drop＝不要那项的原文；[to]／[accept] 只有 revise 与 add 用得上。
     */
    data class AmendOp(
        val kind: AmendKind,
        val text: String,
        val to: String = "",
        val accept: String = "",
        /** 为什么不要这项／为什么改成这样；空 ⇒ 用本轮的 reason 兜底。 */
        val reason: String = "",
    )

    /** 一次删除落成的记录（判据③的下半身：删项必须有这一行，否则收口按缺口红）。 */
    data class DropRecord(
        val text: String,
        val reason: String,
        /** 从第几版起不再有这项（写在版次上，事后能回答「当初约的是什么」。） */
        val version: Int,
        val by: AmendBy,
        val channel: ResignChannel,
        val atMs: Long,
    ) {
        val wire: String get() = STATE_DROPPED
    }

    /** 变更的账：谁提的、谁批的、改了什么、超没超预算。 */
    data class AmendChange(
        val kinds: List<AmendKind>,
        val by: AmendBy,
        val reason: String,
        val ops: List<AmendOp>,
        val fromVersion: Int,
        val budget: AmendBudget,
        /** 超了哪条预算（空表＝没超）；人批路径照样落地，但要留在账上。 */
        val overBudget: List<String>,
    ) {
        /** 这一轮要不要重签：修改判据＝重新立约，删除与追加要她点头——都要她签名。 */
        val needsResign: Boolean get() = kinds.any { it == AmendKind.REVISE || it == AmendKind.DROP }
    }

    /** 变更后每项状态的落点（规矩②⑤）。 */
    data class AmendState(
        /** 未变项保留的结局（含 DONE／ABANDONED）——变更不作废已完成项。 */
        val keepEndings: Map<String, EndingDeclaration>,
        /** 被修改项的新文字：判据变了 ⇒ 回待做，旧结论不算（判据②）。 */
        val resetToTodo: List<String>,
        /** 本轮记下的 DROPPED（不是 ABANDONED）。 */
        val dropped: List<DropRecord>,
    )

    /** 落地的形状：新版快照＋状态迁移。 */
    data class Amended(val snapshot: PledgeSnapshot, val state: AmendState)

    /** 时间／打扰两条预算轴（规矩④）。本层没有时间预估字段，用项数当时间的刻度。 */
    data class AmendBudget(
        val itemsAtFreeze: Int,
        val maxItems: Int,
        val asksUsed: Int,
        val maxAsks: Int,
    ) {
        /** 追加 [added] 项、落到共 [totalAfter] 项 ⇒ 超了哪条预算（空表＝都在预算内）。 */
        fun overByAdd(added: Int, totalAfter: Int): List<String> = buildList {
            if (totalAfter > maxItems) {
                add("时间预算：追加后共 $totalAfter 项，超过立状批准的 $maxItems 项")
            }
            if (asksUsed + added > maxAsks) {
                add("打扰预算：这几项预计累计要问 ${asksUsed + added} 次，超过一个状的 $maxAsks 次")
            }
        }
    }

    /** 预算从一个状的哪两个数算出来（立状项数＋已问次数）。 */
    fun budgetOf(itemsAtFreeze: Int, asksUsed: Int): AmendBudget = AmendBudget(
        itemsAtFreeze = itemsAtFreeze,
        maxItems = minOf(itemsAtFreeze + SELF_ADD_HEADROOM, PlanRules.MAX_ITEMS),
        asksUsed = asksUsed,
        maxAsks = MAX_ASK_ROUNDS,
    )

    /**
     * 判定要看的现状（板子供）。[itemsAtFreeze] 是她签名那一版（v1）的项数。
     *
     * [owed] 是「历版出现过、至今没有下落」的项（[owedTexts]）——312 的重签老路摘项
     * 不记 DROPPED，这些项正欠着那张凭据；允许对它们补记 drop（补记账，不改表）。
     */
    data class AmendContext(
        val snapshot: PledgeSnapshot,
        val itemsAtFreeze: Int,
        val asksUsed: Int,
        val endings: Map<String, EndingDeclaration>,
        val dropped: Set<String>,
        val superseded: Set<String>,
        val owed: Set<String> = emptySet(),
    )

    // ── 解析（写法门：闭集、必填、按位说得清）───────────────────────────────

    /** [parseAmend] 的结局。 */
    sealed interface AmendParse {
        data class Ok(val by: AmendBy, val reason: String, val ops: List<AmendOp>, val notices: List<String>) : AmendParse
        data class Absent(val reason: String) : AmendParse
        data class Error(val message: String) : AmendParse
    }

    /**
     * 从 `update_plan` 参数里读范围变更。
     *
     * 为什么 `reason` 三种变更都必填：变更账要回答「当初为什么改」——312 对重签
     * 立的同一条规矩（负例「重签不写原因」）。写法不合格一律 [AmendParse.Error]
     * 原文回灌，不静默降级成「没变更」。
     */
    fun parseAmend(args: JSONObject): AmendParse {
        val raw = args.opt(ARG_AMEND)
        if (raw == null || raw == JSONObject.NULL) {
            return AmendParse.Absent("这次没有申报范围变更：计划保持现役那一版。")
        }
        val obj = raw as? JSONObject
            ?: return AmendParse.Error("参数「$ARG_AMEND」不是对象：要 {\"by\": \"user|model\", \"reason\": \"…\", \"ops\": [{kind, text, to?, accept?, reason?}]}。")
        val by = AmendBy.ofWire(obj.optString("by"))
        val notices = mutableListOf<String>()
        if (by == null) notices += "amend.by 只认 user / model，这次按「model」（模型自己提的）记。"
        val reason = obj.optString("reason").trim()
        if (reason.isEmpty()) {
            return AmendParse.Error(
                "amend 缺「reason」：为什么改这份计划，卡面与变更账上都要这一句。",
            )
        }
        val arr = obj.optJSONArray("ops")
        if (arr == null || arr.length() == 0) {
            return AmendParse.Error("amend.ops 是空的：这次动的是哪几项，要逐条列出来。")
        }
        val ops = mutableListOf<AmendOp>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i)
                ?: return AmendParse.Error("amend 第 ${i + 1} 条不是对象：要 {kind, text, to?/accept?/reason?}。")
            val kind = AmendKind.ofWire(o.optString("kind"))
                ?: return AmendParse.Error(
                    "amend 第 ${i + 1} 条 kind「${o.optString("kind")}」只认 add / revise / drop。",
                )
            val text = o.optString("text").trim().take(PlanRules.MAX_TEXT_CHARS)
            if (text.isEmpty()) return AmendParse.Error("amend 第 ${i + 1} 条缺「text」：按计划条目原文写。")
            val to = o.optString("to").trim().take(PlanRules.MAX_TEXT_CHARS)
            val accept = o.optString("accept").trim().take(PlanPledgeRules.MAX_ACCEPT_CHARS)
            if (kind == AmendKind.REVISE && to.isEmpty() && accept.isEmpty()) {
                return AmendParse.Error(
                    "amend 第 ${i + 1} 条是 revise 却没给 to 也没给 accept：改判据要写清改成什么。",
                )
            }
            ops += AmendOp(kind, text, to, accept, o.optString("reason").trim().take(PlanPledgeRules.MAX_WHY_CHARS))
        }
        val kinds = ops.map { it.kind }.distinct()
        if (kinds.size > 1) {
            notices += "一轮里混了 ${kinds.size} 种变更（${kinds.joinToString("、") { it.cn }}）：整轮按最严的那条走——要她点头。"
        }
        return AmendParse.Ok(
            by = by ?: AmendBy.MODEL,
            reason = reason.take(PlanPledgeRules.MAX_WHY_CHARS),
            ops = ops,
            notices = notices,
        )
    }

    // ── 判定（三条正路各走各的门）───────────────────────────────────────────

    /** [decide] 的结局。 */
    sealed interface AmendDecision {
        /** 没立状 ⇒ 本层不回话：计划改动照 140 的确认卡走，不编读数也不拦路。 */
        data class Ignored(val reason: String) : AmendDecision

        /** 要她点头（追加要确认／改判据要重签／删项要确认／超预算必须问）。 */
        data class NeedUser(val change: AmendChange, val why: String) : AmendDecision

        /** 批准到手（人批）或预算内的自决单 ⇒ 可以落板。 */
        data class Apply(val change: AmendChange, val outcome: Amended, val channel: ResignChannel) : AmendDecision

        data class Reject(val message: String) : AmendDecision
    }

    /**
     * 判一次范围变更。
     *
     * 顺序（先写法、后预算、再批准）：
     * 1. ops 对不上现役快照（改一项状上没有的、加一项已有的、同一项又改又删）⇒ 拒；
     * 2. 申报的变更与这一版计划不一致（[incoming] 给了才核）⇒ 拒——防「口头说改一处、
     *    表里偷偷改另一处」，这是判据③在落库前的那一半；
     * 3. 还没拿到批准 ⇒ [AmendDecision.NeedUser]（三种变更默认都要问，规矩④超预算更要问）；
     * 4. 自决单只认预算内的追加：改判据＝重新立约、删项＝她的权利不是它的；
     *    超预算的追加「自己认了」正是负例⑤；
     * 5. 人批到手 ⇒ 落地：版本 +1、旧版留账、未变项保留结局、被改项回待做、删项记 DROPPED。
     */
    fun decide(
        ctx: AmendContext,
        ops: List<AmendOp>,
        by: AmendBy,
        reason: String,
        incoming: List<PlanItem>?,
        channel: ResignChannel?,
        atMs: Long,
    ): AmendDecision {
        shapeCheck(ctx, ops)?.let { return AmendDecision.Reject(it) }
        val next = nextEntries(ctx.snapshot.entries, ops)
        val adds = ops.count { it.kind == AmendKind.ADD }
        val budget = budgetOf(ctx.itemsAtFreeze, ctx.asksUsed)
        val over = if (adds > 0) budget.overByAdd(adds, next.size) else emptyList()
        val change = AmendChange(
            kinds = ops.map { it.kind }.distinct(),
            by = by,
            reason = reason,
            ops = ops,
            fromVersion = ctx.snapshot.version,
            budget = budget,
            overBudget = over,
        )
        incoming?.let { plan -> mismatchWithPlan(next, plan)?.let { return AmendDecision.Reject(it) } }
        val nextVersion = ctx.snapshot.version + 1
        if (channel == null) return AmendDecision.NeedUser(change, why = whyAsk(change))
        if (channel == ResignChannel.SELF) {
            if (change.needsResign) return AmendDecision.Reject(selfReject(change))
            if (over.isNotEmpty()) return AmendDecision.Reject(overBudgetReject(over, nextVersion))
        }
        val items = next.map { PlanItem(it.text, PlanRules.STATUS_TODO) }
        val accepts = next.map { it.accept }
        val snapshot = PlanPledgeRules.snapshotOf(items, accepts, nextVersion, ctx.snapshot.goal, atMs, ctx.snapshot.sessionId)
        val state = stateOf(ctx, ops, snapshot.texts, by, reason, channel, nextVersion, atMs)
        return AmendDecision.Apply(change, Amended(snapshot, state), channel)
    }

    /** 写法对不对得上现役那一版（对不上的变更不是「变更」，是凭空改约）。 */
    private fun shapeCheck(ctx: AmendContext, ops: List<AmendOp>): String? {
        val texts = ctx.snapshot.texts
        val onSheet = texts.toSet()
        val dup = ops.map { it.text + "|" + it.kind.wire }.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (dup.isNotEmpty()) return "amend 里有重复条目（${dup.first()}）：一项同一轮只动一次。"
        val revised = ops.filter { it.kind == AmendKind.REVISE }.map { it.text }.toSet()
        val dropped = ops.filter { it.kind == AmendKind.DROP }.map { it.text }.toSet()
        val both = revised.intersect(dropped)
        if (both.isNotEmpty()) return "「${both.first()}」既要改又要删：先定这一项到底还要不要。"
        ops.forEach { op ->
            when (op.kind) {
                AmendKind.ADD -> if (op.text in onSheet || op.text in ctx.superseded) {
                    return "「${op.text}」本来就在第 ${ctx.snapshot.version} 版上，不是追加。"
                }
                AmendKind.REVISE -> if (op.text !in onSheet) {
                    return "要改的「${op.text}」不在第 ${ctx.snapshot.version} 版上：改判据要先说出改哪一项。"
                }
                AmendKind.DROP -> when {
                    op.text in ctx.dropped -> return "「${op.text}」已经记过 DROPPED：不要了只记一次。"
                    // 补记账：上一版被悄悄摘掉的项（312 的重签老路没有 DROPPED 这个概念），
                    // 至今欠着那张凭据——补记它，表不动，只补「不要了＋原因＋从第几版起」
                    op.text !in onSheet && op.text !in ctx.owed ->
                        return "要删的「${op.text}」不在第 ${ctx.snapshot.version} 版上，也不在历版里：删不了没约过的事。"
                    else -> Unit
                }
            }
        }
        // 改完之后重名 ⇒ 结项按原文寻址会串账
        val after = nextEntries(texts.map { PledgeEntry(it, it) }, ops).map { it.text }
        val clash = after.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        if (clash.isNotEmpty()) return "变更后有两项同名（「${clash.first()}」）：结项按条目原文对账，会串。"
        return null
    }

    /** 申报的变更与本版计划是否一致（接线把落库那张表一起给，这里只核口径）。 */
    private fun mismatchWithPlan(next: List<PledgeEntry>, plan: List<PlanItem>): String? {
        val declared = next.map { it.text }.toSet()
        val sent = plan.map { it.text }.toSet()
        val extra = sent - declared
        val missing = declared - sent
        return when {
            extra.isNotEmpty() -> "计划里多出一条「${extra.first()}」不在这次申报的变更里：要加就申报 add，不许顺手加。"
            missing.isNotEmpty() -> "申报的变更里没有「${missing.first()}」，这一版却把它去掉了：删项要申报 drop（记 DROPPED），不许静默缩表。"
            else -> null
        }
    }

    /** 变更后那张表（顺序不动：改的在原位，加的排末尾，删的摘掉）。 */
    private fun nextEntries(current: List<PledgeEntry>, ops: List<AmendOp>): List<PledgeEntry> {
        val revise = ops.filter { it.kind == AmendKind.REVISE }.associateBy { it.text }
        val gone = ops.filter { it.kind == AmendKind.DROP }.map { it.text }.toSet()
        val kept = current.filterNot { it.text in gone }.map { e ->
            val op = revise[e.text]
            if (op == null) e else PledgeEntry(op.to.ifBlank { e.text }, op.accept.ifBlank { e.accept })
        }
        val added = ops.filter { it.kind == AmendKind.ADD }.map { PledgeEntry(it.text, it.accept.ifBlank { it.text }) }
        return kept + added
    }

    /** 状态迁移（规矩②⑤）：未变项保留结局，被改项回待做，删项记 DROPPED。 */
    private fun stateOf(
        ctx: AmendContext,
        ops: List<AmendOp>,
        nextTexts: List<String>,
        by: AmendBy,
        reason: String,
        channel: ResignChannel,
        version: Int,
        atMs: Long,
    ): AmendState {
        val resets = ops.filter { it.kind == AmendKind.REVISE }.map { it.to.ifBlank { it.text } }
        val onSheet = nextTexts.toSet()
        // 未变项一律保留（含 DONE）——规矩⑤：做了就是做了，别的变更动不到它
        val keep = ctx.endings.filterKeys { it in onSheet && it !in resets.toSet() }
        val drops = ops.filter { it.kind == AmendKind.DROP }.map {
            DropRecord(it.text, it.reason.ifBlank { reason }, version, by, channel, atMs)
        }
        return AmendState(keep, resets, drops)
    }

    /** 为什么这一轮要问她（卡面第一句与账上的 why）。 */
    private fun whyAsk(change: AmendChange): String {
        val kinds = change.kinds.joinToString("、") { it.cn }
        val base = "第 ${change.fromVersion} 版军令状这一轮动了「$kinds」：改了约就要她重新点头。"
        return if (change.overBudget.isEmpty()) base else "$base 而且${change.overBudget.joinToString("；")}——超预算的不许自己认。"
    }

    private fun selfReject(change: AmendChange): String {
        val kinds = change.kinds.filter { it == AmendKind.REVISE || it == AmendKind.DROP }
            .joinToString("、") { if (it == AmendKind.REVISE) "改判据要重新立约" else "删项要她确认" }
        return "$kinds：自决单只能认预算内的追加。这一版保持第 ${change.fromVersion} 版不动，" +
            "要走计划变更确认卡让她拍板。"
    }

    private fun overBudgetReject(over: List<String>, nextVersion: Int): String =
        "追加超出批准的预算（${over.joinToString("；")}），不许自己认下来：" +
            "把这几项走计划变更确认卡问她，她点头才进第 $nextVersion 版。"

    // ── 自决单的预算闸（规矩④的另一条腿，接在 312 的重签闸之后）──────────────

    /**
     * 312 的 `guardFrozenEdit` 只拦「删项/改项要人批」，加项一律放行——于是自决单
     * 可以一路把计划从五项加到封顶十二项而一次也没问过她。规矩④要的正是这一格：
     * 预算内的追加照旧放行，超预算的加项即便走重签这条老路也不许自己认下来。
     *
     * @param addedCount 这一版相对现役快照多出来的项数（接线按原文比出来的那个数）
     * @param channel 只有自决单需要这一层；人批与「还没批」都不是本闸的事
     */
    fun guardSelfAddBudget(
        snapshot: PledgeSnapshot,
        itemsAtFreeze: Int,
        asksUsed: Int,
        addedCount: Int,
        channel: ResignChannel?,
    ): PlanPledgeRules.EditGuard {
        if (channel != ResignChannel.SELF || addedCount <= 0) return PlanPledgeRules.EditGuard.Pass
        val over = budgetOf(itemsAtFreeze, asksUsed).overByAdd(addedCount, snapshot.entries.size + addedCount)
        return if (over.isEmpty()) PlanPledgeRules.EditGuard.Pass
        else PlanPledgeRules.EditGuard.Reject(overBudgetReject(over, snapshot.version + 1))
    }

    // ── 静默缩表的底册（判据③：删项没有 DROPPED ⇒ 门禁红）───────────────────

    /**
     * 历版快照里出现过、至今没有下落的项。
     *
     * 下落只有三种：现役那一版还有它／它有 DROPPED 记录／它被 revise 顶替成了新
     * 文字。312 的收口只核现役快照，所以「重签时把没做的项摘掉」在今天不会留任何
     * 痕迹——本函数把底册换成**历版并集**：摘掉的项照样欠着，直到它有了下落。
     */
    fun owedTexts(
        history: List<PledgeSnapshot>,
        current: PledgeSnapshot,
        dropped: Set<String>,
        superseded: Set<String>,
    ): List<String> {
        val onSheet = current.texts.toSet()
        val out = mutableListOf<String>()
        val seen = mutableSetOf<String>()
        history.forEach { version ->
            version.texts.forEach { text ->
                if (!seen.add(text)) return@forEach
                if (text in onSheet || text in dropped || text in superseded) return@forEach
                out += text
            }
        }
        return out
    }

    /**
     * 缩表缺口的收口读数（判据③的门禁红就在这里）：没有下落的项又拿不出结局 ⇒ 缺口。
     *
     * 只补现役快照之外的那些项——现役里的缺口由 312 的 `settle()` 报，一个字不改它。
     */
    fun shrinkGaps(
        history: List<PledgeSnapshot>,
        current: PledgeSnapshot,
        dropped: Map<String, DropRecord>,
        superseded: Set<String>,
        declarations: List<EndingDeclaration>,
        failures: Map<String, Int> = emptyMap(),
    ): List<SettleGap> {
        val ended = declarations.mapNotNull { d -> PlanPledgeRules.validEnding(d)?.let { d.text } }.toSet()
        return owedTexts(history, current, dropped.keys.toSet(), superseded)
            .filter { it !in ended }
            .map {
                SettleGap(
                    text = it,
                    missing = MISSING_SHRINK,
                    minimal = MINIMAL_SHRINK,
                    failures = (failures[it] ?: 0) + 1,
                )
            }
    }

    /** 缺口一句话（卡面与未办成单共用；「不要了」与「做不完」的分别就在这行字上）。 */
    const val MISSING_SHRINK = "从计划里删了却没有 DROPPED 记录"

    /** 最小满足条件（只报标准，不教做法——312 追加④的口径）。 */
    const val MINIMAL_SHRINK = "走 amend(drop) 记下 DROPPED 与原因，或把它做完（done 带证据）"

    // ── 变更账（`计划军令状变更.jsonl`，与业务账分表）────────────────────────

    /**
     * 变更账一行：版本、类型、谁批的、改了什么（派工单 §三）。
     *
     * [prior] 是变更前那一版——revise 的旧判据从它里面取，落进 `superseded` 一并
     * 存档（规矩①「旧版本一律不删」的第二半：旧判据不但在旧快照行里，也在这一行的
     * `prev_accept` 上，事后能直接回答「当初约的是什么」。
     */
    fun encodeChange(
        change: AmendChange,
        outcome: Amended,
        prior: PledgeSnapshot,
        channel: ResignChannel,
        atMs: Long,
    ): JSONObject = JSONObject()
        .put("v", 1)
        .put("event", EVENT_AMEND)
        .put("pledge", prior.id)
        .put("id", outcome.snapshot.id)
        .put("from", change.fromVersion)
        .put("version", outcome.snapshot.version)
        .put("kinds", JSONArray(change.kinds.map { it.wire }))
        .put("by", change.by.wire)
        .put("channel", channel.wire)
        .put("asked", channel == ResignChannel.HUMAN)
        .put("reason", change.reason)
        .put("ops", JSONArray().also { arr -> change.ops.forEach { arr.put(opRow(it, prior)) } })
        .put("reset", JSONArray(outcome.state.resetToTodo))
        .put("dropped", JSONArray().also { arr ->
            outcome.state.dropped.forEach { d ->
                arr.put(
                    JSONObject()
                        .put("text", d.text)
                        .put("state", STATE_DROPPED)
                        .put("reason", d.reason)
                        .put("version", d.version)
                        .put("by", d.by.wire)
                        .put("channel", d.channel.wire)
                        .put("at", d.atMs),
                )
            }
        })
        .put("kept", outcome.state.keepEndings.size)
        .put(
            "budget",
            JSONObject()
                .put("items_at_freeze", change.budget.itemsAtFreeze)
                .put("max_items", change.budget.maxItems)
                .put("asks_used", change.budget.asksUsed)
                .put("max_asks", change.budget.maxAsks)
                .put("over", JSONArray(change.overBudget)),
        )
        .put("at", atMs)
        .put("session", outcome.snapshot.sessionId)

    private fun opRow(op: AmendOp, prior: PledgeSnapshot): JSONObject {
        val old = prior.entries.firstOrNull { it.text == op.text }
        return JSONObject()
            .put("kind", op.kind.wire)
            .put("text", op.text)
            .put("to", op.to)
            .put("accept", op.accept)
            .put("reason", op.reason)
            .put("prev_accept", old?.accept ?: "")
    }

    /** 跨进程续跑用的种子读数：变更账里的 DROPPED、顶替关系与已问次数。 */
    data class AmendSeed(
        val dropped: List<DropRecord>,
        val superseded: Map<String, String>,
        val asksUsed: Int,
    )

    /** 读变更账（坏行跳过不崩，与 312 的 [PlanPledgeRules.decodeSnapshot] 同口径）。 */
    fun seedFrom(rows: List<JSONObject>): AmendSeed {
        val drops = mutableListOf<DropRecord>()
        val superseded = LinkedHashMap<String, String>()
        var asks = 0
        rows.forEach { row ->
            if (row.optString("event") != EVENT_AMEND) return@forEach
            if (row.optBoolean("asked", false)) asks++
            val channel = ResignChannel.entries.firstOrNull { it.wire == row.optString("channel") } ?: ResignChannel.HUMAN
            val by = AmendBy.ofWire(row.optString("by")) ?: AmendBy.MODEL
            val version = row.optInt("version", 0)
            val at = row.optLong("at", 0L)
            val arr = row.optJSONArray("dropped")
            if (arr != null) {
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    drops += DropRecord(
                        text = o.optString("text"),
                        reason = o.optString("reason"),
                        version = o.optInt("version", version),
                        by = AmendBy.ofWire(o.optString("by")) ?: by,
                        channel = ResignChannel.entries.firstOrNull { it.wire == o.optString("channel") } ?: channel,
                        atMs = o.optLong("at", at),
                    )
                }
            }
            val ops = row.optJSONArray("ops")
            if (ops != null) {
                for (i in 0 until ops.length()) {
                    val o = ops.optJSONObject(i) ?: continue
                    if (o.optString("kind") == AmendKind.REVISE.wire) {
                        val to = o.optString("to")
                        if (to.isNotEmpty()) superseded[o.optString("text")] = to
                    }
                }
            }
        }
        return AmendSeed(drops, superseded, asks)
    }
}
