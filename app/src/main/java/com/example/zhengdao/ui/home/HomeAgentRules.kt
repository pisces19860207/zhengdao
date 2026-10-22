// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui.home

/**
 * 第七十三批 · HomeScreen 结构切片 ①②③ —— Agent 卡片的**判据**（纯逻辑）。
 *
 * 这个文件只回答「该显示哪一个」，不回答「长什么样」：按钮的形状/文案/点击后跑什么命令、
 * 状态条用哪个颜色、菜单项怎么写，全部留在 `ui/HomeScreen.kt` 的调用处（等值搬移，
 * 判据的优先序与布尔条件逐字照搬，一条都没改）。
 *
 * 入参与出参都是普通 Kotlin 值（Boolean / 枚举），不碰 `Context`、`AgentRepository`、
 * Compose state —— 所以它能在 JVM 上直测（`app/src/test/.../ui/home/HomeAgentRulesTest.kt`）。
 */

/** Agent 卡片右侧的主操作按钮（六臂 `when` 的六个出口）。 */
internal enum class HomePrimaryAction {
    /** 上次安装失败且环境还在：给「重试安装」。 */
    RetryInstall,

    /** 已安装：给「启动」。 */
    Launch,

    /** 安装标记还在（或有安装命令且环境就绪）：给可点的「安装中」，跳终端看进度。 */
    Installing,

    /** 未安装、有安装命令、环境就绪：给「安装」。 */
    Install,

    /** manifest 里没给安装命令：给禁用的「暂未提供」。 */
    ComingSoon,

    /** 有安装命令但环境没装：给禁用的「先装环境」。 */
    InstallEnvFirst,
}

/**
 * 主操作按钮的判据。优先序**照搬**原 `when`，两处顺序都是有原因的，别当冗余 simplify：
 *
 * 1. `RetryInstall` 排在 `installed` **前面**：安装挂在最后一步时可执行文件往往已经落位
 *    （2026-10-07 hermes 真机实测），那时按 installed 给「启动」会把用户往坏入口上引；
 *    但要带 `envReady`——环境没了时重试安装无从谈起，该落到「先装环境」。
 * 2. `Installing` 排在「安装」前面且不给禁用按钮（2026-10-07 血案）：安装中标记只有
 *    「文件出现」或「15 分钟 TTL」两条清除出口，禁用按钮会让人分不清"没点到/在忙/坏了"。
 *
 * 详细的历史注释仍写在 [com.example.zhengdao.ui.HomeScreen] 各分支上方，原样未动。
 */
internal fun homePrimaryActionOf(
    failedInstall: Boolean,
    installed: Boolean,
    installing: Boolean,
    hasInstallCmd: Boolean,
    envReady: Boolean,
): HomePrimaryAction = when {
    failedInstall && envReady -> HomePrimaryAction.RetryInstall
    installed -> HomePrimaryAction.Launch
    installing -> HomePrimaryAction.Installing
    hasInstallCmd && envReady -> HomePrimaryAction.Install
    !hasInstallCmd -> HomePrimaryAction.ComingSoon
    else -> HomePrimaryAction.InstallEnvFirst
}

/** 卡片描述下方的状态行（四选一；`None` = 整段不渲染）。 */
internal enum class HomeStatusLine {
    /** 安装失败：浅底红字提示条，文案由安装状态侧给（分清「带退出码」与「被打断」）。 */
    Failure,

    /** 安装中：浅底提示条，预告"最后一步是 Agent 自己的配置向导"（E-060）。 */
    InstallHint,

    /** 已装且有本地探测到的版本号：素色小字。 */
    Version,

    /** 三条都不满足：什么都不显示。 */
    None,
}

/**
 * 状态行的判据（等值搬移原 `if / else if` 链）。
 *
 * `Failure` 排第一是刻意的：安装失败时卡片既可能 `installed`（文件已落位）也可能带着版本号，
 * 但"装坏了"比"装好了"更需要先说出来。
 */
internal fun homeStatusLineOf(
    failedInstall: Boolean,
    installing: Boolean,
    installed: Boolean,
    hasInstalledVersion: Boolean,
): HomeStatusLine = when {
    failedInstall -> HomeStatusLine.Failure
    installing -> HomeStatusLine.InstallHint
    installed && hasInstalledVersion -> HomeStatusLine.Version
    else -> HomeStatusLine.None
}

/**
 * 「更多」（⋮）按钮是否出现：已装，且有卸载或安装命令之一的才给（E-059 起菜单里不止「卸载」，
 * 所以有安装命令的也要显示——原先只认 `uninstallCmd`，已装但没卸载命令的 Agent 就没有菜单）。
 */
internal fun homeMoreMenuVisible(
    installed: Boolean,
    hasUninstallCmd: Boolean,
    hasInstallCmd: Boolean,
): Boolean = installed && (hasUninstallCmd || hasInstallCmd)

/** 菜单里「重新安装」一项是否出现：有安装命令且环境就绪（环境没了重装无从谈起）。 */
internal fun homeReinstallItemVisible(hasInstallCmd: Boolean, envReady: Boolean): Boolean =
    hasInstallCmd && envReady

// ═══ 首页入口白名单（第一百四十二批 · 2026-10-22） ═══

/**
 * 首页 Agent 入口白名单：Claude Code / Hermes Agent / AGY CLI / Codex CLI 四件。
 *
 * **决策链（覆盖记录，别当冗余注释）**：
 * - 2026-10-18 第十八批 B 段旧定稿：filter 只认 `id == "hermes"`，其余三件从首页收起
 *   （原文留在 `HomeScreen.kt` 该处注释里备查）；
 * - 2026-10-22 用户 09:22「都恢复」⇒ 《抱朴设置重构方案-2026-10-22.md》§10 拍板四件全恢复，
 *   按 L2 局部变更处理：旧定稿原文留存，本白名单为新决策。
 *
 * **为什么是白名单而不是「注册表里有什么就展什么」**：manifest 可以追加未知 id 的条目
 * （注册表层面照单全收，供版本探测/检测区用），但**首页入口**只放这四个——未知条目没有
 * 经过安装/卸载/数据目录的适配，展出来就是让用户点一个可能跑不起来的按钮。要展新 agent
 * 是改这个白名单的决定，不是 manifest 单方面能推的。
 *
 * OpenCode 不在内：它已内置为抱朴的模型通道，本来就不作为终端 agent 展示（旧注释口径不变）。
 */
internal val HOME_AGENT_ENTRY_IDS: List<String> =
    listOf("claude-code", "hermes", "antigravity", "codex")

/**
 * 首页 Agent 入口是否可见（白名单判据）。
 *
 * 纯函数：入参一个 id，出参一个 Boolean——manifest 追加的未知 id、历史 id（opencode）
 * 一律不进首页。调用方 `items(agents.filter { homeEntryVisible(it.id) }, …)`。
 */
internal fun homeEntryVisible(agentId: String): Boolean = agentId in HOME_AGENT_ENTRY_IDS
