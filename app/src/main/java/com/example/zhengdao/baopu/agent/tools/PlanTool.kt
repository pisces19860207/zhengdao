// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：派工单第十七批 §1.1（A 段：模型能「报计划」）＋ 用户 2026-10-18 逐字口径
// （「他先列计划，做完一条打个勾」）。表在 [BaopuToolCatalog]（一张表），本文件
// 只放 update_plan 自己的 spec 与接线点。
//
// 三条设计口径：
// 1. **写类但免确认**：它只改「抱朴自己的计划态」＋ 落一条 part，不碰用户任何文件
//    ⇒ [ToolPermission.FREE]。若逼它走 CONFIRM，模型每完成一条计划都要弹一次卡
//    问用户「可以更新计划吗」——那是纯骚扰（与「已关工具的桩也改 FREE」同一理）。
// 2. **接线是运行时的**（照 [BaopuBrowser] 房型）：计划态在引擎手里（按会话），
//    spec 注册时还没有引擎 ⇒ [PlanSink] 由引擎装配时注入；没接线就明确拒绝，
//    不静默丢弃（静默丢 = 模型以为计划报上去了，其实没人记）。
// 3. **零 Android 依赖**：只认 [PlanSink] 接口，JVM 单测注假件验全部语义。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PlanRules
import com.example.zhengdao.baopu.RiskClass
import org.json.JSONObject

/**
 * update_plan 的落点（引擎实现；进程级单点，见 [PlanTool.sink]）。
 *
 * 为什么形状是「进 args 出 [ToolOutcome]」而不是返回计划表：截断、sticky done、
 * 落库、内存态全在引擎侧一气完（[com.example.zhengdao.baopu.PlanRules] 是纯判据），
 * 工具层连计划表长什么样都不用知道——将来改 payload 形状不动这里。
 */
internal interface PlanSink {
    fun submit(args: JSONObject): ToolOutcome
}

/** update_plan（第十七批 · 工作计划卡）。 */
internal object PlanTool {

    const val NAME = "update_plan"

    /**
     * 计划提交出口（引擎装配时注入；JVM 单测注入假件）。
     *
     * 为什么是进程级可变单点：引擎是进程级单例（[com.example.zhengdao.baopu.BaopuRuntime]
     * 的 double-check lazy），换通道重建引擎时会换接一次；两次重建之间旧引擎的
     * 引用仍在，但它与库/注册表同源，行为不破（内存计划态会丢——重启级丢舍，
     * 库里的 plan part 不受影响，续跑会从库回种）。
     */
    @Volatile
    var sink: PlanSink? = null

