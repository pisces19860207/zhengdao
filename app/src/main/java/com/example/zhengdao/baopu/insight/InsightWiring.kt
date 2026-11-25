// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十四批 · 534 装配层（串联四件；判据不落在这里）。
//
// 一条链：captureTurn 抓带锚点的念头 → InsightStore 落盘 → associate 用既有索引
// 找「曾经捕获过」的相关念头 → wakeIfEnabled 调 625 runner 浮一行 → 点开才 offer。
//
// 三纪律：
//   1. **不做自动执行**：本包零调度构造、零 Runner 类（复用 625 的）；一次链由
//      外部事件（打开建议页签的机会式调用）驱动，不建循环/定时器；
//   2. **开关关 ⇒ 逐字节一致**：关时 wakeIfEnabled 返回空轮、不 offer，引擎侧
//      drain 空表 ⇒ 提示词不变（判据⑥）；
//   3. **不改 677/625/540**：只读它们的公开缝（RecallIndexPort / SubconsciousRunner），
//      写自己新包的账，不动那三包一行。
package com.example.zhengdao.baopu.insight

import com.example.zhengdao.baopu.agent.QuietGrant
import com.example.zhengdao.baopu.agent.SessionShape
import com.example.zhengdao.baopu.recall.RecallIndexPort

/** 一次完整的「记 + 唤」链的产出（给上层显示/落日志用）。 */
internal data class InsightCycle(
    val captured: CaptureOutcome,
    val wake: InsightWakeRound,
    val associationNote: String,
)

/**
 * 跑一次「捕获 + 关联唤醒」链（机会式，由外部事件驱动；零调度）。
 *
 * @param enabled 开关（关 ⇒ 只抓不唤：捕获保留，wake 空轮、信箱不动）
 * @param store 念头库（[captureTurn] 抓到的写这里，associate 从这里读回）
 * @param index 既有索引（生产＝540 的 sessionIndexPort；只读 search/digest/status）
 */
internal fun runInsightCycle(
    messages: List<TurnMessage>,
    clue: String,
    nowMs: Long,
    workspace: String,
    enabled: Boolean,
    store: InsightStore,
    index: RecallIndexPort,
    grant: QuietGrant?,
    shape: SessionShape,
    lastRunDayKey: Long?,
    suggestedAtByKey: Map<String, Long>,
): InsightCycle {
    val captured = captureTurn(messages, nowMs, workspace)
    captured.thoughts.forEach { store.append(it) }
    if (!enabled) {
        return InsightCycle(captured, InsightWakeRound(emptyList(), "开关关：只记不唤", false), "未关联")
    }
    val known = store.scan().thoughts
    val associations = InsightAssociate.associate(clue, known, index)
    val note = if (associations.isEmpty()) InsightAssociate.statusLine(index) + "：没有关联的旧念头"
    else "关联到 ${associations.size} 条旧念头"
    return InsightCycle(
        captured = captured,
        wake = runInsightWake(associations, nowMs, grant, shape, lastRunDayKey, suggestedAtByKey),
        associationNote = note,
    )
}

/**
 * 点开那一行的唯一放行口：把原文放进信箱，下一轮由引擎 drain 一次。
 * 只有本函数调 [InsightMailbox.offer]（源码门禁钉死），不点 ⇒ 信箱恒空。
 */
internal fun onInsightOpened(signal: com.example.zhengdao.baopu.subconscious.SubconsciousSignal) {
    InsightMailbox.offer(InsightWakeLine.of(signal))
}
