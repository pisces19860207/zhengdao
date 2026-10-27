// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百三十六批 · 反思的**自治档位**——纯判据层（房型照 `SkillWritePolicy`/`ReflectionRules`：
// 表驱动、零 IO、零时钟、零 Android；执行壳在 [AutonomySelfEdit]，挂接点见交付报告那一页说明）。
//
// 一句话：**反思的主体交回模型，人只保留否决、抽查、回滚**。本文件回答的唯一问题是
// 「这一条改动，模型能不能自己写下去」——三档，闭集，没有第四档：
//   · **A 自改**（不弹确认）：技能补示例／改措辞·描述／加索引行／调参数名；提示词追加一条说话纪律。
//   · **B 须人批**（必弹）：新增技能、合并技能、改契约（appliesWhen/success）、改定位策略、改工具描述。
//   · **C 永不自改**（谁都写不进去，连确认卡都不许摆）：权限位、风险档、protected 条款、删技能、
//     工具白名单、确认口径。
//
// 三条形状纪律（都钉在代码里，不靠调用方自觉）：
// 1. **档位由「改法」定，不由「谁提的」定**：同一张表——模型提的、人提的、示教带出来的都走
//    [AutonomyRules.decide]，所以不存在"换个来源就降档"这条路（§2 表右列「谁能改」的落点）。
// 2. **A 档是「文本层」的承诺，所以要复核内容**：声明成 A 不够，本层再拿新旧正文比一遍——
//    结构化行（`<!-- … -->`：草稿头与 233 的契约行）**一字不许动**。动到契约行 ⇒ 按它真实是哪档重判：
//    改了 `appliesWhen`/`success` ⇒ B（那本来就是「改契约」）；动到 `protected` ⇒ C；
//    动到 `status=`/`uses=` 头 ⇒ B（试用期不是措辞）。
//    为什么能一行盖住三项：233 把契约就住在注释行里（`SkillContract` 头部纪律①），
//    192 的草稿头也是注释行——**机器行都在同一个形状里**，比一次就都看见了。
// 3. **自治 ≠ 免检**：A 档的放行条件是「能回滚＋能留痕＋**能跑 206**」三件都在（派工单 §4）。
//    少任何一件 ⇒ 不写盘、按 B 处理——没条件验的自改不是自治，是盲改。
//
// 不新增 `DecisionKind`（派工单 §4：A 档以既有 `auto_act` 记录）；不改不可逆动作的确认口径
// （红线：R2/R3 照旧必问，本层根本不读风险矩阵）。
package com.example.zhengdao.baopu

// ── 三档（派工单 §2 的 A/B/C；闭集）───────────────────────────────────────────

/** 自治档位。顺序即权限从宽到严，[AutonomyRules.worseOf] 用它做「取严不取宽」。 */
internal enum class AutonomyTier(val label: String) {
    /** 模型自己改：不弹确认，但改完必须跑一次 206 对拍，不通过自动回滚。 */
    A_SELF_EDIT("A 自改（不弹确认）"),

    /** 必须人点：任何自动路径都只出确认，不写盘。 */
    B_HUMAN_APPROVAL("B 须人批"),

    /** 永不自改：任何自动路径写不进去，也不许摆成"点一下就过"的确认卡——只能人自己手动改。 */
    C_NEVER_AUTO("C 永不自改"),
}

/**
 * 改法（**闭集**，一张表一条档；派工单 §2 原文逐行照搬）。
 *
 * 新增改法必须同时：① 在这里登记一行并写明档位；② 在 `ReflectionAutonomyTest` 的
 * 三档清单用例里出现（表行数与档位分布有断言，漏登记即红）。
 */
internal enum class AutonomyChangeKind(val tier: AutonomyTier, val label: String, val surface: AutonomySurface) {
    // ── A 档：可回滚、影响面在「文本」，最坏是啰嗦一点 ──
    /** 往技能里补一个例子（正文只增不减，机器行不动）。 */
    SKILL_EXAMPLE_ADD(AutonomyTier.A_SELF_EDIT, "技能补示例", AutonomySurface.SKILL_BODY),

