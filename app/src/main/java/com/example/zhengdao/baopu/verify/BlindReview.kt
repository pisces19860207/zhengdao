// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第三百三十九批 · **双盲复核的判定核**（339 工单口径：影子在副本上重做关键步骤，
// 两边产物/结论**指纹比对**；不一致 ⇒ 发 DISAGREEMENT 给 AccountabilityGate；复核方不看主执行体的自我报告）。
//
// 本批交的是比对与出场的**判定核**：指纹怎么算、怎么比、不一致怎么按 L32 的认定口径进追责；
// 「影子重做」的执行基底（谁去副本上把关键步骤真的跑一遍）还没有生产宿主
// （RunEngine 归 325、接入契约归 333），与本文件零调用方是同一件事：先有判定、后有接线（236 先例）。
//
// ## 判据（输入→输出，逐条可执行）
//   ①**指纹口径**（[BlindReview.digestOf]）：产物/结论各自过同一把 sha256（十六进制 64 位，
//     [BlindReview.isDigest]）——「拿结论原文当指纹」过不了形状关，比对就没有客观锚；
//   ②**盲的形状**（[BlindReviewInput]）：影子能看见的只有 stepId＋重做所需的原始事实——
//     **没有自我报告这个字段**，主执行体的结论在形状上进不了影子的输入；
//   ③**比对**（[BlindReview.compare]）：两枚指纹相等 ⇒ CONCUR；不等 ⇒ DISAGREEMENT；
//     两枚指纹必须都是 64 位 hex（[BlindReview.isDigest]），缺谁都不许比（缺位＝没做，不是「一致」）；
//   ④**出场**（[BlindReview.asFinding]）：DISAGREEMENT ⇒ 经 [AccountabilityGate.blindReviewFinding]
//     进 L32 的认定口径——双盲提出位自动为真（影子就是双盲任一方），**御批确认位留给人**
//     （复核说了不算，L32 判据①：认定须双盲提出＋御批确认）。
//
// ## 为什么御批位不自动
// 双盲只解决「谁说的」，不解决「说了算」：复核与主执行体打架时，收紧哪个类别的权限是 L32 的
// 认定后果——那种事按法典只有御批能定。把 confirmedByThrone 自动置真，等于复核自己给自己批条子。
package com.example.zhengdao.baopu.verify

import com.example.zhengdao.baopu.law.AccountabilityFinding
import com.example.zhengdao.baopu.law.AccountabilityKind
import java.security.MessageDigest

/**
 * 影子重做的输入：只有「重做哪一步」与「重做所需的原始事实」。
 *
 * **盲的形状关**：这里没有自我报告字段——主执行体的结论/辩解在类型上进不了影子的输入；
 * 影子只能从事实自己重做一遍，得出自己的指纹。
 */
internal data class BlindReviewInput(
    /** 重做哪一步（关键步骤的锚；与主执行轨迹对齐用）。 */
    val stepId: String,
    /** 重做所需的原始事实（机器键；不含结论、不含评价、不含自我报告）。 */
    val stepFacts: List<String>,
) {
    init {
        require(stepId.isNotBlank()) { "双盲重做要有步骤锚：没有 stepId 的重做没法与主轨迹对齐" }
        require(stepFacts.isNotEmpty() && stepFacts.all { it.isNotBlank() }) {
            "双盲重做要给原始事实：空事实＝影子什么都没拿到，比出来的「一致」是假的"
        }
    }
}

/** 比对结论（闭集）。 */
internal enum class BlindReviewOutcome(val wire: String) {
    /** 一致：影子重做的指纹与主执行体相同。 */
    CONCUR("concur"),

    /** 不一致 ⇒ 按 L32 认定口径送 [AccountabilityGate]（[BlindReview.asFinding]）。 */
    DISAGREEMENT("disagreement"),
    ;
}

/** 一次双盲比对的结论（两枚指纹各自留着——不一致时谁也不许覆盖谁，御案上并列）。 */
internal data class BlindReviewVerdict(
    val stepId: String,
    val outcome: BlindReviewOutcome,
    /** 主执行体的产物/结论指纹（sha256 十六进制）。 */
    val executorDigest: String,
    /** 影子重做的指纹（sha256 十六进制）。 */
    val shadowDigest: String,
)

/** 双盲复核比对核（纯判定；零 IO、零时钟——指纹由调用方各自算好带来）。 */
internal object BlindReview {

    /**
     * 指纹口径：对产物/结论的机器键清单过同一把 sha256，小写十六进制 64 位。
     *
     * 影子与主执行体**各自**对自己那份算（本函数不读对方，[BlindReviewInput] 里也没有对方的材料）。
     */
    fun digestOf(artifacts: List<String>): String {
        require(artifacts.isNotEmpty() && artifacts.all { it.isNotBlank() }) {
            "指纹要对真实材料算：空清单算出来的是恒同的假指纹"
        }
        val md = MessageDigest.getInstance("SHA-256")
        artifacts.forEach { item ->
            md.update(item.toByteArray(Charsets.UTF_8))
            md.update(byteArrayOf('\n'.code.toByte()))
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 指纹形状：64 位十六进制小写（防「拿结论原文当指纹」——比对必须落在 hash 上）。 */
    fun isDigest(s: String): Boolean = Regex("^[0-9a-f]{64}$").containsMatchIn(s)

    /**
     * 比对：两枚指纹都在形状上才许比（缺位＝没做，不许读成「一致」）；
     * 相等 ⇒ [BlindReviewOutcome.CONCUR]，不等 ⇒ [BlindReviewOutcome.DISAGREEMENT]。
     */
    fun compare(input: BlindReviewInput, executorDigest: String, shadowDigest: String): BlindReviewVerdict {
        require(isDigest(executorDigest)) {
            "主执行体的指纹不合形状（须 64 位 hex）：拿原文/空串当指纹的比对是假绿"
        }
        require(isDigest(shadowDigest)) {
            "影子的指纹不合形状（须 64 位 hex）：影子没真重做就不许出比对结论"
        }
        val outcome = if (executorDigest == shadowDigest) BlindReviewOutcome.CONCUR else BlindReviewOutcome.DISAGREEMENT
        return BlindReviewVerdict(
            stepId = input.stepId,
            outcome = outcome,
            executorDigest = executorDigest,
            shadowDigest = shadowDigest,
        )
    }

    /**
     * DISAGREEMENT ⇒ 按 L32 认定口径出场：造一条**双盲提出位自动为真、御批确认位留给人**的认定。
     *
     * 为什么在这里直接投影事实而不是调 law/ 的口子：双盲的「提出」是**影子自己的事实**
     *（影子就是双盲任一方），由生产者投影给判定层——law/ 的 [AccountabilityGate.decide] 仍然
     * 只认「提出＋御批」两样齐的认定（复核说了不算，御批确认位没人在场 ⇒ decide 判无效、不动权限）。
     * 这也正是名册纪律的形状：谁真的把认定喂进 `AccountabilityGate.decide`，那天的机检才放行 WIRED。
     *
     * CONCUR 没有认定可提（一致不追责）——拿一致来造认定直接抛。
     */
    fun asFinding(
        verdict: BlindReviewVerdict,
        kind: AccountabilityKind,
        category: String,
        atMs: Long,
    ): AccountabilityFinding {
        require(verdict.outcome == BlindReviewOutcome.DISAGREEMENT) {
            "只有不一致（DISAGREEMENT）才进追责：一致（CONCUR）没有认定可提"
        }
        return AccountabilityFinding(
            kind = kind,
            category = category,
            atMs = atMs,
            raisedByBlindReview = true,
            confirmedByThrone = false,
        )
    }
}
