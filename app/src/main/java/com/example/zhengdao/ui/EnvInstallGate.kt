// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
//
// 第五十二批 · P0-5（2026-10-19 蓝总管派工单）：rootfs 整树替换前的会话守卫。
//
// 为什么需要（第四十八批审计 P0-5，标②：真机破坏效果未实测，但代码里确实没有守卫）：
// `RootfsInstaller.swapLocked` 换树是「旧树改名 → 新树就位 → 旧树递归删除」，全程只持
// 安装锁、不看会话。proot / agent / tmux 还跑着的时候把旧树抽掉，运行中的进程后续
// open/exec 就会 ENOENT —— 当场碎在半路，而且用户完全不知道是自己点了个「更新」。
//
// v1 只做「拒装」：活会话时拦下，并给一句人话；**不自动 kill**（静默杀会话会打断
// 用户正在跑的 agent，「确认后结束会话并继续」是 v2）。
//
// 守卫生在 `ui/` 层而不是 `rootfs/`：`rootfs` 不许反向依赖 `terminal`（分层纪律），
// 而且这几条路径都在设置页，判定放在触发点最直观。中央守卫（所有入口一律拦）留 v2。
package com.example.zhengdao.ui

import com.example.zhengdao.rootfs.RunLog

/**
 * 活会话时的固定文案。
 *
 * 两句话是被测试钉死的（派工单 §1）：必须出现「结束当前终端会话」与「没有改动任何文件」。
 * 后者是这一单最要紧的一句——用户被拦下时最想知道的是「我的环境有没有被弄坏」，
 * 答案必须是明确的「没有」，而不是让他自己猜。
 */
internal const val LIVE_SESSION_BLOCK_MESSAGE: String =
    "终端会话还开着：请先结束当前终端会话（回到终端页输入 exit 或点右上角关闭），再重新点一次。" +
        "本次没有改动任何文件——运行环境一个字节都没动，会话里正在跑的东西也还在。"

/**
 * 纯函数门禁：活会话 ⇒ 固定文案；没有活会话 ⇒ null（放行）。
 *
 * 只吃一个 Boolean、不碰 I/O，JVM 直测（本仓无 Robolectric）。
 */
internal fun liveSessionBlocksEnvInstall(alive: Boolean): String? =
    if (alive) LIVE_SESSION_BLOCK_MESSAGE else null

/**
 * 四条设置页路径共用的判定：`true` = 已拦下（调用方**必须立刻 return**，不许再碰任何 IO）。
 *
 * @param scene 给日志用的一句话（「修复环境」/「环境更新」/「回退环境」），也用于定位漏改的入口
 * @param log 注入日志口，默认落 RunLog；单测传空 lambda（JVM 里 android.util.Log 不可用）
 *
 * 为什么用 `SessionManager.isAlive()` 而不是 `hasSession()`：会话对象在、但进程还没 spawn
 * （或已经死透）时 `hasSession()` 仍为 true，拿它当门禁会**误拒**正常安装；反过来
 * `isAlive()` 只认「进程真在跑」，正是「会 ENOENT 的那批会话」的判据。
 */
internal fun envInstallBlocked(
    alive: Boolean,
    scene: String,
    log: (String) -> Unit = { RunLog.log(it) },
): Boolean {
    val reason = liveSessionBlocksEnvInstall(alive) ?: return false
    log("[env-guard] $scene 被拦：$reason")
    return true
}
