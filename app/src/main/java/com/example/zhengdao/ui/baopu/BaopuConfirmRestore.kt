// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第七十二批（§B 80 结构切片 · 纯逻辑抽取）：进程重启后**重建确认卡**的纯合成
// （原 `BaopuChatController.restoreConfirmCard` 的后半段，T3 第一半）。
//
// 为什么抽它：卡的三格（callId / summary / argsJson）都是「库行 → 值」的确定性变换，
// 抽出来才能钉住「参数坏 JSON 也不许抛、摘要照给」「callId 稳定可复现」两条边界。
// **行为零变化**：summary 仍走 `RunRules.confirmSummary`（与在线出卡同一个函数）。
package com.example.zhengdao.ui.baopu

import com.example.zhengdao.baopu.PendingRow
import com.example.zhengdao.baopu.agent.ConfirmRequest
import com.example.zhengdao.baopu.agent.RunRules
import org.json.JSONObject

internal object BaopuConfirmRestore {

    /**
     * 库行 → 恢复出来的确认卡。
     *
     * - `callId = "restored-<pending_at>"`：稳定且可复现（重启两次得到同一张卡，不会叠卡）；
     * - 参数坏 JSON ⇒ 摘要照给（`confirmSummary` 内部按工具名兜底），**不许**因为坏参数就不出卡
     *   ——那张卡是用户点「批准/拒绝」的唯一入口；
     * - `argsJson` 原样带回（批准时引擎侧还要按它执行，不能在这里重排/美化）。
     */
    fun card(row: PendingRow): ConfirmRequest {
        val args = runCatching { JSONObject(row.argsJson) }.getOrNull() ?: JSONObject()
        return ConfirmRequest(
            callId = "restored-${row.at}",
            tool = row.tool,
            summary = RunRules.confirmSummary(row.tool, args),
            argsJson = row.argsJson,
        )
    }
}
