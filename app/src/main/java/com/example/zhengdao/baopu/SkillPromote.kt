// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百零七批 §1.5（2026-10-25 用户拍板，种子与所有草稿技能通用）：
// 草稿 → 转正**只有两条路**——① 用户点「转正」（手动，一次到位）；② 用满 3 次自动转正（不弹卡，
// 用户口径：确认卡已经够多了，这里别再打扰）。两条路都必须在**决策中心**留一条记录，
// 并且**可一键回退为草稿**（回退＝改 status 与索引标记，不删正文）。
//
// 记录落点：技能审计旁挂账本 `技能/_变更.jsonl`（action=promote / promote_undo）——
// 不另起第二套账本（85 批口径），决策中心「记录页」加一个数据源读它。
// 判据与解析全在这层（JVM 可测）；转正/回退的写盘本身仍然只经 SkillStore 那一条路。
package com.example.zhengdao.baopu

import org.json.JSONObject
import java.io.File
import com.example.zhengdao.core.RunLog

internal object SkillPromote {

    /** 审计行 action：转正 / 决策中心回退。 */
    const val ACTION_PROMOTE = "promote"
    const val ACTION_REVERT = "promote_undo"

    /**
     * 审计行 action：草稿自动治理的软退役 / 技能页恢复（第二百零五批追加件③）。
     *
     * 只进决策中心**记录**（谁在什么时候自动退了哪条草稿、恢复可回溯）；
     * 「可回退」在技能页已退役组的「恢复」按钮——恢复走 [SkillAudit.record] 记
     * [ACTION_DRAFT_RETIRE_UNDO]，闭环在账本里看得见。
     */
    const val ACTION_DRAFT_RETIRE = "skill_draft_retire"
    const val ACTION_DRAFT_RETIRE_UNDO = "draft_retire_undo"

    /** 转正原因（派工单 §1.5 的两个值，一字不改——决策中心照它显示）。 */
    const val REASON_MANUAL = "手动"
    const val REASON_FULL_USES = "满3次"

    /** 种子来源标（与安装审计 `source=seed` 同值；非种子为 null）。 */
    const val SOURCE_SEED = "seed"

    /**
     * 这次写盘是不是一次「转正」；是 ⇒ 走的哪条路。
     *
     * 判据：老正文草稿、新正文正式 ⇒ 转正；**次数加了** ⇒ 是「用满 3 次」那一次
     * （184 的 `afterUse` 在第 3 次使用的同一份正文里改头，次数与状态不拆两笔）；
     * 次数没动 ⇒ 用户点的「转正」。其余（草稿→草稿、正式→草稿〔即回退〕、正式→正式）⇒ null。
     */
    fun promoteReasonOf(oldBody: String, newBody: String): String? {
        if (SkillStore.statusOf(oldBody) != "draft") return null
        if (SkillStore.statusOf(newBody) != "active") return null
        return if (SkillStore.usesOf(newBody) > SkillStore.usesOf(oldBody)) REASON_FULL_USES else REASON_MANUAL
    }

    /** 回退要写回的正文：头改回 `status=draft`（uses 原样保留），正文一字不动。非正式技能 ⇒ null。 */
    fun revertBody(body: String): String? {
        if (SkillStore.statusOf(body) != "active") return null
        val lines = body.lines()
        val head = lines.firstOrNull()?.trim().orEmpty()
        val rest = if (head.startsWith("<!--") && head.contains("status=")) {
            lines.drop(1).joinToString("\n")
        } else {
            body
        }
        return SkillStore.draftHeader(status = "draft", uses = SkillStore.usesOf(body)) + "\n" + rest
    }

    /** 决策中心要的一条转正/回退记录（由审计行解析而来）。 */
    data class PromoteLine(
        val name: String,
        val action: String,
        val reason: String,
        val source: String?,
        val ts: Long,
    )

