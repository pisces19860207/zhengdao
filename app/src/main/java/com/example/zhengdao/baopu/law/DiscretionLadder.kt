// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十五批 · 法典卷二 **L9 裁量三级**——一件事最多只许做到哪一档，由生产判定说死。
//
// 出处：第二百七十五批派工单（私有侧《落地排单-2026-10-29》§275，接线第一批＝纯判定、不动执行链）；
// 判据原文＝法典卷二 L9（私有侧《抱朴-法典-2026-10-27》第 30 行；这两份文档不在本库内，别按库内路径找）。
//
// ## 判据（输入→输出，逐条可执行）
// 一组动作各自先按 [classify] 落档，整组的允许档位＝**最严的那一档**（rank 最小）：
// - ①**便宜行事**：R0 只读；或 R1 写＋可回滚＋在工作区内＋不删不发；
// - ②**先斩后奏**：R2 且四要素全齐（可回滚／文件数 ≤ [MAX_FILES]／不含删除／不含外发）且不在禁区；
// - ③**题请**：命中「永不下放」清单（R3／不可逆／改权限／改契约／白名单）或禁区，或②四要素缺任意一条；
// 执行层声明的 [DiscretionTier] 比允许档位更宽 ⇒ [LadderVerdict.violation] 出原文＋
// [LadderVerdict.downgradeToPetition] 为真——这正是法典「怎么判／后果」那两栏（负例：越界仍判先斩后奏）。
//
// ## 两处故意不做（免得以后照抄回来）
// 1. **没有 24 小时追认窗口**。法典 L9 那行原文确实写了「24h 追认窗口」，但第二百四十九批
//    （2026-10-28 改判）已把「先斩后奏＋追认」作废，并留了红旗「不许把追认那套偷偷加回来」；
//    本批只按 §275 的 L9 口径做「三档判定＋越界必落题请」，一个计时器都不接。
//    「超时不算同意」这半句住在 [TimeoutFallback]（L27），不在本层重复实现。
// 2. **不做完成声明核对**（"声明做完了有没有证据"）——那是 271 留在 spec 侧的另一半，
//    §275 没要求，本批不顺手扩。
package com.example.zhengdao.baopu.law

import com.example.zhengdao.baopu.RiskClass

/** 法典 L9 的三档，[rank] 越大＝放得越宽（一组动作取最严＝rank 最小）。 */
internal enum class DiscretionTier(val rank: Int) {
    /** ①便宜行事：R0 读，或 R1 写且可逆、在工作区里、不删不发。 */
    ACT_ON_FACE(2),

    /** ②先斩后奏：R2 且四要素齐（可回滚／≤20 文件／不删／不发，且不在禁区）。 */
    ACT_THEN_REPORT(1),

    /** ③题请：先请旨才许动，法典写明「永不下放」。 */
    PETITION_FIRST(0),
}

/**
 * 一件打算做的事（或已经做了、要回头核对的事）在 L9 眼里的事实。
 *
 * 事实全部由调用方给：风险档由 L7 的 `RiskMatrix` 分好再传进来，本层**不自己分类**，
 * 免得出现两套档位口径。
 */
internal data class PlannedAction(
    /** 动作标识：越界时要点名是哪个动作把整组拖到题请。 */
    val actionId: String,
    /** L7 的四档（R0 读／R1 写证道内／R2 改外面／R3 花钱外发账号）。 */
    val risk: RiskClass,
    /** 可不可回滚（不可逆＝③，同 L5「不可逆必批」）。 */
    val reversible: Boolean,
    /** 要动几个文件（②的上限是 [DiscretionLadder.MAX_FILES]）。 */
    val affectedFiles: Int = 0,
    /** 含不含删除。 */
    val hasDelete: Boolean = false,
    /** 含不含外发（网络写／发给别人）。 */
    val hasExfiltration: Boolean = false,
    /** 落不落在禁区（原件区、密码框这类）。 */
    val inProtectedZone: Boolean = false,
    /** 改不改权限或系统设置（③的永不下放清单）。 */
    val touchesPermission: Boolean = false,
    /** 改不改契约／制度（③的永不下放清单）。 */
    val touchesContract: Boolean = false,
    /** 是不是白名单类操作（③的永不下放清单）。 */
    val requiresWhitelist: Boolean = false,
    /** 写侧发生在地盘内还是地盘外（只管①；R0 只读没有这个问题）。 */
    val insideWorkspace: Boolean = false,
)

