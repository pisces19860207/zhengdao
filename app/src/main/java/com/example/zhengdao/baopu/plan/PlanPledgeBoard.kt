// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-31-第三百一十二批-计划军令状 §做什么 1（写账）＋ 3（缺口进
// 「未办成单」）＋ 4（保留旧快照）＋ 判据③（两个版本都在账上）＋ 负例⑤；
// 派工单-2026-11-01-第三百四十三批-军令状范围变更 §一（三条正路）＋ §二 规矩①③④
// ＋ §三（变更账与业务账分表）＋ 判据③④⑤。
//
// 分工：判据在 `PlanPledge.kt`（312）与 `PlanPledgeAmend.kt`（343，都是纯函数，
// 零 IO 零时钟），**这一层是状态与账**——快照的内存真相、`计划军令状.jsonl` 与
// `计划军令状变更.jsonl` 两本账的落盘（都只追加：旧版本原样留在账上）、收口缺口与
// 弃单进「未办成单」。房型照 `PlanContractBoard`（242）：进程内单例 ＋ `@Volatile`；
// 账写失败只 log，不挡事（记账失败不许挡住用户的事）。
//
// 两本账的分工（343 §三，判据⑥）：`计划军令状.jsonl` 记**状态**（哪一版约了什么、
// 收口什么读数），`计划军令状变更.jsonl` 记**变更**（谁提的、谁批的、改了什么、
// 超没超预算）；「未办成单」是业务账，只在收口时收缺口与弃单——变更本身不进业务账。
//
// 账是**权威**，内存只是视图：跨会话续跑用 [seed] 把上一场的军令状接回来
// （接线从账里 decode；同一版不记第二行）。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendChange
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendDecision
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendKind
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendParse
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.DropRecord
import com.example.zhengdao.baopu.plan.PlanPledgeAmendRules.AmendBy
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndingDeclaration
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeSnapshot
import com.example.zhengdao.baopu.plan.PlanPledgeRules.ResignChannel
import com.example.zhengdao.baopu.plan.PlanPledgeRules.ResignParse
import com.example.zhengdao.baopu.scout.LedgerFanout
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

internal object PlanPledgeBoard {

    /** 账名（与 决策记录.jsonl / 未办成单.jsonl 同一棵「记忆」树）。 */
    const val LEDGER_FILE = "计划军令状.jsonl"

    /** 变更账的账名（343 §三：与状态账、业务账都分表——一本只管一件事）。 */
    const val CHANGE_LEDGER_FILE = PlanPledgeAmendRules.LEDGER_FILE

    /** 落点（装配层注入；空表 ⇒ 只记内存——测试与早期启动安全，账不落地不挡事）。 */
    @Volatile
    private var dirs: List<File> = emptyList()

    @Volatile
    private var active: PledgeSnapshot? = null

    /** 本进程见过的全部版本（内存视图；判据③的读口 [history]，账在盘上是权威）。 */
    private val versions = mutableListOf<PledgeSnapshot>()

    /** 申报过的结局（按条目原文；重复申报按最后一条——与解析层同一口径）。 */
    private val endings = LinkedHashMap<String, EndingDeclaration>()

    /** 每项收口未过的次数（追加②的回灌轮数；重签不清零幸存项——那是「对项」的记录）。 */
    private val failures = LinkedHashMap<String, Int>()

    /** DROPPED 记录（按条目原文；判据③的底册——与 [endings] 分列，一个是「不要了」）。*/
    private val dropped = LinkedHashMap<String, DropRecord>()

    /** 被 revise 顶替掉的旧文字（旧项 → 新项）：顶替是有下落的，不算静默缩表。 */
    private val superseded = LinkedHashMap<String, String>()

    /** 本状累计让她拍板过几次变更（打扰预算；只有人批到手的那一张算数）。 */
    private var asksUsed = 0

    /** 递出去还没回话的变更卡（同一份申报再发 ⇒ 退回同一张，不重复问——判据④）。 */
    @Volatile
    private var pendingAsk: AmendChange? = null

