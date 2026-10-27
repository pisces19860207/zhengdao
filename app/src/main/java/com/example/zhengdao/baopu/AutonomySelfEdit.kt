// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百三十六批 · **A 档自改的执行壳**（派工单 §2 判据后半句＋§4「自治 ≠ 免检」）。
//
// 判据层（[AutonomyRules]）只回答"该不该自己写"；这一层回答"写下去以后怎么办"。三件事按顺序，
// 少一件就不许写：
//   ① **写之前**回滚点必须已经存在（技能＝`SkillStore` 的 `.bak-`，人设＝`PersonaBackup` 的 `.bak-`）；
//   ② **写完**立刻跑一次 206 对拍；
//   ③ 对拍**没通过 ⇒ 自动回滚**（不是"下次再说"，是这一轮就退回去）。
//
// 为什么"没通过"包含"没跑成"：UNRUN／额度满／快照不一致都拿不到"不回归"的证据，
// 而 A 档的前提正是"有证据说它没变坏"。这条口径与 233 的契约修订门槛④一致
// （`SkillContractRevision.review` 里 `compare == null ⇒ 不许采纳`）——**两处共用一句判据**，
// 由 `AutonomySelfEditTest` 的"字面同源"用例钉住，不各写一套（见 [AutonomyEvalGate]）。
//
// 三条纪律：
// · **只有 A 档进得来**：本层第一行就查档位，B/C 直接返回"没写"，绝不给"绕过判据"的第二条路；
// · **写盘只走既有唯一口**：技能＝`SkillStore.plan/apply`，人设＝`PersonaFiles.applyProposal`；
//   本层不 `writeText` 一次（192/23 的写入口纪律不因本批变宽）；
// · **回滚失败必须出声**：`RollbackFailed` 是全场最危险的一态——盘上留着一条没验过的改动，
//   还退不回去。它不静默、不重试成功就谎报，直接把文件路径与备份名摆进 208。
//
// 208 记录用既有 `auto_act`（派工单 §4 点名不新增 kind）；回滚另追一条 `rollback_of`。
package com.example.zhengdao.baopu

import java.io.File

// ── 结论 ────────────────────────────────────────────────────────────────────

/** A 档自改的一轮结果（四态，调用方逐态出声，不许合并成"成功/失败"）。 */
internal sealed interface SelfEditOutcome {
    /** 没写盘：判据不是 A（B/C）或结构复核没过。盘上一个字节都没动。 */
    data class NotWritten(val verdict: AutonomyVerdict, val line: String) : SelfEditOutcome

    /** 写了、对拍也过了 ⇒ 保留。[backup] 是写前那份，人抽查后可以一键退。 */
    data class Kept(
        val proposal: AutonomyProposal,
        val backup: String?,
        val evalLine: String,
        val ledger: DecisionRecord?,
    ) : SelfEditOutcome

    /** 写了、对拍没过 ⇒ **已自动回滚**（派工单 §4 的硬要求兑现处）。 */
    data class RolledBack(
        val proposal: AutonomyProposal,
        val evalLine: String,
        val restoreLine: String,
        val ledger: DecisionRecord?,
    ) : SelfEditOutcome

    /** 写了、对拍没过、回滚又失败 ⇒ 最坏的一态：**必须显式暴露**，不许当成"保留"。 */
    data class RollbackFailed(
        val proposal: AutonomyProposal,
        val evalLine: String,
        val why: String,
        val filePath: String,
    ) : SelfEditOutcome
}

/**
 * 对拍结论能不能让这次自改留着（**唯一一处**「过 / 不过」的判据）。
 *
 * 通过的口径很窄：ADOPT 或 DEFER，**且结果列没有退化**。
 * REJECT／UNRUN／Invalid／没跑 ⇒ 一律不过 ⇒ 回滚。
 * "变差／产物少了"两个字面量与 233 的门槛④同源（那边也是这两个词），
 * 所以这里**不另立同义词**——两张嘴说同一件事，迟早一句通一句不通。
 */
internal object AutonomyEvalGate {

    /** 206 结果列里代表"退化"的字面（与 `SkillContractRevision.review` 用的是同一串）。 */
    val REGRESSION_WORDS: List<String> = listOf("变差", "产物少了")

    fun passedWithoutRegression(outcome: EvalOutcome): Boolean = when (outcome) {
        is EvalOutcome.Ran -> {
            val v = outcome.compare.verdict
            val worse = outcome.compare.resultDiff.filter { d -> REGRESSION_WORDS.any { d.contains(it) } }
            (v == EvalVerdict.ADOPT || v == EvalVerdict.DEFER) && worse.isEmpty()
        }
        // 没跑成＝没有"不回归"的证据 ⇒ 不许留着（自治 ≠ 免检的另一半）
        is EvalOutcome.NotRun, is EvalOutcome.Invalid -> false
    }

