// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十六批 §1/§4：技能的**落盘层**。两条铁律：
//
// 1. **确认才落盘**：学习器产出的是内存草稿，本类只在用户点了「保存技能」之后动手；
// 2. **写盘前先扫凭证**（负例 7）：技能 JSON 里出现明文密码/凭证 ⇒ 一个字节不写。
//
// 落两样东西（同一目录，一个技能一个家）：
// - `技能/<名>/回放.json` —— 机器回放数据（本批新立的格式，schema zl-teach/1）；
// - `技能/<名>/SKILL.md` —— 人读卡（用途/步骤摘要/定位链/指纹），**走 184 的写口**
//   （[SkillStore.plan]/[apply]＋索引一行，房型与 SkillCardWriter.save 同款，不新造）。
//
// 审计：每次**执行**一行 JSON，落 `技能/_回放审计.jsonl`（房型照 SkillAudit 的 _变更.jsonl）。
// 执行器永不回写技能文件——本文件没有任何入口能让执行器改技能（「技能不自动改」结构性成立）。
package com.example.zhengdao.baopu

import com.example.zhengdao.core.RunLog
import java.io.File
import java.security.MessageDigest

internal object TeachReplayStore {

    /** 回放数据文件名（与 SKILL.md 同目录旁挂）。 */
    const val REPLAY_FILE = "回放.json"

    /** 执行审计文件（技能根目录下，追加一行一条）。 */
    const val AUDIT_FILE = "_回放审计.jsonl"

    /**
     * 验收状态（第二百五十五批）：与 `回放.json` 同目录旁挂的**状态快照**。
     * 它**不是第二本账**——只存事件推不出来的东西（样本／累计修正／降级与 stale 的落章）；
     * 每次降级同时进 `技能/_变更.jsonl` 与 208 `决策记录.jsonl`，两边按 opId 对得上。
     */
    const val ACCEPTANCE_FILE = "验收.json"

    /** 落盘结果：written 非空＝成功（skillMd 与回放.json 都在）；否则 reason 说明。 */
    data class SaveResult(val written: File?, val reason: String?, val notice: String? = null) {
        companion object {
            fun ok(file: File, notice: String? = null) = SaveResult(file, null, notice)
            fun reject(reason: String) = SaveResult(null, reason)
        }
    }

    fun sidecarFile(projectRoot: File, name: String): File =
        File(SkillRules.projectSkillDir(projectRoot), "$name/$REPLAY_FILE")

    fun skillMdFile(projectRoot: File, name: String): File =
        File(SkillRules.projectSkillDir(projectRoot), "$name/${SkillRules.SKILL_FILE}")

