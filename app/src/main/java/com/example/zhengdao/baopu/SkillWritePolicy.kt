// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百九十二批 · 技能免批判据（2026-10-24 用户拍板：「小技巧这类只写项目技能、纯流程、
// 可撤销的，不用每次问；其余照旧问」）。一句话：**AUTO 只放行「错了重来也便宜」的东西**，
// 其余一律 NEEDS_CONFIRM。
//
// 结构（判据与壳分家，跟 SkillRules/SkillStore 同一房型）：
//   - [SkillWritePolicy]：**纯函数判据**——无 IO、表驱动：六个否决一张表、五个条件一张表，
//     [SkillWritePolicy.decide] 只是按表各走一遍，不许散装 if（派工单 §6 硬要求）；
//   - [SkillAutoWrite]：**IO 壳**——本批唯一许插的那一层，位置在 `SkillStore.apply` 调用**前**：
//     先 decide()，AUTO 才落盘（写口仍是 SkillStore.plan/apply，备份→正文→索引的顺序一字未动，
//     174 验过），NEEDS_CONFIRM 一个字节都不写；撤销＝改名 `.deleted-*`（不真删）＋去索引＋审计。
package com.example.zhengdao.baopu

import java.io.File

/** 六个否决（派工单 §1：命中任何一个 ⇒ 永远 NEEDS_CONFIRM，哪怕五条都满足）。 */
enum class SkillWriteVeto(val label: String) {
    /** 同目录已有同名 SKILL.md——改比新建危险一档。 */
    MODIFY_EXISTING("改已有技能"),

    /** 全局技能是所有项目共用的人设底座，免批不许碰。 */
    GLOBAL_SCOPE("全局技能"),

    /** 代码块／外链 URL／权限类词——不是纯流程知识。 */
    FORBIDDEN_CONTENT("禁用内容（代码块/外链/权限类词）"),

    /** 正文超 2KB（UTF-8），或根本编码不成 UTF-8。 */
    OVERSIZE("超体积或非 UTF-8"),

    /** 名字要被安全化改写（或不可用）——写下去的落点不是请求的那个名字。 */
    NAME_CONFLICT("名字冲突"),

    /** 写入前备份没备好。 */
    BACKUP_FAILED("写入前备份失败"),
}

/** decide() 的结论；NEEDS_CONFIRM 必须带一句能直接给人和模型看的原因。 */
sealed interface SkillWriteDecision {
    /** 五条全满足且六个否决一个没中 ⇒ 直接写（草稿）。 */
    data object Auto : SkillWriteDecision

    /** 要问用户：veto 非 null ⇒ 是六个否决之一；null ⇒ 是五条之一没满足。 */
    data class NeedsConfirm(val veto: SkillWriteVeto?, val reason: String) : SkillWriteDecision
}

/**
 * decide() 的 ctx：**纯数据，无 IO**。要查盘的事实（同名在不在、备份备没备好、留痕口可用否）
 * 由 IO 壳（[SkillAutoWrite]）先算好装进来——decide 自己不碰文件系统（派工单 §1：纯函数）。
 */
data class SkillWriteContext(
    /** 目标 `技能/<名>/SKILL.md` 已经存在 ⇒「改已有」，永远否决。 */
    val targetExists: Boolean = false,

    /** 备份是否备好（AUTO 恒为新建、无原件可备；调用方报 false 就走否决）。 */
    val backupReady: Boolean = true,

    /** 审计口可用——五条件的第 5 条（留痕）。 */
    val auditReady: Boolean = true,

    /** 撤销口可用——五条件的第 5 条（可撤销）。 */
    val undoReady: Boolean = true,
)

/**
 * 技能写入的**唯一判据入口**（派工单 §1）。
 *
 * 两张表、零分支逻辑：先走否决表（任何命中 ⇒ NEEDS_CONFIRM，后面的行不再看），
 * 再走条件表（五条全绿 ⇒ AUTO）。顺序就是派工单的语义——「哪怕五条都满足，否决一票压死」。
 */
internal object SkillWritePolicy {

    /** AUTO 正文的字节上限（UTF-8；派工单 §1 第 3 条：≤ 2KB）。 */
    const val BODY_MAX_BYTES: Int = 2 * 1024

    /**
     * 禁用词（派工单 §1 第 2 条原列的六个，外加同类「免确认／无需确认／不用确认／跳过权限／提权」
     * 几个直接命令式变体——同一个意思换个说法不该绕过去）。
     */
    val FORBIDDEN_WORDS: List<String> = listOf(
        "权限", "确认", "守卫", "白名单", "工具启用", "跳过确认",
        "免确认", "无需确认", "不用确认", "跳过权限", "提权",
    )

