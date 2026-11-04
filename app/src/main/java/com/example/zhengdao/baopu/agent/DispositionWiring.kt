// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百二十七批 · 三档处置的**接线口**（判定本体见 [DispositionGate]，372 纯判定批）。
//
// 来历：372 交付时钉下的话——「授权闸拒绝那一刻，把被拒事实喂进 DispositionGate.decide，
// 三档处置作为事实源接上」（372 报告 §六）；418 接线缝复核把缝点核实为
// `GuardPipeline.kt` 的 `verdict.rejected` 分支、`noteDeviation` 之后。本文件就是把那句话落地。
//
// 口径（**2026-11-04 用户批示**，推翻本批初稿的「decidedBy=user」解释决定）：
//   * `decidedBy` 取 [DECIDER_CENTER_AUTO]（**不是** [DispositionGate.DECIDER_USER]）——
//     自动代行（引擎依用户立法的矩阵硬拒）**不许记成"人裁"**：账上写 user，事后会追责到
//     不存在的人。判定①的源级钉会如实拒掉这份自动申请（code=not_center），门拒照记——
//     三档处置的"人裁"入口只留给真正的用户决定（御批卡那条线），本接线口只负责"申请被
//     源级钉挡下"这一行的账实相符。
//   * 档位仍报 **REPLY**（回话：本次只回一句，不动状态）——申请形状与管线硬拒的实际行为
//     逐字一致；FREEZE／SUPPRESS 是会话级制度动作，只能人裁，本批不接。
//   * **只加调用不改判定**：管线的拒绝行为、返回值、文案一个字节不动；`DispositionGate.kt`
//     0 行改动（CENTER_AUTO 常量住本文件——判定层不认识"自动"这个词）；本文件的产物只有
//     一行日志（门拒什么记什么，不加工）。
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
     * 自动代行的裁决人标记（**不是** [DispositionGate.DECIDER_USER]）：判定①会把它拒在
     * 中心路径之外——这是**故意的**（2026-11-04 批示：账上不许出现"自动记成人裁"）。
     * 值住本文件不住判定层：372 的法典只认识"user／不是 user"两态，不认识"自动"。
     */
    const val DECIDER_CENTER_AUTO = "center_auto"

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
        // decidedBy 报 center_auto ⇒ 判定①照拒（not_center）——账上不产生"人裁"的处置行，
        // 硬拒事实仍由紧邻的 noteDeviation（偏差账）记，两头账实各归各位。
        val outcome = DispositionGate.decide(
            DispositionRequest(
                tier = DispositionTier.REPLY,
                target = toolName,
                reason = "风险矩阵硬拒（$reasonCode）",
                evidence = "矩阵结论码 $reasonCode；拒因：$detail",
                restoreCondition = "该工具下一次调用重新过矩阵判定：矩阵放行即不再回这句话" +
                    "（本档不动任何状态，无须额外恢复动作）",
                decidedBy = DECIDER_CENTER_AUTO,
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
                log(
                    "抱朴处置：门拒（${outcome.code}）——${outcome.reason}" +
                        "（申请对象 ${toolName}，自动代行不落处置账）",
                )
        }
    }
}