    /** 递出过几张变更卡（判据④「断言弹卡 1 次」量的是这个数，不是请求调用次数）。 */
    @Volatile
    private var cardsHandedOut = 0

    /** 人拒绝过的立状候选（同版不再重复问；「一次问清」的另一半）。 */
    @Volatile
    private var declinedId: String? = null

    /** 收口一轮只结一次（重复 settle 会把未办成单记成假账；新申报重开）。 */
    @Volatile
    private var settled = false

    @Volatile
    private var lastVerdict: PlanPledgeRules.SettleVerdict? = null

    // ── 接线口（装配／引擎调） ───────────────────────────────────────────────

    /** 注入账的落点（生产是「记忆」目录们；不调 ⇒ 账只进内存）。 */
    fun attach(ledgerDirs: List<File>) {
        dirs = ledgerDirs
    }

    fun activeSnapshot(): PledgeSnapshot? = active

    /** 全部版本（判据③的读口；旧版本从冻结那天起就原样在账上）。 */
    fun history(): List<PledgeSnapshot> = synchronized(versions) { versions.toList() }

    /** 账文件（测试与回查用；没接落点 ⇒ null）。 */
    fun ledgerFile(): File? = dirs.firstOrNull()?.let { File(it, LEDGER_FILE) }

    fun lastVerdict(): PlanPledgeRules.SettleVerdict? = lastVerdict

    /**
     * 立状（人在开工卡上点过头之后才调；写账一行 event=freeze）。
     *
     * 为什么不许接线在弹卡**之前**调：冻结是「人签名」那一刻才发生的事——
     * 模型自己发一份长计划就把军令状挂上，等于状没问过人就生效了。
     */
    fun freeze(snapshot: PledgeSnapshot) = synchronized(this) {
        active = snapshot
        synchronized(versions) { versions += snapshot }
        endings.clear()
        failures.clear()
        resetChangeState()
        settled = false
        lastVerdict = null
        declinedId = null
        append(PlanPledgeRules.encodeSnapshot(snapshot, PlanPledgeRules.EVENT_FREEZE))
        RunLog.log(
            "抱朴军令状：立状（第 ${snapshot.version} 版，${snapshot.entries.size} 项，" +
                "账行 pledge:${snapshot.id}）",
        )
    }

    /**
     * 跨会话续跑：把上一场的军令状接回来（账是权威；同一版**不**再写账——
     * 一版两行会让「两个版本」数出第三个来）。
     *
     * @param priorFailures 各项此前收口未过的次数（接线从账里的 settle 行读回；
     *   不给 ⇒ 按 0 起——跨进程的轮数恢复是接线批次的口径，这里只留口子）
     * @param priorVersions 历版快照（343 判据③的底册：只接现役那一版，被悄悄摘掉的
     *   项就没有「欠着」的把柄了——接线从 freeze/resign 行逐版 decode 后传进来）
     * @param priorChanges 变更账行（343：DROPPED、顶替关系与已问次数从
     *   `计划军令状变更.jsonl` 读回；用 [PlanPledgeAmendRules.seedFrom] 解）
     */
    fun seed(
        snapshot: PledgeSnapshot,
        priorFailures: Map<String, Int> = emptyMap(),
        priorVersions: List<PledgeSnapshot> = emptyList(),
        priorChanges: List<JSONObject> = emptyList(),
    ) = synchronized(this) {
        active = snapshot
        synchronized(versions) {
            priorVersions.forEach { old -> if (versions.none { it.id == old.id }) versions += old }
            if (versions.none { it.id == snapshot.id }) versions += snapshot
        }
        endings.clear()
        failures.clear()
        failures.putAll(priorFailures)
        resetChangeState()
        applySeed(PlanPledgeAmendRules.seedFrom(priorChanges))
        settled = false
        lastVerdict = null
        RunLog.log("抱朴军令状：跨会话接回（第 ${snapshot.version} 版，${snapshot.entries.size} 项）")
    }