    /** 改技能里人话句子的说法／描述（不动步骤结构以外的机器行）。 */
    SKILL_WORDING_EDIT(AutonomyTier.A_SELF_EDIT, "技能改措辞", AutonomySurface.SKILL_BODY),

    /** 往索引层加一行（既有行逐字不动，只许追加）。 */
    SKILL_INDEX_LINE_ADD(AutonomyTier.A_SELF_EDIT, "技能加索引行", AutonomySurface.SKILL_INDEX),

    /** 调整技能正文里示例用的参数名（仍是文本）。 */
    SKILL_PARAM_RENAME(AutonomyTier.A_SELF_EDIT, "技能调参数名", AutonomySurface.SKILL_BODY),

    /** 提示词**追加**一条说话纪律（既有条目一个字不改；只许写 B/C 层，A 内核碰不到）。 */
    PROMPT_DISCIPLINE_APPEND(AutonomyTier.A_SELF_EDIT, "提示词追加说话纪律", AutonomySurface.PERSONA_DISCIPLINE),

    // ── B 档：会改变「什么时候会动」──
    /** 新增一条技能。 */
    SKILL_NEW(AutonomyTier.B_HUMAN_APPROVAL, "新增技能", AutonomySurface.SKILL_BODY),

    /** 把两条技能并成一条。 */
    SKILL_MERGE(AutonomyTier.B_HUMAN_APPROVAL, "合并技能", AutonomySurface.SKILL_BODY),

    /** 改契约的 appliesWhen／success（什么时候用、算不算成）。 */
    CONTRACT_EDIT(AutonomyTier.B_HUMAN_APPROVAL, "改契约", AutonomySurface.SKILL_BODY),

    /** 改定位策略（元素怎么找——它决定动作落在谁身上）。 */
    LOCATE_STRATEGY_EDIT(AutonomyTier.B_HUMAN_APPROVAL, "改定位策略", AutonomySurface.TEXT_OTHER),

    /** 改工具描述（模型照它决定调不调）。 */
    TOOL_DESCRIPTION_EDIT(AutonomyTier.B_HUMAN_APPROVAL, "改工具描述", AutonomySurface.TEXT_OTHER),

    // ── C 档：安全边界，谁都不能自动改 ──
    /** 权限位。 */
    PERMISSION_BIT(AutonomyTier.C_NEVER_AUTO, "权限位", AutonomySurface.CONFIG),

    /** 风险档（219 那张表）。 */
    RISK_TIER(AutonomyTier.C_NEVER_AUTO, "风险档", AutonomySurface.CONFIG),

    /** 契约里的 protected 条款。 */
    PROTECTED_CLAUSE(AutonomyTier.C_NEVER_AUTO, "protected 条款", AutonomySurface.SKILL_BODY),

    /** 删除技能。 */
    SKILL_DELETE(AutonomyTier.C_NEVER_AUTO, "删除技能", AutonomySurface.SKILL_BODY),

    /** 工具白名单（开哪些工具）。 */
    TOOL_WHITELIST(AutonomyTier.C_NEVER_AUTO, "工具白名单", AutonomySurface.CONFIG),

    /** 不可逆动作的确认口径（R2/R3 必问这类规矩本身）。 */
    CONFIRM_POLICY(AutonomyTier.C_NEVER_AUTO, "确认口径", AutonomySurface.CONFIG),
    ;

    companion object {
        /** 线值（档案行/审计里那个键）⇒ 改法；认不出 ⇒ null，**不兜默认档**。 */
        fun ofLabel(raw: String): AutonomyChangeKind? =
            entries.firstOrNull { it.label == raw || it.name == raw }
    }
}

/**
 * 改动落在哪个面上（结构性复核按面走不同规则）。
 *
 * 只有三个面是**本批会做结构复核**的：技能正文、索引层、人设纪律段。
 * `TEXT_OTHER`／`CONFIG` 一律不自改（挂在 B/C 档上，复核规则用不上——落点是代码或表，
 * 模型没有写口，本层的职责是"确认写不进去"，不是"替它改"）。
 */
