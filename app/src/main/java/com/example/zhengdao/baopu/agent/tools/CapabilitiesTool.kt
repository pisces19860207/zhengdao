// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-19 第五十九批 §1.3（工具渐进披露的**解锁入口**）——
// 用户原话「有些人百年用不到某些工具」⇒ 低频组默认不进 tools 数组（模型根本看不见），
// 需要时由模型自己按需解锁，而不是让用户手工维护 25 个开关。
//
// 第九十七批（2026-10-20 用户口径「工具全都打开的，能不能默认关/连续用到 N 次再开」）
// **改判**：解锁入口保留，但「自己解锁」改为「向用户申请」——`enable` 会弹一张
// 御批卡（本次会话 / 一直 / 取消），用户同意才落授权。59 批「FREE 自解锁不弹卡」
// 的口径作废（原第 1 条理由「逼它走确认卡是纯骚扰」被用户新口径覆盖）。
//
// 三条设计口径（第九十七批修订后）：
// 1. **仍是 FREE（免确认）**：它自己不改可见性、不碰用户文件——只**发起申请**；
//    真正的授权动作在注册表 [CapabilitiesSink] 落点里按用户的选择执行，
//    `ToolPermission` 分级一个字节没动（红线）。
// 2. **接线是运行时的**（照 [PlanTool.sink] / [AskUserTool.sink] 房型）：名册与授权要
//    同时握着「表 + 用户开关 + 会话解锁 + 长期同意」四样输入，只有 [ToolRegistry] 全都有
//    ⇒ 判据写在注册表里（[BaopuToolCatalog.loadInto] 挂 sink），本文件只放 Schema 与
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
        "看你手上有哪些工具组、并申请启用。低频组默认不出现（不是禁用，是你看不见）：" +
            "不带参数＝列出全量名册（各组一句话＋已授权/未解锁/被用户关闭）；" +
            "带 enable=[\"browser\",\"card\"]＝向用户申请启用这些组——会弹一张卡，" +
            "用户可选「本次会话/一直/取消」，点了同意下一轮才能用。" +
            "被拒、超时或当前没有界面就如实说，先用现有工具做，不要反复申请同一组。" +
            "用户自己在设置里关掉的组你解不开，照实说被关了别绕。"

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
            // 第一百一十九批：required 必须有这一段（哪怕为空）。本工具确实全可选
            // （不带 enable ＝ 只念名册），理由登记在 ToolSchemaRules.REQUIRED_EMPTY_OK。
            "required" to jsonArrayOf(),
            "additionalProperties" to false,
        ),
        // 免确认：它只发起「申请」，授权动作由用户点卡决定（见文件头第 1 条）。
        permission = ToolPermission.FREE,
        // 第九十七批：enable 会**阻塞等用户点卡**（与 ask_user 同一件事）⇒ 不能用文件类 5 秒。
        // 超时口径照抄 AskUserTool（第四十八批 P0-1）：引擎侧等 15 分钟，工具多留 5 秒把
        // 「没人回答」如实回灌；否则用户还没点，工具先被判执行超时。
        timeoutMs = ToolTimeout.CONFIRM_MS,
    ) { args ->
        sink?.handle(args) ?: ToolOutcome.Rejected(
            "capabilities 没接线（注册表未装配）：本次调用不生效。" +
                "照常用手边已有的工具完成用户的请求，别等这张名册。",
        )
    }
}