    /** 人拒绝立状：记住这个候选（同版不再重复问），军令状不生效。 */
    fun noteDeclined(snapshot: PledgeSnapshot) = synchronized(this) {
        declinedId = snapshot.id
        RunLog.log("抱朴军令状：不立状（第 ${snapshot.version} 版候选）——不设结项门")
    }

    fun isDeclined(snapshot: PledgeSnapshot): Boolean = declinedId == snapshot.id

    // ── 重签（判据①的闸 ＋ 判据③的账） ─────────────────────────────────────

    /**
     * 冻结后的计划改动闸（update_plan 落库前调；判据全在
     * [PlanPledgeRules.guardFrozenEdit] ＋ [PlanPledgeAmendRules.guardSelfAddBudget]，
     * 这里只供状态）。
     *
     * 343 加的一格：带了 `amend` 就用它的 reason 当重签理由（一次申报不写两遍话），
     * 312 放行之后再补一次预算判定——自决单加项超预算，走重签这条老路也不许认下来。
     *
     * @param channel null ＝ 这次改动还没拿到人批／自决单
     */
    fun guardEdit(
        incoming: List<PlanItem>,
        args: JSONObject,
        channel: ResignChannel?,
    ): PlanPledgeRules.EditGuard {
        val s = active ?: return PlanPledgeRules.EditGuard.Pass
        val (added, removed) = diff(s.texts, incoming.map { it.text })
        val resign = resignReasonOf(args)
        val verdict = PlanPledgeRules.guardFrozenEdit(s, added, removed, resign, channel, openFeedback())
        if (verdict is PlanPledgeRules.EditGuard.Reject) return verdict
        return PlanPledgeAmendRules.guardSelfAddBudget(s, itemsAtFreeze(), asksUsed, added.size, channel)
    }

    /** `amend` 自带理由 ⇒ 不必再发一份 resign（两份话写成一轮）。 */
    private fun resignReasonOf(args: JSONObject): ResignParse =
        when (val parse = PlanPledgeAmendRules.parseAmend(args)) {
            is AmendParse.Ok -> ResignParse.Ok(parse.reason)
            is AmendParse.Error -> ResignParse.Error(parse.message)
            is AmendParse.Absent -> PlanPledgeRules.parseResign(args)
        }

    // ── 范围变更（343 三条正路的落板处） ────────────────────────────────────

    /**
     * 判一次范围变更，批准到手就当场落板（一次调用一版；312 是 guard→apply 两步，
     * 这里合成一步：申报、判定、写账必须同轨，否则重发一次申报就多出第二个版本）。
     *
     * 三条正路各自的记账（派工单 §一）：
     * · 追加 ⇒ 新项进表、版本 +1、账上记「谁追加的」；预算内可凭自决单，超预算只认人批；
     * · 修改 ⇒ 必须人批（重新立约），旧判据留在旧版快照与变更账的 `prev_accept` 上；
     * · 删除 ⇒ 必须人批，逐项记 DROPPED(原因)——与 ABANDONED 分列，两本账都留得清。
     *
     * @param incoming 这一版要落库的计划（接线给了就核「申报与表是否一致」；不给 ⇒ 只判申报本身）
     * @param channel null ⇒ 只出卡（[AmendDecision.NeedUser]），板上一个字节不改
     */
    fun amend(
        incoming: List<PlanItem>?,
        args: JSONObject,
        channel: ResignChannel?,
        atMs: Long,
    ): AmendDecision = synchronized(this) {
        val s = active ?: return AmendDecision.Ignored(PlanPledgeAmendCards.ignoredAck())
        when (val parse = PlanPledgeAmendRules.parseAmend(args)) {
            is AmendParse.Error -> return AmendDecision.Reject(PlanPledgeAmendCards.rejectedAck(parse.message))
            is AmendParse.Absent -> return AmendDecision.Ignored(parse.reason)
            is AmendParse.Ok -> {
                val ctx = PlanPledgeAmendRules.AmendContext(
                    snapshot = s,
                    itemsAtFreeze = itemsAtFreeze(),
                    asksUsed = asksUsed,
                    endings = endings.toMap(),
                    dropped = dropped.keys.toSet(),
                    superseded = superseded.keys.toSet(),
                    // 历版欠着的项：允许补记 DROPPED（312 老路摘项从不记它）
                    owed = PlanPledgeAmendRules.owedTexts(
                        history(), s, dropped.keys.toSet(), superseded.keys.toSet(),
                    ).toSet(),
                )
                return when (
                    val decision = PlanPledgeAmendRules.decide(
                        ctx, parse.ops, parse.by, parse.reason, incoming, channel, atMs,
                    )
                ) {
                    is AmendDecision.NeedUser -> {
                        // 同一份申报重发 ⇒ 退回同一张卡，不再数第二张（判据④「弹卡 1 次」）
                        if (pendingAsk != decision.change) cardsHandedOut++
                        pendingAsk = decision.change
                        decision
                    }
                    is AmendDecision.Apply -> {
                        commitAmend(s, decision, atMs)
                        decision
                    }
                    else -> decision
                }
            }
        }
    }

