// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十二批 · **「卡里有 ⇒ 正文闭嘴」的判据**（派工单 §1.5 硬规则 ＋ §1.6 分工定死）。
//
// ## 这一层只回答一个问题：这段正文能不能发
// 派工单 §1.5 给的不是「风格建议」，是六条可断言的规则：正文最多一行、不复述卡里字段、
// 不写铺垫、不写推断词、不刷屏。其中「复述」与「推断词」是机器判得出的，所以它们归这里；
// 「正文该写什么内容」不归这里（那是 producer 的事，本批正文只有一行锚点）。
//
// ## 为什么「复述」按**值**比对，不按句子比对
// 卡里的字段是数据（单号、车牌、件数），正文复述时通常不是整句照抄，而是把值嵌进话里。
// 所以判据是「卡里任何一个有值的字段，原文是否出现在正文里」——这条恰好也是派工单 §1.5⑥
// 那句「可用断言比对字段值是否在正文出现」。反过来按句子比对，改一个语气词就绕过去了。
//
// ## 长度用一个朴素的字数上限
// 「一行」在 Compose 里取决于字号与屏宽，判据层拿不到；但「不许刷屏」必须能断言。
// 取 [MAX_BODY_CHARS] = 40（约等于 15sp 在 360dp 屏上的一行半），是个可以商量的数，
// 但它必须存在——没有上限的「一行」在代码里等于没有任何约束。
//
// ## 禁词是**原文扫**，注释里也不许出现
// 与 195 那套「连注释都不许出现外呼词」的处置一致：禁词一旦允许出现在注释里，
// 复制粘贴迟早把它带进用户的屏。所以本文件正文里的禁词以列表形式**只出现一次**，
// 供判据使用；调用方（界面／producer）不许再抄。
package com.example.zhengdao.baopu.cards

/** 一次正文体检的结论（空 ＝ 可以发）。 */
enum class BodyViolation {
    /** 超过一行（含换行符或超过字数上限）。 */
    TOO_LONG,

    /** 出现「我帮你查」这类铺垫（派工单 §1.5②）。 */
    FILLER,

    /** 出现「可能／大概／应该」这类推断词（派工单 §1.5③）。 */
    PREDICTION,

    /** 把卡里已经有值的字段又写了一遍（派工单 §1.5⑥）。 */
    REPEATS_CARD,

    /** 空正文（结果已经成卡，正文却一句锚点都没有 ⇒ 用户不知道卡在哪）。 */
    EMPTY,
}

object ResultCardBody {

    /** 正文一行锚点的字数上限（约 360dp 屏上一行半；超出就是刷屏）。 */
    const val MAX_BODY_CHARS = 40

    /** 铺垫词（§1.5②：不写「我帮你查了一下」「希望对你有所帮助」）。 */
    val FILLER_PHRASES: List<String> = listOf(
        "我帮你查",
        "帮你查了一下",
        "我来帮你",
        "已经帮你",
        "根据你提供",
        "希望对你",
        "仅供参考",
        "如需",
    )

    /** 推断词（§1.5③）：卡里没有的字段一律「未填」，不许拿这些词补）。 */
    val PREDICTION_WORDS: List<String> = listOf(
        "可能",
        "大概",
        "应该",
        "估计",
        "或许",
        "也许",
        "似乎",
        "大约",
    )

    /** 一行锚点的 strings 键（「查到 N 条，见卡」）。 */
    const val BODY_LINE_KEY = "baopu_card_body_line"

    /** 收起后小条那一行的 strings 键（标题 ＋ 条数）。 */
    const val STRIP_LINE_KEY = "baopu_card_strip_line"

    /**
     * 标准锚点那一行（producer 不自己想句子；这一句里没有任何字段值，天然过体检）。
     *
     * @param count 结果条数（>0 走「查到 N 条」，否则退回卡标题）
     */
    fun anchorLine(card: ResultCardSpec, lookup: (String) -> String): String =
        if (card.itemCount > 0) {
            lookup(BODY_LINE_KEY).format(card.itemCount)
        } else {
            lookup(STRIP_LINE_KEY).format(card.title, card.rows.count { it.filled })
        }

    /** 小条那一行（收起态常驻正文；同一份格式化，避免两处措辞漂移）。 */
    fun stripLine(card: ResultCardSpec, lookup: (String) -> String): String =
        lookup(STRIP_LINE_KEY).format(
            card.title,
            if (card.itemCount > 0) card.itemCount else card.rows.count { it.filled },
        )

    /** 正文体检（全部违规项；空表 ＝ 过）。 */
    fun violations(body: String, card: ResultCardSpec?): List<BodyViolation> {
        val out = ArrayList<BodyViolation>(4)
        val text = body.trim()
        if (text.isEmpty()) {
            out += BodyViolation.EMPTY
            return out
        }
        if (text.contains('\n') || text.length > MAX_BODY_CHARS) out += BodyViolation.TOO_LONG
        if (FILLER_PHRASES.any { text.contains(it) }) out += BodyViolation.FILLER
        if (PREDICTION_WORDS.any { text.contains(it) }) out += BodyViolation.PREDICTION
        if (card != null && repeatsCard(text, card)) out += BodyViolation.REPEATS_CARD
        return out
    }

    /** 判据本体：卡里有值的字段，原文有没有在正文里出现（长度 ≥2 才比，单字比较全是假红）。 */
    fun repeatsCard(body: String, card: ResultCardSpec): Boolean =
        card.rows.any { it.filled && it.value.length >= 2 && body.contains(it.value) }

    /**
     * 落日志用的那一行（**永远不含字段值**：§2 客户/司机姓名与手机号不进日志）。
     *
     * 只报「哪份模板、几张卡、几行」，够开发定位，不够泄漏任何一个人的信息。
     */
    fun logLine(state: ResultCardState): String =
        "结果卡 phase=${state.phase} cards=${state.cards.size} " +
            "templates=${state.cards.map { it.templateId }.distinct().joinToString(",")} " +
            "rows=${state.cards.sumOf { it.rows.size }}"
}
