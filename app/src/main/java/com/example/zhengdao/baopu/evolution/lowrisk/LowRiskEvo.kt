// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百六十八批 A（2026-11-10）· 低风险四域接既有进化链的**装配缝**。
//
// 派工单原话：「四域各自接入既有链（428/473/495/517），只用既有机制，不许新建进化机制」。
// 本文件因此只有"转手"，没有"判定"：
//   · 问档 ⇒ 既有 `ConsequenceWiring.currentCanaryBand`（473；档从 208 账读回，不读开关）；
//   · 问深 ⇒ 既有 `ConsequenceWiring.currentDepth`（517；没账 ⇒ 浅）；
//   · 判准 ⇒ 既有 `LowRiskGate.admit`（三格全来自既有链，见其文件头）；
//   · 观察账 ⇒ 既有 `ConsequenceWiring.observe`（473 观察行，指标的唯一来源——
//     495 的"没数不许动档"由 `CanaryMetrics.known` 在梯子里内建，这里不重复判）;
//   · 红线拒账 ⇒ 既有 `ConsequenceWiring.recordAdvisor`（506 追加口径：**被拦也要记**；
//     参谋意见账是既有词表里唯一"拦了也留一行"的那一类，四域的拒行不新开 kind）；
//   · 扫档 ⇒ 既有 `ConsequenceWiring.decideCanaryBand`（473：达标自动转全开／坏消息自动回灰，
//     拒行一律走 `CanaryLadder.refusalOf`，模型自调档在账外就被拒）。
//
// 四域各自的生产落点（一域一处，源级门禁 `LowRiskEvoGateTest` 逐域钉缝）：
//   · 技能＝`agent/tools/SkillTools.kt` 免批写支路（候选=write_skill 提案）；
//   · 提示词尾部＝`CriteriaPitPack.systemSuffix`（候选=尾段版本变更）；
//   · 记忆组织＝`memoryimport/FreeTextMemoryWiring.autoRun`（候选=一次收编）；
//   · 调度降频＝`inspect/NocturnalAuto.runDue`（候选=一趟自动抽检）；
//   · 扫档＝`evolution/EvolutionWiring.onRunSettled` 收工家族一行。
//
// 边界：本文件不碰 `GuardPipeline`（归 548/550）、不碰 `RunEngine`、不写第二本账；
// `CanaryRegistry` 缺省口径（缺省＝关闭或灰度）一个字不改——四域在登记表里的缺省档
// 决定了接线当天生产行为逐字节不变（灰度档照常放行，只是从"没人看"变成"每步落账"）。
package com.example.zhengdao.baopu.evolution.lowrisk

import com.example.zhengdao.baopu.consequence.AdvisorOpinion
import com.example.zhengdao.baopu.consequence.AdvisorRules
import com.example.zhengdao.baopu.consequence.CanaryCapability
import com.example.zhengdao.baopu.consequence.CanaryLadder
import com.example.zhengdao.baopu.consequence.CanaryMetrics
import com.example.zhengdao.baopu.consequence.CanaryObservation
import com.example.zhengdao.baopu.consequence.CanarySample
import com.example.zhengdao.baopu.consequence.ConsequenceWiring
import com.example.zhengdao.core.RunLog

/**
 * 四域的装配缝（判据全在既有链上；本对象只有读账、转手、落账三个动作）。
 *
 * 四域能力对照（473 登记表）：技能=`REFLECTION_AUTO_EDIT`（既有）·
 * 提示词尾部=`PROMPT_TAIL`（本单按登记表自己的扩法补一条，缺省灰度）·
 * 记忆组织=`MEMORY_IMPORT`（既有）· 调度降频=`NOCTURNAL_PATROL`（既有）。
 */
internal object LowRiskEvo {

    /** 参谋账里"这是门出的意见"的标（advisor 线值前缀；读侧按它筛四域的拒行）。 */
    internal const val ADVISOR_GATE_PREFIX: String = "lowrisk-gate:"

    /** 四域在 473 登记表里的能力（扫档按这张清单逐一走梯子）。 */
    internal val CAPABILITIES: List<CanaryCapability> = listOf(
        CanaryCapability.REFLECTION_AUTO_EDIT,
        CanaryCapability.PROMPT_TAIL,
        CanaryCapability.MEMORY_IMPORT,
        CanaryCapability.NOCTURNAL_PATROL,
    )

