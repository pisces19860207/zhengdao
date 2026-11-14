// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百四十三批（2026-11-14）· 学习链第⑦环：**教出来的脚本 → 技能候选 → 568-A 技能域灰度链**。
//
// 通路只有这一条（派工单 §一）：脚本回放收尾 ⇒ 够格就生成一条**技能候选**（不新造候选类型：
// `EvoCandidate`＋既有技能写口）⇒ 进 568-A 的技能域灰度（`LowRiskEvo.gate` → 观察账 →
// 转正／回滚）⇒ 转正后由人一键收回（脚本保留）。
//
// 四件事，一件都不许本文件自己发明：
//   · **判够不够格**＝[ScriptEvoRules]（纯层：3 次同版本成功、校验点全命中、无不可逆动作）；
//   · **能不能进灰度**＝既有 `LowRiskEvo.gate`（红线 → 档位 → 深度，568-A 的判据一字不改，
//     本文件只把结论翻成一句话并把拒行交给 `LowRiskEvo.recordRefusal`）；
//   · **账**＝既有的三本：观察行走 473（`LowRiskEvo.observeRun`）、候选行走 `技能/_变更.jsonl`
//     （`SkillAudit` 的房型，`candidateId` 就是候选 id）、转正/回滚行走 207/208
//     （`SkillPromote.auditPromote` / `auditRevert`——`skill_promote` 与 `promote_undo`，
//     与技能页那颗「转正」按钮同一本账）；**不新建第二本账**；
//   · **写盘**＝`SkillStore.plan`/`apply` 唯一写口（与 write_skill 同款，先备份后写正文再写索引）。
//
// 两条边界（派工单 §三）：
//   ① 自进化的对象是「动作序列与校验点」——本文件只改 SKILL.md 的状态头（草稿↔正式），
//      **一个字节都不改回放.json**（脚本本体归人改/重教）；这也是「一键收回后脚本仍在」的机器保证；
//   ② **不做自动转正**：转正必须人点头（[promote] 只由技能页/人点的那一下进来），
//      与既有技能政策（207 批：手动或满 3 次）一致；机制这一侧只做两件"不问人"的事——
//      生成候选、灰度坏消息回滚。
package com.example.zhengdao.baopu.evolution.script

import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillAudit
import com.example.zhengdao.baopu.SkillPromote
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillRules
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import com.example.zhengdao.baopu.TeachReplayStore
import com.example.zhengdao.baopu.TeachRunReport
import com.example.zhengdao.baopu.TeachSkill
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.evolution.lowrisk.EvoCandidate
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskEvo
import com.example.zhengdao.baopu.evolution.lowrisk.LowRiskVerdict
import com.example.zhengdao.core.RunLog
import com.example.zhengdao.core.TimeFormats
import java.io.File
import org.json.JSONObject

/** 一次动作的结局（ok 之外必带一句话：失败也要有声，不许静默）。 */
internal data class ScriptEvoOutcome(val ok: Boolean, val message: String) {
    companion object {
        fun ok(message: String) = ScriptEvoOutcome(true, message)
        fun refuse(message: String) = ScriptEvoOutcome(false, message)
    }
}

/**
 * 脚本 → 技能候选 → 灰度 → 转正/回滚/收回的装配缝（判据全在纯层与 568-A 的闸上）。
 *
 * 技能域线值固定 `REFLECTION_AUTO_EDIT`（568-A 登记的那一格：技能正文/步骤改动的灰度），
 * 不新开能力、不改登记表。
 */
internal object ScriptEvo {

    /** 候选行在 `技能/_变更.jsonl` 的 action（新增的一类行；老解析器不认它，读别类时不受影响）。 */
    const val ACTION_CANDIDATE = "script_candidate"

    private const val SCOPE = "project"

    /** 技能域——568-A 在登记表里的那一格（档位缺省灰度；不新开能力）。 */
    private val CAP = CanaryCapability.REFLECTION_AUTO_EDIT

    // ── 读 ──────────────────────────────────────────────────────────────────