    /**
     * 转正 ⇒ 决策中心留一条（审计旁挂账本）。写失败 ⇒ false 由调用方 log——
     * 审计写不进不许把已经落盘的转正回滚（71 批① 同口径）。
     */
    fun auditPromote(skillFile: File, scope: String, name: String, reason: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        auditPromoted(skillFile, scope, name, reason, nowMs).also {
            // 第二百零八批 §1 `skill_promote`：转正进决策中心记录页（actor 按两条路分：
            // 「满3次」＝自动，「手动」＝用户点的那一下）。证据 id＝技能键，回滚引用＝正文路径。
            DecisionLedger.record(
                DecisionDraft(
                    kind = DecisionKind.SKILL_PROMOTE,
                    actor = if (reason == REASON_FULL_USES) DecisionActor.AUTO else DecisionActor.USER,
                    reason = "转正：$reason",
                    evidence = "skill:$scope/$name",
                    before = "draft",
                    after = "active",
                    atMs = nowMs,
                    rollbackRef = skillFile.absolutePath,
                ),
            ) ?: com.example.zhengdao.core.RunLog.log(
                "技能转正：决策记录没写成（$name／${DecisionLedger.lastError}）",
            )
        }

    /** 207 的审计行本体（第二百零八批抽出来，好让「审计 + 决策记录」在同一处结算）。 */
    private fun auditPromoted(skillFile: File, scope: String, name: String, reason: String, nowMs: Long): Boolean =
        SkillAudit.record(
            skillFile = skillFile,
            scope = scope,
            name = name,
            action = ACTION_PROMOTE,
            reason = reason,
            beforeSha = "-",
            indexUpdated = true,
            nowMs = nowMs,
            source = if (scope == "global" && name in SeedSkills.NAMES) SOURCE_SEED else null,
        )

    /** 回退 ⇒ 再留一条（action=promote_undo；技能回草稿，正文不删）。 */
    fun auditRevert(skillFile: File, scope: String, name: String, nowMs: Long = System.currentTimeMillis()): Boolean =
        auditReverted(skillFile, scope, name, nowMs).also {
            // 第二百零八批 §2：**回滚本身也留一条**（`rollback_of` 指向被回滚那条转正记录）。
            // 账本里找不到那条（207 之前就转正的老账）⇒ 不硬编一个 id，只留日志——
            // `rollback_of` 指不出目标就等于没痕，那比不记更糟。
            val evidence = "skill:$scope/$name"
            val target = DecisionLedger.installedRoot()?.let { r ->
                DecisionLedger.read(r).firstOrNull {
                    it.kind == DecisionKind.SKILL_PROMOTE && it.evidence == evidence
                }
            }
            if (target == null) {
                com.example.zhengdao.core.RunLog.log("技能回退：决策记录里找不到对应的转正行（$name）——不落 rollback_of")
            } else {
                DecisionLedger.record(
                    DecisionDraft(
                        kind = DecisionKind.ROLLBACK_OF,
                        actor = DecisionActor.USER,
                        reason = "回退为草稿",
                        evidence = target.id,
                        before = "active",
                        after = "draft",
                        atMs = nowMs,
                        rollbackRef = skillFile.absolutePath,
                        rollbackOf = target.id,
                        groupOverride = DecisionGroup.SKILL,
                    ),
                ) ?: com.example.zhengdao.core.RunLog.log(
                    "技能回退：决策记录没写成（$name／${DecisionLedger.lastError}）",
                )
            }
        }

    /** 207 的回退审计行本体。 */
    private fun auditReverted(skillFile: File, scope: String, name: String, nowMs: Long): Boolean =
        SkillAudit.record(
            skillFile = skillFile,
            scope = scope,
            name = name,
            action = ACTION_REVERT,
            reason = "回退为草稿",
            beforeSha = "-",
            indexUpdated = true,
            nowMs = nowMs,
        )

    /** 审计文本 → 转正/回退/草稿治理记录（坏行与别的 action 一律忽略，账本里的杂行不炸解析）。 */
    fun parseLines(text: String?): List<PromoteLine> {
        if (text.isNullOrBlank()) return emptyList()
        val out = mutableListOf<PromoteLine>()
        text.lines().forEach { raw ->
            val o = runCatching { JSONObject(raw) }.getOrNull() ?: return@forEach
            val action = o.optString("action")
            if (action !in PARSED_ACTIONS) return@forEach
            val name = o.optString("name")
            if (name.isEmpty()) return@forEach
            out += PromoteLine(
                name = name,
                action = action,
                reason = o.optString("reason"),
                source = if (o.has("source") && !o.isNull("source")) o.optString("source") else null,
                ts = o.optLong("ts", 0L),
            )
        }
        return out
    }

    /**
     * 每个技能名的**最新一条**记录（与喂入顺序无关：按 ts 取最大，同刻并列取后见者）；
     * 它是转正 ⇒ 当前还是正式技能 ⇒ 决策中心那条可一键回退。
     */
    fun latestPerName(lines: List<PromoteLine>): Map<String, PromoteLine> {
        val last = LinkedHashMap<String, PromoteLine>()
        lines.forEach { l ->
            val prev = last[l.name]
            if (prev == null || l.ts >= prev.ts) last[l.name] = l
        }
        return last
    }

    /** 两层技能目录的转正/回退记录（新→旧；读不到 ⇒ 空，不抛——记录页不许被盘上脏文件带崩）。 */
    fun promoteLines(projectRoot: File, globalRoot: File): List<PromoteLine> {
        val files = listOf(
            SkillAudit.fileIn(SkillRules.projectSkillDir(projectRoot)),
            SkillAudit.fileIn(SkillRules.globalSkillDir(globalRoot)),
        )
        return files.flatMap { f ->
            runCatching { parseLines(f.readText(Charsets.UTF_8)) }.getOrDefault(emptyList())
        }.sortedByDescending { it.ts }
    }

    /** 决策中心记录页的回看条数上限（与 skill_event 那路的 LIMIT 20 同口径）。 */
    const val RECENT_LIMIT = 20

    /** parseLines 认的 action（205 起：草稿治理的两类也进决策中心记录）。 */
    val PARSED_ACTIONS = setOf(ACTION_PROMOTE, ACTION_REVERT, ACTION_DRAFT_RETIRE, ACTION_DRAFT_RETIRE_UNDO)

    /** 记录页要的最近几条（截断住在这层——UI 文件不许再出现裸 take(20) 那类兜底）。 */
    fun recentLines(projectRoot: File, globalRoot: File, limit: Int = RECENT_LIMIT): List<PromoteLine> =
        promoteLines(projectRoot, globalRoot).take(limit)
}
