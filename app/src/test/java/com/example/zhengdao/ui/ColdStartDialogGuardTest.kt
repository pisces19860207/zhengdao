// 独立开发声明：本文件为本项目原创。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 冷启动弹框守卫（B-补-19，2026-10-16 立账；**第二百一十批随首启权限机制退役而改写**）。
 *
 * ## 历史（2026-10-11 ~ 10-16）：首启弹框风暴是怎么被收掉的
 *
 * 台账原条目（`docs/FEATURE-LEDGER.md` §B-补 ④）：`- **B-补-19**（P0-3）**冷启动弹窗风暴**：
 * MainActivity.kt 三段独立 runCatching，首启弹 3~4 个框；原文档自记「已放弃」，但**用户从未在
 * 台账层面拍板** ⇒ 要么立账、要么明确不做。2026-10-16 用户拍板「**立账，并做了**」。
 * 那一刀的修法（`permDialogThisLaunch` 局部标记：一次冷启动最多一个系统框、排过系统框就不查
 * 自更新）与 2026-10-11 的首启权限页（`PermissionSetupScreen`）、2026-10-14 的「每项一辈子
 * 只自动问一次」（`shouldAutoRequest` 一次性闸门）一起，在 2026-10-25 被用户的新口径整体取代：
 *
 * ## 现在守什么（第二百一十批 · 入口与权限归位）
 *
 * 用户 2026-10-25：「欢迎页不用放权限申请了」＋「各种权限申请放到设置里弄一个申请权限的卡」
 * ＋「其它页的申请/去开启全部删掉」。落地后冷启动的口径变成一句话：
 * **一次冷启动零个系统权限框**——首启不叠权限页、不代用户请求任何权限；要授权走设置页
 * 「申请权限」卡（全 App 唯一申请入口，源码门禁见 [PermissionEntryGateTest]）。
 *
 * 于是本类从「最多一个框」翻成「零个框」，并钉住三件容易被人悄悄改回去的事：
 * 1. MainActivity 里没有任何 `requestPermissions(` / 通知权限字面量 / 首启权限页判定；
 * 2. 自更新检查回到**无条件发起**（权限框没了，互斥守卫失去存在理由，留着就是死代码）；
 * 3. 签名自检（非官方包提醒）不许被顺手删——它与权限无关，一直在守。
 *
 * 判据走「源码级断言」，与 [LargeFontWrapGuardTest] / [TouchTargetGuardTest] 同惯例；
 * 断言一律读[剥掉整行注释][code]的源码 —— 解释「这里原来有什么」的注释里必然出现
 * `requestPermissions` 这类反例词（B-补-22 首跑踩过这个坑）。
 */
class ColdStartDialogGuardTest {

    private val mainRel = "app/src/main/java/com/example/zhengdao/MainActivity.kt"

    /** 单测的工作目录是模块目录（`app/`），也可能是仓库根。 */
    private fun repoFile(rel: String): File {
        val cands = listOf(File(rel), File("../$rel"))
        return cands.firstOrNull { it.isFile }
            ?: throw AssertionError("找不到 $rel（试过：${cands.joinToString { it.absolutePath }}）")
    }

    private val screen by lazy { repoFile(mainRel).readText() }

    /** 剥掉**整行 `//` 注释**后的源码，判据一律读它（理由见类 KDoc 末段）。 */
    private val code by lazy { screen.replace(Regex("(?m)^[ \\t]*//.*$"), "") }

    @Test
    fun `门禁_210_冷启动零个系统权限框`() {
        // ① 命令式运行时请求一个都不许有（通知那条 101 已随首启机制退役）。
        assertEquals(
            "MainActivity 里不许出现 requestPermissions（首启零权限请求，第二百一十批）",
            0, Regex("requestPermissions\\(").findAll(code).count(),
        )
        // ② 通知权限字面量 / 一次性闸门 / 首启权限页判定，一律不许回来。
        assertFalse("冷启动不许再碰 POST_NOTIFICATIONS", code.contains("POST_NOTIFICATIONS"))
        assertFalse(
            "首启权限页判定（shouldGuide 一族）应随该页一起退役",
            code.contains("shouldGuide") || code.contains("shouldAutoRequest") ||
                code.contains("permissionSetupPending") || code.contains("guidePermissions"),
        )
        // ③ 首启权限页的路由与 Composabler 都不许还在（NavHost 只剩 welcome/home/settings，
        //    与 RouteRestoreGuardTest 的白名单判据两头夹）。
        assertFalse("NavHost 不许再注册 permission_setup", code.contains("permission_setup"))
    }

    @Test
    fun `门禁_210_自更新检查无条件发起_互斥守卫已退役`() {
        // 权限框没了 ⇒ 「排过系统框就不查更新」的守卫失去存在理由。留着一个永真的
        // `if (!permDialogThisLaunch)` 比没有更糟（读代码的人会以为还有系统框要避）。
        // 计数 2 = 定义自身（`private fun checkAppUpdateInBackground() {` 也含这个形状）
        // ＋ 唯一调用点；少一个是调用被删，多一个是互斥守卫复活。
        assertEquals(
            "checkAppUpdateInBackground 只应有一个调用点（外加定义自身）",
            2, Regex("checkAppUpdateInBackground\\(\\)").findAll(code).count(),
        )
        assertTrue(
            "自更新检查必须无条件发起（写回带 permDialogThisLaunch 守卫 = 互斥死代码复活）",
            code.contains("\n        checkAppUpdateInBackground()"),
        )
        assertFalse("permDialogThisLaunch 局部标记应随权限框一起退役", code.contains("permDialogThisLaunch"))
    }

    @Test
    fun `门禁_210_首启兜底框与签名提醒仍在`() {
        // 签名自检与本批无关，一直在守：被顺手删就算回归。
        assertTrue("非官方签名提醒仍在", code.contains("warnIfUnofficialSignature()"))
        assertTrue("非官方签名提醒仍只在 UNOFFICIAL 时弹", code.contains("SigningCheck.Result.UNOFFICIAL"))
        // 134 批：minSdk=36 已在安装层拦住 <36，`SDK_INT < 36` 兜底框是死分支 ⇒ 已删除。
        assertFalse(
            "SDK<36 兜底框已随 minSdk=36 退役（134 批 ObsoleteSdkInt）",
            code.contains("R.string.main_android_too_old_title"),
        )
    }

    @Test
    fun `门禁_210_退役留痕写进源码注释`() {
        // 这一段读**原文**（注释是判据本身，剥掉就没了）：后来人得知道这里曾经有过什么、
        // 为什么没了，否则「把冷启动请求加回来」看起来像修 bug。
        assertTrue(
            "必须写明首启权限机制已退役（第二百一十批）",
            screen.contains("第二百一十批（入口与权限归位）：首启权限机制整体退役"),
        )
        assertTrue(
            "必须写明冷启动不再发起任何权限请求",
            screen.contains("冷启动不再发起任何权限请求"),
        )
        assertTrue(
            "必须写明要授权走设置页「申请权限」卡",
            screen.contains("要授权走设置页「申请权限」卡"),
        )
    }
}