internal enum class AutonomySurface { SKILL_BODY, SKILL_INDEX, PERSONA_DISCIPLINE, TEXT_OTHER, CONFIG }

// ── 一条提案（纯数据；盘上事实由壳算好装进来，判据自己不摸盘）──────────────────

/**
 * 「这条反思想改什么」。
 *
 * @param scopeProject 落点是不是**项目**技能／项目人设。全局层是所有项目共用的底座，
 *   免批不许碰（沿用 192 的 `GLOBAL_SCOPE` 否决，本批不放宽）。
 * @param rollbackReady 写之前回滚点备好了没有（技能＝备份名，人设＝`.bak`）。
 * @param auditReady 208 的 `auto_act` 这条痕落不落得下去。
 * @param evalRunnable 206 对拍**能不能跑**（有可用通道、没超当日额度）。
 *   A 档三件少任何一件 ⇒ 按 B 处理（判据 3：没条件验的自改是盲改）。
 * @param otherIndexCount 另一层索引已有的条数（索引面上限 B＋C 合算，同 [SkillRules.INDEX_CAP] 口径）。
 */
internal data class AutonomyProposal(
    val kind: AutonomyChangeKind,
    /** 落点名（技能名／索引对象名／"说话纪律"这类段落名）；**不许装正文**。 */
    val target: String,
    /** 现有文本（新增类为空串）。 */
    val currentText: String,
    /** 拟写入的完整文本。 */
    val newText: String,
    val scopeProject: Boolean = true,
    val rollbackReady: Boolean = false,
    val auditReady: Boolean = false,
    val evalRunnable: Boolean = false,
    val otherIndexCount: Int = 0,
    /** 反思那一轮的键（`target::改法`，与 205 的去重键同形；档案闭合用它对上承诺）。 */
    val candidateKey: String = "",
) {
    val dedupKey: String get() = if (candidateKey.isNotBlank()) candidateKey else "$target::${kind.label}"
}

/** 判据结论（三态；调用方**必须**逐态处理，不许把 NeedsApproval 当 AutoEdit 写）。 */
internal sealed interface AutonomyVerdict {
    /** A 档放行：模型自己写，**不弹确认**（写了之后必须跑 206，见 [AutonomySelfEdit]）。 */
    data class AutoEdit(val proposal: AutonomyProposal) : AutonomyVerdict {
        override val requiresApproval: Boolean get() = false
    }

    /** B 档：必弹确认，本层一个字节都不写。 */
    data class NeedsApproval(
        val proposal: AutonomyProposal,
        val reasons: List<String>,
        val tier: AutonomyTier,
    ) : AutonomyVerdict {
        override val requiresApproval: Boolean get() = true
    }

    /** C 档：任何自动路径写不进去；也不许摆成确认卡（[AutonomyRules.offerableAsApproval] 恒 false）。 */
    data class NeverAuto(val proposal: AutonomyProposal, val reasons: List<String>) : AutonomyVerdict {
        override val requiresApproval: Boolean get() = false
    }

    /**
     * 要不要摆确认卡（派工单 §2 三档各自的答法）：A 不弹、B 必弹、C 连弹都不许。
     *
     * C 这里是 `false`，**不是"可以不弹"**——它的含义是"走确认这条路本身就不许存在"，
     * 调用方必须配 [AutonomyRules.offerableAsApproval] 一起判（负例③钉这一对）。
     */
    val requiresApproval: Boolean
}

/**
 * 三档判据的唯一入口（**纯函数**）。
 *
 * 两遍走：① 按 [AutonomyChangeKind.tier] 出底档；② A 档再复核内容结构——
 * 声明成 A 但实际动了机器行的，按它真实的档位重判（B／C）。**只往严的方向改，不往宽**。
 */
internal object AutonomyRules {

