// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十批 · 许愿流水线（2026-11-07）：把"许愿式编程"做成一条可测的流水线。
// 复用既有零件（SkillAutoWrite / RehearsalRunner / CardBuild / OutputCitationGate /
// NovelProjectStore），不新造技能引擎或沙盒。本文件只放形状，判据在各纯函数件里。
package com.example.zhengdao.baopu.wish

/** 四类产物（派工单 §六/§九）：同一条流水，差别只在验收怎么写、试跑在哪跑。 */
enum class WishProduct { SKILL, CARD, CONTENT, NOVEL }

/** 流水线阶段（六步＋终态）。 */
enum class WishStage {
    WISH, SPEC_PENDING, DRAFTING, REHEARSAL, SELF_CHECK, GRAY,
    PROMOTED, ROLLED_BACK, REJECTED, HALTED,
}

/** 一条候选验收判据（用户不会写验收 ⇒ 给 2–3 条让他挑，不许让用户写）。 */
data class CandidateCriterion(
    val id: String,
    val label: String,
    val mechanical: String,
)

/** 规格（派工单 §一②：目标/输入/输出/验收/风险）。 */
data class WishSpec(
    val goal: String,
    val input: String,
    val output: String,
    val candidates: List<CandidateCriterion>,
    val chosen: List<String> = emptyList(),
    val risk: String = "",
) {
    val confirmed: Boolean get() = chosen.isNotEmpty()
    val acceptance: List<CandidateCriterion> get() = candidates.filter { it.id in chosen }
}

/** 一次许愿的完整上下文（贯穿六步）。 */
data class WishContext(
    val wishId: String,
    val wishText: String,
    val product: WishProduct,
    val spec: WishSpec? = null,
    val stage: WishStage = WishStage.WISH,
    val draftVersion: Int = 0,
    val consecutiveFailures: Int = 0,
    val rehearsalOk: Boolean = false,
    val selfCheckOk: Boolean = false,
    val grayUses: Int = 0,
)

/** 流水线出口（调用方只看这个）。 */
sealed interface WishOutcome {
    data class AwaitingConfirmation(val wishId: String, val spec: WishSpec) : WishOutcome
    data class InGray(val wishId: String, val product: WishProduct, val uses: Int) : WishOutcome
    data class Promoted(val wishId: String, val product: WishProduct) : WishOutcome
    data class RolledBack(val wishId: String, val reason: String) : WishOutcome
    data class Rejected(val reason: String) : WishOutcome
    data class Halted(val wishId: String, val failures: Int) : WishOutcome
}