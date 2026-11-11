// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百四十九批 · `delegate_peer_task`（使臣团的**管线入口**——派工单 549 ③
// 「A2A 委派必须过 GuardPipeline 的闸，不许另开 agent 专用通道」的落点）。
//
// 房型照 [com.example.zhengdao.baopu.agent.multiagent.SubTaskTool]（127 批）：
// spec 注册在注册表、落点（sink）由引擎装配时注入、没接线就明确拒绝。三条结构保证：
//   · **工具只有一条执行路径**（GuardPipeline 类注释的原文级规矩）：本 spec 只从注册表经
//     管线到达——停机闸（471 intercept）、授权闸（风险矩阵/确认卡）、租约闸（467 的
//     onRejected/onFinished）都在管线的第 ①②③ 段跑完才轮到执行体；
//   · **未分类 ⇒ fail-closed R2 必问**：本工具**不进**矩阵表与分组表（那三处是配套账，
//     归 ToolRegistry 的在飞租约方合并时三行一起补——见交付报告），矩阵查不到 ⇒ R2 必问
//     ＋ unclassified_tool 账行，授权闸照咬，不会漏问；
//   · **闸后的下半段**全在 [EnvoyDeliver]：租约/准入/档位三道闸在执行体里按序再过一遍，
//     拒了就是一个字节不发。
package com.example.zhengdao.baopu.peer

import com.example.zhengdao.baopu.agent.ToolGroup
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import org.json.JSONObject

/** delegate_peer_task 的落点（引擎装配时注入；JVM 夹具注入接 [EnvoyDeliver] 的那一件）。 */
internal fun interface EnvoySink {
    fun submit(args: JSONObject): ToolOutcome
}

/** delegate_peer_task（第五百四十九批 · 使臣团）。 */
internal object EnvoyTool {

    const val NAME = "delegate_peer_task"

    /** 挂账用的工具名（租约账/事件账与 spec 同名不同格：管线用 [NAME]，对端账用 [EnvoyDeliver.LEDGER_TOOL]）。 */
    const val LEDGER_TOOL: String = EnvoyDeliver.LEDGER_TOOL

    /**
     * 进程级落点（与 SubTaskTool.sink 同一房型、同一取舍：引擎是进程级单例，换通道重接一次）。
     * 没接线 ⇒ 明确拒绝，不静默丢弃。
     */
    @Volatile
    var sink: EnvoySink? = null

    /** 写进系统提示的工具描述（≤ [ToolSpec.MAX_DESC] 字符；工具描述是注入面）。 */
    const val DESCRIPTION: String =
        "把一件活派给外部的 A2A agent（使臣团）：按 endpoint 找到对端，先过准入" +
            "（没考过准入的不派），再过外发租约闸，然后 message/send 发任务并轮询到收场。" +
            "返回终态（completed/failed/canceled）或 input-required——要人补输入时，" +
            "带原任务号（task_id）和补充内容**再调一次本工具**续同一任务，不要重开新任务。" +
            "writes=true 表示这活要对方动它的盘或外部资源：只有准入 FULL 档接，" +
            "只读档会被拒。每次委派都要用户批准（授权卡），批了才发得出字节。"

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "endpoint" to jsonObjectOf(
                    "type" to "string",
                    "description" to "对端 A2A 服务地址（http(s)://host[:port]，卡按 /.well-known/agent-card.json 拉）",
                ),
                "task" to jsonObjectOf(
                    "type" to "string",
                    "description" to "要派出去的活（一段自含任务说明；材料随文带上——对端读不到本机盘）。" +
                        "带 task_id 时这里填补充给它的那条话",
                ),
                "writes" to jsonObjectOf(
                    "type" to "boolean",
                    "description" to "这活要不要对方动它的盘/外部资源（默认 false；true 只有准入 FULL 档接）",
                ),
                "task_id" to jsonObjectOf(
                    "type" to "string",
                    "description" to "原任务号（可选）：input-required 后续话用——填了就是续这个任务，不填就是派新活",
                ),
            ),
            "required" to jsonArrayOf("endpoint", "task"),
            "additionalProperties" to false,
        ),
        permission = ToolPermission.FREE,
        timeoutMs = TOOL_TIMEOUT_MS,
        // 组盖章：注册表按 ToolGroupTable 盖（本工具不进那张表——归在飞租约方合并时补），
        // 这里显式给 AGENT 组，测试注册表直接用它。
        group = ToolGroup.AGENT,
        riskTier = "R2",
        // 只读本机盘（派活不改盘），但**不许并行**：它要外发并阻塞整段（与 run_subtasks 同立场）。
        readOnly = true,
        parallelSafe = false,
    ) { args ->
        sink?.submit(args) ?: ToolOutcome.Rejected(
            "delegate_peer_task 没接线（引擎未装配）：本次调用不生效。" +
                "把任务当作一个整体自己完成，或告诉用户稍后再试。",
        )
    }

    /** 委派的墙钟上限：准入考试（60s 总限）＋ 轮询（120s）＋ 收口余量。 */
    const val TOOL_TIMEOUT_MS = 300_000L
}
