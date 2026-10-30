// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第二百九十批 · **一页能力清单**（派工单 §3：「宰相把结果压成一页能力清单（≤10 行）」）。
//
// 这一页有两个读者，形态要同时伺候：
//   · **模型**：首条消息前回灌——它得知道"本机能做什么/不能做什么"，才不会把不可用说成可以做；
//   · **人**：工作区里落一份 `记忆/能力清单.md`，打开就能看。
//
// 形制纪律（都是判据的靶心）：
//   ① **总行数 ≤ 10**（含标题）——钳在构造期 [compose]，不在渲染层截；
//   ② **人话、不翻旧账**："上次开过、现在没了"而不是"你拒了 3 次"；
//   ③ **不可用不许美化**：做不了就写原因，不写"可以试试"；
//   ④ **顺序稳定**：按 [Capability] 枚举序，不按时间/次数排（清单要能跨次对比）。
package com.example.zhengdao.baopu.scout

import com.example.zhengdao.core.TimeFormats

internal object ScoutSheet {

    /** 一页清单（标题＋正文，总行数已按 [ScoutLimits.MAX_SHEET_LINES] 钳过）。 */
    internal data class Sheet(
        /** 标题那行（含探测时刻）。 */
        val headline: String,
        /** 正文行（不含标题）。 */
        val lines: List<String>,
        /** 这次清单里**第一次**把哪些能力标成"刚被收回待提一次"（调用方据此记 mentioned）。 */
        val revokedToMention: List<Capability>,
    ) {
        val totalLines: Int get() = lines.size + 1
        val fits: Boolean get() = totalLines <= ScoutLimits.MAX_SHEET_LINES

        /** 落盘/回灌的正文（标题＋每行一个 `- ` 条目）。 */
        fun render(): String = (listOf(headline) + lines.map { "- $it" }).joinToString("\n")

        /** 给模型的那一句纪律（不让它把清单外的事说成能做）。 */
        fun modelText(): String = render() + "\n" +
            "- 只回答这份清单里做得到的事；清单说做不了或需授权的，直接说原因并给恢复入口，别假装能做。"
    }

    /**
     * 组装清单（纯函数）。
     *
     * 分桶（决定措辞，不决定顺序；顺序仍按枚举序）：
     * - 能直接用（granted，无限制度）
     * - 能用但有限制（granted，有 note）
     * - 要用时会问你要不要开（denied 次数 0–2：判据层还允许问一次）
     * - 上次开过、现在没了（revoked，且这次要提一次）
     * - 现在没开（要用请到系统设置里打开）（denied ≥3；以及**已经提过一次**的 revoked——判据层不再主动问）
     * - 现在做不了（unavailable，带原因；含"这次探测失败"）
     */
    fun compose(records: List<ScoutRecord>, atMs: Long): Sheet {
        val byCap = records.associateBy { it.capability }
        val ordered = Capability.entries.mapNotNull { byCap[it] }

        val available = ordered.filter { it.state == ScoutState.GRANTED && it.note.isBlank() }
        val limited = ordered.filter { it.state == ScoutState.GRANTED && it.note.isNotBlank() }
        val ask = ordered.filter { it.state == ScoutState.DENIED && it.denials < 3 }
        val revokedNew = ordered.filter { it.state == ScoutState.REVOKED && !it.revokedMentioned }
        // 提过一次之后的 revoked 与"驳回 ≥3 次"是同一种处境：判据层已经不许再主动问（[ScoutRules.proactiveAskAllowed]），
        // 于是"要用时会问你要不要开"就成了假话——只能自己去系统设置里打开。两者并排同一句。
        val silent = ordered.filter {
            (it.state == ScoutState.DENIED && it.denials >= 3) ||
                (it.state == ScoutState.REVOKED && it.revokedMentioned)
        }
        val unavailable = ordered.filter { it.state == ScoutState.UNAVAILABLE }

        val body = mutableListOf<String>()
        if (available.isNotEmpty()) {
            body += "能直接用：" + available.joinToString("、") { it.capability.cn }
        }
        if (limited.isNotEmpty()) {
            body += "能用但有限制：" + limited.joinToString("、") {
                if (it.note.isBlank()) it.capability.cn else "${it.capability.cn}（${it.note}）"
            }
        }
        // "刚收回"单独一行，是判据⑤的那**一次**提醒（中性措辞，不念次数、不说"又"）；
        // 提过一次以后就折进下面那条通用行，免得每次启动都像在翻旧账。
        if (revokedNew.isNotEmpty()) {
            body += "上次开过、现在没了，要用时再开一次：" + revokedNew.joinToString("、") { it.capability.cn }
        }
        if (ask.isNotEmpty()) {
            body += "要用时会问你要不要开：" + ask.joinToString("、") { it.capability.cn }
        }
        if (silent.isNotEmpty()) {
            body += "现在没开（要用请到系统设置里打开）：" + silent.joinToString("、") { it.capability.cn }
        }
        if (unavailable.isNotEmpty()) {
            // 用"现在做不了"而不是"本机做不了"：读数是**这一次**的（没插耳机/没联网/探针失败），
            // 写成"本机没有"会把一个当下状态说成永久事实——正是"把不可用说成不行"的另一种撒谎。
            body += "现在做不了：" + unavailable.joinToString("、") {
                "${it.capability.cn}（${it.reason}）"
            }
        }
        if (body.isEmpty()) {
            // 一项都没读出来（探针全失败/账本空）——**不许**写成"什么都能做"。
            body += "这次没探到能力状态（原因见账本 ${ScoutLimits.LEDGER_FILE}）。"
        }

        val headline = "【本机能力】${TimeFormats.monthDay(atMs)} ${TimeFormats.hourMinute(atMs)} 探测"
        // 形制闸：正常情况下最多 6 行正文；这里再钳一次是**防以后加桶**把 10 行顶破。
        val lines = (listOf(headline) + body).let { all ->
            if (all.size <= ScoutLimits.MAX_SHEET_LINES) all.drop(1) else all.drop(1).take(ScoutLimits.MAX_SHEET_LINES - 1)
        }
        val sheet = Sheet(
            headline = headline,
            lines = lines,
            revokedToMention = revokedNew.map { it.capability },
        )
        check(sheet.totalLines <= ScoutLimits.MAX_SHEET_LINES) {
            "capability sheet exceeds ${ScoutLimits.MAX_SHEET_LINES} lines"
        }
        return sheet
    }

    /**
     * 给**模型**的一行摘要（首条消息前的回灌口；UI/引擎接线是后一批的事，本批先把话定死）。
     *
     * 为什么不是把账本原样倒给模型：明细（次数、时间、机型）是**人查账**用的，
     * 回灌给模型的只有"能不能做"——多了只会诱导它复述用户的历史决定。
     */
    fun modelLine(records: List<ScoutRecord>, atMs: Long): String {
        val sheet = compose(records, atMs)
        val blocked = records.count { it.blocked }
        return sheet.headline + " · 能直接用 ${records.size - blocked} 项 · 受限 $blocked 项"
    }
}