    /** 代码块栅栏：行首三反引号。 */
    private val CODE_FENCE = Regex("(?m)^\\s*```")

    /** eval/exec 词（词边界，防「evaluate」误伤）。 */
    private val EVAL_EXEC = Regex("\\b(eval|exec)\\b")

    /** 外链 URL。 */
    private val URL = Regex("https?://|www\\.")

    /**
     * 正文里所有命中的禁用项（**纯函数**；空 ⇒ 干净）。返回命中描述，测试与工具文案直接用。
     */
    fun forbiddenFindings(body: String): List<String> {
        val out = mutableListOf<String>()
        if (CODE_FENCE.containsMatchIn(body)) out += "代码块"
        if (EVAL_EXEC.containsMatchIn(body)) out += "eval/exec"
        if (URL.containsMatchIn(body)) out += "外链 URL"
        FORBIDDEN_WORDS.forEach { w -> if (body.contains(w)) out += "禁用词「$w」" }
        return out
    }

    /**
     * 正文能不能按 UTF-8 量出字节（**严格编码**：未配对代理项直接判失败 ⇒ null）。
     * null 或超 [BODY_MAX_BYTES] 都算 [SkillWriteVeto.OVERSIZE]——「非 UTF-8」按派工单归并进体积否决。
     */
    fun utf8Bytes(body: String): Int? =
        runCatching { body.encodeToByteArray(0, body.length, throwOnInvalidSequence = true).size }.getOrNull()

    private fun oversized(body: String): Boolean = utf8Bytes(body)?.let { it > BODY_MAX_BYTES } ?: true

    /** 六个否决（**表**：一行一个否决，命中即拒）。 */
    private val VETO_TABLE: List<Pair<SkillWriteVeto, (SkillProposal, SkillWriteContext) -> Boolean>> = listOf(
        SkillWriteVeto.GLOBAL_SCOPE to { p, _ -> p.scope != SkillScope.PROJECT },
        SkillWriteVeto.MODIFY_EXISTING to { _, c -> c.targetExists },
        SkillWriteVeto.FORBIDDEN_CONTENT to { p, _ -> forbiddenFindings(p.body).isNotEmpty() },
        SkillWriteVeto.OVERSIZE to { p, _ -> oversized(p.body) },
        SkillWriteVeto.NAME_CONFLICT to { p, _ ->
            SkillStore.sanitizeName(p.name).let { it == null || it != p.name.trim() }
        },
        SkillWriteVeto.BACKUP_FAILED to { _, c -> !c.backupReady },
    )

    /** 五条全满足才 AUTO（**表**：一行一个条件，全绿才放行）。 */
    private val AUTO_CONDITIONS: List<Triple<String, (SkillProposal, SkillWriteContext) -> Boolean, String>> = listOf(
        Triple("只写项目技能", { p, _ -> p.scope == SkillScope.PROJECT }, "落点必须是项目技能（scope=PROJECT）"),
        Triple("纯流程知识", { p, _ -> forbiddenFindings(p.body).isEmpty() }, "正文只能是自然语言步骤/话术/格式口径（不含代码块、外链、权限类词）"),
        Triple("体积与命名", { p, _ -> !oversized(p.body) && SkillStore.sanitizeName(p.name) != null }, "正文 ≤ 2KB（UTF-8）且名字合法"),
        Triple("先落草稿", { p, _ -> p.draft }, "免批只落草稿（draft=true），转正要走用满 3 次的既有口"),
        Triple("留痕可撤销", { _, c -> c.auditReady && c.undoReady }, "审计与一键撤销口必须可用"),
    )

    /**
     * 唯一判据入口（**纯函数、无 IO、可表驱动测**）。
     *
     * 路径落点不由这里拼（派工单 §1 第 1 条）——「落点在项目技能目录内」由调用方
     * 拿 `SkillRules.projectSkillDir` 算出的 plan 来保证，本函数只看 scope 与盘上事实。
     */
    fun decide(proposal: SkillProposal, ctx: SkillWriteContext): SkillWriteDecision {
        VETO_TABLE.forEach { (veto, hit) ->
            if (hit(proposal, ctx)) return SkillWriteDecision.NeedsConfirm(veto, "命中否决：${veto.label}")
        }
        AUTO_CONDITIONS.forEach { (_, ok, reason) ->
            if (!ok(proposal, ctx)) return SkillWriteDecision.NeedsConfirm(null, reason)
        }
        return SkillWriteDecision.Auto
    }
}

