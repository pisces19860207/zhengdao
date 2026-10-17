// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第十批追加包二（`ui/baopu/BaopuExtractSheet.kt`：选书 → 切分预览 →
// **一次 ask_user**：逐章摘要／只要索引／精读某几章）＋ §1 C 段（提问卡四形状）。
//
// 为什么把卡做成纯函数：派工单 §1 C 段原话「落成纯函数（AskUserCard 的数据类＋
// 校验函数），UI 只是渲染它 ⇒ 可 JVM 直测」。抽取的三个选项正好 3 个（2–4 内），
// 推荐位给「逐章摘要」（默认最有用的一档）。
package com.example.zhengdao.baopu.library

import com.example.zhengdao.baopu.AskUserCard
import com.example.zhengdao.baopu.AskUserOption
import com.example.zhengdao.baopu.AskUserCardRules

/** 抽取模式（三个选项 + 自由输入的解释结果）。 */
internal enum class ExtractMode { DIGEST, INDEX, DEEP }

/** 一次解释的结果（[chapters] 仅 [ExtractMode.DEEP] 用；空 = 没选到章）。 */
internal data class ExtractChoice(val mode: ExtractMode, val chapters: Set<Int> = emptySet())

/**
 * 卡片文案（**由 UI 层从 strings.xml 注入**——纯函数不碰 Context，
 * 但文案必须双语；派工单 §2.4 只许加 baopu_* 键）。
 */
internal data class ExtractLabels(
    val digest: String,
    val index: String,
    val deep: String,
    val freePrompt: String,
)

/**
 * 章节抽取的提问卡与自由输入解释（**纯函数**，JVM 直测）。
 */
internal object ExtractAsk {

    const val ID_DIGEST = "digest"
    const val ID_INDEX = "index"
    const val ID_DEEP = "deep"

    /** 最多列几个章标题（派工单：前 3 章）。 */
    const val PREVIEW_TITLES = 3

    /**
     * 造卡（三选项，推荐第一＝逐章摘要）。
     *
     * @param chapterCount 总章数（问题里要说清「这本书有几章」）
     * @param firstTitles  前几章标题（拼进问题行，让用户确认切得对）
     */
    fun card(chapterCount: Int, firstTitles: List<String>, labels: ExtractLabels): AskUserCard {
        val preview = firstTitles.take(PREVIEW_TITLES).joinToString("；") { it.ifBlank { "…" } }
        val q = if (preview.isBlank()) {
            "这本共 $chapterCount 章，怎么处理？"
        } else {
            "这本共 $chapterCount 章（$preview…），怎么处理？"
        }
        return AskUserCard(
            question = q,
            options = listOf(
                AskUserOption(id = ID_DIGEST, label = labels.digest, recommended = true),
                AskUserOption(id = ID_INDEX, label = labels.index),
                AskUserOption(id = ID_DEEP, label = labels.deep),
            ),
            freeTextPrompt = labels.freePrompt,
        )
    }

    /**
     * 自由输入 ⇒ 模式（**纯函数、确定性**；对不上 ⇒ null，UI 明说不猜）。
     *
     * 优先级（窄的先判）：
     * 1. 纯章号串（`3,5,8`／`3 5 8`／中文逗号）⇒ DEEP + 那些章；
     * 2. 含「索引」⇒ INDEX；
     * 3. 含「摘要」⇒ DIGEST；
     * 4. 含「精读」/「细读」/「读」⇒ DEEP（章号另行解析，没解析到就空集）。
     */
    fun parse(text: String): ExtractChoice? {
        val t = text.trim()
        if (t.isEmpty()) return null
        val nums = Regex("""\d+""").findAll(t).mapNotNull { it.value.toIntOrNull() }.toList()
        val onlyNumbers = t.all { it.isDigit() || it in " ,，、\t\n" }
        if (onlyNumbers && nums.isNotEmpty()) return ExtractChoice(ExtractMode.DEEP, nums.toSet())
        return when {
            t.contains("索引") -> ExtractChoice(ExtractMode.INDEX)
            t.contains("摘要") -> ExtractChoice(ExtractMode.DIGEST)
            t.contains("精读") || t.contains("细读") || t.contains("读") ->
                ExtractChoice(ExtractMode.DEEP, nums.toSet())
            else -> null
        }
    }

    /** 卡必须过 [AskUserCardRules]（三选项 + 推荐第一 + 空白行永远在）。 */
    fun isValid(card: AskUserCard): Boolean = AskUserCardRules.isValid(card)
}