    /**
     * 确认后的落盘（唯一写口）。顺序：凭证扫描 → 名字安全化 → 回放.json 先写临时文件 →
     * SKILL.md 走 184 写口 → 临时文件转正。SKILL.md 成功而回放.json 转正失败 ⇒ 返回失败
     * 原因（人可以手删半条，机器不静默吞）。
     */
    fun saveConfirmed(
        draft: TeachSkillDraft,
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        stampText: String,
    ): SaveResult {
        val json = draft.skill.toJsonText()
        // ① 凭证扫描（负例 7）：发现问题一个字节不写
        val findings = TeachSafety.credentialFindings(json)
        if (findings.isNotEmpty()) {
            return SaveResult.reject("技能里有明文凭证，写盘前拒绝：${findings.joinToString("；")}")
        }
        val safeName = SkillStore.sanitizeName(draft.skill.name)
            ?: return SaveResult.reject("技能名不可用（只许字母／数字／中文／_／-，且不能为空）")

        // ①.5 第二百五十五批 · 第一段入库闸（G2 → G3 → G1）：拒收就一个字节不写；
        // G1 不过（0/1 个站点验过）⇒ 强制草稿，并把原因写进人读卡的「示教警示」。
        val existingMd = skillMdFile(projectRoot, safeName).takeIf { it.isFile }
            ?.let { runCatching { it.readText(Charsets.UTF_8) }.getOrNull() }
        val gate = SkillAcceptance.entryGate(
            subject = EntrySubject.Replay(draft.skill, contractBody = existingMd),
            rehearsal = Rehearsal(sites = listOfNotNull(TeachSafety.hostOf(draft.skill.startUrl))),
        )
        val gateNotice: String? = when (gate) {
            is EntryVerdict.Reject -> return SaveResult.reject(gate.reason)
            is EntryVerdict.DraftOnly -> gate.reason
            is EntryVerdict.Pass -> null
        }

        // ② 回放.json 先进临时文件（不转正前盘上没有它）
        val target = sidecarFile(projectRoot, safeName)
        val tmp = File(target.parentFile, "$REPLAY_FILE.tmp-$stampText")
        runCatching {
            target.parentFile?.mkdirs()
            tmp.writeText(json, Charsets.UTF_8)
        }.getOrElse {
            return SaveResult.reject("回放数据写临时文件失败：${it.javaClass.simpleName}")
        }

        // ③ SKILL.md 走 184 写口（房型＝SkillCardWriter.save：先探盘上状态，头按现状重算）
        val humanBody = skillMdBody(draft.skill, draft.warnings + listOfNotNull(gateNotice))
        val probe = SkillStore.plan(
            proposal = SkillProposal(
                scope = SkillScope.PROJECT,
                name = safeName,
                body = humanBody,
                reason = "示教录制（教它一次）",
                draft = true,
            ),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stampText,
        )
        probe.blocked?.let {
            runCatching { tmp.delete() }
            return SaveResult.reject(it)
        }
        val existingBody = if (probe.file.isFile) {
            runCatching { probe.file.readText(Charsets.UTF_8) }.getOrNull()
        } else {
            null
        }
        val isDraft = gateNotice != null || (existingBody?.let { SkillStore.statusOf(it) == "draft" } ?: true)
        val uses = existingBody?.let { SkillStore.usesOf(it) } ?: 0
        val finalBody = if (isDraft) {
            SkillStore.draftHeader(status = "draft", uses = uses) + "\n" + humanBody
        } else {
            humanBody
        }
        val proposal = SkillProposal(
            scope = SkillScope.PROJECT,
            name = safeName,
            body = finalBody,
            reason = "示教录制（教它一次）",
            draft = false,
        )
        val planned = SkillStore.plan(
            proposal = proposal.copy(draft = isDraft),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stampText,
        )
        if (planned.file.absolutePath != probe.file.absolutePath) {
            runCatching { tmp.delete() }
            return SaveResult.reject("技能名「$safeName」落不到它自己的目录，先改个名字")
        }
        // apply 用带好头的 proposal（draft=false，头在 finalBody 里）——与 SkillCardWriter 同款，
        // 不这样会把草稿头写两遍。
        when (val applied = SkillStore.apply(planned.copy(proposal = proposal), personaFiles, personaFiles.read(PersonaLayer.C_PROJECT).orEmpty())) {
            is SkillWriteResult.Rejected -> {
                runCatching { tmp.delete() }
                return SaveResult.reject(applied.reason)
            }
            is SkillWriteResult.Written -> Unit
        }

        // ④ 转正（同目录 rename 原子性够用；失败如实报，不静默）
        return if (tmp.renameTo(target) || runCatching {
                tmp.copyTo(target, overwrite = true).let { tmp.delete() }
            }.getOrDefault(false) && target.isFile
        ) {
            // 第二百五十五批：重教一遍＝验收从头来（旧样本属于旧正文，不清会拿旧账判新技能）
            saveAcceptance(projectRoot, safeName, SkillAcceptance.onRelearned())
            SaveResult.ok(target, gateNotice)
        } else {
            SaveResult.reject("SKILL.md 已写（${planned.file.name}），但回放数据转正失败——请重存一次")
        }
    }

    /** 读回技能（项目层；解析失败/不存在 ⇒ null）。 */
    fun load(projectRoot: File, name: String): TeachSkill? {
        val f = sidecarFile(projectRoot, name)
        if (!f.isFile) return null
        return runCatching { TeachSkill.fromJson(f.readText(Charsets.UTF_8)) }.getOrNull()
    }

    /** 执行审计追加一行（失败只记日志，不影响回放结果——审计不该弄丢回放本身）。 */
    fun appendAudit(projectRoot: File, line: String): Boolean = runCatching {
        val root = SkillRules.projectSkillDir(projectRoot)
        root.mkdirs()
        File(root, AUDIT_FILE).appendText(line + "\n", Charsets.UTF_8)
        true
    }.getOrElse {
        RunLog.log("回放审计行写入失败：${it.javaClass.simpleName}")
        false
    }

