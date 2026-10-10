// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import android.content.Context
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.zhengdao.core.IssueCenter
import com.example.zhengdao.rootfs.RootfsInstaller
import com.example.zhengdao.rootfs.RunLog
import com.example.zhengdao.terminal.InstallNotifier
import java.io.File

/**
 * 安装过程可见性的**非 Activity 入口**（2026-10-08，E-036 §7）。
 *
 * 终端页那三处安装走的是 [com.example.zhengdao.TerminalActivity] 里的
 * `installStatus/finishInstall`（它们还要顺手更新页面顶部那条横幅）。设置页里有三条同样
 * 长、同样会让人干等的路径，此前**只有 Toast**：
 * - 「检查环境更新 → 下载并安装」：`toastOnMain("下载中 20%")` —— 312 MB 下载期间，每个
 *   20% 闪一条 Toast，用户原话就是"提示时间有点短……我以为要重新下载呢"；
 * - 「修复环境」：只发开始/结束两条 Toast，中间几分钟静默；
 * - 「回退环境版本」（对话框自己写着"约几分钟"）：同样只有结束一条 Toast。
 *
 * 这里把四处落点收成一份实现：`InstallProgress`（Compose 单一状态）+ `InstallNotifier`
 * （通知栏常驻进度，离开 App 也看得见）+ `RunLog`（落 `Download/证道/logs/`）+ 成功结论写
 * `files/install-notice.txt`（由 [com.example.zhengdao.terminal.ProotLauncher] 带进终端横幅）。
 */
object InstallFlow {

    /** 开始一次安装：状态置位 + 立刻发一条通知（用户点完按钮、锁屏或切走都不会失去线索）。 */
    fun start(ctx: Context, text: String, fromLocal: Boolean) {
        InstallProgress.start(text, fromLocal)
        RunLog.log(text)
        InstallNotifier.update(ctx, text)
    }

    /** 过程中的一行进度；`percent` ≥ 0 时通知栏显示确定进度条。 */
    fun update(ctx: Context, text: String, percent: Int = -1) {
        RunLog.log(text)
        InstallProgress.update(text)
        InstallNotifier.update(ctx, text, percent)
    }

    /** 成功收尾：横幅常驻结论 + 通知改成可划掉的「已就绪」+ 给终端留一行。 */
    fun finish(ctx: Context, text: String) {
        RunLog.log(text)
        InstallProgress.finish(text)
        InstallNotifier.finish(ctx, text, failed = false)
        writeTerminalNotice(ctx, text)
        // 装成功了，主界面上那条「环境安装失败」就该消失（#4：问题卡不能留着旧账）
        IssueCenter.resolve(ISSUE_INSTALL_FAILED)
    }

    /** 失败收尾：状态标红、通知标失败（调用方再决定要不要补 Toast）。 */
    fun fail(ctx: Context, text: String) {
        RunLog.log(text)
        InstallProgress.finish(text, failed = true)
        InstallNotifier.finish(ctx, text, failed = true)
        // #4：安装/修复/回退失败此前只落在设置页那一行与通知里，回到主界面就断了线索。
        // 主界面问题卡带「去设置重试」入口（重试动作就是设置页「修复环境」那条路）。
        IssueCenter.report(
            id = ISSUE_INSTALL_FAILED,
            title = "环境安装/修复失败",
            detail = text,
            actionLabel = "去设置重试",
            actionId = IssueCenter.ACTION_OPEN_SETTINGS,
        )
    }

    /** 主界面问题卡里「环境安装/修复失败」那条的 id（成功收尾时清掉）。 */
    const val ISSUE_INSTALL_FAILED = "install-failed"

    /**
     * 有没有安装/更新任务在跑（#11 / E-078）。
     *
     * 两个来源都要看：[InstallProgress] 是"UI 可见的任务状态"（由本对象 start/finish 维护），
     * [RootfsInstaller.isInstalling] 是**进程级的真锁**——设置页四条长流程只能进 [InstallProgress]，
     * 而终端页三处安装、增量更新、启动修复走的是别的路径。只看看前者，按钮就会在
     * "终端页正在装环境"时仍然可点。
     */
    fun isRunning(): Boolean = InstallProgress.isRunning() || RootfsInstaller.isInstalling()

    /**
     * 写 `files/install-notice.txt`：终端是原生 TerminalView，App 不能往里注入文本，
     * 只能等下次开会话时由 ProotLauncher 把这一行拼进启动横幅（读完即删，只出现一次）。
     */
    fun writeTerminalNotice(ctx: Context, text: String) {
        runCatching { File(ctx.filesDir, "install-notice.txt").writeText(text) }
            .onFailure { RunLog.log("写终端提示失败：${it.message}") }
    }

    /**
     * 设置页里摊在按钮下面的一行状态（修复 / 回退 / 更新三处共用）。
     * 读的是 Compose 状态，安装过程中会自己重组刷新。
     */
    @Composable
    fun StatusLine(prefix: String = "") {
        val st = InstallProgress.state.value ?: return
        // 图标跟阶段走："⏳ 修复完成…" 看着别扭（真机验过一遍才发现的）
        val mark = prefix.ifEmpty {
            when (st.phase) {
                InstallProgress.Phase.Running -> "⏳"
                InstallProgress.Phase.Done -> "✅"
                InstallProgress.Phase.Failed -> "⚠️"
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            text = "$mark ${st.text}",
            style = MaterialTheme.typography.bodySmall,
            color = if (st.phase == InstallProgress.Phase.Failed) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
