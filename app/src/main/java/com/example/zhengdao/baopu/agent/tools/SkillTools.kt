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
import com.example.zhengdao.baopu.SkillRules
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
            "会改用户文件：写前自动备份、每次改动记一行 `技能/_变更.jsonl`，可回滚；全局技能不许自查。正文别写没实测的东西——没验的段落标「未实测」。"

    /**
     * 「技能被用过」的被动记录器（第五十七批 §1.1）。
     *
     * - 装配层（BaopuRuntime）注入：写 `skill_event` 表一行（skill／sessionId／step／ok／时间）。
     * - **只记录，不据此自动改技能**——本批最重要的边界，红线原文。
     * - 返回值：给「答复里一句话提醒」用的尾巴（§1.2，null = 没提醒）；记录器内部
     *   自己兜异常，这里再包一层 runCatching 双保险。
     */
    fun interface UseRecorder {
        fun onUse(name: String, ok: Boolean): String?
    }

    fun spec(
        workspaceRoot: () -> File,
        personaFiles: () -> PersonaFiles,
        useRecorder: UseRecorder? = null,
    ) = ToolSpec(
        name = "write_skill",
        description = DESC,
        // 第五十八批（C 层自治，2026-10-19 用户拍板）：项目技能不再弹确认卡——
        // 备份、索引、`技能/_变更.jsonl` 留痕三重保障；全局档仍直接拒（见下面 GLOBAL 分支）。
        permission = ToolPermission.FREE,
        inputSchema = JSONObject(
            """
            {
              "type": "object",
              "properties": {
                "name": {"type": "string", "description": "技能名（会变成目录名，只许字母/数字/中文/_/-）"},
                "scope": {"type": "string", "enum": ["project", "global"], "description": "落点：project=跟工作区，global=跟 App"},
                "body": {"type": "string", "description": "SKILL.md 正文（Markdown）"},
                "reason": {"type": "string", "description": "为什么写/为什么改，一句话"},
                "draft": {"type": "boolean", "description": "是否草稿（默认 true；草稿也会进索引但标未验证）"},
                "action": {"type": "string", "enum": ["write", "use", "promote"], "description": "write=写/改（默认）；use=这条技能刚用过一次（次数+1，用满 3 次可转正）；promote=把用满的草稿转正"}
              },
              "required": ["name", "body"]
            }
            """.trimIndent(),
        ),
        executor = { args ->
            val action = args.optString("action").trim().lowercase().ifEmpty { "write" }
            val name = args.optString("name").trim()
            val out = runAction(args, workspaceRoot, personaFiles)
            // 第五十七批 §1.1：use 收口**必记一条**（成功失败都记——失败也是「用了没成」的证据）。
            // 记录器内部自己兜异常、这里再包一层 runCatching 双保险 ⇒ 记录失败只丢提醒，
            // 绝不影响这次工具调用的结果本身（红线：只记录、不阻塞、不据此改技能）。
            if (action == "use" && useRecorder != null) {
                val tip = runCatching { useRecorder.onUse(name, out is ToolOutcome.Ok) }.getOrNull()
                if (out is ToolOutcome.Ok && !tip.isNullOrBlank()) {
                    ToolOutcome.Ok(out.text + "\n" + tip)
                } else {
                    out
                }
            } else {
                out
            }
        },
        timeoutMs = ToolTimeout.FILE_MS,
    )

    /** 原 executor 体（第五十七批抽出来给上面的记录薄壳包着；语义一行没动）。 */
    private fun runAction(
        args: JSONObject,
        workspaceRoot: () -> File,
        personaFiles: () -> PersonaFiles,
    ): ToolOutcome {
        val action = args.optString("action").trim().lowercase().ifEmpty { "write" }
        val name = args.optString("name").trim()
        val root0 = workspaceRoot()
        val pf0 = personaFiles()
        // use / promote：只动**项目技能**，且只碰已存在那一条（不新建、不改名）
        if (action == "use" || action == "promote") {
            return lifecycle(action, name, root0, pf0)
        }
            val body = args.optString("body")
            val scope = when (args.optString("scope").lowercase()) {
                "global" -> SkillScope.GLOBAL
                else -> SkillScope.PROJECT
            }
            // 🛑 2026-10-19 用户口径（逐字：「全局技能不能让他随便改」）：
            // 全局技能是所有项目共用的人设底座，抱朴一旦能自写，等于它可以悄悄改自己的规矩；
            // 项目技能仍可自写（草稿＋确认卡＋写前备份），全局只能由用户在本机设置页里改。
            if (scope == SkillScope.GLOBAL) {
                return ToolOutcome.Rejected(
                    "全局技能不许我自己改：那是所有项目共用的底座。要动全局技能，请你在设置页里改，" +
                        "或者改成项目技能（scope=project）。",
                )
            }
            val draft = if (args.has("draft")) args.optBoolean("draft") else true
            return if (name.isEmpty()) {
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
                plan.blocked?.let { return ToolOutcome.Failed(it) }
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
    }

    /**
     * 技能生命周期两步（2026-10-19 用户口径：像养孩子——不懂就查、查到就记、用多了转正）。
     *
     * - `use`：这条技能刚被用过一次 ⇒ 正文头 uses+1（仍是草稿）；
     * - `promote`：用满 [SkillStore.PROMOTE_USES] 次 ⇒ 头改 status=active、索引换成正式行。
     *
     * 两条都只碰**项目技能**（全局技能抱朴不许改），且只碰已存在的那一条——
     * 名字对不上就明说，不新建一个"看起来差不多"的。
     */
    private fun lifecycle(action: String, name: String, root: File, pf: PersonaFiles): ToolOutcome {
        if (name.isEmpty()) return ToolOutcome.Failed("name 不能为空")
        val file = File(SkillRules.projectSkillDir(root), "$name/${SkillRules.SKILL_FILE}")
        if (!file.isFile) {
            return ToolOutcome.Failed("没有这条项目技能：$name（先用 list_dir 看 技能/ 下有什么）")
        }
        val old = runCatching { file.readText(Charsets.UTF_8) }.getOrNull()
            ?: return ToolOutcome.Failed("读不出技能正文：${file.absolutePath}")
        val isDraft = SkillStore.statusOf(old) == "draft"
        val uses = SkillStore.usesOf(old)
        return when (action) {
            "use" -> {
                if (!isDraft) {
                    return ToolOutcome.Failed("$name 已经是正式技能，不用再记次数")
                }
                val newBody = SkillStore.bumpUses(old)
                val after = SkillStore.usesOf(newBody)
                val msg = if (SkillStore.canPromote(newBody)) {
                    "（已用 $after/${SkillStore.PROMOTE_USES} 次，够了）"
                } else {
                    "（已用 $after/${SkillStore.PROMOTE_USES} 次）"
                }
                writeViaStore(root, pf, name, newBody, reason = "用过一次：记次数", keepDraft = true)
                    .let { r ->
                        when (r) {
                            is ToolOutcome.Ok -> ToolOutcome.Ok(r.text + "\n" + msg)
                            else -> r
                        }
                    }
            }
            "promote" -> {
                if (!isDraft) return ToolOutcome.Failed("$name 已经是正式技能了")
                if (!SkillStore.canPromote(old)) {
                    return ToolOutcome.Failed(
                        "$name 才用了 $uses/${SkillStore.PROMOTE_USES} 次，先用满再转正（先用起来才知道它值不值）",
                    )
                }
                val newBody = SkillStore.draftHeader(status = "active", uses = uses) +
                    "\n" + stripHeader(old)
                writeViaStore(root, pf, name, newBody, reason = "用满了：转正", keepDraft = false)
            }
            else -> ToolOutcome.Failed("不认识的动作：$action（只认 write / use / promote）")
        }
    }

    /** 正文头单独一笔：plan 里 draft=true 会把 uses 重置成 0，所以正文要自己带好再写。 */
    private fun writeViaStore(
        root: File,
        pf: PersonaFiles,
        name: String,
        newBody: String,
        reason: String,
        keepDraft: Boolean,
    ): ToolOutcome {
        val globalRoot = File(pf.globalFile().parentFile ?: root, "")
        val base = SkillProposal(
            scope = SkillScope.PROJECT,
            name = name,
            body = newBody,
            reason = reason,
            draft = false,
        )
        // 索引行仍按草稿算（草稿要一直带「未验证」标），但写进正文的用已经算好的 newBody
        val planned = SkillStore.plan(
            base.copy(draft = keepDraft),
            root,
            globalRoot,
            stamp(),
            indexCountAfterAdd = 0,
        )
        planned.blocked?.let { return ToolOutcome.Failed(it) }
        return when (val r = SkillStore.apply(planned.copy(proposal = base), pf, pf.read(PersonaLayer.C_PROJECT).orEmpty())) {
            is SkillWriteResult.Written -> ToolOutcome.Ok(
                "已更新技能状态：${name}" + if (r.indexUpdated) "（索引已同步）" else "（索引没写上，见 App 提示）",
            )
            is SkillWriteResult.Rejected -> ToolOutcome.Failed(r.reason)
        }
    }

    /** 剥掉第一行状态头（只认第一行、只认带 status= 的注释）。 */
    private fun stripHeader(body: String): String {
        val lines = body.lines()
        val head = lines.firstOrNull()?.trim().orEmpty()
        return if (head.startsWith("<!--") && head.contains("status=")) lines.drop(1).joinToString("\n") else body
    }
    private fun stamp(): String = com.example.zhengdao.util.TimeFormats.fileStamp()
}


