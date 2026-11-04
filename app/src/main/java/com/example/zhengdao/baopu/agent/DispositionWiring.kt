// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 · 三档处置的**接线口**（判定本体见 [DispositionGate]，372 纯判定批）。
//
// 来历：372 交付时钉下的话——「授权闸拒绝那一刻，把被拒事实喂进 DispositionGate.decide，
// 三档处置作为事实源接上」（372 报告 §六）；418 接线缝复核把缝点核实为
// `GuardPipeline.kt` 的 `verdict.rejected` 分支、`noteDeviation` 之后。本文件就是把那句话落地。
//
// 口径（本批的解释决定，报告中已列给验收方裁定）：
//   * 档位取 **REPLY**（回话：本次只回一句，不动状态）——与管线硬拒的实际行为逐字一致
//     （`Result.Rejected` 回一段拒因文本，状态零变动）；FREEZE／SUPPRESS 是会话级制度动作，
//     只能人裁，本批不接（`priorFreezes` 恒空 ⇒ 二阶升级那条判定在这里永远够不着，这是
//     如实的：本批不建会话处置账，升级链归后续批）。
//   * `decidedBy` 取 [DispositionGate.DECIDER_USER]——中心路径（引擎）依用户立法的风险
//     矩阵行事；2026-11-02 裁定 b：授权闸拒绝＝防线正常工作。使臣团／执行体仍然进不来
//     （判定①的源级钉一个字没改）。
//   * **只加调用不改判定**：管线的拒绝行为、返回值、文案一个字节不动；本文件的产物只有
//     一行日志（门放行了记一行、门拒了也如实记一行——判什么记什么，不加工）。
//
// 为什么住 agent/ 不住 law/：法典接线名册的口径是「law/ 互引不算接线」（LawSourceWiringGateTest
// 扫生产时排除 law/ 自身），且管线代码面禁引 `baopu.law.`（346-B 的 GuardScopeEscapeFeedTest）——
// 315 的 EmergencyWiring 同款：**接线口住判定层外面**，判定本体一个字不动。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.law.DispositionGate
import com.example.zhengdao.baopu.law.DispositionOutcome
import com.example.zhengdao.baopu.law.DispositionRequest
import com.example.zhengdao.baopu.law.DispositionTier

internal object DispositionWiring {

    /**
     * 风险矩阵硬拒那一刻的事实喂入门（判定处只加这一行调用）。
     *
     * @param toolName 被拒的工具名（处置对象）
     * @param reasonCode 矩阵结论码（稳定键，如 `原件区` 类；逐字来自判定）
     * @param detail 矩阵给的人话拒因（多条以「；」相连——与 noteDeviation 同源同串）
     * @param log 日志口（生产走管线注入的 [com.example.zhengdao.baopu.agent.RunLog] 通道）
     */
    fun onMatrixReject(
        toolName: String,
        reasonCode: String,
        detail: String,
        log: (String) -> Unit,
    ) {
        // 判定全在门里：这里只把事实装进申请，不预判、不加工、不吞结论。
        val outcome = DispositionGate.decide(
            DispositionRequest(
                tier = DispositionTier.REPLY,
                target = toolName,
                reason = "风险矩阵硬拒（$reasonCode）",
                evidence = "矩阵结论码 $reasonCode；拒因：$detail",
                restoreCondition = "该工具下一次调用重新过矩阵判定：矩阵放行即不再回这句话" +
                    "（本档不动任何状态，无须额外恢复动作）",
                decidedBy = DispositionGate.DECIDER_USER,
                at = System.currentTimeMillis(),
            ),
        )
        when (outcome) {
            is DispositionOutcome.Allowed ->
                log(
                    "抱朴处置：REPLY 档记一行——${outcome.disposition.reason}" +
                        "（对象 ${outcome.disposition.target}；证据：${outcome.disposition.evidence}）",
                )

            is DispositionOutcome.Rejected ->
                log("抱朴处置：门拒（${outcome.code}）——${outcome.reason}")
        }
    }
}
