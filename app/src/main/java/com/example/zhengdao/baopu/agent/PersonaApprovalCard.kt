// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 依据：派工单 2026-10-20 第九十六批 §3（御批卡：本工作区 / 全局（仅 B）/ 拒绝）。
//
// 与第九十七批同一张卡：`AskUserRequest/AskUserOption`（`ask_user` 的既有形状），
// **不新造卡片组件、不改任何 Composable**。
package com.example.zhengdao.baopu.agent

import com.example.zhengdao.baopu.PersonaPatch
import com.example.zhengdao.baopu.PersonaPatchScope

/**
 * 人设补丁的御批卡（三选一，按作用域裁剪）。
 *
 * 口径（98 总单 §2.3）：
 * - **本工作区**：只写 C 层（跟工作区走），默认推荐；
 * - **全局（仅 B）**：只有 B 层补丁才给这个按钮（C 层补丁不给，避免「顺手升全局」）；
 * - **拒绝**：一个字节都不写。
 */
internal object PersonaApprovalCard {

    const val ID_WORKSPACE = "workspace"
    const val ID_GLOBAL = "global"
    const val ID_CANCEL = "cancel"

    /** 卡片问题里给补丁起个短名（第一条条款的头 24 字）。 */
    fun previewOf(patch: PersonaPatch): String =
        patch.sections.firstOrNull()?.text?.trim()?.take(24).orEmpty()

    fun request(patch: PersonaPatch): AskUserRequest {
        val options = mutableListOf(
            AskUserOption(
                id = ID_WORKSPACE,
                label = "本工作区",
                detail = "只对当前工作区生效（C 层）；写前自动备份，可回滚",
                recommended = true,
            ),
        )
        if (patch.scope == PersonaPatchScope.B_GLOBAL) {
            options += AskUserOption(
                id = ID_GLOBAL,
                label = "全局（仅 B）",
                detail = "所有工作区都生效（B 层，跟 App 走）；写前自动备份，可回滚",
            )
        }
        options += AskUserOption(
            id = ID_CANCEL,
            label = "拒绝",
            detail = "一个字节都不写",
        )
        return AskUserRequest(
            question = "把这段记进人设？「${previewOf(patch)}…」（${patch.reason}）",
            options = options,
        )
    }
}
