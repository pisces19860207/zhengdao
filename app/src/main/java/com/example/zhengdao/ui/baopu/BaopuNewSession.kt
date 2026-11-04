// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第四百三十四批（顶栏对齐设计稿）·「新会话」的**唯一实现**。
//
// 为什么要收这一处（用户 2026-11-04 追加指令，逐字）：
//   顶栏「＋」移除后，`newSession` **逻辑不许丢**，统一到
//   ① 左侧栏「新会话」 ② 工作区的「＋」 ⇒ 抽**单一入口**（一处实现、两处调用），顶栏不再重复。
//   判据：两处都能新建（真机各点一次）；全仓 `newSession` 调用点只有这两个（grep 为证）。
//
// 收之前有 5 处 `controller.newSession()`（抽屉行 / 工作区当前行 / 顶栏「＋」 / 切工作区确认 / 选文件夹），
// 「换工作区 + 留痕 + 开新会话」那六行在三个地方各抄一遍——改一处漏两处，
// 最坏的一种漏是**换了工作区却没留痕**（26-B 那条内核留痕规矩）。
//
// 收之后：
//   · `controller.newSession()` 全仓只剩这一处（本文件第 1 次也是唯一一次调用）；
//   · `BaopuNewSession.open(...)` 全仓两处调用——① 抽屉「新会话」行（不带路径＝不换工作区），
//     ② 工作区入口的共享函数 `openInWorkspace`（三条腿：当前文件夹 / 确认卡切过去 / 选新文件夹，
//     都是同一个用户入口，不算第二个）；
//   · 顶栏「＋」整颗删除，不在这张表里出现。
package com.example.zhengdao.ui.baopu

import android.content.Context
import com.example.zhengdao.baopu.BaopuWorkspace
import com.example.zhengdao.baopu.kernel.KernelAudit
import com.example.zhengdao.core.RunLog

internal object BaopuNewSession {

    /**
     * 开一个新会话；要换工作区就**先换、换不成就不开**（否则会开在旧目录里，人却以为在新目录）。
     *
     * 三种去处分开写（[NewSessionWhere]），不用「null = 不换 / 空串 = 换默认」这种要人记的暗号：
     * [NewSessionWhere.DefaultWorkspace] 那一行不是「自定义路径」，走 [BaopuWorkspace.setDefault]
     * （mode=default），[NewSessionWhere.Folder] 走 [BaopuWorkspace.setCustom]。
     * 两条都要留痕（26-B：内核项变更必须记账），但**路径没动就不记**——记了就是假账。
     *
     * @return false = 没开成（切工作区失败，调用方负责提示人）。
     */
    fun open(context: Context, controller: BaopuChatController, where: NewSessionWhere): Boolean {
        when (where) {
            NewSessionWhere.Stay -> Unit
            is NewSessionWhere.Folder -> {
                val before = BaopuWorkspace.hostDir(context).absolutePath
                if (!BaopuWorkspace.setCustom(context, where.path)) return false
                auditWorkspaceMove(context, before)
            }
            NewSessionWhere.DefaultWorkspace -> {
                val before = BaopuWorkspace.hostDir(context).absolutePath
                BaopuWorkspace.setDefault(context)
                auditWorkspaceMove(context, before)
            }
        }
        controller.newSession()
        return true
    }

    /** 工作区真的换了地方才留痕（26-B；路径没变留一条＝假账）。 */
    private fun auditWorkspaceMove(context: Context, before: String) {
        val after = BaopuWorkspace.hostDir(context).absolutePath
        if (after != before) RunLog.log(KernelAudit.workspace(after, before))
    }
}

/** 「在哪儿开新会话」的三种去处（第四百三十四批单入口的入参形态）。 */
internal sealed class NewSessionWhere {
    /** 就在当前工作区开，不碰设置。 */
    object Stay : NewSessionWhere()

    /** 回到默认工作区（`Download/证道` 那一格，mode=default）。 */
    object DefaultWorkspace : NewSessionWhere()

    /** 切到某个文件夹再开（切不成 ⇒ 不开，并让人知道）。 */
    data class Folder(val path: String) : NewSessionWhere()
}