    /** REJECT 单独认出来（回滚话术要能区分"判坏了"和"没验成"）。 */
    fun isHardReject(outcome: EvalOutcome): Boolean =
        outcome is EvalOutcome.Ran && outcome.compare.verdict == EvalVerdict.REJECT

    /** 一句话结论（进 208 的 `after` 与档案的「有没有变好」）。 */
    fun outcomeOf(outcome: EvalOutcome): CommitmentOutcome = when {
        !passedWithoutRegression(outcome) && isHardReject(outcome) -> CommitmentOutcome.WORSE
        !passedWithoutRegression(outcome) && outcome is EvalOutcome.Ran -> CommitmentOutcome.SAME
        !passedWithoutRegression(outcome) -> CommitmentOutcome.UNCHECKED
        else -> CommitmentOutcome.BETTER
    }

    /** 对拍那一行原文（没跑也要有行，档案才不空）。 */
    fun lineOf(outcome: EvalOutcome): String = when (outcome) {
        is EvalOutcome.Ran -> outcome.table.lines().firstOrNull().orEmpty()
        is EvalOutcome.NotRun -> outcome.line
        is EvalOutcome.Invalid -> "对拍作废：${outcome.reason}"
    }
}

// ── 执行壳 ──────────────────────────────────────────────────────────────────

/**
 * A 档自改的执行口（**薄 IO＋一条链**；判据一律在外面，这里只按结论办事）。
 *
 * @param personaFiles 人设/索引的唯一合法写口（本层不自己开文件写）
 * @param projectRoot 抱朴工作区根（技能落点用）
 * @param globalRoot 全局人设根
 * @param eval 跑一次 206（由调用方注入：它才知道通道、额度、当日第几次）
 * @param currentIndexText 当前技能索引全文（人设 C 段里那些【技能】行）
 * @param stamp 备份名用的时间戳（测试固定，生产走 `TimeFormats.fileStamp()`）
 * @param nowMs 208 记录时间
 */