    /** 盘上这条脚本现在够不够格（读技能＋验收样本现算；没有脚本 ⇒ null）。 */
    fun candidacyOf(projectRoot: File, name: String): ScriptCandidacy? {
        val skill = TeachReplayStore.load(projectRoot, name) ?: return null
        return ScriptEvoRules.evaluate(skill, samplesOf(projectRoot, name), ScriptEvoRules.versionOf(skill))
    }

    /** 这条技能是不是回放脚本（有没有 `回放.json`）——技能页/决策中心据此分流。 */
    fun isScript(projectRoot: File, name: String): Boolean =
        TeachReplayStore.sidecarFile(projectRoot, name).isFile

    // ── 回放收尾（生产调用点①：TeachReplay.recordAcceptance）────────────────

    /**
     * 一次回放落定之后的三件事（顺序即语义）：
     *
     * 1. **观察账**：这一跑的结果进 473（技能域的指标唯一来源，内存计数没有入口）——
     *    档位要不要动是梯子的事，本函数只落一条观察；
     * 2. **坏消息回滚**：已转正的脚本这一跑没成 ⇒ 立刻回草稿并留一行（灰度回滚；
     *    判据"写死"在 [rollback] 里，不问人——带病执行比退回草稿危险）；
     * 3. **够格就生成候选**：草稿且条件齐 ⇒ [consider]（档位不允许就只留着，不落候选行）。
     *
     * @return 有话要说时的一句人话（生成候选／回滚），没事发生 ⇒ null（不打扰）
     */
    fun onReplaySettled(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        skill: TeachSkill,
        report: TeachRunReport,
        nowMs: Long,
    ): String? {
        observe(skill.name, report, nowMs)
        val draft = isDraft(projectRoot, skill.name)
        if (!draft && !report.ok) {
            val why = "回放失败（${report.stopReason ?: "第 ${report.failedStep} 步"}）"
            return rollback(projectRoot, globalRoot, personaFiles, skill.name, why, nowMs).message
        }
        return if (draft) consider(projectRoot, skill, nowMs) else null
    }

    /** 落一条 473 观察行（失败只 log——账落不上不许把回放本身弄丢）。 */
    private fun observe(name: String, report: TeachRunReport, nowMs: Long) {
        runCatching {
            LowRiskEvo.observeRun(CAP, ok = report.ok, atMs = nowMs, askedHuman = report.ask != null)
        }.onFailure { RunLog.log("脚本候选：观察账没落上（$name／${it.javaClass.simpleName}）") }
    }

    // ── 生成候选 ────────────────────────────────────────────────────────────

    /**
     * 够格就生成候选（**幂等**：同一版本只落一行候选账；重教换版本 ⇒ 新的一行）。
     *
     * 档位那一层照 568-A 的口径：门说 Hold（档位关闭/深度不够）⇒ **候选保留、不落账**
     * （"还没轮到它"不是"它做错了"，255/495 的老纪律）；门说 Refuse（红线）⇒ 拒并记账。
     */
    fun consider(projectRoot: File, skill: TeachSkill, nowMs: Long): String? {
        val verdict = ScriptEvoRules.evaluate(
            skill,
            samplesOf(projectRoot, skill.name),
            ScriptEvoRules.versionOf(skill),
        )
        return when (verdict) {
            is ScriptCandidacy.Refused -> {
                RunLog.log(verdict.note)
                verdict.note
            }
            is ScriptCandidacy.NotYet -> {
                RunLog.log(verdict.note)
                null
            }
            is ScriptCandidacy.Ready -> admit(projectRoot, skill, verdict.candidate, nowMs)
        }
    }

