// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百八十四单 · MCP 服务端的**工具白名单**（判据③的闸）。
//
// 唯一真相源还是那两张既有表：目录 [com.example.zhengdao.baopu.agent.BaopuToolCatalog]
// ＋分组表 [com.example.zhengdao.baopu.agent.ToolGroupTable]。本文件**不建第三张清单**，
// 只做一次机械筛选＋一张有理由的排除集：
//
//   进白名单 ⇔ R0（riskTier 逐字等于 "R0"）∧ readOnly ∧ FREE ∧ 不在 [EXCLUDED]。
//
// 排除集每件都写理由——它们多半也标着"只读"，只是只读**不是它们对外部调用者的
// 合适语义**（弹卡等人/改会话状态/改外部配置/联网出口）。少一件比多一件安全：
// fail-closed 的方向永远是把暴露面往小收。
package com.example.zhengdao.baopu.mcp.server

import com.example.zhengdao.baopu.RiskClass
import com.example.zhengdao.baopu.RiskMatrix
import com.example.zhengdao.baopu.agent.BaopuToolCatalog
import com.example.zhengdao.baopu.agent.ToolGroupTable
import com.example.zhengdao.baopu.agent.ToolPermission
import com.example.zhengdao.baopu.agent.ToolSpec
import java.io.File

internal object McpWhitelist {

    /**
     * 服务端**不**对外暴露的 R0 件（每件一行理由）。
     *
     * 为什么按名排除而不是按组：这八件散在 CORE（ask_user/capabilities/update_plan/
     * mcp_list/search_web/browser_open/preview_local_file）——按组会把 read_file 一起带走。
     */
    private val EXCLUDED: Set<String> = setOf(
        // 弹卡等人：外部调用者答不了卡，list 出来只会让对端以为能问。
        "ask_user",
        // 改会话状态：capabilities 改解锁格、update_plan 改计划卡——"只读盘"但不是"读"。
        "capabilities",
        "update_plan",
        // 外部 MCP 自配五件：外部 Agent 没有资格改这台机器的外部工具配置（R1/R3 语义在身）。
        "mcp_list",
        "mcp_add_server",
        "mcp_connect",
        "mcp_disconnect",
        "mcp_set_token",
        // 联网出口：矩阵虽是 R0，但外部调用不该能触发这台机器的对外请求（保守收一面）。
        "search_web",
        "browser_open",
        // 切玄览页签/本地预览：动的是界面状态，不是"读"——对外只留纯读件。
        "preview_local_file",
    )

    /**
     * 白名单快照（**现算不缓存**：目录/分组表/矩阵表都是常量或内存表，成本可忽略；
     * 每次现算让"表变了白名单自动跟着变"成为结构性质，而不是一条要人记得的同步纪律）。
     *
     * 档位的唯一真相源是 **RiskMatrix（173/219）**：`baseOf(tool) == R0`——
     * `ToolSpec.riskTier` 只是 218 批的登记标签，存量工具（read_file 一批）不回填，
     * 拿它当判据会把最核心的几件读工具漏掉（第一版实测踩过：白名单只剩六件）。
     *
     * @param workspaceRoot 工厂需要一根路径；这里**只借它出元数据**（name/schema/组章），
     *   拿到的 spec 绝不执行——执行件是 [McpToolRunner] 的缝，归 548。
     */
    fun specs(workspaceRoot: File = File(".")): List<ToolSpec> =
        BaopuToolCatalog.ENTRIES
            .map { entry ->
                // 组章照注册表同一条路补：ToolGroupTable 是唯一分组表，这里不自己盖章。
                entry.factory { workspaceRoot }.let { spec ->
                    spec.copy(group = ToolGroupTable.GROUP_OF[spec.name] ?: spec.group)
                }
            }
            .filter { it.name !in EXCLUDED }
            .filter {
                RiskMatrix.baseOf(it.name) == RiskClass.R0 &&
                    it.readOnly &&
                    it.permission == ToolPermission.FREE
            }

    fun names(workspaceRoot: File = File(".")): List<String> = specs(workspaceRoot).map { it.name }

    fun find(name: String, workspaceRoot: File = File(".")): ToolSpec? =
        specs(workspaceRoot).firstOrNull { it.name == name }
}
