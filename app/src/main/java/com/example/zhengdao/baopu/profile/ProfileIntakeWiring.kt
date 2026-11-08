// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十九批 · 画像装配接线（派工单 §一）：`AskIntakeGate.profileProvider` 的生产挂接。
//
// 为什么是这个形状：
//   · 画像符号只许住在本包与 BaopuRuntime（ProfileInvisibilityTest 钉死）⇒ 查询本体住这里，
//     装配层只调一行 [install]；开关读法由装配层注入（本包零 Android 依赖）。
//   · 数据源只用既有读口：`UserProfileBlackbox.isStableKind`（引擎自己的「已知」闸：
//     ≥20 有效样本＋份额 ≥60% 连续 ≥14 天）＋ `distribution`（Dirichlet 后验份额当置信度）。
//     不新造画像、不加读口、不改两层结构。
//   · 冷启动/画像为空/弱先验 ⇒ 一律 null（＝「这类问题没有画像读数」），不造数。
//   · 授权类恒 null：画像不许代「能不能」（499 边界）；这里连读数都不给。
//   · 读数只用于「要不要问」：产出的 conclusion 是机器组织的通用话，不含任何采样原文
//     （引擎类型上就只有机器键/哈希，原文根本进不来）；不进上下文拼装。
package com.example.zhengdao.baopu.profile

import com.example.zhengdao.baopu.agent.AskClass
import com.example.zhengdao.baopu.agent.AskIntakeGate
import com.example.zhengdao.baopu.agent.ProfileBoundary

/**
 * 画像读数口（529 批的唯一生产挂接；测试直接调 [answerFor] 或挂假开关）。
 *
 * 开关默认开；关掉 ⇒ [answerFor] 恒 null ⇒ 与接线前逐字一致（判据④）。
 */
internal object ProfileIntakeWiring {

    /** 开关读法（装配层注入 SP 读口；本包不碰 Android）。 */
    @Volatile
    private var enabled: () -> Boolean = { true }

    /** 时钟（调用方传入，与画像引擎同一纪律；测试注入假时钟）。 */
    @Volatile
    private var clock: () -> Long = { System.currentTimeMillis() }

    /** 装配（幂等，重复装配只换读法）。生产唯一调用点在 BaopuRuntime（与 selfCheck 同处）。 */
    fun install(
        enabled: () -> Boolean = { true },
        now: () -> Long = { System.currentTimeMillis() },
    ) {
        this.enabled = enabled
        clock = now
        AskIntakeGate.profileProvider = { cls -> answerFor(cls) }
    }

    /**
     * 查一次（判据②③④⑤的落点）。
     *
     * 返回 null 的全部情形：授权类／开关关／黑箱未装配／该类未成稳定偏好／置信不足。
     * 「≥2 条事实」按引擎闸口径落地：稳定闸本身要求 ≥20 有效样本（弱先验 <20 一律等分），
     * 所以不足 20 条时这里必为 null——闸不放宽（派工单边界：两层结构不动）。
     */
    fun answerFor(cls: AskClass): ProfileBoundary.ProfileAnswer? {
        if (cls == AskClass.AUTHORIZATION) return null
        if (!enabled()) return null
        if (!UserProfileBlackbox.installed()) return null
        val now = clock()
        val kind = cls.wire
        if (!UserProfileBlackbox.isStableKind(kind, now)) return null
        val share = UserProfileBlackbox.distribution(now, CANDIDATE_KINDS)[kind] ?: return null
        if (share < ProfileBoundary.MIN_CONFIDENCE) return null
        return ProfileBoundary.ProfileAnswer(
            entryKey = kind,
            conclusion = CONCLUSION,
            // 黑箱读口不出精确样本数（面积钉死）；isStableKind 成立即引擎已证 ≥20，
            // 这里报的是闸下限（保守方向），不是测量值——只用于过边界闸与落账。
            sampleCount = ProfileBoundary.MIN_SAMPLES,
            confidence = share,
        )
    }

    /** 清空（测试专用：谁用谁还；开关/时钟回默认，读数口摘回 null）。 */
    fun resetForTest() {
        enabled = { true }
        clock = { System.currentTimeMillis() }
        AskIntakeGate.profileProvider = null
    }

    /** 候选类＝四类非授权问题的机器键（分布在这四类上算后验；不虚构候选键）。 */
    private val CANDIDATE_KINDS = AskClass.values()
        .filter { it != AskClass.AUTHORIZATION }
        .map { it.wire }

    /** 代答话（机器组织的通用结论；引擎里没有原文，这句话就是全部「内容」）。 */
    private const val CONCLUSION = "这类事沿用你的长期做法"
}