    /** 规范序列化的 SHA-256（「技能未变（哈希不变）」判据用）。 */
    fun sha256Of(skill: TeachSkill): String {
        val d = MessageDigest.getInstance("SHA-256").digest(skill.toJsonText().toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }

    /** SKILL.md 人读卡（技能卡三件套：用途、步骤摘要、定位链＋指纹）。 */
    internal fun skillMdBody(skill: TeachSkill, warnings: List<String>): String = buildString {
        appendLine("# 回放技能：${skill.name}")
        appendLine()
        appendLine("由玄览「教它一次」示教生成；机器回放数据在同目录 `${REPLAY_FILE}`（schema ${TeachSkill.SCHEMA}）。")
        appendLine()
        appendLine("## 用途")
        appendLine(skill.purpose.ifBlank { "（示教时没写用途，回放前补一句）" })
        appendLine()
        appendLine("## 步骤")
        skill.steps.forEach { s ->
            appendLine("${s.index}. ${TeachLearner.stepLine(s)}")
        }
        appendLine()
        appendLine("## 定位链（tier 越小越优先；十级：role→testid→aria→id→name→placeholder→文本→表单上下文→CSS→XPath）")
        skill.steps.forEach { s ->
            appendLine("- 第 ${s.index} 步：${s.locators.joinToString(" → ") { it.describe() }}")
        }
        appendLine()
        appendLine("## 指纹（起点页关键表头/按钮；对不上 ⇒ 回放停手报「页面结构变了」）")
        skill.fingerprint.anchors.forEach { appendLine("- [${it.kind}] ${it.name}") }
        appendLine()
        appendLine("## 安全")
        appendLine("- 授权域：${skill.allowedDomains.joinToString("、").ifEmpty { "（空——回放会拒绝执行）" } }")
        appendLine("- 点过的元素类别：${skill.clickTagWhitelist.joinToString("/").ifEmpty { "（没有点击步骤）" } }")
        appendLine("- 拒点词（提交|确认|删除|作废|发货|派车|结算|支付|充值）命中 ⇒ 代码层面一律不点")
        if (warnings.isNotEmpty()) {
            appendLine("- 示教警示：")
            warnings.forEach { appendLine("  - $it") }
        }

    }

    // ── 第二百五十五批 · 第二段用中校验（状态旁挂；事件仍进既有两本账）────────────

    /** 一次记账的落地结论（给调用方一句话提示用）。 */
    data class RunRecord(
        val update: AcceptanceUpdate,
        val demoted: Boolean,
        val demotionOpIds: List<String>,
        val notes: List<String>,
    )

    fun acceptanceFile(projectRoot: File, name: String): File =
        File(SkillRules.projectSkillDir(projectRoot), "$name/$ACCEPTANCE_FILE")

    /** 读验收状态（文件不在/读不出 ⇒ 空状态；**不改写**盘上坏文件——下一笔正常覆盖）。 */
    fun loadAcceptance(projectRoot: File, name: String): AcceptanceState {
        val f = acceptanceFile(projectRoot, name)
        if (!f.isFile) return AcceptanceState()
        return runCatching { AcceptanceState.fromJson(f.readText(Charsets.UTF_8)) }.getOrNull()
            ?: AcceptanceState()
    }

    /** 写验收状态（临时文件转正；失败只 log——记账失败不许把回放本身弄丢）。 */
    fun saveAcceptance(projectRoot: File, name: String, state: AcceptanceState): Boolean = runCatching {
        val f = acceptanceFile(projectRoot, name)
        f.parentFile?.mkdirs()
        val tmp = File(f.parentFile, "$ACCEPTANCE_FILE.tmp")
        tmp.writeText(state.toJsonText(), Charsets.UTF_8)
        if (!tmp.renameTo(f)) {
            tmp.copyTo(f, overwrite = true)
            tmp.delete()
        }
        true
    }.getOrElse {
        RunLog.log("验收状态写入失败：$name（${it.javaClass.simpleName}）")
        false
    }

    /**
     * 一次**执行实例**记进验收（幂等：同 opId 一个数都不放大）。
     *
     * U1 触发时**当场**降为草稿：走 [SkillStore.plan]/[apply]（不手改文件），
     * 在 `技能/_变更.jsonl` 记 `skill_demoted`，并在 208 记一条 `skill_demoted`
     * （evidence 带两次 opId）——本批不新建第二本账。
     */
    fun recordRun(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        skill: TeachSkill,
        report: TeachRunReport,
        nowMs: Long,
        opId: String? = null,
    ): RunRecord {
        val state = loadAcceptance(projectRoot, skill.name)
        val sample = AcceptanceSample(
            opId = opId ?: SkillAcceptance.opIdOf(report, nowMs),
            atMs = nowMs,
            ok = report.ok,
            fingerprintOk = fingerprintOkOf(report),
            driftCount = report.drifts.size,
            site = TeachSafety.hostOf(skill.startUrl) ?: "",
        )
        val update = SkillAcceptance.applyRun(state, sample, nowMs)
        if (update.duplicate) {
            return RunRecord(update, demoted = false, demotionOpIds = emptyList(), notes = update.notes)
        }
        saveAcceptance(projectRoot, skill.name, update.state)
        val notes = update.notes.toMutableList()
        val demoted = if (update.demotedNow) {
            applyDemotion(
                projectRoot = projectRoot,
                globalRoot = globalRoot,
                personaFiles = personaFiles,
                name = skill.name,
                opIds = update.demotionOpIds,
                nowMs = nowMs,
                notes = notes,
            )
        } else {
            false
        }
        return RunRecord(update, demoted, update.demotionOpIds, notes)
    }

    /**
     * 指纹是否还对得上：执行器报「页面结构变了」或「重试前指纹复核不通过」⇒ 对不上（U3）；
     * 「取不到页面结构指纹」只是**判不了**（页面没加载完），不当成环境变了——不猜。
     */
    internal fun fingerprintOkOf(report: TeachRunReport): Boolean {
        val r = report.stopReason ?: return true
        return !(r.contains("页面结构变了") || r.contains("指纹复核不通过"))
    }

    /** U1 落地：active → draft（保留 uses 与正文；索引行跟着改回「草稿·未验证」）。 */
    private fun applyDemotion(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        opIds: List<String>,
        nowMs: Long,
        notes: MutableList<String>,
    ): Boolean {
        val file = skillMdFile(projectRoot, name)
        if (!file.isFile) return false
        val old = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        // 已经是草稿 ⇒ 不重复写、不重复留痕（幂等的最后一道保险）
        if (SkillStore.statusOf(old) != "active") return false
        val uses = SkillStore.usesOf(old)
        val body = SkillStore.draftHeader(status = "draft", uses = uses) + "\n" + stripStatusHeader(old)
        val base = SkillProposal(
            scope = SkillScope.PROJECT,
            name = name,
            body = body,
            reason = "连续 ${SkillAcceptance.DEMOTE_FAILURES} 次失败：降为草稿",
            draft = false,
        )
        val planned = SkillStore.plan(
            proposal = base.copy(draft = true),
            projectRoot = projectRoot,
            globalRoot = globalRoot,
            backupStamp = stamp(nowMs),
            indexCountAfterAdd = 0,
        )
        planned.blocked?.let {
            notes += "降级没写成：$it"
            return false
        }
        val beforeSha = sha256Text(old).take(12)
        return when (val r = SkillStore.apply(planned.copy(proposal = base), personaFiles, personaFiles.read(PersonaLayer.C_PROJECT).orEmpty())) {
            is SkillWriteResult.Rejected -> {
                notes += "降级没写成：${r.reason}"
                false
            }
            is SkillWriteResult.Written -> {
                val reason = "连续 ${SkillAcceptance.DEMOTE_FAILURES} 次执行失败（opId ${opIds.joinToString("、")}）"
                if (!SkillAudit.record(
                        skillFile = file,
                        scope = "project",
                        name = name,
                        action = "skill_demoted",
                        reason = reason,
                        beforeSha = beforeSha,
                        indexUpdated = r.indexUpdated,
                        nowMs = nowMs,
                        source = "acceptance",
                    )
                ) {
                    RunLog.log("降级审计行写入失败：$name")
                }
                val rec = DecisionLedger.record(
                    DecisionDraft(
                        kind = DecisionKind.SKILL_DEMOTED,
                        actor = DecisionActor.AUTO,
                        reason = reason,
                        evidence = "skill:project/$name@$beforeSha;opId=${opIds.joinToString(",")}",
                        before = "active",
                        after = "draft",
                        atMs = nowMs,
                    ),
                )
                if (rec == null) {
                    RunLog.log("降级决策记录写入失败：$name（${DecisionLedger.lastError ?: "未知"}）")
                }
                notes += "已降为草稿：$name（不再自动选中；依据 opId ${opIds.joinToString("、")}）"
                true
            }
        }
    }

    /** 只剥第一行状态头（契约行也是注释，不能像 contentWithoutHeader 那样整段剥掉）。 */
    private fun stripStatusHeader(body: String): String {
        val lines = body.lines()
        val head = lines.firstOrNull()?.trim().orEmpty()
        return if (head.startsWith("<!--") && head.contains("status=")) lines.drop(1).joinToString("\n") else body
    }

    private fun sha256Text(text: String): String {
        val d = MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }
    }
}
