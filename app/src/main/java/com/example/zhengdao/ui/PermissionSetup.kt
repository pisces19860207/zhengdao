// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import android.content.Context
import android.content.pm.PackageManager
import android.os.Environment
import androidx.core.content.ContextCompat

/**
 * 权限判据的既有单一来源（2026-10-11 起；第二百一十批收敛后只剩三项）。
 *
 * ## 这个文件原来叫什么、为什么只剩这些
 *
 * 原文件 `ui/PermissionSetupScreen.kt` = 首启「权限」页（一屏列全几项权限、逐项点开授权）
 * ＋ 本 object。2026-10-25 用户三条要求（欢迎页不碰权限、申请入口统一进设置页「申请权限」卡）
 * 落地时**整页退役**：首启不再叠权限页、冷启动不再请求任何权限，页面的 Composable 与
 * 「已引导过 / 已自动问过」那一套 prefs 机制（guided / shouldGuide / shouldAutoRequest /
 * *_Asked / markGuided…）随同删除——没有调用方了，留着就是审计点名的「零引用函数」。
 * 页面位置的历史与判据留痕见 `docs/FEATURE-LEDGER.md` 与 ERRATA。
 *
 * 剩下的三项都还有人用，且都是**判定**（状态显示），不是申请入口：
 * - [notificationsGranted]：`SchedulePermissions` 委托它（调度与设置页同一结论）；
 * - [manageAllFilesGranted]：设置页「申请权限」卡的第一行状态；
 * - [NOTIFICATIONS_PERMISSION]：通知权限字面量的唯一一份（申请点在「申请权限」卡）。
 *
 * ⚠️ 判据不许改：这三项的判定逻辑自 2026-10-11 起一字未动（本次只删了没人用的部分）。
 * 要动判定先读 ScheduleRedLineGateTest 与 StoragePermissionGuardTest 钉的是什么。
 */
object PermissionSetup {

    /**
     * 通知权限的字面量（与清单里声明的那一份一字不差）。
     * 全文件只此一处：判定与申请都用它，免得两处各写一遍字符串、改一处漏一处。
     */
    const val NOTIFICATIONS_PERMISSION = "android.permission.POST_NOTIFICATIONS"

    /**
     * 「所有文件访问」（MANAGE_EXTERNAL_STORAGE）是否已开启。
     *
     * 第㉕刀（2026-10-15）起真话变了：targetSdk 28 时代该开关**只对 target≥30 的 App 存在**、
     * 系统对证道永远返回 false（2026-10-04 修复记录：此前用它做存储判定，恒 false），所以
     * 这一行当时只作**可选增强**展示而绝不参与「必需项就绪」判定。抬到 36 后开关**可达**，
     * 且它就是共享存储的唯一通道（READ/WRITE 运行时权限在 30+ 已 inert）。
     */
    fun manageAllFilesGranted(): Boolean =
        runCatching { Environment.isExternalStorageManager() }.getOrDefault(false)

    /**
     * 通知权限（POST_NOTIFICATIONS，安卓 13+ 运行时权限，AndroidManifest.xml 已声明）。
     *
     * 这里只是**展示/判定状态**：申请点只在设置页「申请权限」卡（第二百一十批唯一化），
     * 本函数不发起任何请求。
     */
    fun notificationsGranted(ctx: Context): Boolean = runCatching {
        ContextCompat.checkSelfPermission(ctx, NOTIFICATIONS_PERMISSION)
    }.getOrDefault(PackageManager.PERMISSION_DENIED) == PackageManager.PERMISSION_GRANTED
}
