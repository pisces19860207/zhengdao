// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十七批 · **出卡落账**（产出／查询同一行口径）。
//
// ## 为什么要这一行
// 「这张卡算产出还是查询？有没有依据？几条？」——这三问必须能从日志里对出来，
// 否则「依据闸到底有没有生效」只能靠读代码判断。所以每出一张卡就落一行，
// 两条出卡路径都落到同一个 [line]：
//   · 查询类（[ResultCards.push]：物流单、知识点）不要求依据 ⇒ 落「有无依据=无 依据条数=0」；
//   · 产出类（[OutputCards.push]）把依据闸的结论原样落进来，被拒的那次落「不出卡 产出（理由）」。
//
// ## 不记什么
// 与 `ResultCardBody.logLine` 同一口径：**只记类目、条数、模板名与行数**。
// 依据正文、单号、姓名、正文一个字都不进日志（§2）。
package com.example.zhengdao.baopu.cards

/** 出卡落账（纯函数，JVM 直测；不读盘、不读时钟）。 */
object CardLedger {

    /** 卡的两类（闭集）：查询类不要求依据；产出类必须有依据——这就是两类的分界。 */
    enum class Kind(val label: String) {
        /** 写作／选题／做卡／报告这类真产出。 */
        PRODUCE("产出"),

        /** 物流单、知识点这类查询结果。 */
        QUERY("查询"),
    }

    /**
     * 一行落账。
     *
     * @param kind 卡的类目
     * @param verdict 产出类传依据闸的结论（Pass／Reject）；**查询类传 null**
     *   ——「查询卡不要求依据」在类型上就写不出「无依据所以没出卡」这套叫法
     * @param state 落账后的卡区状态（尾部复用 [ResultCardBody.logLine]，字段值不进来）
     */
    fun line(kind: Kind, verdict: CitationVerdict?, state: ResultCardState): String {
        val head = when {
            verdict == null -> "出卡 ${Kind.QUERY.label}"
            verdict is CitationVerdict.Pass -> "出卡 ${Kind.PRODUCE.label}"
            else -> "不出卡 ${Kind.PRODUCE.label}（${(verdict as CitationVerdict.Reject).reason}）"
        }
        val count = (verdict as? CitationVerdict.Pass)?.citations?.size ?: 0
        val has = if (count > 0) "有" else "无"
        return "$head 有无依据=$has 依据条数=$count | ${ResultCardBody.logLine(state)}"
    }
}