    /** A 档正文体积上限：直接复用 192 的那个数，不另立第二个（同一口径两张表迟早分叉）。 */
    const val BODY_MAX_BYTES: Int = SkillWritePolicy.BODY_MAX_BYTES

    /** 确认卡该不该摆这一条（负例③的第二道闸：C 档连"点一下就过"都不给）。 */
    fun offerableAsApproval(kind: AutonomyChangeKind): Boolean = kind.tier != AutonomyTier.C_NEVER_AUTO

    /** 三档清单（报告与技能页说明用同一份拼法；行数＝改法数，测试钉住不被悄悄加行）。 */
    fun tierTableText(): String = AutonomyTier.entries.joinToString("｜") { tier ->
        "${tier.label}：" + AutonomyChangeKind.entries.filter { it.tier == tier }.joinToString("、") { it.label }
    }

    // ── 主判据 ────────────────────────────────────────────────────────────

    fun decide(proposal: AutonomyProposal): AutonomyVerdict {
        val base = proposal.kind.tier
        // C 档：底档就是天花板，没有任何复核能把它放宽（先判它，短路）。
        if (base == AutonomyTier.C_NEVER_AUTO) {
            return AutonomyVerdict.NeverAuto(
                proposal,
                listOf("C 档（${proposal.kind.label}）：安全边界，任何自动路径都写不进去——只能人自己手动改"),
            )
        }
        if (base == AutonomyTier.B_HUMAN_APPROVAL) {
            return AutonomyVerdict.NeedsApproval(proposal, listOf("B 档（${proposal.kind.label}）：会改变「什么时候会动」，必须人点"), base)
        }
        // A 档：先过「三件都在」，再复核内容结构。
        val reasons = mutableListOf<String>()
        if (!proposal.scopeProject) reasons += "落点不是项目层（全局层是所有项目共用的底座，免批不许碰——沿用 192 的 GLOBAL_SCOPE 否决）"
        if (!proposal.rollbackReady) reasons += "写之前没有可用的回滚点（自治的前提是错了能一键退回去）"
        if (!proposal.auditReady) reasons += "208 的 auto_act 留痕落不下去（改了什么人都看不见 ⇒ 抽查无从下手）"
        if (!proposal.evalRunnable) reasons += "206 对拍跑不了（没可用通道或当日额度已满）——没条件验的自改是盲改"
        val smuggled = structuralFindings(proposal)
        if (smuggled != null) {
            // 声明成 A、内容其实是 B/C：按真实的档位走（取严不取宽），"三件不齐"的话一起说。
            val realTier = worseOf(base, smuggled.tier)
            val all = reasons + smuggled.reasons
            if (realTier == AutonomyTier.C_NEVER_AUTO) {
                return AutonomyVerdict.NeverAuto(proposal, all)
            }
            return AutonomyVerdict.NeedsApproval(proposal, all, realTier)
        }
        if (reasons.isNotEmpty()) {
            return AutonomyVerdict.NeedsApproval(proposal, reasons, AutonomyTier.B_HUMAN_APPROVAL)
        }
        return AutonomyVerdict.AutoEdit(proposal)
    }

    /** 取严不取宽：两档里选权限更小的那个（枚举序 A\<B\<C 正好是"越来越严"）。 */
    fun worseOf(a: AutonomyTier, b: AutonomyTier): AutonomyTier = if (a.ordinal >= b.ordinal) a else b

    // ── A 档的内容复核（防「声明 A、实为 B/C」）────────────────────────────

    /** 复核结论：没有发现 ⇒ null；发现 ⇒ 它真实属于哪档＋逐条原因。 */
    internal data class StructuralFinding(val tier: AutonomyTier, val reasons: List<String>)

