// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百一十六批 · 许愿接线 ①入口：一句话「我想要一个能把 X 变成 Y 的东西」的判定层。
// 纯函数，无 IO，不落账——判什么在这里，怎么进流水线在 WishFlow（装配层）。
// 房型与 TopicSuggest.isTopicRequest（190 批）同款：刻意窄的触发词，普通聊天不许误触发；
// 确认回话「许愿确认 …」与题材流的「下一批」同一道——都在 send() 的壳层拦，不进引擎。
package com.example.zhengdao.baopu.wish

/** 许愿入口判定（纯函数；判定与执行分两半，执行在 WishFlow）。 */
internal object WishTrigger {

    /**
     * 许愿触发词（判据①的那句原话）。
     *
     * 刻意窄，两式都须同时有「改造意图（变成/转成/改成/换成）」与产物名词：
     *   ① 愿望头＋「…变成 Y 的<产物>」（派工单原话「我想要一个能把 X 变成 Y 的东西」）；
     *   ② 「把 X 变成<产物>」——尾巴直接是产物名词、没有愿望头也算（「把 Markdown 变成微头条」），
     *      尾巴只认闭集产物词，普通聊天里的「变成英语／变成 zip」不命中。
     * 显式口令「许愿 …」永远命中（人点名要进流水线，不用猜）。
     */
    private val WISH_REQUEST = Regex(
        "((我想要|我想要个|我想要一|给我做|帮我做).{0,20}(变成|转成|改成|换成).{0,36}" +
            "的(东西|工具|技能|流程|助手|小工具|卡|卡片|世界书|文章|文案|帖子|模板|脚本|书|章|小说))" +
            "|((把|将).{1,24}(变成|转成|改成|换成)" +
            "(微头条|文章|文案|卡片|卡|世界书|小说|书|章|技能|流程|模板|脚本))",
    )

    /** 显式口令：句首「许愿」两个字就进（后面的原话整体当许愿文本）。 */
    private const val EXPLICIT_MARK = "许愿"

    /** 确认回话：「许愿确认 [wishId] S1 S2」——wishId 可省（省了接最近一条未决许愿）。 */
    private val CONFIRM_REQUEST = Regex(
        "^许愿确认\\s*(wish-[A-Za-z0-9\\-]+)?\\s*((?:[SCTN]\\d+[\\s，,、]*)+)$",
    )

    fun isWishRequest(text: String): Boolean {
        val flat = text.replace(Regex("\\s+"), "")
        if (flat.startsWith(EXPLICIT_MARK) && flat.length > EXPLICIT_MARK.length) return true
        return WISH_REQUEST.containsMatchIn(flat)
    }

    /** 产物类型猜（contains 顺序＝猜序；都不命中 ⇒ SKILL——「东西/工具」的原型格）。 */
    fun productOf(text: String): WishProduct = when {
        text.contains("卡") || text.contains("世界书") -> WishProduct.CARD
        text.contains("小说") || text.contains("书") || text.contains("章") -> WishProduct.NOVEL
        text.contains("微头条") || text.contains("文章") || text.contains("文案") ||
            text.contains("帖子") -> WishProduct.CONTENT
        else -> WishProduct.SKILL
    }

    /** 一条确认回话（wishId 可空＝接最近一条；ids 已按 [WishSpecBuilder.confirm] 的口径挑好）。 */
    data class Confirm(val wishId: String?, val ids: List<String>)

    /** 解析确认回话；不像确认回话 ⇒ null。 */
    fun confirmRequest(text: String): Confirm? {
        val m = CONFIRM_REQUEST.find(text.trim()) ?: return null
        val ids = m.groupValues[2]
            .replace("，", " ").replace(",", " ").replace("、", " ")
            .trim().split(Regex("\\s+"))
            .filter { it.matches(Regex("[SCTN]\\d+")) }
        if (ids.isEmpty()) return null
        return Confirm(m.groupValues[1].takeIf { it.isNotBlank() }, ids.distinct())
    }
}
