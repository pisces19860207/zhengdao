// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · MCP 服务端的**租约闸**（五件必备之三：每次调用过租约）。
//
// 判定与账都借既有真件，不另起炉灶：类目走 [com.example.zhengdao.baopu.authz.LeaseRules]，
// 内存账走 [com.example.zhengdao.baopu.authz.LeaseStore]——与引擎的租约是**同一本账**。
//
// 语义只有一句话：**外部调用者没有"答卡"这个器官，所以租约未命中就只剩两个出口——**
//   · 这一类在 App 内本就免卡直行（R0 ∧ readOnly ∧ FREE：矩阵不弹卡，租约也管不着）
//     ⇒ 与 App 内同权放行（账上标 `lease=free`，看得见是哪一档放行的）；
//   · 其余（任何要卡/要租约的档）⇒ 拒。外部 Agent 想用，先在 App 内由用户把
//     授权/租约给出来（那一步归 548 的会话接线），本闸这里永远 fail closed。
//
// 会话格：租约按 sessionId 分格（A 会话的租约在 B 会话不命中）。外部调用者没有
// App 会话，给一个恒定负号会话——与真实会话（正号）物理不同格，互不借用。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.authz.LeaseRules
import com.example.zhengdao.baopu.authz.LeaseStore
import com.example.zhengdao.baopu.authz.LeaseUse
import com.example.zhengdao.baopu.authz.LeaseVerdict
import org.json.JSONObject

internal object McpCallLease {

    /** 服务端专用会话号（负号恒定值；真实会话号是正的，不撞格）。 */
    const val MCP_SESSION_ID = -584L

    /** 一次判定的三个出口（放行的两种都已在账上分得清）。 */
    sealed interface Verdict {
        /** 租约真命中：扣次放行（与 App 内命中同一条账）。 */
        data class Hit(val leaseId: String, val remaining: Int) : Verdict

        /** 免卡类（R0 只读 FREE）：租约未命中也放行——App 内它们本就不需要卡。 */
        data class FreePass(val cls: String) : Verdict

        /** 拒（fail closed）。 */
        data class Reject(val missReason: String) : Verdict
    }

    /**
     * 每次 tools/call **必过**这里（判定本体就是 [LeaseStore.hit]——账上真扣过/真查过，
     * 不是形式参数）。targets 传空：外部调用者的路径参数没有 App 的工作区根可锚，
     * 目录型租约对它们天然不命中（out_of_scope）——这本身就是保守侧。
     */
    fun admit(spec: ToolSpec, args: JSONObject, opId: String, nowMs: Long): Verdict {
        val cls = LeaseRules.classOf(spec)
        return when (val v = LeaseStore.hit(sessionId = MCP_SESSION_ID, cls = cls, targets = emptyList(), args = args, cost = 1, opId = opId, nowMs = nowMs)) {
            is LeaseVerdict.Hit -> Verdict.Hit(v.leaseId, v.remaining)
            is LeaseVerdict.Miss ->
                if (isFreePassClass(spec)) Verdict.FreePass(cls)
                else Verdict.Reject(v.reason.wire)
        }
    }

    /** 收口：命中放行的那笔把"用一次"落成账（没命中过 ⇒ 静默，与 LiveLeaseGate 同款）。 */
    fun noteUsed(opId: String, tool: String, label: String) {
        val use: LeaseUse = LeaseStore.takePending(opId) ?: return
        LeaseStore.noteUsed(use, tool, label)
    }

    /** 免卡类的唯一判据（档位与白名单同源 RiskMatrix；两处都 fail closed）。 */
    fun isFreePassClass(spec: ToolSpec): Boolean =
        RiskMatrix.baseOf(spec.name) == RiskClass.R0 &&
            spec.readOnly &&
            spec.permission == ToolPermission.FREE
}
