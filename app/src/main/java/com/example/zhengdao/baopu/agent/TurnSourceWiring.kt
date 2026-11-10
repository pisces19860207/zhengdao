// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百七十四批 · 「当前轮次消息号」的**接线信箱**（引擎写、工具层读）。
//
// 来历（574 派工单）：`ledger_add` / `todo_add` 的来源号改**可选**——模型不传时，
// 工具层用**本轮用户消息的 messageId** 自动补；定时/后台跑的时候没有这句话 ⇒ 落 null，
// **不写「缺口-XXX」这类占位号**。
//
// 为什么需要一个 object：工具执行体住在注册表的闭包里，拿不到引擎的轮内状态；
// 而用户消息的 id 只有引擎知道（落库那一刻才产生）。与 [DeviationWiring] 同一手法：
// 引擎在新起一轮/插话落库时把出处**存**在这里（@Volatile 保证工具线程读得到），
// 工具层落账时来取——出处还是引擎的真实状态，只是走了一个双方都看得见的信箱。
//
// 三条纪律：
//   1. **宁空勿猜**：没有本轮消息号（定时/后台/续跑）就回 -1，调用方落 null——
//      不编号、不回退上一轮的旧值（信箱每轮覆盖，收工摘空）；
//   2. **只转写**：本文件不产事实，messageId 是引擎落库后的真值，原样进出；
//   3. **静默**：读信箱失败不抛不连累主流程（工具层的自动补是锦上添花，不是闸）。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject

internal object TurnSourceWiring {

    @Volatile private var msgId: Long = -1L

    /** 新起一轮/插话落库：存这条用户消息的 id（每轮覆盖——来源永远跟着当前轮）。 */
    fun installTurn(newMsgId: Long) {
        msgId = newMsgId
    }

    /** 收工：摘空（收工后的工具调用不该再拿到上一轮的号——宁空勿猜）。 */
    fun clearTurn() {
        msgId = -1L
    }

    /** 当前轮的用户消息号（没有 ⇒ -1；调用方按「≤0 ＝ 没有」落 null）。 */
    fun currentTurnMessageId(): Long = msgId

    /**
     * 工具层取来源号的**唯一口**（574：`ledger_add` / `todo_add` 共用这一份，不许各写一套）：
     * 调用方传了就用它的（trim 后非空）；没传就用本轮消息号自动补——
     * 没有本轮消息号（定时/后台）⇒ 空串（落盘口落 null，**不写占位号**）。
     */
    fun resolveFrom(args: JSONObject, argName: String): String {
        val given = args.optString(argName).trim()
        if (given.isNotEmpty()) return given
        val mid = currentTurnMessageId()
        return if (mid > 0L) mid.toString() else ""
    }

    /** 测试摘信箱（进程级全局不清就是随执行顺序变红——`DeviationWiring.resetForTest` 同款教训）。 */
    fun resetForTest() {
        msgId = -1L
    }
}