    /**
     * 问一次门（档位与深度都从账上现读）。
     *
     * 账根没挂 ⇒ 档按缺省、深按浅（`currentCanaryBand`/`currentDepth` 自己的口径）——
     * 技能/收编/暗卫三域缺省都是灰度 ⇒ 没装配时生产行为不变，只是观察账落不上（`stamp` 只 log）。
     */
    fun gate(capability: CanaryCapability, candidate: EvoCandidate): LowRiskVerdict {
        val band = ConsequenceWiring.currentCanaryBand(capability.wire)
        val depth = ConsequenceWiring.currentDepth(capability.wire)
        return LowRiskGate.admit(band, depth, candidate)
    }

    /** 落一条观察账（473 观察行；返回值只说明账落上没有，失败已带原因进日志——不拦动作）。 */
    fun observeRun(
        capability: CanaryCapability,
        ok: Boolean,
        atMs: Long,
        askedHuman: Boolean = false,
        permanentLoss: Boolean = false,
    ): Boolean = ConsequenceWiring.observe(
        CanaryObservation(capability.wire, ok = ok, askedHuman = askedHuman, permanentLoss = permanentLoss),
        atMs,
    )

    /**
     * 红线拒并记账（判据④的"记账"半边；"拒"已由 [LowRiskGate] 出过结论）。
     *
     * 走既有参谋意见账：506 给这一类写明的用途就是"被拦下来的意见也记这一类，
     * 不记就等于没人知道模型试过一次"。advice 恒为 fail——门不给人发票。
     *
     * @return 既有 [ConsequenceWiring.recordAdvisor] 的返回值（null＝账上收下了这行）
     */
    fun recordRefusal(
        capability: CanaryCapability,
        line: EvoRedLine,
        why: String,
        candidate: EvoCandidate,
        atMs: Long,
    ): String? = ConsequenceWiring.recordAdvisor(
        AdvisorOpinion(
            advisor = ADVISOR_GATE_PREFIX + capability.wire,
            advice = AdvisorRules.ADVICE_FAIL,
            reason = "红线拒（${line.cn}）：$why",
            risks = listOf(line.wire, candidate.target),
            aboutOwnOutput = false,
            atMs = atMs,
        ),
    )

    /**
     * 收工扫档（生产调用点：`EvolutionWiring.onRunSettled` 一行——收工家族是 428 定下的
     * 收尾位置，四域的梯子跟判据链同一个节拍走）。
     *
     * 每域一次：样本从账上算指标，达标自动转全开、坏消息自动回灰——判定全在
     * [ConsequenceWiring.decideCanaryBand]（473 的梯子），本函数只管"逐域都问到"。
     * 单域算不动不许带崩别域（runCatching 只 log——账读不动是账的问题，不是四域的问题）。
     */
    fun sweepOnSettle(nowMs: Long) {
        CAPABILITIES.forEach { cap ->
            runCatching {
                ConsequenceWiring.decideCanaryBand(cap.wire, nowMs, passedRoundsOf(cap.wire, nowMs))
            }.onFailure {
                RunLog.log("低风险四域扫档没走完（${cap.wire}）：${it.javaClass.simpleName}")
            }
        }
    }

    /**
     * 「连续两轮达标」的两轮怎么从账上数（473 `PASS_ROUNDS_FOR_FULL` 的生产读法）。
     *
     * 梯子要的"轮"是一次读数窗口；窗口值在账上没有行（档位没动不落账，473 的纪律），
     * 所以两轮＝**两个不重叠的窗都达标**：14 天整窗＋7 天近窗，各自从同一条样本账现算。
     * 这样"连续"完全由账支撑——进程重启、换机都不丢，内存里不攒任何计数（473 §二② 的字面）。
     */
    internal fun passedRoundsOf(capabilityWire: String, nowMs: Long): Int {
        val samples: List<CanarySample> = ConsequenceWiring.samplesOf(capabilityWire)
        val fullWindow = CanaryMetrics.of(samples, nowMs)
        val recentWindow = CanaryMetrics.of(samples, nowMs, CanaryLadder.WINDOW_DAYS / 2)
        return listOf(fullWindow, recentWindow).count { it.passedForFull }
    }
}
