// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百一十三批 · 「答案先行」的**落库后处理**（派工单 §1 次落点：防模型不听话）。
//
// ## 这一层只做一件事，而且只做一次
// 系统提示已经写了八条纪律（`BaopuPreset.personaText` 的「## 输出纪律（答案先行）」一节），
// 但提示是请求，不是保证。本层把**最容易犯的那一条**兜住：回答开头的铺垫句。
// 删完之后剩下的字一个字节都不动——不改事实、不改数字、不重排、不摘要。
// 派工单明确写了「别写成大清洗」，所以连"结尾的客套句"都不删（那是另一件事，没授权）。
//
// ## 两道闸，缺一不删
// **内容闸**：句子里只要出现数字、反引号、路径分隔符、引号/书名号、表格竖线、尖括号、URL ⇒
// 这句话里有事实，**永不删**（派工单负例⑥「后处理把数字/路径/引用删掉」判的就是这个）。
// **词表闸**：整句必须被 [TOKENS] 这批铺垫 token **连续覆盖到末尾**，多出一个字都不算。
// 「我帮你查一下」算铺垫；「我帮你查一下物流，已经签收了」不算——后半句是答案，删不起。
//
// ## 为什么"整句覆盖"而不是"以铺垫开头就删"
// 前缀式规则会把「好的建议是先补结尾」这种真答案削掉。整句覆盖意味着：**被删那句话不含任何信息**，
// 删了不会有人少看见一个事实。这条性质可测——`AnswerFirstRulesTest` 拿十条对拍样本逐条断言
// "事实集合只增不减"（数字、路径、代码段、引用全部原样活着）。
//
// ## 有意不收的词
// 「可以」「没问题」「当然」「行」「是」「对」不入词表：用户问「回收站能清吗」，
// 答「可以。」——那就是答案本身，删了就是削事实。
//
// ## 结构不动
// 代码块（三个反引号／波浪线）、列表行、表格行、标题、引用块都由内容闸＋结构闸挡在外面；
// 含 `<tool_result …>` 的整条消息直接原样返回（那是素材不是答话，
// RunEngine 的 feed/note 行也走同一个落库口，不能被这里改写）。
//
// 零 Android/IO/网络，输入一个字符串、输出一个字符串，JVM 直测。
package com.example.zhengdao.baopu

internal object AnswerFirstRules {

    /** 最多看开头几句——超过它就不是"一句开场白"，是整段灌水，那该由提示词管。 */
    const val MAX_PREAMBLE_UNITS = 5

    /** 开头铺垫的字数上限：删掉的字节超过它就停（防大清洗的硬顶）。 */
    const val MAX_PREAMBLE_CHARS = 100

    /**
     * 句界：中文句末标点 + 全角/半角冒号（铺垫句常用「……：」引出正文）
     * + 逗号/顿号（铺垫多半是「好的，」这种半句）+ 换行。
     *
     * 逗号切在「我帮你查一下」与「物流，已经签收了」中间也不怕：每一片都要各自过
     * 内容闸，带数字/路径/引用的那一片永远删不掉——被删的只有**不含事实的半句本身**。
     */
    private const val BOUNDARIES = "。！？!?…；;：:，,、\n"

    /** 尾巴上的分隔符（标点与空白），不算内容；这里含逗号，词表的一条 token 吃掉「，」尾巴。 */
    private const val SEP = "，,、;；:：!！?？~～。 \t\r"

    /** 内容闸：出现任何一个 ⇒ 句中有事实，不删。 */
    private val FACT_MARKS = charArrayOf(
        '`', '|', '<', '>', '/', '\\', '#',
        '「', '」', '『', '』', '“', '”', '‘', '’', '"', '《', '》',
    )

    /** 内容闸（子串级）：URL。 */
    private val FACT_SUBSTRINGS = arrayOf("http", "www.", ".com", ".cn")

    /** 结构闸：以这些字符开头的行是列表／标题／引用块／表格，不是铺垫句。 */
    private val STRUCT_STARTS = charArrayOf('-', '*', '•', '·', '>', '#', '|', '~', '+')

