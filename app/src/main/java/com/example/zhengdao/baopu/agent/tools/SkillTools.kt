// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第二十三批 A 段（追加）· 让抱朴**自己**把摸索到的平台流程写成技能，并在平台变化时改它。
//
// 用户原话：「抱朴也可以把各个网络平台的真实流程记下来，做成自己的技能，他可改这个他自己做的技能
// （网络平台会自己加东西，抱朴要跟进才行）」＋「不然一遍遍解释很难受，还有就是他没有相应网络平台的
// 技能的话，下一次继续抓瞎，浪费token」。
//
// 三条口径：
//   1. **必过确认卡**（permission = CONFIRM）：写技能会改用户的文件，模型不许悄悄落手；
//      卡上能看到 名字／落点／正文前 200 字／字数／备份名（`SkillStore.plan` 一次算好）；
//   2. **写前备份、同名即改**：[SkillStore.apply] 已实现——平台变了就地改自己的技能；
//   3. **写完必进索引**（含草稿，标「草稿·未验证」）：否则下一次它看不见，只能重新摸索。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaLayer
import com.example.zhengdao.baopu.PersonaFiles
import com.example.zhengdao.baopu.SkillProposal
import com.example.zhengdao.baopu.SkillScope
import com.example.zhengdao.baopu.SkillStore
import com.example.zhengdao.baopu.SkillWriteResult
import org.json.JSONObject
import java.io.File

/** 写技能（`write_skill`）：抱朴自己的「记下来」通道。 */
internal object SkillTools {

    /** 工具描述（进模型上下文的唯一说明，写得越清楚它越不会乱写）。 */
    private const val DESC =
        "把自己摸索到的流程（比如某个网络平台的填写方式）写成技能文件 SKILL.md。" +
            "写前自动备份、同名就是改上一次写的那份（平台改了就改名同一次）、写完自动加索引。" +
            "会改用户文件，所以必须先经确认卡。正文别写没实测的东西——没验的段落标「未实测」。"

    fun spec(workspaceRoot: () -> File, personaFiles: () -> PersonaFiles) = ToolSpec(
        name = "write_skill",
        description = DESC,
        permission = ToolPermission.CONFIRM,
        inputSchema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "name": {"type": "string", "description": "技能名（会变成目录名，只许字母/数字/中文/_/-）"},
                "scope": {"type": "string", "enum": ["project", "global"], "description": "落点：project=跟工作区，global=跟 App"},
                "body": {"type": "string", "description": "SKILL.md 正文（Markdown）"},
                "reason": {"type": "string", "description": "为什么写/为什么改，一句话"},
                "draft": {"type": "boolean", "description": "是否草稿（默认 true；草稿也会进索引但标未验证）"}
              },
              "required": ["name", "body"]
            }
            """.trimIndent(),
        ),
        executor = { args ->
            val name = args.optString("name").trim()
            val body = args.optString("body")
            val scope = when (args.optString("scope").lowercase()) {
                "global" -> SkillScope.GLOBAL
                else -> SkillScope.PROJECT
            }
            // 🛑 2026-10-19 用户口径（逐字：「全局技能不能让他随便改」）：
            // 全局技能是所有项目共用的人设底座，抱朴一旦能自写，等于它可以悄悄改自己的规矩；
            // 项目技能仍可自写（草稿＋确认卡＋写前备份），全局只能由用户在本机设置页里改。
            if (scope == SkillScope.GLOBAL) {
                return@ToolSpec ToolOutcome.Rejected(
                    "全局技能不许我自己改：那是所有项目共用的底座。要动全局技能，请你在设置页里改，" +
                        "或者改成项目技能（scope=project）。",
                )
            }
            val draft = if (args.has("draft")) args.optBoolean("draft") else true
            if (name.isEmpty()) {
                ToolOutcome.Failed("name 不能为空")
            } else if (body.isBlank()) {
                ToolOutcome.Failed("body 不能为空——空技能写下去只会占索引位")
            } else {
                val root = workspaceRoot()
                val pf = personaFiles()
                // 全局技能落 filesDir/抱朴记忆，项目技能落工作区；plan 只读目录状态，不写盘。
                val globalRoot = File(pf.globalFile().parentFile ?: root, "")
                val proposal = SkillProposal(
                    scope = scope,
                    name = name,
                    body = body,
                    reason = args.optString("reason").ifBlank { "抱朴自己记下来的" },
                    draft = draft,
                )
                val plan = SkillStore.plan(proposal, root, globalRoot, stamp(), indexCountAfterAdd = 0)
                plan.blocked?.let { return@ToolSpec ToolOutcome.Failed(it) }
                val layer = if (scope == SkillScope.GLOBAL) PersonaLayer.B_GLOBAL else PersonaLayer.C_PROJECT
                when (val r = SkillStore.apply(plan, pf, pf.read(layer).orEmpty())) {
                    is SkillWriteResult.Written -> ToolOutcome.Ok(
                        buildString {
                            append("已写入技能：").append(r.file.absolutePath)
                            append('\n').append("索引：")
                            append(if (r.indexUpdated) "已更新（草稿会标「草稿·未验证」）" else "未更新（索引写入被拒，见 App 提示）")
                            r.backup?.let { append('\n').append("改掉了旧版本，备份：").append(it) }
                        },
                    )
                    is SkillWriteResult.Rejected -> ToolOutcome.Failed(r.reason)
                }
            }
        },
        timeoutMs = ToolTimeout.FILE_MS,
    )

    private fun stamp(): String = com.example.zhengdao.util.TimeFormats.fileStamp()
}


