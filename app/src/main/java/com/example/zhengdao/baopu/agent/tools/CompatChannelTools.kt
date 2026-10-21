// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单第一百一十九批 §1——「每个工具都有 required/additionalProperties/type/enum；
// 发出去的 schema 与校验用的是同一份」。
//
// 为什么把 read / bash 这两条从装配层搬过来：它们原本是
// [com.example.zhengdao.baopu.BaopuRuntime.registry] 里**内联的 ToolSpec(...)**，
// 而那条路要 Context 才走得到（服务器/单测都进不去）⇒ JVM 门禁测试永远看不见 bash 的
// schema，等于「31 个工具里有一个不受契约约束」。搬到零依赖的 object 之后：
// - schema 只有这一份（read 的 schema 直接取 [ReadFileTool.spec]，不抄第二遍）；
// - 装配层只保留「按通道决定注册不注册」，不再自己拼 schema；
// - `ToolSchemaGateTest` 能把 31 个工具一个不漏地过一遍。
//
// ⚠️ 这两条是**免费通道兼容占位**（② 台账：免费档要求 tools 数组里含 `read` / `bash`，
// 否则服务端 403 FreeTierError）。它们不是能力，别当成「抱朴能执行 shell」的证据。
package com.example.zhengdao.baopu.agent

import org.json.JSONObject

/** 免费通道的两条兼容占位条目（`read` / `bash`；由 [com.example.zhengdao.baopu.BaopuRuntime] 按通道注册）。 */
internal object CompatChannelTools {

    const val NAME_READ = "read"
    const val NAME_BASH = "bash"

    /**
     * `read`：read_file 的别名（schema 与执行器都直接取 [ReadFileTool]，不抄第二份）。
     *
     * @param readFileEnabled read_file 是否被用户开着。关着时注册的是**同样拒绝**的形态——
     *   别名不许当成绕开用户开关的洞（刀 2 口径）。
     */
    fun readSpec(readFileEnabled: Boolean = true): ToolSpec =
        if (readFileEnabled) {
            ToolSpec(
                name = NAME_READ,
                description = "免费网关兼容占位：等价于 read_file（路径相对工作区根）。",
                inputSchema = ReadFileTool.spec().inputSchema,
                permission = ToolPermission.FREE,
                // 第九十二批：它就是 read_file 的别名 ⇒ 同样只读、同样可并行
                readOnly = true,
                parallelSafe = true,
            ) { args -> ReadFileTool.spec().executor(args) }
        } else {
            // read_file 被关 ⇒ 别名同样拒绝（否则设置页的开关就白设了）
            ToolSpec(
                name = NAME_READ,
                description = "免费网关兼容占位：read_file 已被用户关闭，本别名同样不可用。",
                inputSchema = ReadFileTool.spec().inputSchema,
                permission = ToolPermission.FREE,
            ) { _ ->
                ToolOutcome.Rejected(
                    "read_file 已被用户关闭（在 抱朴 → 抽屉「抱朴设置」→ 抱朴工具 里打开）。" +
                        "本 App 不提供别的读取通道，也不会替你打开它。",
                )
            }
        }

    /**
     * `bash`：占位条目，**永远拒绝**（本 App 不跨 rootfs、没有 shell）。
     *
     * 它存在的唯一意义是把那句边界说明回灌给模型。schema 因此是「空的 object」：
     * - `required: []`（没有任何必填，登记在 [ToolSchemaRules.REQUIRED_EMPTY_OK]）；
     * - `additionalProperties: true`（**有意放开**，登记在
     *   [ToolSchemaRules.ADDITIONAL_OPEN_OK]：模型传 `command` 时我们要让它看见
     *   「不提供 shell 执行」，而不是「未知参数 command」）。
     */
    fun bashSpec(): ToolSpec = ToolSpec(
        name = NAME_BASH,
        description = "免费网关兼容占位：本 App 不提供 shell 执行（安全边界，不跨 rootfs）。",
        inputSchema = jsonObjectOf(
            "type" to "object",
            "properties" to JSONObject(),
            "required" to jsonArrayOf(),
            // 有意放开：见上面 KDoc 与 ToolSchemaRules.ADDITIONAL_OPEN_OK
            "additionalProperties" to true,
        ),
        permission = ToolPermission.FREE,
    ) { _ ->
        ToolOutcome.Rejected(
            "bash 被拒：本 App 不提供 shell 执行（不跨 rootfs 是硬边界）；" +
                "文件相关的事用 list_dir / read_file。",
        )
    }
}
