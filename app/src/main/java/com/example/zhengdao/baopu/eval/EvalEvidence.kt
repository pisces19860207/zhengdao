// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第一百六十八批（派工单 §2 消费点 B）：**eval 证据按来源读**——
// 决策口优先用「个人生产证据」（本机 ProdEval 产物，7 天内），否则回退「发版证据」
// （构建期 eval 门禁的报告，stage 进 assets/eval/eval-result.json），
// 两者都没有 ⇒ null ⇒ policy 判 DEFER（**不是通过**）。
//
// 为什么必须有来源字段：同一个 passRate 来自本机实测还是发版流水线，含义不同——
// 99 批把「哪次算最近」在构建期定死；本批把运行期那一份也纳入，但**必须标出来源与日期**，
// 不许两套分数混用（派工单 §2 原文）。来源与日期随 [com.example.zhengdao.baopu.EvolutionEval]
// 一路带进决策审计与建议卡。
package com.example.zhengdao.baopu.eval

import com.example.zhengdao.baopu.EvolutionEval

/** eval 证据的来源（决策卡与审计里必须标）。 */
internal enum class EvalEvidenceSource {
    /** 发版证据：构建期 eval 门禁的报告 ⇒ assets/eval/eval-result.json（99 批那一份）。 */
    RELEASE,

    /** 个人生产证据：本机 ProdEval 产物 ⇒ filesDir/eval/prod-result.json。 */
    PERSONAL_PROD,
}

/** 生产证据保鲜期：7 天（派工单 §2「优先读 7 天内 prod-result」）。 */
const val PROD_EVAL_MAX_AGE_MS = 7L * 24 * 60 * 60 * 1000

/**
 * 选一份证据（**纯函数**；负例靶子：过期生产证据冒充可用、缺证据返非 null）。
 *
 * @param prod 个人生产证据（本机；拿不到 ⇒ null）
 * @param release 发版证据（随包；拿不到 ⇒ null）
 * @param now 当前时刻（调用方传，JVM 直测）
 * @param maxAgeMs 生产证据保鲜期（默认 [PROD_EVAL_MAX_AGE_MS]）
 * @return 优先**新鲜**的 prod；prod 缺/过期 ⇒ release；都没有 ⇒ null（DEFER，不是通过）
 */
internal fun selectEvalEvidence(
    prod: EvolutionEval?,
    release: EvolutionEval?,
    now: Long,
    maxAgeMs: Long = PROD_EVAL_MAX_AGE_MS,
): EvolutionEval? {
    // 保鲜期判据是 ranAt：没有 ranAt 的生产证据判不了新鲜程度 ⇒ 按没有处理（不猜）。
    // 时钟拨快／未来时间戳（差为负）同样按不新鲜——宁可回退，也不拿它当过线依据。
    if (prod != null && prod.ranAt != null && now - prod.ranAt in 0..maxAgeMs) return prod
    return release
}