    /**
     * 结构化复核（按面走）。返回 null ⇒ 这条 A 档名副其实。
     *
     * 判据只有一条主线：**A 档动的是人话，不是机器行**。
     * 机器行＝以 `<!--` 起、`-->` 收的整行注释（草稿头＋233 的契约行都长这样，所以比一次全看见）；
     * 索引层＝带 [SkillRules.INDEX_MARK] 的行。
     */
    fun structuralFindings(p: AutonomyProposal): StructuralFinding? = when (p.kind.surface) {
        AutonomySurface.SKILL_BODY -> skillBodyFindings(p)
        AutonomySurface.SKILL_INDEX -> indexFindings(p)
        AutonomySurface.PERSONA_DISCIPLINE -> disciplineFindings(p)
        // B/C 档的面（代码／表）压根没有自动写口；这里不复核，判据在上面已短路。
        AutonomySurface.TEXT_OTHER, AutonomySurface.CONFIG -> null
    }

    /** 整行机器注释（草稿头、契约行都算；标题与人话正文都不算）。 */
    private fun machineLines(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.startsWith("<!--") && it.endsWith("-->") }

    private fun skillBodyFindings(p: AutonomyProposal): StructuralFinding? {
        val oldMachine = machineLines(p.currentText)
        val newMachine = machineLines(p.newText)
        if (oldMachine == newMachine) {
            // 机器行一字没动 ⇒ 契约、protected、试用期头都不可能被这条改到；剩下只看文本本身。
            return textOnlyFinding(p)
        }
        // 动到了机器行：按「动的到底是哪一类」分档。
        val reasons = mutableListOf<String>()
        var tier = AutonomyTier.B_HUMAN_APPROVAL
        if (deletedWholeContract(oldMachine, newMachine)) {
            tier = AutonomyTier.C_NEVER_AUTO
            reasons += "把契约整段删了：撤契约等于「以后不再受任何约束」，不在自改范围"
        }
        for (line in (oldMachine - newMachine.toSet()) + (newMachine - oldMachine.toSet())) {
            when {
                protectedLine(line) -> {
                    tier = worseOf(tier, AutonomyTier.C_NEVER_AUTO)
                    reasons += "动了 protected 条款：$line（C 档：受保护语义约束谁都不能自动改）"
                }

                statusHeader(line) -> {
                    tier = worseOf(tier, AutonomyTier.B_HUMAN_APPROVAL)
                    reasons += "动了试用期头（status/uses）：草稿转正式与次数不是措辞，须人批"
                }

                contractLine(line) -> {
                    tier = worseOf(tier, AutonomyTier.B_HUMAN_APPROVAL)
                    reasons += "改了契约行：$line（appliesWhen/success 属 B 档：会改变「什么时候会动」）"
                }

                else -> {
                    tier = worseOf(tier, AutonomyTier.B_HUMAN_APPROVAL)
                    reasons += "动了别的机器行：$line（自改只许动人话）"
                }
            }
        }
        if (reasons.isEmpty()) return null
        return StructuralFinding(tier, reasons.toList())
    }

    /** 契约行里有没有 protected 段。 */
    private fun protectedLine(line: String): Boolean =
        contractLine(line) && line.contains(SkillContract.SECTION_PROTECTED)

    private fun contractLine(line: String): Boolean = line.contains(SkillContract.MARK)

    private fun statusHeader(line: String): Boolean = line.contains("status=")

    /** 原来有契约、新正文一条契约行都不剩 ⇒ 撤契约（不是改边界）。 */
    private fun deletedWholeContract(oldMachine: List<String>, newMachine: List<String>): Boolean =
        oldMachine.any { contractLine(it) } && newMachine.none { contractLine(it) }

    /** 措辞类改动的文本闸：禁用内容／超体积／空正文，一条不放过（复用 192 的判据）。 */
    private fun textOnlyFinding(p: AutonomyProposal): StructuralFinding? {
        val findings = SkillWritePolicy.forbiddenFindings(p.newText)
        val bytes = SkillWritePolicy.utf8Bytes(p.newText)
        val reasons = mutableListOf<String>()
        if (findings.isNotEmpty()) reasons += "拟写入内容含禁用项：${findings.joinToString("、")}——A 档只许自然语言文本（沿用 192 的 FORBIDDEN_CONTENT）"
        if (bytes == null) reasons += "拟写入内容编码不成 UTF-8，字节数量不出来（沿用 192 的 OVERSIZE）"
        else if (bytes > BODY_MAX_BYTES) reasons += "拟写入内容 $bytes 字节 > 自改上限 $BODY_MAX_BYTES（改到这么大的东西不是措辞，是要人批的改动）"
        if (p.newText.isBlank()) reasons += "拟写入内容是空的：这不是改，是抹"
        if (reasons.isEmpty()) return null
        return StructuralFinding(AutonomyTier.B_HUMAN_APPROVAL, reasons)
    }

