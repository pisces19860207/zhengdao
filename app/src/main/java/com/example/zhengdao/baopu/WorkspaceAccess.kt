// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第一百五十六批：放开工作区边界（用户批准即可）。用户口径逐字：
// 「现在我觉得不应该限制他的工作目录或者工作区了，用户觉得可以那就是可以」。
//
// 语义不是删掉保护，而是把「硬拒」改成「问一次，用户同意就做」：
// - 工作区内 ⇒ 行为不变（INSIDE）；
// - 工作区外且用户在设置里关掉了这个能力 ⇒ DENIED（原文拒绝，与 156 批之前逐字一致）；
// - 工作区外且开关开、本会话还没授权过 ⇒ ASK_USER（管线复用现有确认卡，不新造 UI）；
// - 本会话里用户已经在确认卡上批准过同一工作区根 ⇒ 放行（157 补充：**一次会话问一次**，
//   授权按 sessionId 落库、重启 App 不再问；账本搬去 `WorkspaceGrant.kt`）。
//
// 红线（派工单 156 §0）：不许静默放行——ASK_USER 永远先过一次用户的卡；
// `PathGuard` 本身一个字不改（回收站/备份/原件区的越界保护仍靠它）。
package com.example.zhengdao.baopu

import java.io.File

/** 工作区外访问的判据结论（第一百五十六批；纯函数层，JVM 直测）。 */
enum class AccessVerdict {

    /** 在工作区内，或本会话已获用户授权 ⇒ 放行（行为与 156 批之前的工作区内路径一致）。 */
    INSIDE,

    /** 在工作区外、开关开、还没问过 ⇒ 走一次用户确认（管线复用确认卡）。 */
    ASK_USER,

    /** 在工作区外且用户关掉了这个能力 ⇒ 原文拒绝（文案与 156 批之前逐字一致）。 */
    DENIED,
}

/**
 * 判据内核（**纯函数**：不碰 Android API、不读全局、不写盘，JVM 直测）。
 *
 * 四象限（派工单 156 §1 原样）：
 * - `target` 在 `root` 内 ⇒ INSIDE（行为不变）；
 * - 在工作区外且 `settingOn=false` ⇒ DENIED（用户关掉这个能力）；
 * - 在工作区外且 `settingOn=true` 且 `userGranted=false` ⇒ **ASK_USER**；
 * - 已在本次**会话**里授权过（`userGranted=true`）⇒ 放行（按 INSIDE 口径返回：
 *   「放行」与「在内」对调用方是同一种处置，枚举不再加第四个值）。
 *
 * 边界判定复用 `rootfs.PathGuard.isInside`（组件级比较），**不另造第二套判据**。
 */
fun workspaceAccessVerdict(root: File, target: File, userGranted: Boolean, settingOn: Boolean): AccessVerdict {
    val inside = runCatching {
        com.example.zhengdao.rootfs.PathGuard.isInside(root, target)
    }.getOrDefault(false)
    return when {
        inside -> AccessVerdict.INSIDE
        !settingOn -> AccessVerdict.DENIED
        userGranted -> AccessVerdict.INSIDE
        else -> AccessVerdict.ASK_USER
    }
}

/**
 * 生产接线的读数点（工具层自检用）——仿 `AskUserTool.sink` 的进程级注入先例：
 * 对象自身零业务逻辑，两个读数闭包由装配层（`BaopuRuntime.build`）一次性喂入。
 *
 * 为什么是全局读数而不是给每个工具的纯函数加参数：工具的判据块在**执行线程**上现读，
 * 设置页改开关、用户点批准都即时生效（与组开关「下一轮生效」同口径）；默认值
 * `{ false }`/`{ false }` 保证存量 JVM 直测（没接线的环境）行为与 156 批之前逐字一致。
 */
internal object WorkspaceAccessPolicy {

    /** 设置项「允许访问工作区外」现值（生产 = `WorkspacePrefs.enabled(appCtx)`；默认关 ⇒ 存量行为）。 */
    @Volatile
    var settingOn: () -> Boolean = { false }

    /** 本会话授权账本读数（生产 = `WorkspaceGrants::granted`；默认永假 ⇒ 存量行为）。 */
    @Volatile
    var granted: (File) -> Boolean = { false }

    /** 工具层自检的统一入口（判据本体在 [workspaceAccessVerdict]，这里只拼读数）。 */
    fun verdict(root: File, target: File): AccessVerdict =
        workspaceAccessVerdict(root, target, granted(root), settingOn())
}

/**
 * 工具层「工作区边界自检」的**统一出口**（第一百五十六批；替换各工具里的
 * `if (!PathGuard.isInside(...)) reject` 一律改为调这里）。
 *
 * 三分支映射只写这一遍（十个工具各抄一遍就是十条语义）：
 * - INSIDE ⇒ null（放行，调用方继续）；
 * - ASK_USER ⇒ 拒绝文案（**生产不可达**：管线 ② 段在工具执行前必先弹卡并把授权记进
 *   [WorkspaceGrants]，执行线程到这里读到的已是「已授权」；这一支兜的是「绕过管线
 *   直调工具」的异常形态——宁可拒，不许静默放行）；
 * - DENIED ⇒ [deniedReason]（各工具的**原文**照旧传入，156 批之前的文案逐字不动）。
 *
 * @return null = 放行；非 null = 给模型的拒绝原文（调用方包成 `ToolOutcome.Rejected`）
 */
internal fun workspaceBoundaryCheck(root: File, target: File, shown: String, deniedReason: String): String? =
    when (WorkspaceAccessPolicy.verdict(root, target)) {
        AccessVerdict.INSIDE -> null
        AccessVerdict.ASK_USER ->
            "需用户批准：$shown 在工作区（${root.absolutePath}）外。" +
                "工作区外访问要先过一次用户确认，未经批准已拒绝。"
        AccessVerdict.DENIED -> deniedReason
    }
