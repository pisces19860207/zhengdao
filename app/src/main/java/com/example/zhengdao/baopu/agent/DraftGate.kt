// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百七十八批（派工单 §2 · ③b 核验闸）：**上御案之前，逐条核过**。
//
// 路线图 §9.2b 的原话是"暗卫就不是抽检了，而是逐条核对"——拟稿写完之后**自动**送核验，
// 对提案里每一条"事实主张"给一个结论：
//   真 ⇒ 放行；假 ⇒ 该条不得上御案（打回拟稿重写）；无法判定 ⇒ 允许上，但卡面必须明写"未核实"。
//
// 三条纪律（写死在这里，别处不许改口）：
//   1. **核验方是接口不是实现**：暗卫（279）没合之前用 [StubVerifier]——它一律回"无法判定"。
//      为什么桩不许回"真"：没人核过就说真，等于把"未核实"伪装成"已核"，正是本批负例本体。
//   2. **缺判定的主张按"无法判定"处理**：核验方漏回一条，不能当成通过（也不能当假）。
//   3. **≤20 条**：逐条核对很贵，超了就拆单，不是把 200 条一起怼上去。
package com.example.zhengdao.baopu.agent

/**
 * 一条**事实主张**（拟稿里"我凭什么这么说"的那一句）。
 *
 * @param id 主张编号（`C1`/`C2`…）：核验结果按它对回去，不靠文本比对
 * @param text 主张原文（**人话**；技术字段不许出现在这里——那是证据区的事）
 * @param evidenceRef 证据引用（账本行 / 日志行 / 事件 id）——核验方要能指到具体那一行
 */
internal data class DraftClaim(
    val id: String,
    val text: String,
    val evidenceRef: String,
)

/** 一条主张的核验结论（③b 的三个输出值）。 */
internal enum class ClaimVerdict(val cn: String) {
    TRUE("真"),
    FALSE("假"),
    UNVERIFIABLE("无法判定"),
    ;

    companion object {
        fun ofWire(wire: String): ClaimVerdict? = entries.firstOrNull { it.name == wire || it.cn == wire }
    }
}

/**
 * 一条核验结果。
 *
 * @param evidence 判定的依据（哪一行日志/账本）；"假"必须指得到证据，不许只说"不对"
 */
internal data class ClaimCheck(
    val claimId: String,
    val verdict: ClaimVerdict,
    val evidence: String = "",
)

/** 核验方（暗卫 279 接进来时换实现，接口不动）。 */
internal fun interface ClaimVerifier {
    fun verify(claims: List<DraftClaim>): List<ClaimCheck>
}

/**
 * 暗卫未合之前的**桩**：一律回"无法判定"。
 *
 * 这不是偷懒，是唯一诚实的选择——
 * 桩要是回"真"，卡面上就会出现"已核·真"，而实际上**没有任何东西核过**。
 * 负例"把无法判定写成真"正是这么发生的。
 */
internal object StubVerifier : ClaimVerifier {
    override fun verify(claims: List<DraftClaim>): List<ClaimCheck> =
        claims.map { ClaimCheck(claimId = it.id, verdict = ClaimVerdict.UNVERIFIABLE, evidence = "") }
}

/** 闸的三种结局。 */
internal sealed class DraftGateResult {
    /** 放行（可能带"无法判定"的条目——卡面据此显示"未核实"）。 */
    data class Passed(val checks: List<ClaimCheck>) : DraftGateResult()

    /** 打回（拟稿重写）：有主张被判为假，或主张条数超限。 */
    data class Rejected(val reason: DraftReject, val bad: List<ClaimCheck>) : DraftGateResult()
}

/** 打回的原因（机器键；人话在拟稿层）。 */
internal enum class DraftReject(val cn: String) {
    FALSE_CLAIM("有事实主张被判为假：该条不得上御案，打回重写"),
    TOO_MANY_CLAIMS("事实主张超过 20 条：拆单再送核"),
}

/**
 * ③b 核验闸（**纯函数**：不读盘、不碰网、不写账）。
 *
 * 顺序刻意是"先查条数、再核内容"：超限的单子不必花核验成本。
 */
internal object DraftGate {

    /** 一次提案的核验条目上限（路线图 §9.2b 的成本护栏）。 */
    const val MAX_CLAIMS = 20

    fun check(claims: List<DraftClaim>, verifier: ClaimVerifier = StubVerifier): DraftGateResult {
        if (claims.size > MAX_CLAIMS) {
            return DraftGateResult.Rejected(DraftReject.TOO_MANY_CLAIMS, emptyList())
        }
        val returned = runCatching { verifier.verify(claims) }.getOrDefault(emptyList())
        val byId = returned.associateBy { it.claimId }
        // 核验方漏回的那几条按"无法判定"补齐：既不许当通过，也不许当假
        val full = claims.map { c -> byId[c.id] ?: ClaimCheck(c.id, ClaimVerdict.UNVERIFIABLE) }
        val bad = full.filter { it.verdict == ClaimVerdict.FALSE }
        if (bad.isNotEmpty()) {
            return DraftGateResult.Rejected(DraftReject.FALSE_CLAIM, bad)
        }
        return DraftGateResult.Passed(full)
    }

    /** 这张单子最终是不是"全部已核"（决定卡面标"真"还是"未核实"）。 */
    fun allVerified(checks: List<ClaimCheck>): Boolean =
        checks.isNotEmpty() && checks.all { it.verdict == ClaimVerdict.TRUE }

    /** 有没有"无法判定"的（卡面要明写"未核实"）。 */
    fun hasUnverified(checks: List<ClaimCheck>): Boolean =
        checks.any { it.verdict == ClaimVerdict.UNVERIFIABLE }

    /** 日志那一行（格式是判据，埋在调用点抓不到）。 */
    internal fun logLine(result: DraftGateResult): String = when (result) {
        is DraftGateResult.Passed ->
            "抱朴核验：放行（${result.checks.size} 条主张，" +
                "${result.checks.count { it.verdict == ClaimVerdict.UNVERIFIABLE }} 条未核实）"

        is DraftGateResult.Rejected ->
            "抱朴核验：打回（${result.reason.cn}）" +
                result.bad.joinToString("") { "｜${it.claimId}=${it.verdict.cn}" }
    }
}
