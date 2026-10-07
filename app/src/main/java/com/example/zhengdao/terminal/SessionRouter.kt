// 独立开发声明：本文件为本项目从零编写。可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

/**
 * 「全局单会话」模型的**路由决策**（用户 2026-10-07 定稿）。
 *
 * 为什么单独抽出来：这条规则是整个终端行为的骨架，一旦写错，症状是
 * "点了 agy 却看到 claude"或"聊到一半的会话被自己顶掉"——都是难查的现场问题。
 * 抽成不依赖 Android 的纯函数后，决策表可以直接被单测逐格钉住
 * （见 `app/src/test/java/com/example/zhengdao/terminal/SessionRouterTest.kt`）。
 *
 * 规则一句话：**同一个 Agent 就 attach 回去，别的（换 Agent / 安装 / 别的命令）
 * 就把这条会话整条换掉，不带命令的（点洞天、点"安装中"去看进度）什么都不动。**
 *
 * 与旧实现的区别（方向纠正）：旧代码是"多窗口并存"——每次点启动就 `C-b c` 开一个
 * **新窗口**再注入，靠"扫 /proc 数同名进程 + 20 秒窗口守卫"来避免重复实例。
 * 那是把问题当成"怎么管住好几个实例"，而用户要的是"根本不存在并存"。
 */
object SessionRouter {

    enum class Action {
        /** 同一个 Agent：直接 attach 回当前会话 —— 不重启、不新增、不注入。 */
        ATTACH_CURRENT,

        /** 换 Agent / 安装 / 别的命令 / 旧会话已死：先把会话整条 kill 掉，再起一条新的。 */
        REPLACE_SESSION,

        /** 本次请求不带命令（只是"打开终端"）：会话原样保留，attach 上去就行。 */
        KEEP_SESSION,
    }

    /**
     * @param requestHasCommand 本次请求是否带着要执行的命令
     * @param requestAgentId    命令属于哪个 Agent（安装命令也带；无归属则为 null）
     * @param requestIsLaunch   这条命令是不是"启动该 Agent"（false = 安装 / 清理 / 卸载等）
     * @param currentAgentId    当前这条会话里跑的是谁（null = 没有 / 不知道）
     * @param hasSession        当前会话是否还在（[SessionManager.hasSession]）
     */
    fun decide(
        requestHasCommand: Boolean,
        requestAgentId: String?,
        requestIsLaunch: Boolean,
        currentAgentId: String?,
        hasSession: Boolean,
    ): Action = when {
        // 1. 只是"打开终端"（洞天 tab / 通知栏 / 丹房点「安装中」去看进度）
        //    —— 用户要的是**看现在这一屏**，任何重启都会毁掉他正要看的东西。
        !requestHasCommand -> Action.KEEP_SESSION

        // 2. 同一个 Agent 再点一次启动 —— 接回去，聊到一半的上下文原样保留。
        //    hasSession 是必要条件：会话已经死了（App 被杀过）就没有可接的东西，
        //    必须走重启那条路。
        requestIsLaunch &&
            !requestAgentId.isNullOrBlank() &&
            requestAgentId == currentAgentId &&
            hasSession -> Action.ATTACH_CURRENT

        // 3. 其余全部换掉：换 Agent、安装、清理/卸载命令、会话已死。
        //    维护类命令（清理缓存 / 卸载）也走这条：它们绝不能被打进一个正在跑的
        //    Agent 的交互界面里（会变成发给那个 Agent 的输入），而单会话又只容得下一个。
        else -> Action.REPLACE_SESSION
    }
}
