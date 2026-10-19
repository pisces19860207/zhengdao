// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十八批（2026-10-19 用户「C 层可以让他自治」「按你的来」）：
// 给抱朴一条**只许追加**的 C 层人设通道。
//
// 为什么不放开 write_file：那个口子能整篇覆盖人设（改/删既有条款）——自治到那一步
// 就等于让它改自己的缰绳。这里只接受「现有正文 + 末尾新条款」的 proposed，
// 不满足 appendOnly 直接拒，其余一律走提案/设置页。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.PersonaProposal
import com.example.zhengdao.baopu.PersonaRules
import org.json.JSONObject

internal object AppendPersonaTool {

    private const val DESC =
        "在**本项目人设**（C 层）末尾追加一条新条款（只许追加，不能改/删已有条款）。" +
            "用于把用户明确说过的规矩记进本项目；动全局人设请让用户去设置页。"

    fun spec(personaFiles: () -> PersonaFiles) = ToolSpec(
        name = "append_persona",
        description = DESC,
        permission = ToolPermission.FREE,
        inputSchema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "text": {"type": "string", "description": "要追加的条款（一行一条，可带编号）"},
                "reason": {"type": "string", "description": "为什么加（一句话）"}
              },
              "required": ["text"]
            }
            """.trimIndent(),
        ),
        executor = { args ->
            val clause = args.optString("text").trim()
            if (clause.isEmpty()) {
                ToolOutcome.Failed("text 不能为空")
            } else {
                val pf = personaFiles()
                val current = pf.read(PersonaLayer.C_PROJECT).orEmpty()
                val next = if (current.isBlank()) clause else current.trimEnd() + "\n" + clause
                if (!PersonaRules.appendOnly(current, next)) {
                    ToolOutcome.Rejected("C 层人设只能追加：拟写入内容不是「原有正文 + 末尾新条款」")
                } else {
                    val p = PersonaRules.propose(
                        PersonaLayer.C_PROJECT,
                        current,
                        next,
                        args.optString("reason").ifBlank { "抱朴追加本项目条款" },
                    )
                    if (p != null && pf.applyProposal(p)) {
                        ToolOutcome.Ok("已追加到本项目人设（写前自动备份）：${clause.take(60)}")
                    } else {
                        ToolOutcome.Failed("人设没有写进去（提案被拒或写盘失败）")
                    }
                }
            }
        },
        timeoutMs = ToolTimeout.FILE_MS,
    )
}
