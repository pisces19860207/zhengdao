// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第七十八批（§B 80 切片）——从 BaopuScreen.kt 抽出**纯判据/纯换算**。
// 本文件不许出现 @Composable、不许拿 Context、不许碰控制器或 IO：
// 输入普通值，输出普通值/数据类，全部可在 JVM 里直测。
package com.example.zhengdao.ui.baopu

/**
 * 抽屉宽度（dp）：窄屏按屏宽 80%，宽屏封顶 300dp。
 *
 * 逐字搬自 BaopuScreen.kt 原式
 * `if (screenWidthDp * 0.8f > 300f) 300.dp else (screenWidthDp * 0.8f).dp`
 * 的**数值部分**；`.dp` 仍由屏幕那一层调用（本文件不引 Compose）。
 * 判据是 `>` 不是 `>=`：375dp 屏恰好 80% = 300dp，仍走 else 分支（两边同值，但保持原式）。
 */
internal fun drawerWidthDp(screenWidthDp: Int): Float =
    if (screenWidthDp * 0.8f > 300f) 300f else screenWidthDp * 0.8f

/**
 * 顶栏状态行：状态文案 ＋（引擎跑起来后）「 · 步号」。
 *
 * 逐字保留原式 `label + if (step > 0) " · $step" else ""`：
 * 0 与负数都不加尾巴（步号从 1 起算；-1 是「没在跑」的哨兵）。
 */
internal fun statusLineWithStep(label: String, engineStep: Int): String =
    label + if (engineStep > 0) " · $engineStep" else ""

/**
 * 顶栏「最近一条回复」的取法：**最后一条非 user 消息的最后一个 text part**。
 *
 * 这是原式的逐字搬运（含三个容易被「顺手修」的怪癖）：
 * 1. **末尾的 user 消息会被跳过**：找的是最后一条非 user，不是「最后一条消息」。
 * 2. **不回退**：最后一条非 user 消息若没有 text part（纯工具轮），返回空串——
 *    不去前面翻有正文的那条。抽屉预览用的是另一个函数 [lastAssistantReply]
 *    （有回退、会拼接多段），两者用途不同，别合并。
 * 3. **只取最后一个 text part**：同一条消息里有多个文本段时只显示最后一段。
 *
 * 本批是结构切片，红线是「行为零变化」；要改口径另开单。
 */
internal fun lastNonUserPartText(messages: List<BaopuUiMessage>): String =
    messages.lastOrNull { it.role != "user" }
        ?.parts?.lastOrNull { it.kind == "text" }?.text.orEmpty()

/**
 * 底部操作卡的输入快照：把「字段有没有」翻成 [BottomCardsInput] 的五面旗。
 *
 * 原式在屏幕上用 `!= null` / `isNotEmpty()` 直接构造；搬到这里后判据只剩一处：
 * 列表**非空**才算有那张卡（空列表 = 没有）。
 */
internal fun screenBottomCardsInput(
    hasConfirmCard: Boolean,
    hasAskCard: Boolean,
    pendingMemoryCount: Int,
    memoryCandidateCount: Int,
    skillProposalCount: Int,
): BottomCardsInput = BottomCardsInput(
    confirmCard = hasConfirmCard,
    askCard = hasAskCard,
    pendingMemory = pendingMemoryCount > 0,
    memoryCandidate = memoryCandidateCount > 0,
    skillProposal = skillProposalCount > 0,
)