    private fun indexFindings(p: AutonomyProposal): StructuralFinding? {
        val oldIndex = indexLines(p.currentText)
        val newIndex = indexLines(p.newText)
        val reasons = mutableListOf<String>()
        // 既有行必须逐字、逐序还在（「加索引行」＝只追加）。
        val newSet = newIndex.toSet()
        oldIndex.forEach { if (it !in newSet) reasons += "索引既有行被改或没了：${it.take(40)}（A 档只许追加）" }
        if (newIndex.size < oldIndex.size) reasons += "索引行数从 ${oldIndex.size} 减到 ${newIndex.size}：去行属 B 档（会改变模型看得见什么）"
        val addedCount = newIndex.size - oldIndex.size
        val total = p.otherIndexCount + newIndex.size
        if (total > SkillRules.INDEX_CAP) reasons += "两层索引合计 $total > 上限 ${SkillRules.INDEX_CAP}（先删几条再谈加，别把闸挤开）"
        if (addedCount < 1 && reasons.isEmpty()) reasons += "这一条没真的加行：索引面上只许新增"
        if (reasons.isEmpty()) return null
        return StructuralFinding(AutonomyTier.B_HUMAN_APPROVAL, reasons)
    }

    private fun disciplineFindings(p: AutonomyProposal): StructuralFinding? {
        val reasons = mutableListOf<String>()
        if (!PersonaRules.appendOnly(p.currentText, p.newText)) {
            reasons += "说话纪律只许**追加**：拟写入内容不是「原有人设 ＋ 末尾新条款」（既有条目一个字都不许改）"
        }
        if (reasons.isEmpty()) return null
        return StructuralFinding(AutonomyTier.B_HUMAN_APPROVAL, reasons)
    }

    /** 带 [SkillRules.INDEX_MARK] 的行（索引层的机器行）。 */
    private fun indexLines(text: String): List<String> =
        text.lines().map { it.trim() }.filter { it.startsWith(SkillRules.INDEX_MARK) }
}

/**
 * 「把 A 档的拟写正文落到某个面上」之前，先算一句人话（确认卡/日志共用一份，不各写一套）。
 *
 * 为什么需要一个函数：判据的三档结论要出现在 208、卡面、日志三个地方。三处各自拼串，
 * 迟早一个说"A 自改"一个说"需要确认"。这里给唯一一句。
 */
internal object AutonomyTell {

    fun line(v: AutonomyVerdict): String = when (v) {
        is AutonomyVerdict.AutoEdit -> "A 档自改「${v.proposal.kind.label}」→ ${v.proposal.target}（不弹确认；改完必须跑 206）"
        is AutonomyVerdict.NeedsApproval -> "${v.tier.label}「${v.proposal.kind.label}」→ ${v.proposal.target}：${v.reasons.joinToString("；")}"
        is AutonomyVerdict.NeverAuto -> "C 档拒绝「${v.proposal.kind.label}」→ ${v.proposal.target}：${v.reasons.joinToString("；")}"
    }

    /** 机检键（进 208 的 evidence；和档位名一个拼法，别两处各造）。 */
    fun evidenceKey(v: AutonomyVerdict): String = when (v) {
        is AutonomyVerdict.AutoEdit -> "autonomy_tier:A:${v.proposal.dedupKey}"
        is AutonomyVerdict.NeedsApproval -> "autonomy_tier:${v.tier.name}:${v.proposal.dedupKey}"
        is AutonomyVerdict.NeverAuto -> "autonomy_tier:C:${v.proposal.dedupKey}"
    }
}
