// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf

/**
 * 环境/Agent 安装进度（2026-10-08）。
 *
 * 起因是用户的一次真实误会（原话）：
 * 「那个提示时间有点短，终端里也没有显示，我以为要重新下载呢，还跑到环境检测那里点了
 * 很多次下载……后来我在终端等了一下终端就刷新了」。
 *
 * 查下去有三处叠加的原因，缺一不可，所以这里一次性补齐"进度可见"这一环：
 * 1. 安装里程碑原来只发 `Toast.LENGTH_SHORT`（2 秒）——人眼刚扫到就没了；
 * 2. 真机实测那次确实**没有重新下载**（用的是 `Download/证道/rootfs/` 里 312 MB 的本地包），
 *    但文案是"检测到本地安装包，直接安装"，**没有一个字告诉用户"不联网"**；
 * 3. 布局里虽然有一个 `@+id/status_banner`（注释还写着"安装/下载/解压等进度提示统一由本
 *    横幅承载"），但**代码里从来没有引用过它**——横幅是死的。于是"终端里没有显示"。
 *
 * 本对象是"当前进度"的唯一真相源：终端页写、主页与设置页读（Compose 状态，天然驱动重组）。
 * 它只存一行字和阶段，不存历史——历史在 `Download/证道/logs/`。
 */
object InstallProgress {

    enum class Phase { Running, Done, Failed }

    data class Status(
        val phase: Phase,
        val text: String,
        /** 本次是否走本地缓存包（不联网）；决定文案要不要强调"没有联网下载" */
        val fromLocal: Boolean = false,
    )

    private val _state = mutableStateOf<Status?>(null)

    /** 供 Compose 读取；没有安装进行中时为 null。 */
    val state: State<Status?> get() = _state

    @Volatile
    private var busy = false

    /** 是否正在安装（设置页据此禁用「检查环境更新」，避免重复点击）。 */
    fun isRunning(): Boolean = busy

    fun start(text: String, fromLocal: Boolean = false) {
        busy = true
        _state.value = Status(Phase.Running, text, fromLocal)
    }

    fun update(text: String) {
        val cur = _state.value
        _state.value = Status(Phase.Running, text, cur?.fromLocal ?: false)
    }

    fun finish(text: String, failed: Boolean = false) {
        busy = false
        val cur = _state.value
        _state.value = Status(
            if (failed) Phase.Failed else Phase.Done,
            text,
            cur?.fromLocal ?: false,
        )
    }

    /** 用户离开终端页 / 安装结果被看到之后清掉（可重入）。 */
    fun clear() {
        busy = false
        _state.value = null
    }

    /** 给非 Compose 调用方（通知栏、日志）用的一行文案。 */
    fun line(): String? = _state.value?.text
}