    /** 落板（[amend] 内部用；一次变更一次提交）。 */
    private fun commitAmend(prior: PledgeSnapshot, decision: AmendDecision.Apply, atMs: Long) {
        val change = decision.change
        val outcome = decision.outcome
        val next = outcome.snapshot
        active = next
        synchronized(versions) { versions += next }
        endings.clear()
        endings.putAll(outcome.state.keepEndings)
        outcome.state.dropped.forEach { dropped[it.text] = it }
        change.ops.filter { it.kind == AmendKind.REVISE && it.to.isNotEmpty() }
            .forEach { superseded[it.text] = it.to }
        if (decision.channel == ResignChannel.HUMAN) asksUsed++
        pendingAsk = null
        settled = false
        lastVerdict = null
        // 回灌轮数只清「有下落的」项：现役里的、DROPPED 的、被顶替的；被悄悄摘掉的仍欠着
        val settledTexts = (
            PlanPledgeAmendRules.owedTexts(history(), next, dropped.keys.toSet(), superseded.keys.toSet()) +
                next.texts + dropped.keys + superseded.keys
            ).toSet()
        failures.keys.retainAll(settledTexts)
        val (added, removed) = diff(prior.texts, next.texts)
        append(
            PlanPledgeRules.encodeSnapshot(
                next,
                PlanPledgeRules.EVENT_RESIGN,
                added,
                removed,
                change.reason,
                decision.channel,
            ),
        )
        appendChange(PlanPledgeAmendRules.encodeChange(change, outcome, prior, decision.channel, atMs))
        RunLog.log(
            "抱朴军令状：范围变更（第 ${change.fromVersion}→${next.version} 版，" +
                "${change.kinds.joinToString("、") { it.cn }}，${change.by.cn}提出、" +
                "${if (decision.channel == ResignChannel.HUMAN) "人批" else "自决单"}，" +
                "账行 amend:${next.id}）",
        )
    }

    /** 她还没回话的那张变更卡（null ⇒ 没在问）；接线据此知道重发申报是不是同一件事。 */
    fun pendingAmend(): AmendChange? = pendingAsk

    /** 递出过几张变更卡（判据④的读数）。 */
    fun cardsHandedOut(): Int = cardsHandedOut

    /** DROPPED 记录（与 [lastVerdict] 的缺口、endings 的弃单分列——判据③的底册）。 */
    fun droppedRecords(): List<DropRecord> = synchronized(this) { dropped.values.toList() }

    /** 顶替关系（旧项 → 新项）。 */
    fun supersededFrom(): Map<String, String> = synchronized(this) { superseded.toMap() }

    /** 当前预算读数（卡面与观测面用）。 */
    fun amendBudget(): PlanPledgeAmendRules.AmendBudget =
        PlanPledgeAmendRules.budgetOf(itemsAtFreeze(), asksUsed)

    /** 变更账文件（测试与回查用；没接落点 ⇒ null）。 */
    fun changeLedgerFile(): File? = dirs.firstOrNull()?.let { File(it, CHANGE_LEDGER_FILE) }

