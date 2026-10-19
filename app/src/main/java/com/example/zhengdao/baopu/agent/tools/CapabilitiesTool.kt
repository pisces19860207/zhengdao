// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §1.3（工具渐进披露的**解锁入口**）——
// 用户原话「有些人百年用不到某些工具」⇒ 低频组默认不进 tools 数组（模型根本看不见），
// 需要时由模型自己按需解锁，而不是让用户手工维护 25 个开关。
//
// 三条设计口径：
// 1. **FREE（免确认）**：它只改「本轮模型能看见哪些工具组」，不碰用户任何文件 ⇒
//    与 update_plan 同一档位。若逼它走确认卡，模型每发现一个工具就要弹一次框问用户
//    「可以让我看见 browser 组吗」——那是纯骚扰（与「已关工具的桩也改 FREE」同一理）。
// 2. **接线是运行时的**（照 [PlanTool.sink] / [AskUserTool.sink] 房型）：名册与解锁要
//    同时握着「表 + 用户开关 + 会话解锁」三样输入，只有 [ToolRegistry] 全都有 ⇒
//    判据写在注册表里（[BaopuToolCatalog.loadInto] 挂 sink），本文件只放 Schema 与
//    「没接线就明说」。
// 3. **零 Android 依赖**：JVM 单测直接注假件验全部语义。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject

/**
 * `capabilities` 的落点（[ToolRegistry] 实现；接线见 [BaopuToolCatalog.loadInto]）。
 *
 * 为什么形状是「进 args 出 [ToolOutcome]」而不是返回组表：可见性判据只有一份
 * （[visibleTools]），名册、解锁、tools 数组三处都从它出——判据留在注册表里，
 * 工具层连「哪些组默认可见」都不必知道，将来改分组不动这里。
 */
internal interface CapabilitiesSink {
    fun handle(args: JSONObject): ToolOutcome
}

/** capabilities（第五十九批 §1.3：能力组的名册与解锁）。 */
internal object CapabilitiesTool {

    const val NAME = "capabilities"

    /** 唯一的参数：要在本会话解锁的组名数组。 */
    const val ARG_ENABLE = "enable"

    /**
     * 落点（注册表装配时注入；没接线就**明说**，不静默返回空名册——
     * 静默空名册等于告诉模型「这 App 一个工具都没有」）。
     */
    @Volatile
    var sink: CapabilitiesSink? = null

    /** 可以点名的组：COMPAT（免费通道占位）不在其列——它由通道决定，解锁它没有意义。 */
    fun unlockableKeys(): List<String> =
        ToolGroup.values().filter { it != ToolGroup.COMPAT }.map { it.key }

    /** 写进系统提示的工具描述（≤ [ToolSpec.MAX_DESC] 字符；工具描述是注入面）。 */
    const val DESCRIPTION: String =
        "看你手上有哪些工具组、并按时解锁。低频工具组默认不出现（不是禁用，是你看不见），" +
            "需要时再解锁：不带参数＝列出全量名册（各组一句话＋已解锁/未解锁/被用户关闭）；" +
            "带 enable=[\"browser\",\"card\"]＝**本会话内**解锁这些组，下一轮就能用。" +
            "解锁不跨会话（新会话回到默认），也不能打开被用户关掉的组——那种情况如实说。" +
            "先想清楚要做什么再解锁，不要一次把所有组都点开。"

    /** 构造注册用 Spec。 */
    fun spec(): ToolSpec = ToolSpec(
        name = NAME,
        description = DESCRIPTION,
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to jsonObjectOf(
                ARG_ENABLE to jsonObjectOf(
                    "type" to "array",
                    "description" to "要在本会话解锁的组名（core 与 edit 本来就默认可见，" +
                        "不用点；写错组名会被拒并列出可用组名）",
                    "items" to jsonObjectOf(
                        "type" to "string",
                        // enum 与那张唯一的组表同源（ToolGroup）——两处各写一份必漂移
                        "enum" to jsonArrayOf(*unlockableKeys().toTypedArray()),
                    ),
                ),
            ),
            "additionalProperties" to false,
        ),
        // 免确认：只改「本轮能看见哪些工具组」，不碰用户文件（见文件头第 1 条）
        permission = ToolPermission.FREE,
        timeoutMs = ToolTimeout.FILE_MS,
    ) { args ->
        sink?.handle(args) ?: ToolOutcome.Rejected(
            "capabilities 没接线（注册表未装配）：本次调用不生效。" +
                "照常用手边已有的工具完成用户的请求，别等这张名册。",
        )
    }
}
