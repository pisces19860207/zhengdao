// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第二百一十六批 · MCP OAuth 2.1：**授权回调的进程级收件箱**。
//
// 为什么不让 Activity 直接换 token：换 token 是网络 + 写加密存储（IO），Activity 只该
// 做「把外部 Intent 拍平 → 投递」这一件事（与第八十三批分享/深链同一条纪律）。
// 面板（[McpServerScreen]）在组合时观察这里，拿到回调后自己起 IO 去完成流程——
// 那条路已经在用 `Dispatchers.IO`，不需要再引入线程。
//
// 口径：**只存一份**。用户连点两次授权、浏览器回来两次，只处理最后一条（旧 state 反正在
// [McpAuthFlow] 里已经作废）。回调里带的是 code/state，都是短命凭据，不落盘、不打印。
package com.example.zhengdao.ui.baopu

import androidx.compose.runtime.State
import androidx.compose.runtime.mutableStateOf
import com.example.zhengdao.baopu.mcp.McpOAuthCallback

internal object McpAuthInbox {

    private val pending = mutableStateOf<McpOAuthCallback?>(null)

    /** 面板观察这个（热启动时回调是后到的，必须能订阅到）。 */
    internal val pendingState: State<McpOAuthCallback?> get() = pending

    /** Activity 侧投递（深链路由通过后调用）。 */
    fun deliver(callback: McpOAuthCallback) {
        pending.value = callback
    }

    /**
     * 面板消费（只消费一次）。
     *
     * 按**引用**比对：组合会重放效果，旧回调不能吞掉后到的新回调
     * （与 BaopuIntents.consumePrompt 同款写法）。
     */
    fun consume(expected: McpOAuthCallback): Boolean {
        if (pending.value !== expected) return false
        pending.value = null
        return true
    }
}
