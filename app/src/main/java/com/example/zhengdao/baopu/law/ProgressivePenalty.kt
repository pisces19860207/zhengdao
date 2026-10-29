// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十五批 · 法典卷七 **L24 累进罚则**——「可再一再二，绝不可过三」的**生产判定层**。
//
// 出处：第二百七十五批派工单（私有侧《落地排单-2026-10-29》§275，接线第一批＝纯判定、不动执行链）；
// 判据原文＝法典卷七 L24（私有侧《抱朴-法典-2026-10-27》；这两份文档不在本库内，别按库内路径找）。
// 本文件做的事：把 271 批（分支 `feat/law-gates`，未合并）写在测试侧的 L24 判据搬进 `app/src/main`：
// 那份 spec 自带一个 `Penalty` 对象，生产怎么改它都不会红；这里让判定住在生产，门禁测试只调它。
//
// ## 判据（输入→输出，逐条可执行，没有"视情况而定"）
// 同 `kind` 在 [WINDOW_MS]（30 天）窗口内的**有效**违规次数 `n`（含本次）：
// - `n = 1` ⇒ [PenaltyTier.RECORD] 记：落一条账，**不打扰**；
// - `n = 2` ⇒ [PenaltyTier.ASK] 问：弹卡，给两个选项；
// - `n = 3` ⇒ [PenaltyTier.BLOCK] 拦：拒绝执行，须御批才解；
// - `n ≥ 4` ⇒ [PenaltyTier.FUSE] 停：绕道再犯，熔断本次会话、保留现场供御批，并出御案报告。
//   FUSE 的两条边界（2026-10-29 裁定 Q1）：
//   ①**只由累进计数触发**——除了 `n ≥ 4` 这条路，本层不给它开第二个入口（自首减档也不许留着熔断）；
//   ②**只熔断本次会话**（[PenaltyVerdict.haltSession]）＋出一份御案报告（[PenaltyVerdict.imperialReportRequired]），
//     不封账号、不删数据：本层的返回值里根本没有"账号级/数据级动作"这种字段，执行层无从依据。
// 三条修正：
// 1. **主动报账减一档**（自首）：[PenaltyTier.FUSE]→[PenaltyTier.BLOCK]→[PenaltyTier.ASK]→
//    [PenaltyTier.RECORD]，且 RECORD 不再往下降——减档不许减成"没这一条"；
// 2. **疑罪从无**：[Violation.evidenceSufficient] 为假 ⇒ 恒为 RECORD、本次**不计入**累进，
//    历史里无证据的条次同样不计（否则"没证据也能攒次数"，下次就能凭它拦人）；
// 3. **窗口归零**：窗口外的同类旧账不参与计数（30 天无同类 ⇒ 归零），归零的是**计数**不是**记录**
//    （账本身永久留档，留档是 208 台账的事，本层只决定这一条按哪档说话）。
//
// ## 为什么放在生产、又为什么只有判定
// 执行层（276 要接的 `GuardPipeline` 拒绝分支）手里有"这次是不是第三次"的事实，却不该自己有档位口径；
// 台账（208）手里有历史，却不该决定"拦不拦"。所以这一层把口径单独写死，且**零 IO、零时钟、零落盘**：
// 历史列表与 `nowMs` 全部由调用方给，本层只回一个说得出理由、也写清了"怎么解"的结论。
package com.example.zhengdao.baopu.law

/** L24 的四档罚则（按严厉程度递增声明，比较一律用 [ProgressivePenalty.decide] 的返回值）。 */
internal enum class PenaltyTier {
    /** 记：落一条账，不打扰。 */
    RECORD,

    /** 问：弹卡，给两个选项。 */
    ASK,

    /** 拦：拒绝执行，须御批才解。 */
    BLOCK,

    /** 停：熔断本次会话，保留现场供御批。 */
    FUSE,
}

/**
 * 一条违规事实（208 台账行的最小投影）。
 *
 * 由调用方从台账映射而来（276 的活）；本层不读盘，所以 [atMs] 与 [selfReported] 都是传进来的事实。
 */
internal data class Violation(
    /** 违规类型，同 `kind` 才累进（法典：同 kind 同违规类型）。 */
    val kind: String,
    /** 这次违规的时刻（毫秒）。 */
    val atMs: Long,
    /** 主动报账（自首）⇒ 减一档。 */
    val selfReported: Boolean = false,
    /** 证据是否够：不够 ⇒ 不罚只记，且不计入累进（疑罪从无）。 */
    val evidenceSufficient: Boolean = true,
)

/**
 * L24 的一次判定结论（执行层要做的每件事都在这里，不需要再自己判档）。
 *
 * - [effectiveCount] 是**有效**次数（窗口内、有证据、同 kind，含本次；疑罪从无那条不计）；
 * - [counted] 说明本次有没有进这个数（只有的罪状时才 false）；
 * - [disturb] 是"要不要打扰用户"：只有 RECORD 为 false，问/拦/停都要出声；
 * - [requiresImperialApproval] 为真 ⇒ 这一档只能由御批解除，执行层不许自行放行；
 * - [haltSession] 为真 ⇒ 只熔断**本次会话**（法典的"停"）并保留现场，不涉及账号或数据（Q1 第 ② 条）；
 * - [imperialReportRequired] 为真 ⇒ 这次要出一份御案报告——只有 FUSE 要，别的档都是 false。
 */
