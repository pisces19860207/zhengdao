// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据的公开接口：本项目自己的数据模型（OcMessage / OcPart）。
package com.example.zhengdao.ui.taiji

import com.example.zhengdao.oc.OcMessage
import com.example.zhengdao.oc.OcPart
import com.example.zhengdao.oc.ToolState

/**
 * 「长按消息 → 复制到剪贴板」的**纯函数**部分（无 Compose、无 Context，可 JVM 单测）。
 *
 * ## 为什么要有这一层
 *
 * 复制这件事看起来只是 `ClipboardManager.setPrimaryClip(text)`，但"文本是什么"并不平凡：
 * 一条消息的 `parts` 里可能是正文（Text）、思考过程（Reasoning）、工具调用（Tool）、
 * 附件（File）的任意组合，甚至**只有**思考过程或**只有**一次工具调用（真机实测：
 * Agent 只调工具、一句话不说时，整条消息没有任何 `Text` part）。
 *
 * 如果只取 `filterIsInstance<OcPart.Text>()`，那么"长按工具卡 → 复制"会得到**空字符串**，
 * 用户看到的是"复制成功了但剪贴板是空的"——正是本项目最反感的那种"看着成功、其实没有"
 * （参见 ERRATA E-020 / E-045 / E-054）。所以这里定死两条规则：
 *
 * 1. **正文优先**：有 `Text` part 时只复制正文（思考过程与工具输出是辅助信息，
 *    混进剪贴板会把用户真正想复制的那句话淹掉）；
 * 2. **正文为空时兜底**：按顺序把思考过程 / 工具调用（工具名 + 状态 + 入参 + 结果）/
 *    附件名 / 未知内容拼出来，保证"任何一条可见消息都复制得出东西"。
 *
 * 工具入参 / 结果可能极长（`shell` 的输出、整份文件内容），故各自截断（见 [TOOL_FIELD_LIMIT]），
 * 截断处明确写「（已截断）」而不是静默切掉。
 */
internal object MessageCopy {

    /** 单个工具字段（入参 / 结果）进入剪贴板的上限。8K 字符≈几千字，足够"够用"又不至于把剪贴板塞爆。 */
    internal const val TOOL_FIELD_LIMIT = 8000

    /** 拼一条消息的可复制文本。空消息返回空串（调用方据此禁用「复制」入口）。 */
    internal fun textOf(msg: OcMessage): String {
        val body = msg.parts
            .filterIsInstance<OcPart.Text>()
            .map { it.text.trim() }
            .filter { it.isNotEmpty() }
            .joinToString("\n\n")
        if (body.isNotEmpty()) return body

        // 正文为空：兜底拼装（顺序＝parts 顺序，即模型产生内容的顺序）
        return msg.parts.mapNotNull { part ->
            when (part) {
                is OcPart.Reasoning -> part.text.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "（思考过程）\n$it" }

                is OcPart.Tool -> toolText(part)

                is OcPart.File -> "（附件）${part.filename}"

                is OcPart.Unknown -> part.raw.trim()
                    .takeIf { it.isNotEmpty() }
                    ?.let { "（未知内容 ${part.type}）\n${clip(it, TOOL_FIELD_LIMIT)}" }

                is OcPart.Text -> null // 已在上面的正文分支处理
            }
        }.joinToString("\n\n")
    }

    /** 整段对话的可复制文本：每条消息加一行角色抬头，消息之间用分隔线断开。 */
    internal fun textOfConversation(messages: List<OcMessage>): String =
        messages.mapNotNull { msg ->
            val body = textOf(msg)
            if (body.isEmpty()) null else "${roleLabel(msg)}：\n$body"
        }.joinToString("\n\n———\n\n")

    /** 角色抬头（中文，给"复制全部对话"用）。 */
    internal fun roleLabel(msg: OcMessage): String = when (msg.role) {
        OcMessage.Role.USER -> "我"
        OcMessage.Role.ASSISTANT -> "助手"
        OcMessage.Role.SYSTEM -> "系统"
    }

    /** 工具状态的中文文案（复制用；UI 里 [ToolCallCard] 的徽标文案与这里语义一致）。 */
    internal fun stateText(state: ToolState): String = when (state) {
        ToolState.Running -> "执行中"
        ToolState.Success -> "已完成"
        ToolState.Error -> "失败"
        ToolState.Unknown -> "未知"
    }

    private fun toolText(part: OcPart.Tool): String {
        val sb = StringBuilder()
        sb.append("（工具 ").append(part.toolName.ifBlank { "未命名" })
            .append(" · ").append(stateText(part.state)).append('）')
        part.input?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append("\n入参：\n").append(clip(it, TOOL_FIELD_LIMIT))
        }
        part.output?.trim()?.takeIf { it.isNotEmpty() }?.let {
            sb.append("\n结果：\n").append(clip(it, TOOL_FIELD_LIMIT))
        }
        return sb.toString()
    }

    /** 超长截断，并在尾部明确标注「已截断」（不静默）。 */
    private fun clip(text: String, limit: Int): String =
        if (text.length <= limit) text else text.take(limit) + "\n…（已截断，原文 ${text.length} 字）"
}