/** 免批审计要记的「谁」：来源会话与来源候选 id（162 的候选、196 的学习器产出带 id；工具通道两者都空）。 */
data class SkillAutoSource(val sessionId: Long? = null, val candidateId: String? = null)

/** [SkillAutoWrite.tryAutoWrite] 的结果。 */
sealed interface SkillAutoOutcome {
    /** 已免批落盘（草稿）。 */
    data class Written(val file: File, val result: SkillWriteResult.Written) : SkillAutoOutcome

    /** 判据说「要问」：一个字节都没写，原因原样带回给调用方。 */
    data class NeedsConfirm(val decision: SkillWriteDecision.NeedsConfirm) : SkillAutoOutcome

    /** 免批判据过了，但写盘被既有判据挡了（索引满等）——原因原样带回。 */
    data class Rejected(val reason: String) : SkillAutoOutcome
}

/**
 * 免批写入与撤销的 **IO 壳**——本批插在 `SkillStore.apply` 调用前的那一层。
 *
 * 三条红线钉死在这：
 * 1. **不改写盘顺序**：落盘只调 `SkillStore.plan`/`apply`（备份→正文→索引，174 验过）；
 *    本类自己不 writeText 任何 SKILL.md（源码级判据见 `SkillWritePolicyTest`）。
 * 2. **NEEDS_CONFIRM 不落盘**：decide 不过就返回，一个字节都不写。
 * 3. **免批恒为草稿**：写盘用的提案把 `draft` 钉死 `true`（decide 的条件 4 之外再钉一道）。
 */
internal object SkillAutoWrite {

    /** 审计行 action 值：免批落地记 `auto`、撤销记 `undo`；技能页凭「最后一行 action」认免批技能。 */
    const val AUDIT_ACTION_AUTO = "auto"
    const val AUDIT_ACTION_UNDO = "undo"

    /**
     * 免批写一次（够格才写，不够格什么都不动）。
     *
     * @param source 来源会话／候选 id（审计行要的「谁」；工具通道给 null）
     * @param nowMs 审计行时间戳（测试注入用）
     */
    fun tryAutoWrite(
        proposal: SkillProposal,
        projectRoot: File,
        globalRoot: File,
        personaFiles: PersonaFiles,
        stamp: String,
        source: SkillAutoSource? = null,
        nowMs: Long = System.currentTimeMillis(),
    ): SkillAutoOutcome {
        // ① 先探一次落点：plan 是纯函数，只问路径不写盘（与 SkillCardWriter.save 同款探法）。
        val probe = SkillStore.plan(proposal.copy(draft = true), projectRoot, globalRoot, stamp)
        // ② decide()（纯）：盘上事实由这里算好装进 ctx。
        val ctx = SkillWriteContext(targetExists = probe.file.isFile)
        val decision = SkillWritePolicy.decide(proposal, ctx)
        if (decision is SkillWriteDecision.NeedsConfirm) return SkillAutoOutcome.NeedsConfirm(decision)
        // ③ AUTO：正式 plan。draft 钉死 true；来源写进 reason——apply 自己那行审计因此也带来源。
        val beforeSha = SkillAudit.shaOf(probe.file)
        val auto = proposal.copy(
            draft = true,
            reason = proposal.reason +
                "（免批：来源会话=${source?.sessionId ?: "-"} 来源候选=${source?.candidateId ?: "-"}）",
        )
        val planned = SkillStore.plan(auto, projectRoot, globalRoot, stamp)
        planned.blocked?.let { return SkillAutoOutcome.Rejected(it) }
        // ④ 写盘仍是那一条路：SkillStore.apply（备份→正文→索引，顺序一字未动）。
        val r = SkillStore.apply(planned, personaFiles, personaFiles.read(PersonaLayer.C_PROJECT).orEmpty())
        val written = r as? SkillWriteResult.Written
            ?: return SkillAutoOutcome.Rejected((r as SkillWriteResult.Rejected).reason)
        // ⑤ 留痕：补一行 action=auto 的审计（谁/何时/来源会话/来源候选 id），技能页凭它标「免批」。
        SkillAudit.record(
            skillFile = written.file,
            scope = "project",
            name = planned.proposal.name,
            action = AUDIT_ACTION_AUTO,
            reason = proposal.reason,
            beforeSha = beforeSha,
            indexUpdated = written.indexUpdated,
            nowMs = nowMs,
            auto = true,
            sessionId = source?.sessionId,
            candidateId = source?.candidateId,
        )
        return SkillAutoOutcome.Written(written.file, written)
    }

