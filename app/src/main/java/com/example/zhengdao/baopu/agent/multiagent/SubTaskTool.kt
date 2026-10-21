// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单《第一百二十七批 · 多 Agent 协作 v1》§0/§2 与开工闸门
// 《多Agent协作v1-协作模型-2026-10-21.md》§2。房型照 [PlanTool]／[AskUserTool]：
// spec 注册在表里、落点（sink）由引擎装配时注入；没接线就明确拒绝，不静默丢弃。
//
// 渐进披露（第五十九批房型）：本工具归 `agent` 组，**默认不可见**——模型先调
// `capabilities` 看见这一组、解锁后才用得上（默认可见工具数被
// ProgressiveToolsTest 硬钉 12，本批不占默认面）。
package com.example.zhengdao.baopu.agent.multiagent

import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import com.example.zhengdao.baopu.agent.ToolOutcome
import com.example.zhengdao.baopu.agent.jsonArrayOf
import com.example.zhengdao.baopu.agent.jsonObjectOf
import org.json.JSONObject

/** run_subtasks 的落点（引擎装配时注入；JVM 单测注入假件）。 */
internal fun interface SubTaskSink {
    fun submit(args: JSONObject): ToolOutcome
}

/** run_subtasks（第一百二十七批 · 多 Agent 协作 v1）。 */
internal object SubTaskTool {

    const val NAME = "run_subtasks"

    /**
     * 计划提交出口（引擎装配时注入；JVM 单测注入假件）。
     *
     * 为什么是进程级可变单点：引擎是进程级单例，换通道重建引擎时会换接一次；
     * 两次重建之间旧引擎的引用仍在，它与库/注册表同源，行为不破（与
     * [PlanTool.sink] 同一房型、同一取舍）。
     */
    @Volatile
    var sink: SubTaskSink? = null

    /** 写进系统提示的工具描述（≤ [ToolSpec.MAX_DESC] 字符；工具描述是注入面）。 */
    const val DESCRIPTION: String =
        "把当前任务拆成 1-5 个子任务，交给至多 2 个子 Agent 并行或串行执行，收回汇总结果。" +
            "子 Agent 是只读研究工：只能读文件、列目录、搜内容，不能写文件、不能问用户、" +
            "也看不到你和用户的对话历史——每条子任务的 goal 必须自含（要做什么、依据什么）。" +
            "可选 input（材料/背景）与 acceptance（验收标准）；timeout_seconds 可给 10-300。" +
            "适合「读很多处再综合」的大任务；单文件的小事别拆，自己直接做。" +
            "子结果不会自动写进任何文件——要不要落产物、落什么，由你决定。"

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "goal" to jsonObjectOf(
                    "type" to "string",
                    "description" to "整体任务的一句话自述（审计用；不写也行）",
                ),
                "subtasks" to jsonObjectOf(
                    "type" to "array",
                    "description" to "1-5 条子任务；执行顺序即数组顺序（并发上限 2）",
                    "items" to jsonObjectOf(
                        "type" to "object",
                        "properties" to jsonObjectOf(
                            "goal" to jsonObjectOf(
                                "type" to "string",
                                "description" to "这条子任务要做什么（必填，自含，≤200 字）",
                            ),
                            "input" to jsonObjectOf(
                                "type" to "string",
                                "description" to "给这条子任务的材料/背景（≤2000 字；路径清单也行）",
                            ),
                            "acceptance" to jsonObjectOf(
                                "type" to "string",
                                "description" to "这条子任务怎样算完成（≤200 字）",
                            ),
                            "tools" to jsonObjectOf(
                                "type" to "array",
                                "description" to "想用的只读工具名（可选；不给＝默认全配，给＝只能收窄）",
                                "items" to jsonObjectOf("type" to "string"),
                            ),
                            "timeout_seconds" to jsonObjectOf(
                                "type" to "integer",
                                "description" to "这条子任务的墙钟上限（秒，10-300，默认 90）",
                                "minimum" to 10,
                                "maximum" to 300,
                            ),
                        ),
                        "required" to jsonArrayOf("goal"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
            "required" to jsonArrayOf("subtasks"),
            "additionalProperties" to false,
        ),
        // 免确认：本工具自己不碰用户任何文件（子任务是只读研究工）——与 update_plan
        // 同理，逼它走确认卡等于每次分工都弹一次框。
        permission = ToolPermission.FREE,
        // 只读盘但**不许并行**：它是会阻塞一整段时间的元调用（整个分解跑完才返回），
        // 与 ask_user 同立场——标 readOnly 让它进「读」的账，parallelSafe=false
        // 让它独占一段，不和别的调用并行（92 批两条标记的依赖关系机检认这个组合）。
        readOnly = true,
        parallelSafe = false,
        // 全局截止 300s + 30s 收口余量（与 SubAgentRules.TOOL_TIMEOUT_MS 同源）
        timeoutMs = SubAgentRules.TOOL_TIMEOUT_MS,
    ) { args ->
        sink?.submit(args) ?: ToolOutcome.Rejected(
            "run_subtasks 没接线（引擎未装配）：本次调用不生效。" +
                "把任务当作一个整体自己完成，或告诉用户稍后再试。",
        )
    }
}
