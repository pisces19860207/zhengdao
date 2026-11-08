// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百二十五批 · 灰度期的**真实使用**受理层（纯判据，零 IO）。
//
// 510 把灰度计数（`WishGrayRelease.recordUse`）与转正判定造好了，但 `WishPipeline.recordUse`
// 全仓零生产调用方 ⇒ 灰度永远停在 1 次，转正这条腿是空的。本批补的就是「谁把使用喂进来」：
//   · 三个真实来源（派工单点名）：**工具命中**（技能被工具调用真跑成）、**卡片采纳**（用户采纳了
//     这张卡的候选）、**内容进台账**（许愿产出的内容被记进业务台账）；
//   · **幂等**：同源同归属键只算一次（`TOOL_HIT|技能名`）——重放/重试/双击都不许把 uses 顶上去；
//   · **手动不计**：手动补记（`manual=true`）只留痕，**不增 uses**——"我自己点一下"不是真实使用，
//     这条是灰度读数可信的前提；
//   · **每次一行**：每一个受理事件落一行账（含被挡的），账上看得见"收了几次、算了几次、为什么没算"。
//
// 判据与 IO 分离（照 205/283 的房型）：本件只有形状与纯函数，落盘在 [WishUsageLedger]，
// 装配与调流水线在 `agent/WishUsageWiring`（与 516 同一条界线：碰盘的活儿不放在被扫的许愿包）。
package com.example.zhengdao.baopu.wish

/** 真实使用的三个来源（派工单点名的三种；闭集，认不出就不算）。 */
enum class WishUseSource(val cn: String) {
    /** 工具命中：许愿产出的技能被工具调用真跑成一次。 */
    TOOL_HIT("工具命中"),

    /** 卡片采纳：用户采纳了这张卡（不是关掉/忽略）。 */
    CARD_ADOPTED("卡片采纳"),

    /** 内容进台账：许愿产出的内容被记进业务台账一行。 */
    CONTENT_LEDGER("内容进台账"),
}

/**
 * 一次使用事件（受理层的唯一输入）。
 *
 * @param target 归属键：技能名／候选卡 id／台账文件名；默认也拿它当幂等键
 * @param wishId 显式归属（调用方知道是哪条许愿时给）；null ⇒ 由流水线按 [target] 在灰度里认领
 * @param manual 手动补记 ⇒ **不计真实**（只落痕）
 * @param detail 一句人话（进账用；空也照落，账上另有来源与归属键）
 * @param dedupeKey 幂等键的**覆盖**：内容进台账这条腿要按"同一篇内容"去重，
 *   而不是按文件名——同一篇稿连点两次「记台账」会落两个文件名（序号+1），
 *   拿文件名当键就变成两次使用、直接把转正顶上去。null ⇒ 用 [target]。
 */
data class WishUseEvent(
    val source: WishUseSource,
    val target: String,
    val wishId: String? = null,
    val manual: Boolean = false,
    val detail: String = "",
    val dedupeKey: String? = null,
) {
    /** 幂等键（同源同归属键＝同一件事；内容进台账按内容去重）。 */
    val useKey: String get() = WishUsageRules.keyOf(source, dedupeKey ?: target)
}

/** 受理结论（五种，闭集；账上照它写一行）。 */
enum class WishUseVerdict {
    /** 收了，真的算了一次使用。 */
    COUNTED,

    /** 同一件事已经算过（幂等挡住），本次不增。 */
    DUPLICATE,

    /** 手动补记：只留痕，不增。 */
    MANUAL,

    /** 找不到归属的灰度许愿（不在灰度期／归属键对不上），不增。 */
    UNOWNED,

    /** 流水线没装配（没有 Context 的那条路），只留痕，不增。 */
    UNWIRED,
}

/** 一次受理的结果（调用方与测试只看这个）。 */
data class WishUsageOutcome(
    val verdict: WishUseVerdict,
    val wishId: String?,
    val uses: Int,
    val note: String,
) {
    val counted: Boolean get() = verdict == WishUseVerdict.COUNTED
}

/** 受理的纯判据（零 IO；测试直接喂它，不必造盘）。 */
internal object WishUsageRules {

    /** 幂等键的拼法（一处定义，别处不许再抄：`TOOL_HIT|技能名`）。 */
    fun keyOf(source: WishUseSource, target: String): String = "${source.name}|${target.trim()}"

    /** 归属键空 ⇒ 不认（空键会把所有无主事件去重成一条，那是最坏的假绿）。 */
    fun accepts(event: WishUseEvent): Boolean = event.target.isNotBlank()

    /** 手动补记不计真实——这条是本层的**主判据**。 */
    fun countsAsReal(event: WishUseEvent): Boolean = !event.manual && accepts(event)

    /** 幂等：同一个键只算第一次（手动事件不参与去重——它本来就不计数）。 */
    fun alreadyCounted(event: WishUseEvent, seen: Set<String>): Boolean =
        !event.manual && event.useKey in seen
}