/**
 * L9 的一次判定结论。
 *
 * [tier] 是「这一组最多只许做到哪一档」；[claimedTier] 是执行层声明它实际走了哪一档。
 * 两者一不符，[violation] 就非空，且 [downgradeToPetition] 必为真——这正是法典「怎么判／后果」那两栏。
 */
internal data class LadderVerdict(
    val lawRef: String,
    /** 允许档位（一组动作里最严的那一档）。 */
    val tier: DiscretionTier,
    /** 哪些动作把整组钉在了这一档（越界要点名）。 */
    val bindingActionIds: List<String>,
    /** 为什么不能更宽（人读的一句话清单）。 */
    val blockReasons: List<String>,
    /** 执行层声明走的是哪一档（null = 只是事前问能走到哪，还没走）。 */
    val claimedTier: DiscretionTier?,
    /** 声明比允许更宽 ⇒ 违规原文；没有就为 null。 */
    val violation: String?,
    /** 违规 ⇒ 降回题请（法典后果原文）。 */
    val downgradeToPetition: Boolean,
    /** ③ 才为真：必须先请旨，本层不许自行放行。 */
    val mustAskFirst: Boolean,
    /** ① 才为真。 */
    val permitsSelfDisposal: Boolean,
    /** ② 才为真。 */
    val permitsActThenReport: Boolean,
    /** 「怎么解」：一句可执行的话，同 L24 的口径。 */
    val howToLift: String,
)

/** L9 的唯一判据实现（生产）。 */
internal object DiscretionLadder {

    /** 法条引用（`LawRef` 枚举扩到 L24–L27 归 276，本批继续用字符串）。 */
    const val LAW_REF = "L9"

    /** ②先斩后奏的文件数上限（法典原文：≤20 文件）。 */
    const val MAX_FILES = 20

    /**
     * 判一组动作最多只许做到哪一档；给了 [claimedTier] 就顺手核对「走法」合不合法。
     *
     * @param actions 同一批要做的动作（至少一个；空清单直接拒收，不许判成"没动作所以随便做"）
     * @param claimedTier 执行层声明它按哪一档做了；null＝只问不判违规
     */
    fun decide(actions: List<PlannedAction>, claimedTier: DiscretionTier? = null): LadderVerdict {
        require(actions.isNotEmpty()) { "L9 判定至少要给一个动作：空清单不许被读成便宜行事" }
        require(actions.all { it.actionId.isNotBlank() }) { "L9 的 actionId 不许为空：越界要能点名是谁" }
        require(actions.all { it.affectedFiles >= 0 }) { "L9 的文件数不许为负：负数是填错，不等于「没有文件」" }

        val judged = actions.map { it.actionId to classify(it) }
        val allowedRank = judged.minOf { it.second.tier.rank }
        val allowed = judged.first { it.second.tier.rank == allowedRank }.second.tier
        val binding = judged.filter { it.second.tier.rank == allowedRank }.map { it.first }
        val reasons = judged.filter { it.second.tier.rank == allowedRank }
            .flatMap { it.second.reasons }
            .distinct()

        val violation: String? = when {
            claimedTier == null -> null
            claimedTier.rank <= allowedRank -> null
            else ->
                "越界仍走了「${cn(claimedTier)}」：允许档位只到「${cn(allowed)}」，" +
                    "只许做到 ${binding.joinToString("、")} 这一档（${why(reasons)}），记一次违规"
        }

        return LadderVerdict(
            lawRef = LAW_REF,
            tier = allowed,
            bindingActionIds = binding,
            blockReasons = reasons,
            claimedTier = claimedTier,
            violation = violation,
            downgradeToPetition = violation != null,
            mustAskFirst = allowed == DiscretionTier.PETITION_FIRST,
            permitsSelfDisposal = allowed == DiscretionTier.ACT_ON_FACE,
            permitsActThenReport = allowed == DiscretionTier.ACT_THEN_REPORT,
            howToLift = liftOf(allowed),
        )
    }