    private val ORDERED_LIST = Regex("""^\s*\d+\s*[.、)）]""")

    /**
     * 数据围栏标记（feed/note 行的整条回灌）：一个字都不许动。
     *
     * 值必须与 `agent/DataFence.FENCE_OPEN` 同一个串——门禁拿 assertEquals 钉住
     * （见 AnswerFirstRulesTest「围栏标记与 DataFence 同源」），谁改了哪一处都会红。
     */
    const val FENCE_MARK = "<tool_result"

    /**
     * 铺垫 token（词表闸的 vocabulary；每条都**锚在句首**，整句覆盖才算铺垫）。
     *
     * 主语的副词那一条允许空匹配，所以调用处只认**非空**匹配：整句必须一句一句被吃干净。
     */
    private val TOKENS: List<Regex> = listOf(
        // 应答：好的／嗯／收到／明白了
        Regex("""^(?:好的|好啦|好吧|好嘞|嗯+|哦+|噢+|哎|收到|明白了?|了解(?:了)?|清楚了)[$SEP]*"""),
        // 致歉：抱歉／对不起／不好意思
        Regex("""^(?:非常|真|太|实在)?(?:抱歉|对不起|不好意思)[，,、;；:：\s]*"""),
        // 让你久等了
        Regex("""^(?:(?:让|令)(?:你|您)久等了?|久等了|等了有点久)[，,、;；:：\s]*"""),
        // 稍等（后面没有事实下文时才够成铺垫）
        Regex("""^(?:稍等|请稍候|请稍等)(?:片刻|一下|一会儿|一会)?[，,、;；:：\s]*"""),
        // 主语＋副词：我这就／我先／马上
        Regex(
            """^(?:那么|那|这|接下来|下面|现在)?(?:我们|我|咱|咱们|本人)?(?:这边|这里)?""" +
                """(?:已经|这就|正要|马上|立刻|赶紧|先|来|将|会|要|得)?""",
        ),
        // 帮你／为您
        Regex("""^(?:帮|替|为)(?:你|您)(?:的)?(?:忙)?[，,、;；:：\s]*"""),
        // 动作：看看／查一下／梳理一下／说明一下
        Regex(
            """^(?:查看|看|查|核对|检查|确认|梳理|整理|回答|说明|解释|讲|说|找|搜|试|翻|过|处理|准备|办|弄|整)""" +
                """(?:了|过)?(?:一下|一遍|一番|一眼|一看|下)?[$SEP]*""",
        ),
        // 引用式开场：根据你提供的信息
        Regex(
            """^(?:根据|按照|依照|结合)(?:你|您|上面|上文|所)?(?:提供|给出|给|说|提到)?""" +
                """(?:的)?(?:信息|资料|内容|情况|要求|描述)?[$SEP]*""",
        ),
        // 复述问题：你的问题是／你问的是／关于这个问题
        Regex("""^(?:你|您)(?:的)?(?:问题|提问)(?:是|说的)?[$SEP]*"""),
        Regex("""^(?:你|您)(?:刚才|刚刚)?问(?:的是|到的是|的)?[$SEP]*"""),
        Regex("""^(?:关于|针对)(?:你|您|这个|该)?(?:的)?(?:问题|提问|这件事|这事|需求)[$SEP]*"""),
        // 以下是我的回答
        Regex("""^以下是(?:我|你|您|本)?(?:的)?(?:回答|回复|结果|内容|说明|答复|整理)?[$SEP]*"""),
        // 客套句挪到开头时：希望对你有帮助／仅供参考
        Regex("""^(?:希望|但愿)(?:对|给)(?:你|您)(?:的)?(?:有所)?(?:有)?(?:帮助|有用|理解|参考)?[$SEP]*"""),
        Regex(
            """^(?:仅供参考|如有(?:问题|疑问)|欢迎(?:随时)?(?:追问|补充)|""" +
                """如果还有(?:问题|需要))[，,、;；:：\s]*""",
        ),
        // 自我说明：我是 AI／作为助手／我的能力有限
        Regex(
            """^(?:我|咱)(?:是|只是|不过是)(?:一个|一名)?\s*""" +
                """(?:AI|ai|人工智能|语言模型|大模型|助手|机器人)[，,、;；:：\s]*""",
        ),
        Regex(
            """^(?:作为|身为)(?:一个|一名)?\s*(?:AI|ai|人工智能|语言模型|大模型|助手|机器人)""" +
                """(?:来说|而言)?[，,、;；:：\s]*""",
        ),
        Regex("""^(?:我的?|咱的?)?(?:能力|水平)(?:有限|有限得很|到这里)?[$SEP]*"""),
        // 纯分隔符（吃掉「，」「。」这类尾巴；整句只剩标点时由字母闸拦下）
        Regex("""^[$SEP]+"""),
    )