internal class AutonomySelfEdit(
    private val personaFiles: PersonaFiles,
    private val projectRoot: File,
    private val globalRoot: File,
    private val eval: (AutonomyProposal) -> EvalOutcome,
    private val currentIndexText: String,
    private val stamp: () -> String = { System.currentTimeMillis().toString() },
    private val nowMs: () -> Long = { System.currentTimeMillis() },
) {

    /**
     * 走一遍：查档 → 写 → 跑对拍 → 留或退。
     *
     * `SkillWritePolicy` 在这里**不参与**（192 免批判据仍按自己的口径管"新技能"）。
     * 为什么能不管：A 档的三条替代判据更强——结构复核（机器行一字不动）＋ 必跑 206 ＋ 自动回滚，
     * 而 192 的 `MODIFY_EXISTING` 否决是为了防"没人验过就改"，本层每一改都有对拍兜着。
     * 这段口径冲突由交付报告交蓝总管裁定，代码里不偷偷扩权（派工单红线）。
     */
    fun run(proposal: AutonomyProposal): SelfEditOutcome {
        val verdict = AutonomyRules.decide(proposal)
        if (verdict !is AutonomyVerdict.AutoEdit) {
            return SelfEditOutcome.NotWritten(verdict, AutonomyTell.line(verdict))
        }
        return when (proposal.kind.surface) {
            AutonomySurface.SKILL_BODY -> skillPath(proposal)
            // 索引层住在 C 段人设里（那些【技能】行），写口同样是 PersonaFiles——
            // 判据在 [AutonomyRules.indexFindings] 分过叉，落盘不用分。
            AutonomySurface.SKILL_INDEX, AutonomySurface.PERSONA_DISCIPLINE -> personaPath(proposal)
            // B/C 的面（代码／表）到这里不可能还在 A 档：decide 已短路。真出现就是判据被改坏，
            // 按"没写"处理并留一句——不许静默写下去。
            AutonomySurface.TEXT_OTHER, AutonomySurface.CONFIG ->
                SelfEditOutcome.NotWritten(verdict, "面上没有自动写口：${proposal.kind.label}（${proposal.kind.surface}）")
        }
    }

    // ── 技能正文／索引：都落进 SkillStore 那一条道 ──────────────────────────

    private fun skillPath(p: AutonomyProposal): SelfEditOutcome {
        val name = SkillStore.sanitizeName(p.target)
            ?: return SelfEditOutcome.NotWritten(
                AutonomyVerdict.NeverAuto(p, listOf("技能名非法：${p.target}")),
                "技能名非法，没写（自改不许改名兜底）",
            )
        val before = readSkillBody(name)
        if (before == null) {
            // A 档只改**已有**的技能。没有正文＝"新增"，新增是 B 档（派工单 §2 表第二行）。
            return SelfEditOutcome.NotWritten(
                AutonomyVerdict.NeedsApproval(
                    p,
                    listOf("这条技能还没有正文文件：要写它就是**新增技能**，属 B 档（必须人点）"),
                    AutonomyTier.B_HUMAN_APPROVAL,
                ),
                "没有这条技能的原件 ⇒ 不是自改，是新增（B 档），没写",
            )
        }
        if (SkillStore.statusOf(before) != "active") {
            // 草稿技能的试用期头与索引「未验证」标由现有写口**重算**（`withDraftHeader` 用默认值），
            // 走 A 档会把 uses 清零、索引丢掉未验证标——那是行为改变，不是改措辞。
            // 草稿转正另有其道（184/207 的 afterUse／转正），本批不借 A 档挤进来。
            return SelfEditOutcome.NotWritten(
                AutonomyVerdict.NeedsApproval(
                    p,
                    listOf("草稿技能不在自改范围：试用期计数与「未验证」标会被写口重算（那是行为改变，不是改措辞）"),
                    AutonomyTier.B_HUMAN_APPROVAL,
                ),
                "草稿技能不自改（计数会被重算），没写",
            )
        }
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = name,
            body = p.newText,
            reason = "A 档自改：${p.kind.label}",
            // 恒 false：能走到这里的只有正式技能（草稿已在上面挡掉）。
            // 传 true 会让 `withDraftHeader` 补一个 uses=0 的新头 ⇒ 把试用期抹了。
            draft = false,
        )
        val plan = SkillStore.plan(proposal, projectRoot, globalRoot, stamp())
        plan.blocked?.let {
            return SelfEditOutcome.NotWritten(AutonomyVerdict.AutoEdit(p), "写口预检没过：$it")
        }
        // ① 回滚点：写之前就得有（有原件 ⇒ apply 必留备份；没留成就是退不回去）
        val written = SkillStore.apply(plan, personaFiles, currentIndexText)
        val backup = when (written) {
            is SkillWriteResult.Written -> written.backup
            is SkillWriteResult.Rejected ->
                return SelfEditOutcome.NotWritten(AutonomyVerdict.AutoEdit(p), "没写成：${written.reason}")
        }
        if (backup.isNullOrBlank()) {
            // 有原件却没备份成 ⇒ 改了退不回去。这一条宁可不做：判据 3 的"能回滚"是前提不是愿望。
            return SelfEditOutcome.NotWritten(
                AutonomyVerdict.AutoEdit(p),
                "写前备份没落成，改了退不回去 ⇒ 这次不自改（盘上保持原样）",
            )
        }
        // ② 立刻跑 206
        val outcome = eval(p)
        val line = AutonomyEvalGate.lineOf(outcome)
        // ③ 过 ⇒ 留；不过 ⇒ 退
        if (AutonomyEvalGate.passedWithoutRegression(outcome)) {
            return kept(p, backup, line, outcome)
        }
        val restored = SkillStore.restore(plan.file, stamp())
        return when (restored) {
            is SkillWriteResult.Written -> SelfEditOutcome.RolledBack(
                proposal = p,
                evalLine = line,
                restoreLine = "已回滚到 $backup（${plan.file.absolutePath}）",
                ledger = record(p, CommitmentOutcome.WORSE, line, backup, rolledBack = true),
            )
            is SkillWriteResult.Rejected -> SelfEditOutcome.RollbackFailed(
                proposal = p,
                evalLine = line,
                why = restored.reason,
                filePath = plan.file.absolutePath,
            )
        }
    }

    /** 现读技能正文（没文件 ⇒ 空串，按"新建"处理，`decide` 那边会挡掉 A 档的新增）。 */
    private fun readSkillBody(name: String): String? {
        val f = File(File(SkillRules.projectSkillDir(projectRoot), name), SkillRules.SKILL_FILE)
        return if (f.isFile) runCatching { f.readText(Charsets.UTF_8) }.getOrNull() else null
    }

    // ── 人设说话纪律：只走 PersonaFiles 那一条道 ────────────────────────────

    private fun personaPath(p: AutonomyProposal): SelfEditOutcome {
        val layer = PersonaLayer.C_PROJECT
        val current = personaFiles.read(layer) ?: ""
        val rule = PersonaRules.propose(layer, current, p.newText, "A 档自改：${p.kind.label}")
            ?: return SelfEditOutcome.NotWritten(
                AutonomyVerdict.AutoEdit(p),
                "落不成提案：拟写入是空的，或这一层不可写（A 内核只读）",
            )
        val hadFile = personaFiles.fileOf(layer).isFile
        if (!personaFiles.applyProposal(rule)) {
            return SelfEditOutcome.NotWritten(AutonomyVerdict.AutoEdit(p), "人设写盘失败，没改成")
        }
        // 写口自己会备份；这里复核"确实有得退"（首次写入没原件可退 ⇒ 允许，删掉新写的等于回到空）
        val backup = PersonaBackup.latestOf(personaFiles.fileOf(layer))?.name
        if (hadFile && backup == null) {
            return SelfEditOutcome.NotWritten(
                AutonomyVerdict.AutoEdit(p),
                "写前备份没落成，改了退不回去 ⇒ 这次不自改",
            )
        }
        val outcome = eval(p)
        val line = AutonomyEvalGate.lineOf(outcome)
        if (AutonomyEvalGate.passedWithoutRegression(outcome)) {
            return kept(p, backup, line, outcome)
        }
        val restored = PersonaBackup.restore(personaFiles.fileOf(layer), stamp())
        return when (restored) {
            is PersonaBackupResult.Done -> SelfEditOutcome.RolledBack(
                proposal = p,
                evalLine = line,
                restoreLine = "已回滚 C 段（备份 ${restored.backup.name}）",
                ledger = record(p, CommitmentOutcome.WORSE, line, restored.backup.name, rolledBack = true),
            )
            is PersonaBackupResult.Rejected -> SelfEditOutcome.RollbackFailed(
                proposal = p,
                evalLine = line,
                why = restored.reason,
                filePath = personaFiles.fileOf(layer).absolutePath,
            )
        }
    }

    // ── 留着（A 档的"留"同样要留痕；不是没人管）─────────────────────────────

    private fun kept(
        p: AutonomyProposal,
        backup: String?,
        line: String,
        outcome: EvalOutcome,
    ): SelfEditOutcome.Kept {
        val better = if (outcome is EvalOutcome.Ran && outcome.compare.verdict == EvalVerdict.ADOPT) {
            CommitmentOutcome.BETTER
        } else {
            // DEFER：跑成了、没见回归，但也没见好——账按"没变好"记，别谎报成"变好"
            CommitmentOutcome.SAME
        }
        return SelfEditOutcome.Kept(
            proposal = p,
            backup = backup,
            evalLine = line,
            ledger = record(p, better, line, backup, rolledBack = false),
        )
    }

    /**
     * 208 落一条（派工单 §1「监督」那一行：人**只需要知道改了什么**，不需要知道该改哪里）。
     *
     * `evidence` 用 [AutonomyTell.evidenceKey] 那个拼法＋对拍行，抽检按档位分组才数得出来。
     * 回滚另追一条 `rollback_of`——回滚本身也是改动，不留痕就等于"什么都没发生过"；
     * 它跟着被回滚那条进同一组（`groupOverride`），否则分组卡的「可撤销数」会对不上。
     */
    private fun record(
        p: AutonomyProposal,
        outcome: CommitmentOutcome,
        evalLine: String,
        backup: String?,
        rolledBack: Boolean,
    ): DecisionRecord? {
        val auto = AutonomyVerdict.AutoEdit(p)
        val draft = DecisionDraft(
            kind = DecisionKind.AUTO_ACT,
            actor = DecisionActor.AUTO,
            reason = "${AutonomyTell.line(auto)}｜对拍=${outcome.wire}",
            evidence = AutonomyTell.evidenceKey(auto) + "｜" + evalLine.take(80),
            before = p.currentText.take(60),
            after = if (rolledBack) "已自动回滚：${p.kind.label}" else p.newText.take(60),
            atMs = nowMs(),
            rollbackRef = backup ?: "",
            reversible = !rolledBack,
        )
        val main = DecisionLedger.record(draft)
        if (rolledBack && main != null) {
            DecisionLedger.record(
                draft.copy(
                    kind = DecisionKind.ROLLBACK_OF,
                    reason = "A 档自改没通过对拍，自动退回：${p.kind.label}→${p.target}",
                    evidence = "rollback_of:${main.id}",
                    before = "已写入的新版",
                    after = backup ?: "上一版",
                    rollbackOf = main.id,
                    reversible = false,
                    groupOverride = main.group,
                ),
            )
        }
        return main
    }
}

// ── 一句人话（卡面/日志/设置页共用）────────────────────────────────────────────

internal object SelfEditTell {
    fun line(o: SelfEditOutcome): String = when (o) {
        is SelfEditOutcome.NotWritten -> "没自改：${o.line}"
        is SelfEditOutcome.Kept -> "自改保留：${o.proposal.kind.label}→${o.proposal.target}（备份 ${o.backup ?: "无"}；对拍 ${o.evalLine.take(40)}）"
        is SelfEditOutcome.RolledBack -> "自改已自动回滚：${o.proposal.kind.label}→${o.proposal.target}（${o.restoreLine}）"
        is SelfEditOutcome.RollbackFailed -> "⚠️ 自改没通过对拍且回滚失败：${o.why}｜文件 ${o.filePath}｜请人工处理"
    }
}