    /** 她拍板过的变更次数（打扰预算的分子）。 */
    fun asksUsed(): Int = asksUsed

    /**
     * 重签落地（guardEdit 放行**之后**调，一次改动一签）：旧快照原样留账
     * （event=resign 一行带删项/加项/为什么/通道 ＋「计划变更」标记），新版本成为现役。
     *
     * @param accepts 新版各条的验收方式（没变的条目沿用旧条，新条以条目原文兜底——
     *   由接线对齐；这里不猜）
     */
    fun applyResign(
        incoming: List<PlanItem>,
        accepts: List<String>,
        reason: String,
        channel: ResignChannel,
        atMs: Long,
        by: AmendBy = AmendBy.MODEL,
    ): PledgeSnapshot? = synchronized(this) {
        val old = active ?: return null
        val next = PlanPledgeRules.snapshotOf(incoming, accepts, old.version + 1, old.goal, atMs, old.sessionId)
        val (added, removed) = diff(old.texts, next.texts)
        active = next
        synchronized(versions) { versions += next }
        endings.clear()
        settled = false
        lastVerdict = null
        failures.keys.retainAll(next.texts.toSet())
        append(
            PlanPledgeRules.encodeSnapshot(
                next,
                PlanPledgeRules.EVENT_RESIGN,
                added,
                removed,
                reason,
                channel,
            ),
        )
        // 343 判据③：重签摘掉的项也要有 DROPPED 凭据——不给凭据就是「静默缩表」，
        // 收口按历版并集照样报红。变更账补这一行（与 amend 的 drop 同一种账行）。
        val drops = removed.map { DropRecord(it, reason, next.version, by, channel, atMs) }
        drops.forEach { dropped[it.text] = it }
        if (drops.isNotEmpty()) {
            appendChange(PlanPledgeAmendRules.encodeResignDrops(old, next, drops, reason, channel, atMs))
        }
        RunLog.log(
            "抱朴军令状：重签（第 ${old.version}→${next.version} 版，加 ${added.size}／删 ${removed.size}，" +
                "原因：$reason，通道 ${channel.wire}）",
        )
        next
    }

    /** 还在回灌期里的项（收口没过 ≥1 次、且至今没有有效结局）：自决单无权删它们。 */
    fun openFeedback(): Set<String> = synchronized(this) {
        failures.keys.filter { PlanPledgeRules.validEnding(endings[it]) == null }.toSet()
    }

    // ── 结项申报 ＋ 收口（判据②的落点） ─────────────────────────────────────

    /** 结项申报进板（接线先过 [PlanPledgeRules.parseCloseout]，Error 由它回灌）。 */
    fun noteCloseout(declarations: List<EndingDeclaration>) = synchronized(this) {
        declarations.forEach { endings[it.text] = it }
        settled = false
    }

    /**
     * 收口（结项门禁）：逐项对快照判「完成／未完成」，缺口与弃单写「未办成单」，
     * 账上落一行 settle。
     *
     * 343 判据③在这一层生效：底册从「现役那一版」换成「历版并集」——被重签悄悄摘掉
     * 又没有 DROPPED 记录的项照样欠着，收口按缺口报红（312 的 `settle()` 一个字没改，
     * 它管现役，本层补历版）。
     *
     * @param doneTexts 计划卡上已打勾的条目（只把缺口说得准一点；**打勾不构成结局**）
     * @return null ＝ 没立状（没有结项门，不编读数）；同一版重复调 ⇒ 返回上次读数不重写账
     */
    fun settle(doneTexts: Set<String>, atMs: Long): PlanPledgeRules.SettleVerdict? = synchronized(this) {
        val s = active ?: return null
        lastVerdict?.let { if (settled) return it }
        val prior = failures.toMap()
        val base = PlanPledgeRules.settle(s, endings.values.toList(), doneTexts, prior)
        val shrunk = PlanPledgeAmendRules.shrinkGaps(
            history(), s, dropped, superseded.keys.toSet(), endings.values.toList(), prior,
        )
        val verdict = if (shrunk.isEmpty()) base else base.copy(gaps = base.gaps + shrunk)
        settled = true
        lastVerdict = verdict
        verdict.gaps.forEach { g -> failures[g.text] = g.failures }
        append(PlanPledgeRules.encodeSettle(verdict, s, atMs))
        recordUnfinished(verdict, atMs)
        RunLog.log(
            "抱朴军令状：收口（${if (verdict.complete) "完成" else "未完成"}——" +
                "${verdict.ended.size}/${verdict.total} 有结局，缺口 ${verdict.gaps.size}）",
        )
        verdict
    }