    /** 写进系统提示的工具描述（≤ [ToolSpec.MAX_DESC] 字符；工具描述是注入面）。 */
    const val DESCRIPTION: String =
        "更新你的工作计划。接到任务先用本工具列 3–12 条计划；之后每完成一条就再调一次，" +
            "把整张表重发（做完的标 done、正在做的标 doing、没做的标 todo）。" +
            "计划有变（发现新步骤、合并或拆开、顺序变了）也在原表上改：新步骤加进去；" +
            "增删步骤时 App 可能先弹确认卡，按用户的选择来。已完成的条目不许漏写。" +
            "每次调用全量覆盖；本版最多 12 条、每条最多 60 字，超出自动截断。" +
            "计划只走本工具，不要写进回复正文（正文只留最终答复）。" +
            "列计划时带上 contract（goal/steps 每步 risk、touches 白名单、expected_asks）：" +
            "用户批准之后它就是本次运行的上限，动到计划外的东西会先停下来问人。"

    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                "items" to jsonObjectOf(
                    "type" to "array",
                    // 第一百一十九批：属性必须带 description（模型看不见怎么填就在猜）。
                    "description" to "整张计划表（全量覆盖）：每条形如 " +
                        "{\"text\":\"要做的事\",\"status\":\"todo|doing|done\"}；" +
                        "计划有变时整表重发、新步骤加进来，已完成的条目要保留",
                    "items" to jsonObjectOf(
                        "type" to "object",
                        "properties" to jsonObjectOf(
                            "text" to jsonObjectOf(
                                "type" to "string",
                                "description" to "这一条要做什么（≤60 字，超出自动截断）",
                            ),
                            "status" to jsonObjectOf(
                                "type" to "string",
                                // enum 与校验同源（PlanRules.STATUSES）——两处各写一份必漂移
                                "enum" to jsonArrayOf(*PlanRules.STATUSES.toTypedArray()),
                                "description" to "todo=还没做 / doing=正在做 / done=做完了",
                            ),
                        ),
                        "required" to jsonArrayOf("text", "status"),
                        "additionalProperties" to false,
                    ),
                ),
            ),
            // 第二百四十二批（计划即契约）：契约是这份计划的**上限声明**，跟着计划一起给。
            // 可选项（不进 required）：老口径的"只列计划不带契约"必须照样能用——没有契约
            // 就没有上限，执行路径与今天逐字节相同（契约只封顶，从不放行，也从不强制）。
            "contract" to jsonObjectOf(
                "type" to "object",
                "description" to "本次运行的契约（用户批准计划后生效）：{goal, steps, touches, " +
                    "readonly, expected_asks, stop_if}。steps 与 items 按位置一一对齐，" +
                    "每一步都要给 risk；缺字段会被拒（不是当成没契约）",
                "properties" to jsonObjectOf(
                    "goal" to jsonObjectOf(
                        "type" to "string",
                        "description" to "一句话说清这次要干成什么",
                    ),
                    "steps" to jsonObjectOf(
                        "type" to "array",
                        "description" to "与 items 同序的每一步约束：{\"may_touch\":[…],\"risk\":\"R0|R1|R2|R3\"}" +
                            "（步骤文字以 items 为准，这里不重写）",
                        "items" to jsonObjectOf(
                            "type" to "object",
                            "properties" to jsonObjectOf(
                                "may_touch" to jsonObjectOf(
                                    "type" to "array",
                                    "description" to "这一步允许动到的目录或网址（相对工作区根写）",
                                    "items" to jsonObjectOf("type" to "string"),
                                ),
                                "risk" to jsonObjectOf(
                                    "type" to "string",
                                    // enum 与四档同源（RiskClass）——两处各写一份必漂移
                                    "enum" to jsonArrayOf(*RiskClass.entries.map { it.name }.toTypedArray()),
                                    "description" to "这一步的预估上限：R0=只读 / R1=在工作区内产出 / " +
                                        "R2=改工作区外或删改已有 / R3=花钱、外发、账号类",
                                ),
                            ),
                            "required" to jsonArrayOf("risk"),
                            "additionalProperties" to false,
                        ),
                    ),
                    "touches" to jsonObjectOf(
                        "type" to "array",
                        "description" to "整轮都可能动到的目标（目录/网址白名单）：动到这里的**以外**" +
                            "就立刻停下来问人",
                        "items" to jsonObjectOf("type" to "string"),
                    ),
                    "readonly" to jsonObjectOf(
                        "type" to "array",
                        "description" to "只读区（可以读、绝不可以写）：写进去即偏离",
                        "items" to jsonObjectOf("type" to "string"),
                    ),
                    "expected_asks" to jsonObjectOf(
                        "type" to "integer",
                        "minimum" to 0,
                        "maximum" to 20,
                        "description" to "预估这次要问用户几次（可以是 0）：跑完按实际问的次数对账",
                    ),
                    "stop_if" to jsonObjectOf(
                        "type" to "array",
                        "description" to "出现什么情况就停（人话条件，随计划卡一起给用户看）",
                        "items" to jsonObjectOf("type" to "string"),
                    ),
                ),
                "required" to jsonArrayOf("goal", "steps", "touches", "expected_asks"),
                "additionalProperties" to false,
            ),
            "required" to jsonArrayOf("items"),
            "additionalProperties" to false,
        ),
        // 免确认：只改自己的计划态，不碰用户文件（见文件头第 1 条）
        permission = ToolPermission.FREE,
    ) { args ->
        sink?.submit(args) ?: ToolOutcome.Rejected(
            "update_plan 没接线（引擎未装配）：本次调用不生效。" +
                "把计划暂时放在心里，照常完成用户的请求。",
        )
    }
}