    /** 过 568-A 的门；放行才落候选行（同 id 已有行 ⇒ 不重复落）。 */
    private fun admit(projectRoot: File, skill: TeachSkill, candidate: ScriptCandidate, nowMs: Long): String? {
        val evo = candidate.asEvoCandidate()
        when (val v = LowRiskEvo.gate(CAP, evo)) {
            is LowRiskVerdict.Refuse -> {
                LowRiskEvo.recordRefusal(CAP, v.redLine, v.note, evo, nowMs)
                RunLog.log("脚本候选没过自进化红线：${v.note}")
                return null
            }
            is LowRiskVerdict.Hold -> {
                RunLog.log("脚本够格但灰度档没开：${v.note}（候选保留，不落账）")
                return null
            }
            is LowRiskVerdict.Allow -> Unit
        }
        if (alreadyRecorded(projectRoot, candidate.id)) return null
        val written = SkillAudit.record(
            skillFile = TeachReplayStore.skillMdFile(projectRoot, skill.name),
            scope = SCOPE,
            name = skill.name,
            action = ACTION_CANDIDATE,
            reason = candidate.line(),
            beforeSha = "-",
            indexUpdated = false,
            nowMs = nowMs,
            candidateId = candidate.id,
        )
        return if (written) candidate.line() + " ⇒ 已生成技能候选（转正要人点；灰度档=${CAP.cn}）" else {
            RunLog.log("脚本候选行没落上：${skill.name}")
            null
        }
    }

    // ── 转正（人点的那一下；不自作主张）────────────────────────────────────

    /**
     * 把一条够格的脚本从草稿转正（判据②的"灰度"这一半：门不放行就不动一个字节）。
     *
     * 不满足条件时返回**为什么**（判据③ 的"报"落在这里：不可逆脚本会得到
     * 「脚本不允许转正：…」），而不是静默失败。
     */
    fun promote(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        nowMs: Long,
    ): ScriptEvoOutcome {
        val skill = TeachReplayStore.load(projectRoot, name)
            ?: return ScriptEvoOutcome.refuse("没有这个回放技能：$name（先在玄览里教一遍）")
        val file = TeachReplayStore.skillMdFile(projectRoot, name)
        val old = readBody(file) ?: return ScriptEvoOutcome.refuse("读不出技能正文：$name")
        if (SkillStore.statusOf(old) != "draft") return ScriptEvoOutcome.refuse("$name 已经是正式技能了")
        val candidate = when (
            val c = ScriptEvoRules.evaluate(skill, samplesOf(projectRoot, name), ScriptEvoRules.versionOf(skill))
        ) {
            is ScriptCandidacy.Refused -> return ScriptEvoOutcome.refuse(c.note)
            is ScriptCandidacy.NotYet -> return ScriptEvoOutcome.refuse(c.note)
            is ScriptCandidacy.Ready -> c.candidate
        }
        val evo = candidate.asEvoCandidate()
        when (val v = LowRiskEvo.gate(CAP, evo)) {
            is LowRiskVerdict.Refuse -> {
                LowRiskEvo.recordRefusal(CAP, v.redLine, v.note, evo, nowMs)
                return ScriptEvoOutcome.refuse("这条候选踩了自进化红线，不转正：${v.note}")
            }
            is LowRiskVerdict.Hold -> return ScriptEvoOutcome.refuse("灰度没走到这一步，先不转正：${v.note}")
            is LowRiskVerdict.Allow -> Unit
        }
        val reason = "脚本稳定：${candidate.okReplays} 次回放全命中"
        val written = writeStatus(projectRoot, globalRoot, personaFiles, name, SkillStore.promoteBody(old), false, reason)
        written.reason?.let { return ScriptEvoOutcome.refuse(it) }
        if (!SkillPromote.auditPromote(file, SCOPE, name, reason, nowMs)) {
            RunLog.log("脚本转正记录写入失败：$name")
        }
        return ScriptEvoOutcome.ok(
            "${candidate.line()} ⇒ 已转正（决策中心可一键收回，脚本保留）",
        )
    }

    // ── 回滚 / 收回（两条路都只改状态头，脚本一个字节不动）──────────────────

    /** 灰度回滚（机制那一侧：已转正的脚本一跑失败就回草稿——带病执行比退回草稿危险）。 */
    fun rollback(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        why: String,
        nowMs: Long,
    ): ScriptEvoOutcome = demote(projectRoot, globalRoot, personaFiles, name, "灰度回滚：$why", nowMs)