    /** 新一轮（新请求＝新任务）：军令状跟计划同生命周期，旧状不带过来。 */
    fun clear() = synchronized(this) {
        active = null
        synchronized(versions) { versions.clear() }
        endings.clear()
        failures.clear()
        resetChangeState()
        declinedId = null
        settled = false
        lastVerdict = null
    }

    /** 直测收尾（生产不调：落点是进程态）。 */
    fun resetForTest() {
        clear()
        dirs = emptyList()
    }

    // ── 内件 ────────────────────────────────────────────────────────────────

    /** 变更态清零（新立状／新轮次／跨会话重播变更前都先归零，避免上状的 DROPPED 串进来）。 */
    private fun resetChangeState() {
        dropped.clear()
        superseded.clear()
        asksUsed = 0
        pendingAsk = null
        cardsHandedOut = 0
    }

    /** 变更账读回的种子（DROPPED／顶替／已问次数；[seed] 续跑时用）。 */
    private fun applySeed(seedState: PlanPledgeAmendRules.AmendSeed) {
        seedState.dropped.forEach { dropped[it.text] = it }
        seedState.superseded.forEach { (from, to) -> superseded[from] = to }
        asksUsed = seedState.asksUsed
    }

    /** 她签名那一版（v1）的项数：时间预算的基数。没历史兜到现役。 */
    private fun itemsAtFreeze(): Int = synchronized(this) {
        versions.firstOrNull()?.entries?.size ?: active?.entries?.size ?: 0
    }

    /** 与 140 的结构变更同判：只认条目原文的增删（打勾/换顺序不算，返回空表）。 */
    private fun diff(old: List<String>, new: List<String>): Pair<List<String>, List<String>> {
        val oldSet = old.toSet()
        val newSet = new.toSet()
        return new.filter { it !in oldSet } to old.filter { it !in newSet }
    }

    /** 缺口与弃单逐行进未办成单（账写失败只 log，不挡事——290 同一口径）。 */
    private fun recordUnfinished(verdict: PlanPledgeRules.SettleVerdict, atMs: Long) {
        if (dirs.isEmpty()) return
        val ledger = UnfinishedLedger(dirs)
        PlanPledgeRules.unfinishedRows(verdict, atMs).forEach { row ->
            val ok = runCatching { ledger.record(row) }.getOrDefault(false)
            if (!ok) RunLog.log("未办成单写失败：${row.intent}")
        }
    }

    /** 账追加一行（[LedgerFanout] 自带 runCatching；这里再兜一层，失败只 log）。 */
    private fun append(line: JSONObject) {
        if (dirs.isEmpty()) return
        val ok = runCatching { LedgerFanout(dirs, LEDGER_FILE).appendLine(line.toString()) }
            .getOrDefault(false)
        if (!ok) RunLog.log("军令状账写失败（${LEDGER_FILE}）")
    }

    /** 变更账追加一行（另一本账、另一个文件：一本只管一件事，343 判据⑥）。 */
    private fun appendChange(line: JSONObject) {
        if (dirs.isEmpty()) return
        val ok = runCatching { LedgerFanout(dirs, CHANGE_LEDGER_FILE).appendLine(line.toString()) }
            .getOrDefault(false)
        if (!ok) RunLog.log("军令状变更账写失败（${CHANGE_LEDGER_FILE}）")
    }
}
