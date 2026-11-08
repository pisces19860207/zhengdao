// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百三十七批（冲突入御案 · 裁决落账）：冲突卡上的 批/驳/搁置 与普通御案卡走**同一条**
// 御批账（[RoyalDecisionLedger]）；另外按派工单补一行 208（[DecisionLedger]），
// **复用既有 kind**（[DecisionKind.ADVISOR_OPINION]）——不新造 kind、不给 208 加字段。
//
// 为什么另写一个口而不是塞进 `RoyalDecisionLedger`：御批账记「对哪条意见怎么裁」，
// 208 记「这件事发生过」；两本账各记各的，混在一本会让抽检分母算错（278 分家的理由）。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.DecisionActor
import com.example.zhengdao.baopu.DecisionDraft
import com.example.zhengdao.baopu.DecisionKind
import com.example.zhengdao.baopu.DecisionLedger
import com.example.zhengdao.baopu.DecisionRecord
import com.example.zhengdao.baopu.inspect.OpinionConflict

internal object ConflictRoyalWiring {

    /**
     * 裁一次冲突：御批账一行（压制重复上卡）＋ 208 一行（决策中心可查）。
     *
     * @return 208 的那一行；没装配落点/写失败 ⇒ null（御批账那行照落——压制不能丢）
     */
    fun record(
        conflict: OpinionConflict,
        verdict: RoyalVerdict,
        now: Long = System.currentTimeMillis(),
    ): DecisionRecord? {
        RoyalDecisionLedger.record(conflict.id, verdict, now)
        val a = conflict.a
        val b = conflict.b
        return DecisionLedger.record(
            DecisionDraft(
                kind = DecisionKind.ADVISOR_OPINION,
                actor = DecisionActor.USER,
                reason = "御案冲突裁决（${verdict.cn}）：${a.gist}／${b.gist}",
                evidence = "conflict:${conflict.id}",
                before = "${a.source.name}:${a.gist} ／ ${b.source.name}:${b.gist}",
                after = verdict.cn,
                atMs = now,
                // 意见没有反方向：裁完就是把这一页翻过去，不在决策中心开"撤销裁决"的口子
                reversible = false,
            ),
        )
    }
}
