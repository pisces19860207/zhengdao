// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 540 模糊唤回 · **卡面纯层**（`LedgerBoardRules` 同房型：判据全在这儿定完，
// Composable 只按表渲染；不引 Compose、不引 UI 符号，JVM 直测）。
//
// 卡面两态：**要点行**（每行必带来源消息 id ＋「跳原文」动作）与**没找到**
// （一行固定口径，零动作）。「跳原文」给不给，由 [RecallCardRules.jumpOf] 一处判：
// 引擎已经丢过缺 id 的命中，这里再钉一道——卡面层的形状不许依赖上游都懂事。
package com.example.zhengdao.baopu.recall

/** 卡面一行的数据（来源 id 摆在明处：派工单③「要点必带消息 id」要看得见）。 */
internal data class RecallRowView(
    val text: String,
    val sourceId: Long,
    val sessionId: Long,
)

/** 召回卡的两种形态：`notFound=true` 时 [rows] 必空（没找到就是没找到，不许垫凑数行）。 */
internal data class RecallCardView(
    val notFound: Boolean,
    val rows: List<RecallRowView>,
)

internal object RecallCardRules {

    /**
     * 引擎产出 → 卡面数据。要点卡只有 found 形态；没找到形态一行文案、零行、零动作。
     */
    fun viewOf(outcome: RecallEngine.Outcome): RecallCardView = RecallCardView(
        notFound = !outcome.found,
        rows = if (outcome.found) {
            outcome.points.map { RecallRowView(it.text, it.messageId, it.sessionId) }
        } else {
            emptyList()
        },
    )

    /**
     * 一行的「跳原文」动作（会话 id to 消息 id，正好是
     * `BaopuChatController.resolveJump`／544 `LedgerJump` 吃的那对数）。
     * 任一端 id 不真 ⇒ null＝这一行不渲染跳转钮，**不是**拿 0 硬跳。
     */
    fun jumpOf(row: RecallRowView): Pair<Long, Long>? =
        if (row.sessionId > 0L && row.sourceId > 0L) row.sessionId to row.sourceId else null
}