    /** 一句话是不是**纯铺垫**（内容闸 + 结构闸 + 词表闸三关全过才算）。 */
    fun isPreamble(sentence: String): Boolean {
        val s = sentence.trim()
        if (s.isEmpty()) return false
        if (!s.any { it.isLetter() }) return false // 纯标点不成句，宁可留着也别乱删
        if (s.any { it.isDigit() }) return false // 内容闸①：有数字＝有事实
        for (c in FACT_MARKS) if (s.indexOf(c) >= 0) return false // 内容闸②：代码/路径/引用/表格
        for (p in FACT_SUBSTRINGS) if (s.contains(p)) return false // 内容闸③：URL
        if (s[0] in STRUCT_STARTS) return false // 结构闸：列表/标题/引用块/表格开头
        if (ORDERED_LIST.containsMatchIn(s)) return false // 结构闸：有序列表
        var pos = 0
        var steps = 0
        val limit = MAX_PREAMBLE_UNITS * 2 + 4
        while (pos < s.length) {
            if (++steps > limit) return false // 一句铺垫用不上这么多 token——再多就是别的东西
            val rest = s.substring(pos)
            var longest = 0
            for (t in TOKENS) {
                val m = t.find(rest) ?: continue
                if (m.range.first != 0) continue
                if (m.value.length > longest) longest = m.value.length
            }
            if (longest == 0) return false // 多出一个字就不算铺垫
            pos += longest
        }
        return true
    }

    /** 这段回答是不是**以铺垫开头**（判据与对拍用；生产改写只走 [tidy]）。 */
    fun opensWithPreamble(text: String): Boolean {
        for (u in units(text)) {
            val content = u.first.trim()
            if (content.isEmpty()) continue
            return isPreamble(content)
        }
        return false
    }

    /**
     * 落库前的唯一改写：删掉开头的铺垫句，其余一个字节不动。
     *
     * 原样返回（等于没动手）的四种情况：
     * ①整条含 `<tool_result …>` 回灌；②开头不是铺垫；③铺垫之后全是空白（不许把回答删空）；
     * ④铺垫的长度超过 [MAX_PREAMBLE_CHARS]（那不是开场白，是整段灌水，不归这里管）。
     */
    fun tidy(raw: String): String {
        if (raw.isBlank()) return raw
        if (raw.contains(FENCE_MARK)) return raw
        var cut = 0
        var seen = 0
        for (u in units(raw)) {
            val content = u.first.trim()
            if (content.isEmpty()) continue // 空行跳过（末尾的 trimStart 会把它们抹平）
            if (seen >= MAX_PREAMBLE_UNITS) break
            if (!isPreamble(content)) break
            if (u.second > MAX_PREAMBLE_CHARS) break
            cut = u.second
            seen++
        }
        if (cut == 0) return raw
        val rest = raw.substring(cut).trimStart(' ', '\t', '\r', '\n')
        return if (rest.isEmpty()) return raw else rest
    }

    /** 切成「一句 + 它在原文里的结束下标」——边界符归本句，空白归下一句。 */
    private fun units(text: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>(8)
        var i = 0
        while (i < text.length) {
            var j = i
            while (j < text.length && !BOUNDARIES.contains(text[j])) j++
            val end = if (j < text.length) j + 1 else j
            out += text.substring(i, end) to end
            i = end
        }
        return out
    }
}
