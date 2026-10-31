// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单-2026-10-31-第三百一十二批-计划军令状 §做什么 1（写账）＋ 3（缺口进
// 「未办成单」）＋ 4（保留旧快照）＋ 判据③（两个版本都在账上）＋ 负例⑤。
//
// 分工：判据在 `PlanPledge.kt`（纯函数，零 IO 零时钟），**这一层是状态与账**——
// 快照的内存真相、`计划军令状.jsonl` 的落盘（只追加：旧版本原样留在账上）、
// 收口缺口与弃单进「未办成单」。房型照 `PlanContractBoard`（242）：进程内单例 ＋
// `@Volatile`；账写失败只 log，不挡事（记账失败不许挡住用户的事）。
//
// 账是**权威**，内存只是视图：跨会话续跑用 [seed] 把上一场的军令状接回来
// （接线从账里 decode；同一版不记第二行）。
package com.example.zhengdao.baopu.plan

import com.example.zhengdao.baopu.PlanRules.PlanItem
import com.example.zhengdao.baopu.plan.PlanPledgeRules.EndingDeclaration
import com.example.zhengdao.baopu.plan.PlanPledgeRules.PledgeSnapshot
import com.example.zhengdao.baopu.plan.PlanPledgeRules.ResignChannel
import com.example.zhengdao.baopu.scout.LedgerFanout
import com.example.zhengdao.baopu.scout.UnfinishedLedger
import com.example.zhengdao.core.RunLog
import java.io.File
import org.json.JSONObject

internal object PlanPledgeBoard {

    /** 账名（与 决策记录.jsonl / 未办成单.jsonl 同一棵「记忆」树）。 */
    const val LEDGER_FILE = "计划军令状.jsonl"

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
     */
    fun seed(snapshot: PledgeSnapshot, priorFailures: Map<String, Int> = emptyMap()) = synchronized(this) {
        active = snapshot
        synchronized(versions) { if (versions.none { it.id == snapshot.id }) versions += snapshot }
        endings.clear()
        failures.clear()
        failures.putAll(priorFailures)
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
     * [PlanPledgeRules.guardFrozenEdit]，这里只供状态）。
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
        val resign = PlanPledgeRules.parseResign(args)
        return PlanPledgeRules.guardFrozenEdit(s, added, removed, resign, channel, openFeedback())
    }

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
     * @param doneTexts 计划卡上已打勾的条目（只把缺口说得准一点；**打勾不构成结局**）
     * @return null ＝ 没立状（没有结项门，不编读数）；同一版重复调 ⇒ 返回上次读数不重写账
     */
    fun settle(doneTexts: Set<String>, atMs: Long): PlanPledgeRules.SettleVerdict? = synchronized(this) {
        val s = active ?: return null
        lastVerdict?.let { if (settled) return it }
        val prior = failures.toMap()
        val verdict = PlanPledgeRules.settle(s, endings.values.toList(), doneTexts, prior)
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
}