    /**
     * 一键撤销（派工单 §2）：改名 `<名>.SKILL.md.deleted-<时间戳>`（**不真删**）＋
     * 从索引去掉该行 ＋ 审计一行。撤完技能页不再显示、模型下一轮看不到、索引行数为原值。
     *
     * 只许撤「最后一行审计是 auto」的技能——确认制写的技能本来就没自动落过盘，没有免批撤销。
     * 顺序上先改名后去索引：索引去不掉就把名字改回来（不留「正文没了索引还在」的半条）。
     */
    fun undoAutoWrite(
        name: String,
        projectRoot: File,
        personaFiles: PersonaFiles,
        stamp: String,
        nowMs: Long = System.currentTimeMillis(),
    ): SkillWriteResult {
        val skillDir = SkillRules.projectSkillDir(projectRoot)
        val file = File(File(skillDir, name), SkillRules.SKILL_FILE)
        if (!file.isFile) return SkillWriteResult.Rejected("没有这条项目技能：$name")
        if (!isAutoWritten(skillDir, name)) {
            return SkillWriteResult.Rejected("这条不是免批写的（或已经被改过/撤过），没有一键撤销")
        }
        val beforeSha = SkillAudit.shaOf(file)
        // ① 归档式删除：改名 .deleted-*，原件一个字节不动（SkillStore 唯一删除口）。
        val archived = SkillStore.archive(file, stamp)
        val gone = (archived as? SkillWriteResult.Written)?.file
            ?: return SkillWriteResult.Rejected((archived as SkillWriteResult.Rejected).reason)
        // ② 去索引：只动 C 层那一行，仍走人设层唯一写盘口（不许自己 fopen 人设文件）。
        val current = personaFiles.read(PersonaLayer.C_PROJECT).orEmpty()
        val indexRemoved = when (val edit = SkillRules.removeIndexLine(current, name)) {
            is SkillRules.EditResult.Ok -> personaFiles.applyProposal(
                PersonaProposal(
                    layer = PersonaLayer.C_PROJECT,
                    current = current,
                    proposed = edit.newText,
                    reason = "撤销免批技能：$name",
                ),
            )
            // 索引里本来就没有这行（异常态的兜底）⇒ 目标状态已达成，撤销继续。
            is SkillRules.EditResult.NotFound -> true
            is SkillRules.EditResult.OverCap -> false // 不可达（删除不减行数），防御性返回失败
        }
        if (!indexRemoved) {
            // 索引去不掉 ⇒ 把正文改回原名：盘上回到撤销前，明说失败，不静默吞半条。
            runCatching { gone.renameTo(file) }
            return SkillWriteResult.Rejected("索引行没去掉，撤销没有完成，技能还在：$name")
        }
        // ③ 审计一行（审计文件一直在原地追加，不删不改——撤了也要能查到「谁撤的」）。
        SkillAudit.record(
            skillFile = gone,
            scope = "project",
            name = name,
            action = AUDIT_ACTION_UNDO,
            reason = "一键撤销免批技能（正文改名 ${gone.name} 留在原地）",
            beforeSha = beforeSha,
            indexUpdated = true,
            nowMs = nowMs,
        )
        return SkillWriteResult.Written(file = gone, backup = null, indexUpdated = true)
    }

    /** 这条技能的**最后一行**审计是不是 `auto`（免批落地后没被动过 ⇒ 可一键撤销）。 */
    fun isAutoWritten(skillDir: File, name: String): Boolean =
        lastActionByName(skillDir).get(name) == AUDIT_ACTION_AUTO

    /**
     * 技能页标「免批」用的名单：审计里每个技能名的**最后一行** action，
     * 取 `auto` 的那些名字（被确认制改过（update）或撤过（undo）的自动掉出名单）。
     */
    fun autoWrittenNames(skillDir: File): Set<String> =
        lastActionByName(skillDir).filterValues { it == AUDIT_ACTION_AUTO }.keys

    /** 审计文件 → 名字到最后一行 action 的映射（文件不在／行坏 ⇒ 当没有）。 */
    private fun lastActionByName(skillDir: File): Map<String, String> {
        val f = SkillAudit.fileIn(skillDir)
        if (!f.isFile) return emptyMap()
        val last = LinkedHashMap<String, String>()
        runCatching {
            f.readLines().forEach { line ->
                val o = runCatching { org.json.JSONObject(line) }.getOrNull() ?: return@forEach
                val n = o.optString("name")
                if (n.isNotEmpty()) last[n] = o.optString("action")
            }
        }
        return last
    }
}