    /** 一键收回（人那一侧）：回到未转正状态，**脚本保留**（回放.json 一个字节不动）。 */
    fun revoke(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        nowMs: Long,
    ): ScriptEvoOutcome = demote(projectRoot, globalRoot, personaFiles, name, "人收回：回到未转正（脚本保留）", nowMs)

    /** 回滚与收回的同一件活：状态头改回 draft ＋ 留一行 `promote_undo`（207/208 的既有账）。 */
    private fun demote(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        why: String,
        nowMs: Long,
    ): ScriptEvoOutcome {
        val file = TeachReplayStore.skillMdFile(projectRoot, name)
        val old = readBody(file) ?: return ScriptEvoOutcome.refuse("读不出技能正文：$name")
        val body = SkillPromote.revertBody(old)
            ?: return ScriptEvoOutcome.refuse("$name 不是正式技能，没有可回滚的转正")
        val written = writeStatus(projectRoot, globalRoot, personaFiles, name, body, true, why)
        written.reason?.let { return ScriptEvoOutcome.refuse(it) }
        if (!SkillPromote.auditRevert(file, SCOPE, name, nowMs)) {
            RunLog.log("脚本回滚记录写入失败：$name")
        }
        return ScriptEvoOutcome.ok("已回到未转正：$name（$why）；脚本原样保留")
    }

    // ── 小工具 ──────────────────────────────────────────────────────────────

    private fun samplesOf(projectRoot: File, name: String) =
        TeachReplayStore.loadAcceptance(projectRoot, name).samples

    private fun isDraft(projectRoot: File, name: String): Boolean =
        readBody(TeachReplayStore.skillMdFile(projectRoot, name))?.let { SkillStore.statusOf(it) == "draft" } ?: true

    private fun readBody(file: File): String? =
        if (file.isFile) runCatching { file.readText(Charsets.UTF_8) }.getOrNull() else null

    /** 候选行是不是已经落过（同 id＝同脚本同版本；幂等靠这一句，不靠内存计数）。 */
    private fun alreadyRecorded(projectRoot: File, candidateId: String): Boolean {
        val file = SkillAudit.fileIn(SkillRules.projectSkillDir(projectRoot))
        if (!file.isFile) return false
        val text = runCatching { file.readText(Charsets.UTF_8) }.getOrNull() ?: return false
        // 空行先剔掉：append-only 文件末尾恒有一个换行，`line.isBlank() || …` 会让 any 恒真
        // ——那正是「幂等」写成「永远不落行」的经典假实现（643 自测当场抓到）。
        return text.lines().filter { it.isNotBlank() }.any { line ->
            runCatching {
                val o = JSONObject(line)
                o.optString("action") == ACTION_CANDIDATE && o.optString("candidateId") == candidateId
            }.getOrDefault(false)
        }
    }

    /** 状态头那一笔（与 `SkillTools.writeViaStore` 同款：正文自己带好头，索引跟着状态走）。 */
    private fun writeStatus(
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        name: String,
        newBody: String,
        stillDraft: Boolean,
        reason: String,
    ): WriteOutcome {
        val base = SkillProposal(SkillScope.PROJECT, name, newBody, reason, draft = false)
        val planned = SkillStore.plan(
            base.copy(draft = stillDraft),
            projectRoot,
            globalRoot,
            TimeFormats.fileStamp(),
            indexCountAfterAdd = 0,
        )
        planned.blocked?.let { return WriteOutcome(it) }
        return when (
            val r = SkillStore.apply(planned.copy(proposal = base), personaFiles, personaFiles.read(PersonaLayer.C_PROJECT).orEmpty())
        ) {
            is SkillWriteResult.Written -> WriteOutcome(null)
            is SkillWriteResult.Rejected -> WriteOutcome(r.reason)
        }
    }

    /** 写盘结论（reason 非空＝没写成／写成了但有话要说）。 */
    private data class WriteOutcome(val reason: String?)

    /** 候选 → 568-A 的候选形状（技能域线值；body 就是证据那一句，账行里看得见）。 */
    private fun ScriptCandidate.asEvoCandidate(): EvoCandidate =
        EvoCandidate(domain = CAP.wire, target = "script:$scriptId@$version", body = line())
}
