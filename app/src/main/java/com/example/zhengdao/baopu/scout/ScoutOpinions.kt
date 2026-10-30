// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · 斥候交给**四器**的那条意见（派工单 §3：「斥候产 `Opinion(source=SCOUT)`」）。
//
// 边界（与 `Opinion.kt` 的四器契约**同一把尺**，不另立一套）：
// - gist ≤8 字符、单行；证据必须是**账本引用**，不是"据说"；
// - `lawRef = NONE`：斥候是**事实**不是判决，不替法典猜法条（Opinion 的 suggestionOnly 由此得出）；
// - **产一条意见 ≠ 允许弹卡**：能不能主动提归 [ScoutRules.proactiveAskAllowed]，
//   本文件只负责"把事实说清楚"，不掌权（治理细则「只出事实，不掌权」）。
//
// 为什么 granted 不出意见：四器需要的是"哪里会出问题"；把能干的事也报一遍只会把
// 分类器的注意力稀释成噪声（也违背"只答现在能做到的事"的回灌口径）。
package com.example.zhengdao.baopu.scout

import com.example.zhengdao.baopu.inspect.ActionCode
import com.example.zhengdao.baopu.inspect.LawRef
import com.example.zhengdao.baopu.inspect.Opinion
import com.example.zhengdao.baopu.inspect.OpinionAdapter
import com.example.zhengdao.baopu.inspect.Severity

internal object ScoutOpinions {

    /** 一项能力的一条意见；[ScoutState.GRANTED] 返回 null（没问题的项不报）。 */
    fun of(record: ScoutRecord): Opinion? = when (record.state) {
        ScoutState.GRANTED -> null

        ScoutState.REVOKED -> OpinionAdapter.SCOUT.adapt(
            severity = Severity.MINOR,
            gist = "权限被收回",
            action = ActionCode.REVIEW,
            lawRef = LawRef.NONE,
            evidence = evidenceOf(record),
        )

        ScoutState.DENIED -> OpinionAdapter.SCOUT.adapt(
            severity = Severity.MAJOR,
            gist = "缺授权",
            action = ActionCode.REVIEW,
            lawRef = LawRef.NONE,
            evidence = evidenceOf(record),
        )

        ScoutState.UNAVAILABLE -> OpinionAdapter.SCOUT.adapt(
            severity = Severity.MAJOR,
            gist = "能力不可用",
            action = ActionCode.REVIEW,
            lawRef = LawRef.NONE,
            evidence = evidenceOf(record),
        )
    }

    /** 按 [Capability] 枚举序产一遍（顺序稳定，四器那边才好对账）。 */
    fun forVessels(records: Collection<ScoutRecord>): List<Opinion> {
        val byCap = records.associateBy { it.capability }
        return Capability.entries.mapNotNull { byCap[it] }.mapNotNull { of(it) }
    }

    /**
     * 证据：账本引用（可回查是哪一行）＋ 机型/系统版本（287 追加：失败结论要带机型与系统版本）。
     * 两项都不是用户内容，也没有一项需要读用户数据才能得到。
     */
    private fun evidenceOf(record: ScoutRecord): List<String> {
        val refs = mutableListOf(record.ref())
        if (record.device.isNotBlank()) refs += "device:${record.device}"
        return refs
    }
}
