// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百三十九批 I2 · 数字无出处。
//
// 查什么（派工单 §1 原行）：回答里的数字找不到表/文件来源 ⇒ 复用 227 `AnswerGate` 的判据，
// **抽检**（不是每轮），抽 k=10，命中 ≥1 报。
//
// 三件不写清楚就会变成假绿的事：
// 1. **判据不自造**：本文件不实现任何"数字像不像话"的规则，只调 `AnswerGate.check`。
//    两条路各判一套，同一个回答在一边绿一边红，纠察自己就成了第二个口径源。
// 2. **ground 必须是日志里的工具回灌原文**，不是模型的自述（§7.3 第三条）。
//    房型：`part(kind='tool')` 的 payload 里 `result` 那一格就是当轮回灌的原文
//    （`RunEngine.persistToolExchange:3955`），与 227 在跑的那条 ground 同源同形。
// 3. **抽检有上限**：`PatrolSources` 那侧的 SQL 带 `LIMIT`，本函数只在已取到的池子里
//    等距取 10 条（负例④「全检」在两层各钉一次）。
package com.example.zhengdao.baopu.inspect

import com.example.zhengdao.baopu.agent.AnswerGate
import com.example.zhengdao.core.TimeFormats

/** 一条待抽的回答样本（answer 与 ground 都取自库里的 part 行，不来自模型自述）。 */
internal data class AnswerSample(
    val messageId: Long,
    val sessionId: Long,
    val atMs: Long,
    val answer: String,
    /** 本轮成功工具的回灌原文（`part(kind='tool')` 的 `result`；多段拼在一起）。 */
    val ground: String,
)

/** I2 的结果。 */
internal data class NumberGroundingResult(
    val sampled: Int,
    val hits: List<Pair<AnswerSample, AnswerGate.Finding>>,
)

internal object NumberGroundingCheck {

    /** 抽几条（派工单写死 k=10）。 */
    const val SAMPLE_K = InspectLimits.ANSWER_SAMPLE

    /** 只认这一条判据：数字不在表里（另两条②③归 I4 的虚报率口径，别在这儿混报）。 */
    private fun numberFindings(sample: AnswerSample): List<AnswerGate.Finding> =
        AnswerGate.check(answer = sample.answer, ground = sample.ground, workMode = true)
            .filter { it.check == AnswerGate.Check.NUMBER_NOT_FROM_TABLE }

    /** 抽检（纯函数：给一批样本，等距取 k 条跑判据）。 */
    fun sample(pool: List<AnswerSample>, k: Int = SAMPLE_K): List<AnswerSample> =
        InspectSample.spread(pool, k)

    /**
     * 「虚报」的轮次数（I4 那条 20% 的分子）：同一批 227 判据里的②「疑似推算」与
     * ③「结论与没找到矛盾」，**一条都不自造**。
     *
     * 为什么不在这里单独写一套规则：I2 与 I4 用的是同一份回答账，两套口径迟早互相打脸。
     * 数的是**轮次**（一条回答里两处矛盾也只算一轮）——比率的分母就是"抽到的轮次"，
     * 分子按回答数才能对得上。
     */
    fun overclaimRounds(pool: List<AnswerSample>): Int = pool.count { s ->
        AnswerGate.check(answer = s.answer, ground = s.ground, workMode = true)
            .any { it.check != AnswerGate.Check.NUMBER_NOT_FROM_TABLE }
    }

    fun run(pool: List<AnswerSample>, k: Int = SAMPLE_K): NumberGroundingResult {
        val picked = sample(pool, k)
        val hits = picked.flatMap { s -> numberFindings(s).map { s to it } }
        return NumberGroundingResult(picked.size, hits)
    }

    private fun evidenceOf(sample: AnswerSample, finding: AnswerGate.Finding): String =
        "${TimeFormats.monthDay(sample.atMs)} ${TimeFormats.hourMinute(sample.atMs)} " +
            "会话 ${sample.sessionId}·消息 ${sample.messageId}：${finding.text}"

    fun check(pool: List<AnswerSample>, k: Int = SAMPLE_K): InspectFinding {
        val res = run(pool, k)
        if (res.sampled == 0) {
            return InspectFinding(
                check = InspectCheck.NUMBER_GROUNDING,
                verdict = InspectVerdict.NO_DATA,
                oneLine = InspectText.line("数字无出处：窗口内没有可抽的回答"),
                suggestion = "有过对话再巡（抽检要有池子，空池不算通过）",
                count = 0,
            )
        }
        val hits = res.hits.size
        val evidence = res.hits.take(InspectDetailLimits.MAX_EVIDENCE_PER_FINDING)
            .map { (s, f) -> evidenceOf(s, f) }
        return if (!InspectSample.reportable(hits, InspectLimits.SAMPLE_MIN_HITS)) {
            InspectFinding(
                check = InspectCheck.NUMBER_GROUNDING,
                verdict = InspectVerdict.CLEAR,
                oneLine = InspectText.line("数字无出处 0 条（抽 ${res.sampled} 条回答）"),
                suggestion = "无需处理",
                evidence = listOf("抽样 ${res.sampled}/${pool.size} 条回答，全部数字有回灌出处"),
                count = 0,
            )
        } else {
            InspectFinding(
                check = InspectCheck.NUMBER_GROUNDING,
                verdict = InspectVerdict.HIT,
                oneLine = InspectText.line("数字无出处 $hits 条（抽 ${res.sampled} 条）：${res.hits.first().second.text}"),
                suggestion = "把那句改成读数，或让它先查一次表再答（AnswerGate 口径不变）",
                evidence = evidence,
                count = hits,
            )
        }
    }
}
