// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十六批 B · 预读回执闸：**形状层**（数据，零 IO、零时钟依赖、零全局状态）。
//
// ## 这批要管的两张纸
// ① 派单侧的**任务单**（[ReadFirstOrder]）：这件事开工前该读什么——文件:行／判据／坑账条目，
//   外加一格 `requireReceipt`（派单的人说了算：只要提示，还是要回执才许动手）。
// ② 承接侧的**回执**（[PreReadReceipt]）：三行——读了哪些／我的理解／打算改哪些文件。
//   这三行是"我读懂了"的**唯一凭据**，缺一行就当没交（判据②），不许"缺一行也算交了"放水。
//
// ## 为什么两张纸分开、又必须同键
// 分开的理由：派单的人写清单时，承接方还没读；合在一起写就成了"自己给自己出题"。
// 同键的理由（派工单 ④／用户 2026-11-06 裁定）：账要能回答**"它当时怎么理解的"**——
// 只把回执单独存一本，事后翻到回执却不知道当时挂了哪张单子，等于没留痕。
// 所以两张纸都带同一个 `taskKey`，同一本账、同一次回放（[PreReadLedger]）。
//
// ## taskKey 是什么
// 任务的稳定身份。生产侧只有一条真路：450 批回填的 `taskId`（＝已批准计划契约的规范化摘要），
// 没回填时回落到本轮 `runId`。两者都拿不到 ⇒ 本批**不参与**（宁空勿猜，不去编一个键）。
package com.example.zhengdao.baopu.preread

/**
 * 必读清单里一条的性质（**闭集**，派工单 ① 点名的三种）。
 *
 * 为什么是闭集：清单要能机器比对（收工时"这条读了没"）。放开成自由文本就没法比，
 * 也只会在账里攒出一堆认不出来的行。
 */
internal enum class ReadFirstKind(val wire: String, val cn: String) {
    /** 「文件:行」——真去打开那个位置（唯一能观测到"读没读"的一种）。 */
    FILE_LINE("file_line", "文件:行"),

    /** 判据条目——法典/派工单上写死的那一条。 */
    CRITERION("criterion", "判据"),

    /** 坑账条目——ERRATA 的 E-xxx 或同类踩坑记录。 */
    PITFALL("pitfall", "坑账条目"),
    ;

    companion object {
        fun ofWire(wire: String): ReadFirstKind? = entries.firstOrNull { it.wire == wire }
    }
}

/** 必读清单上的一条（[target] 是"读哪儿"，[why] 是"为什么非读不可"，可空）。 */
internal data class ReadFirstItem(
    val target: String,
    val kind: ReadFirstKind,
    val why: String = "",
) {
    /** 账上与卡上的一行：`种类｜目标｜为什么`。 */
    fun render(): String = buildString {
        append(kind.cn).append("｜").append(target)
        if (why.isNotBlank()) append("｜").append(why)
    }
}

/**
 * 派单侧的任务单（可附必读清单）。
 *
 * [requireReceipt] 是**显式**那一格：true＝要回执才许写；false＝只提示不拦（三态里的②）。
 * 长程任务那一路的自动要求不在这里填——那由 [PreReadBoard.armByLongTask] 单独记，
 * 两条来源在 [PreReadRules.mode] 里合成，谁也不覆盖谁。
 */
internal data class ReadFirstOrder(
    val taskKey: String,
    val items: List<ReadFirstItem> = emptyList(),
    val requireReceipt: Boolean = false,
    /** 任务单上那一句话（回看时先看见"这是件什么事"，不是一串路径）。 */
    val title: String = "",
    val atMs: Long = 0L,
) {
    val hasItems: Boolean get() = items.isNotEmpty()
}

/**
 * 承接侧的回执（三行）。
 *
 * 三行的口径按派工单 ② 逐字对齐：[readLines]＝读了哪些、[understanding]＝我的理解、
 * [plannedFiles]＝打算改什么（**文件**，收工时拿它比实际改动；这一格不是自由感想，
 * 自由感想在 [understanding] 里）。
 */
internal data class PreReadReceipt(
    val taskKey: String,
    val readLines: List<String> = emptyList(),
    val understanding: String = "",
    val plannedFiles: List<String> = emptyList(),
    val atMs: Long = 0L,
    val receiptId: String = "",
)

/** 回执哪里不齐（**闭集**；判据②「缺一项 ⇒ 视为未交」的机器形态）。 */
internal enum class ReceiptGap(val wire: String, val cn: String) {
    BLANK_TASK("blank_task", "回执没写是哪个任务"),
    BLANK_READS("blank_reads", "第一行缺：读了哪些"),
    BLANK_UNDERSTANDING("blank_understanding", "第二行缺：我的理解"),
    BLANK_FILES("blank_files", "第三行缺：打算改什么"),
    TOO_MANY_READS("too_many_reads", "「读了哪些」超出条数上限"),
    TOO_MANY_FILES("too_many_files", "「打算改什么」超出条数上限"),
    TOO_LONG("too_long", "单行超长（回执是摘要，不是正文）"),
    ;
}

/** 任务单哪里不合规（同样闭集；不合规的单子**不落账**，别让坏数据进可回放的账）。 */
internal enum class OrderIssue(val wire: String, val cn: String) {
    BLANK_TASK("blank_task", "任务单没写是哪个任务"),
    UNKNOWN_TARGET("unknown_target", "条目目标为空"),
    DUPLICATE_TARGET("duplicate_target", "同一条目标重复"),
    TOO_MANY_ITEMS("too_many_items", "必读清单条目超过上限"),
    TOO_LONG("too_long", "单行超长"),
    ;
}

/**
 * 尺寸上限（写死在这里，不散在各处）。
 *
 * 为什么要上限：这两张纸要进**同一本可回放的账**，也要出现在卡上一屏之内。
 * 一份"必读清单"列 40 条、回执每行写一段论文 ⇒ 账没法回放、卡没法看、
 * 收工对照也没法逐条对。上限不是防恶意，是防"写了等于没写"。
 */
internal object PreReadLimits {

    /** 一张任务单最多几条必读（超过就该合并条目，不是把闸改成不限）。 */
    const val MAX_ITEMS: Int = 12

    /** 回执第一行「读了哪些」最多几条。 */
    const val MAX_READ_LINES: Int = 12

    /** 回执第三行「打算改什么」最多几个文件（越界对照按这份逐条比）。 */
    const val MAX_PLANNED_FILES: Int = 20

    /** 任一行（条目目标／理解／文件名）的字符上限——摘要，不是正文。 */
    const val MAX_LINE_CHARS: Int = 200
}