    /** 一个动作落在哪一档 ＋ 为什么不能更宽（顺序写死：先③清单，再禁区，再②①，兜底③）。 */
    private fun classify(a: PlannedAction): TierAndReasons {
        val never = neverDelegated(a)
        if (never.isNotEmpty()) return TierAndReasons(DiscretionTier.PETITION_FIRST, never)
        if (a.inProtectedZone) {
            return TierAndReasons(
                DiscretionTier.PETITION_FIRST,
                listOf("落在禁区（禁区只许题请；真碰 L8 硬拒清单由 RiskMatrix 直接拒，本层不给它放行证）"),
            )
        }
        if (fitsSelfDisposal(a)) return TierAndReasons(DiscretionTier.ACT_ON_FACE, emptyList())
        val shortfalls = actThenReportShortfalls(a)
        if (shortfalls.isEmpty()) return TierAndReasons(DiscretionTier.ACT_THEN_REPORT, emptyList())
        return TierAndReasons(DiscretionTier.PETITION_FIRST, shortfalls)
    }

    /** 法典③的永不下放清单：R3／不可逆／权限／契约／白名单，命中任一条就是题请。 */
    private fun neverDelegated(a: PlannedAction): List<String> {
        val hit = mutableListOf<String>()
        if (a.risk == RiskClass.R3) hit += "R3 花钱／外发／动账号"
        if (!a.reversible) hit += "不可逆（同 L5：只能由人点头）"
        if (a.touchesPermission) hit += "改权限或系统设置"
        if (a.touchesContract) hit += "改契约或制度"
        if (a.requiresWhitelist) hit += "白名单类操作"
        return hit
    }

    /** 法典①：R0 只读，或 R1 写且可回滚、在工作区内、不删不发。文件数不在①设限——「批量」归 L8 硬拒。 */
    private fun fitsSelfDisposal(a: PlannedAction): Boolean {
        if (a.risk == RiskClass.R0) return true
        return a.risk == RiskClass.R1 && a.reversible && a.insideWorkspace &&
            !a.hasDelete && !a.hasExfiltration
    }

    /** 法典②的四要素差什么：空表＝四条全齐，才许先斩后奏。 */
    private fun actThenReportShortfalls(a: PlannedAction): List<String> {
        val missing = mutableListOf<String>()
        if (a.risk != RiskClass.R2) missing += "不是 R2（R1 写在工作区外的也走不到这一档）"
        if (!a.reversible) missing += "不可回滚"
        if (a.affectedFiles > MAX_FILES) missing += "文件数 ${a.affectedFiles} 超过 $MAX_FILES"
        if (a.hasDelete) missing += "含删除"
        if (a.hasExfiltration) missing += "含外发"
        return missing
    }

    /**
     * 违规原文里那句"为什么只能做到这档"。
     *
     * 理由为空时只可能是②（③必带卡点、①是最宽档不可能被判越界），所以这句话是写死的，不用分支。
     */
    private fun why(reasons: List<String>): String = reasons.joinToString("；").ifEmpty {
        "四要素齐只够先斩后奏：做完必须把结果与回滚点上呈御案，不许连报告都省"
    }

    private fun liftOf(tier: DiscretionTier): String = when (tier) {
        DiscretionTier.ACT_ON_FACE -> "不用请旨，但做完必须留痕（L4：谁、何时、改了什么、可逆否）"
        DiscretionTier.ACT_THEN_REPORT -> "可先做，做完把结果与回滚点上呈御案；留痕缺一格按 L4 记违规"
        DiscretionTier.PETITION_FIRST -> "先题请、人点头才动；本层永不自行放行，也不因等待而默许"
    }

    /** 违规原文里的档位中文名，与法典用词一致（别让读日志的人去猜枚举名）。 */
    private fun cn(tier: DiscretionTier): String = when (tier) {
        DiscretionTier.ACT_ON_FACE -> "便宜行事"
        DiscretionTier.ACT_THEN_REPORT -> "先斩后奏"
        DiscretionTier.PETITION_FIRST -> "题请"
    }

    /** [classify] 的返回壳：档位＋卡住它的理由。 */
    private data class TierAndReasons(val tier: DiscretionTier, val reasons: List<String>)
}
