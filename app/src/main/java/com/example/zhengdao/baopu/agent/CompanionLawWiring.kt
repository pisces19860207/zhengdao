// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十一批（2026-11-12）· 相处模式制度面的**接线**（法典 L34 的两件落点）：
// 左相在 [com.example.zhengdao.baopu.MediatorWiring] 判完档之后，把这一轮的既成事实
// 经观察口递到这里（装配层 `BaopuRuntime` 注册），本件只做两件**只读**的事：
//
//   ① 学习档收口的**达成判定**（右相，verify 包的 [CompanionAchievementGate]，纯判定）：
//      上一轮判了学习档 ⇒ 这一轮的回复就是收口证据，判成 PASS/FAIL/UNVERIFIABLE，
//      落一行运行日志。UNVERIFIABLE 也落行——"没法判断（要问或留观）"是三态之一，沉默等于漏判；
//   ② **改判落账**（[CompanionRejudgeLedger]）：用户点名纠正（correction 非空）⇒ 落一行
//      原判/新判/依据，进自治理中心（governance_state 的 companion 段）。
//
// 住址为什么在 agent/ 而不是 verify/：verify 包是**纯判定区**（302 源级门禁禁时钟禁写盘），
// 而落账要取钟、要写盘——与 AskGovLedger 同住 agent/，判定件留在 verify/（判定零时钟零 IO）。
// 纪律：本件不改判档结果（观察者拿到的是快照）、不写提示词、不弹卡——右相只有三态结论
// 与账，没有执行权（302 的权限口径同款）。时钟只在这里取一次（落账的 at；判定零时钟）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.CompanionDecision
import com.example.zhengdao.baopu.CompanionMode
import com.example.zhengdao.baopu.verify.CompanionAchievementGate
import com.example.zhengdao.core.RunLog

internal object CompanionLawWiring {

    /**
     * [com.example.zhengdao.baopu.MediatorWiring.turnObserver] 的生产实现。
     * 两个判定互相独立：学习档收口判达成（判的是**上一轮**），改判落账（看的是**这一轮**
     * 的点名纠正）——同一次回调里两件都做，互不依赖。
     */
    fun onTurn(
        turn: Int,
        sessionId: Long,
        prevMode: CompanionMode?,
        decided: CompanionDecision,
        correction: CompanionDecision?,
        userText: String,
        nowMs: Long = System.currentTimeMillis(),
    ) {
        // ① 右相达成：上一轮是学习档 ⇒ 这一轮的回复是收口证据（非学习档 ⇒ 判定回 null，不落行）
        CompanionAchievementGate.judge(prevMode, userText)?.let { a ->
            RunLog.log(CompanionAchievementGate.line(turn, sessionId, a))
        }
        // ② 改判落一行账（原判/新判/依据；只有用户点名才落，账本件内部再防一道）
        CompanionRejudgeLedger.append(
            prevMode = prevMode,
            decided = decided,
            turn = turn,
            sessionId = sessionId,
            atMs = nowMs,
        )
    }
}
