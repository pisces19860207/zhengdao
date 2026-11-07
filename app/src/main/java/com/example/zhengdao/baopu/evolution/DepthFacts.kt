// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十七批 · 深度裁定的**读数源缝**（派工单 §一①「进化提案进入判定前调一次」的进料口）。
//
// 为什么是缝而不是直接 import：左相的成本读数住在 `agent` 包（弹卡日预算／打扰治理），
// 而 `agent` 已经 import `evolution`（金吾卫收工触发进化）——evolution 反手 import agent
// 就是包循环。本仓对依赖方向有闸（边界闸扫 import），所以照 `DecisionLedger.executorId`
// 同一条注入房型：**装配层把算好的读数递进来，本包只认形状不认出处**。
//
// 缺读数的口径（判据②的字面）：**全部默认值一律偏保守**——预算 0、置信 0、打扰 0、
// 授权档 0、误拦不升、灰度没交付。[CostFacts]／[EvidenceFacts] 的合成规则拿到这些默认值
// 必然停在最底档（浅＝只记不改）："不知道"永远不升档（505 判据②的老话，本批沿用）。
package com.example.zhengdao.baopu.evolution

/**
 * 左相/右相里**进化层自己算不出**的那几格读数（装配层给；缺哪格就留默认，不猜）。
 *
 * @param cardBudgetLeft 弹卡日预算余量（`AskDailyBudget.LIMIT - usedToday`；≤0＝用尽）
 * @param profileConfidence 画像置信 0..1（今天生产里还没有置信读数口 ⇒ 装配层给 0＝不升档）
 * @param interruptedToday 当日已打扰次数（今日问卡张数）
 * @param authTier 授权档映射（问卡治理：严格/标准 ⇒ 1＝确认后动，放权 ⇒ 2＝自决）
 * @param falseBlockRising 误拦申诉较昨日上升（问卡治理的误拦申诉账）
 * @param canaryAvailable 灰度可用（后果治理的灰度档读到全开才算；默认 false＝深档判不出）
 */
internal data class DepthSideReadings(
    val cardBudgetLeft: Int = 0,
    val profileConfidence: Double = 0.0,
    val interruptedToday: Int = 0,
    val authTier: Int = 0,
    val falseBlockRising: Boolean = false,
    val canaryAvailable: Boolean = false,
) {
    /** 折成左相成本位（[CostFacts] 的字段一一对应，不另造第三种形状）。 */
    fun toCostFacts(): CostFacts = CostFacts(
        cardBudgetLeft = cardBudgetLeft,
        profileConfidence = profileConfidence,
        interruptedToday = interruptedToday,
        authTier = authTier,
    )

    /** 灰度读数折成右相那一格。 */
    fun toCanaryStatus(): CanaryStatus =
        if (canaryAvailable) CanaryStatus.AVAILABLE else CanaryStatus.UNAVAILABLE
}

/**
 * 读数源（进程内单例缝）：装配层装、进化层读、测试换自己的。
 *
 * 默认 `{ DepthSideReadings() }`＝没装配 ⇒ 全默认 ⇒ 浅档（判据②的"无调用/读数缺失 ⇒ 浅档"）。
 * 读数源抛了也算没读数（拿默认），不许一个异常把进化链带塌。
 */
internal object DepthFactsWiring {

    @Volatile
    var sideReadings: () -> DepthSideReadings = { DepthSideReadings() }

    fun readings(): DepthSideReadings = runCatching { sideReadings() }.getOrDefault(DepthSideReadings())

    /** 直测收尾（谁装谁还）。 */
    fun resetForTest() {
        sideReadings = { DepthSideReadings() }
    }
}
