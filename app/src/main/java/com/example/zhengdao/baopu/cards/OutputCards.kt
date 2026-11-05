// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百六十五批 · **产出类出卡的唯一入口**。
//
// 查询类结果（物流单、知识点卡）继续走 [ResultCards.push]：它们不是「产出」，不要求依据。
// 产出类（写作／选题／做卡／报告）必须走这里：闸不过时返回的 state 与进来时逐字相同，
// 一张卡都没加。这样「没依据却出了卡」不是靠调用方自觉，而是写不出来。
package com.example.zhengdao.baopu.cards

object OutputCards {

    /** 一次准入的结果：新状态 ＋ 闸的结论（拒时调用方拿 detail 出提示）。 */
    data class Admission(val state: ResultCardState, val verdict: CitationVerdict)

    fun push(
        state: ResultCardState,
        card: ResultCardSpec,
        citations: List<Citation>,
        exists: (Citation) -> Boolean,
        messages: CitationMessages,
    ): Admission {
        val verdict = OutputCitationGate.check(citations, exists, messages)
        return when (verdict) {
            is CitationVerdict.Reject -> Admission(state, verdict)
            is CitationVerdict.Pass -> Admission(
                ResultCards.push(
                    state,
                    card.copy(citations = verdict.citations, citationLine = verdict.line),
                ),
                verdict,
            )
        }
    }
}
