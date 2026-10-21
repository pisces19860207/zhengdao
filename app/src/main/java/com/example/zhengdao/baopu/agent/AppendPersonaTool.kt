// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十八批（2026-10-19 用户「C 层可以让他自治」「按你的来」）：
// 给抱朴一条**只许追加**的 C 层人设通道。
//
// 第九十六批（2026-10-20，98 总单子项 B）**改口径**：B/C 是「模型建议、用户御批」——
// 58 批那条「调了就写」的直写通道作废。现在这条工具会把条款包成**typed patch**，
// 弹一张御批卡（本工作区 / 拒绝），用户点同意才走 [PersonaPatchApply] 落盘。
// 依 96 单红线：工具权限级仍是 FREE（它不自己改可见性），`ToolPermission` 一个字没动。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaConsent
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaPatch
import com.example.zhengdao.baopu.PersonaPatchApply
import com.example.zhengdao.baopu.PersonaPatchScope
import com.example.zhengdao.baopu.PersonaPatchRules
import com.example.zhengdao.baopu.PersonaSection
import com.example.zhengdao.baopu.PersonaSectionKind
import org.json.JSONObject

internal object AppendPersonaTool {

    private const val DESC =
        "在**本项目人设**（C 层）末尾追加一条新条款（只许追加，不能改/删已有条款）。" +
            "调用后会弹一张卡问用户（本工作区 / 拒绝），**用户点同意才写**；" +
            "写前自动备份，可回滚。动全局人设请让用户去设置页。"

    /**
     * @param ask 弹卡并等用户点（默认取 `AskUserTool.sink`，与 `ask_user` 同一张卡、
     *   同一套阻塞语义；null = 当前没有界面 ⇒ 明说不写）
     */
    fun spec(
        ask: ((AskUserRequest) -> AskUserOption?)? = null,
        personaFiles: () -> PersonaFiles,
    ) = ToolSpec(
        name = "append_persona",
        description = DESC,
        permission = ToolPermission.FREE,
        inputSchema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "text": {"type": "string", "description": "要追加的条款（一行一条，可带编号）"},
                "reason": {"type": "string", "description": "为什么加（一句话，会显示在御批卡上）"}
              },
              "required": ["text"],
              "additionalProperties": false
            }
            """.trimIndent(),
        ),
        executor = { args ->
            val clause = args.optString("text").trim()
            if (clause.isEmpty()) {
                ToolOutcome.Failed("text 不能为空")
            } else if (clause.length > PersonaPatchRules.MAX_SECTION_CHARS) {
                ToolOutcome.Failed("条款太长（≤${PersonaPatchRules.MAX_SECTION_CHARS} 字符）：请拆成一条一句")
            } else {
                val pf = personaFiles()
                val wsId = PersonaPatchApply.workspaceIdOf(pf)
                val patch = PersonaPatch(
                    scope = PersonaPatchScope.C_PROJECT,
                    workspaceId = wsId,
                    sections = listOf(PersonaSection(PersonaSectionKind.RULES, clause)),
                    reason = args.optString("reason").ifBlank { "抱朴追加本项目条款" },
                )
                val responder = ask ?: AskUserTool.sink
                if (responder == null) {
                    ToolOutcome.Rejected(
                        "现在没有界面能弹御批卡：人设条款**没有写入**。" +
                            "等用户在的时候再试，或请用户去设置页改人设。",
                    )
                } else {
                    val picked = responder(PersonaApprovalCard.request(patch))
                        ?: return@ToolSpec ToolOutcome.Rejected(
                            "用户没有回应（超时或取消了）：人设没有写入，别当作已记住。",
                        )
                    val consent = when (picked.id) {
                        PersonaApprovalCard.ID_WORKSPACE -> PersonaConsent.WORKSPACE
                        PersonaApprovalCard.ID_GLOBAL -> PersonaConsent.GLOBAL
                        else -> PersonaConsent.DECLINED
                    }
                    when (val r = PersonaPatchApply.apply(patch, consent, wsId, pf)) {
                        is com.example.zhengdao.baopu.PersonaApplyResult.Applied ->
                            ToolOutcome.Ok("已记进本项目人设（写前自动备份）：${clause.take(60)}")
                        is com.example.zhengdao.baopu.PersonaApplyResult.Rejected ->
                            ToolOutcome.Rejected(r.reason)
                    }
                }
            }
        },
        // 第九十六批：会**阻塞等用户点卡**（与 ask_user 同一件事）⇒ 不能用文件类 5 秒。
        // 超时口径照抄 AskUserTool（第四十八批 P0-1）：引擎等 15 分钟，工具多留 5 秒
        // 把「没人回答」如实回灌。
        timeoutMs = ToolTimeout.CONFIRM_MS,
    )
}