internal data class PenaltyVerdict(
    val lawRef: String,
    val tier: PenaltyTier,
    val effectiveCount: Int,
    val counted: Boolean,
    val disturb: Boolean,
    val requiresImperialApproval: Boolean,
    val haltSession: Boolean,
    val imperialReportRequired: Boolean,
    val reason: String,
    val howToLift: String,
)

/** L24 的唯一判据实现（生产）。 */
internal object ProgressivePenalty {

    /** 法典 L24 的窗口：30 天。 */
    const val WINDOW_MS = 30L * 24 * 60 * 60 * 1000

    /**
     * 法条引用（落账与卡面引用用）。
     *
     * 写成字符串而不是 270 的 `LawRef` 枚举：那个枚举归 272 的 `baopu/inspect/` 片区管（它只给自己补了 `L28`），
     * 且 270 的闭集钉把 `"L24"` 列在「必须抛异常」名单里 ⇒ 补它是**两行同改**。按 2026-10-29 裁定（Q5）这活归 276：
     * 扩完枚举与闭集钉后把这一格换成 `LawRef.L24`，本批不碰那两个文件。
     */
    const val LAW_REF = "L24"

    /**
     * 判一次：这次违规按哪档说话。
     *
     * @param fresh 本次违规（[Violation.kind] 决定与哪批历史累进）
     * @param history 历史违规（可以混着别的 kind、可以混着窗口外的，本层自己筛）
     * @param nowMs 现在（由调用方给，本层不读时钟）
     * @param windowMs 窗口，默认 [WINDOW_MS]；单测传别的值来验"改窗口 ⇒ 判定变"
     */
    fun decide(
        fresh: Violation,
        history: List<Violation>,
        nowMs: Long,
        windowMs: Long = WINDOW_MS,
    ): PenaltyVerdict {
        require(fresh.kind.isNotBlank()) { "L24 判定的 kind 不许为空：累进靠它分组" }
        val prior = history.count {
            it.kind == fresh.kind && it.evidenceSufficient && inWindow(it.atMs, nowMs, windowMs)
        }
        // 疑罪从无先出签：既不定罚，也不给下一次攒次数（所以 counted=false）
        if (!fresh.evidenceSufficient) {
            return PenaltyVerdict(
                lawRef = LAW_REF,
                tier = PenaltyTier.RECORD,
                effectiveCount = prior,
                counted = false,
                disturb = false,
                requiresImperialApproval = false,
                haltSession = false,
                imperialReportRequired = false,
                reason = "证据不足：只记一条，不罚、不计入累进（疑罪从无）",
                howToLift = "补上证据再奏：证据够的那一次才按累进定档",
            )
        }
        val n = prior + 1
        val base = when {
            n <= 1 -> PenaltyTier.RECORD
            n == 2 -> PenaltyTier.ASK
            n == 3 -> PenaltyTier.BLOCK
            else -> PenaltyTier.FUSE
        }
        // 自首减一档；RECORD 已经是最低档，不许减成"没这一条"
        val tier = if (fresh.selfReported) oneSofter(base) else base
        return PenaltyVerdict(
            lawRef = LAW_REF,
            tier = tier,
            effectiveCount = n,
            counted = true,
            disturb = tier != PenaltyTier.RECORD,
            requiresImperialApproval = tier == PenaltyTier.BLOCK || tier == PenaltyTier.FUSE,
            haltSession = tier == PenaltyTier.FUSE,
            imperialReportRequired = tier == PenaltyTier.FUSE,
            reason = reasonOf(tier, n, fresh.selfReported),
            howToLift = liftOf(tier),
        )
    }

    /** 只数**已经发生**的：未来的账不参与窗口（时钟回拨或误填时刻，都不许提前把累进顶满）。 */
    private fun inWindow(atMs: Long, nowMs: Long, windowMs: Long): Boolean =
        atMs <= nowMs && nowMs - atMs <= windowMs

    /** 减一档（FUSE→BLOCK→ASK→RECORD，RECORD 到此为止）。 */
    private fun oneSofter(tier: PenaltyTier): PenaltyTier = when (tier) {
        PenaltyTier.FUSE -> PenaltyTier.BLOCK
        PenaltyTier.BLOCK -> PenaltyTier.ASK
        PenaltyTier.ASK -> PenaltyTier.RECORD
        PenaltyTier.RECORD -> PenaltyTier.RECORD
    }

    /** 给模型/卡面的一句理由（含第几次，含为什么减档）。 */
    private fun reasonOf(tier: PenaltyTier, n: Int, self: Boolean): String {
        val tail = if (self) "（主动报账，减一档）" else ""
        return when (tier) {
            PenaltyTier.RECORD -> "第 ${n} 次：记一条，不打扰$tail"
            PenaltyTier.ASK -> "第 ${n} 次：弹卡问一次，给两个选项$tail"
            PenaltyTier.BLOCK -> "第 ${n} 次：拦下不执行，须御批才解$tail"
            PenaltyTier.FUSE -> "第 ${n} 次（绕道再犯）：熔断本次会话，保留现场供御批$tail"
        }
    }

    /** 「怎么解」一句（法典硬要求：拦截时必须连解除方式一起给，不许只说"不行"）。 */
    private fun liftOf(tier: PenaltyTier): String = when (tier) {
        PenaltyTier.RECORD -> "无需解除：这一条只落账"
        PenaltyTier.ASK -> "这一张卡上选一个选项即解（放行 / 改掉做法）"
        PenaltyTier.BLOCK -> "须御批才解：御案上批这一次；不批则维持拒绝"
        PenaltyTier.FUSE -> "会话已熔断：现场留档并出御案报告，须御批才恢复"
    }
}
