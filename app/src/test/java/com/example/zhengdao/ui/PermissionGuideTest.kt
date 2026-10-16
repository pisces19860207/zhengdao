// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * §B 顺序 15「冷启动每次都申请权限 + 弹通知框」的验收（2026-10-14）。
 *
 * ## 先说清本刀修什么（避免与审计报告的说法混淆）
 * 台账/报告原标题写的是「写过 `KEY_GUIDED` 后 `shouldGuide` **恒 false**」，那是**旧文件
 * `ui/PermissionUi.kt` 的实现**。现在的 `PermissionSetup.shouldGuide`（在本包的
 * `PermissionSetupScreen.kt` 里）已是 `!guided && missingRequired`，**不再恒 false** ——
 * 「按当前缺哪些权限判断」那一半早在 v2.0.14（`084c811`）就做到了。
 *
 * 真正造成"每次都弹"的是**另一条通道**：`MainActivity` 冷启动那段在「已引导过」时
 * **逐个补请求缺的权限**，而它的判据只看"缺不缺" —— 用户拒绝过（或系统永远不给，
 * 如 targetSdk=28 在部分 ROM 上拿不到共享存储），就会**每次冷启动再弹一次系统框**。
 *
 * ## 修法与两半验收
 * 1. **纯逻辑**（可行为断言）：`PermissionSetup.shouldAutoRequest(asked, missing)`
 *    = 缺权限 **且** 本项没自动问过 ⇒ **每项权限最多自动问一次**；
 * 2. **接线**（文本门禁）：MainActivity 真的走了这个闸门、更新检查真的被权限判定挡住、
 *    权限页返回键真的写了标记。两半缺一不成立。
 *
 * ## 判据
 * 台账原文：清应用数据后连续冷启动 5 次，不得自动弹框。
 * ⚠️ 精确化（已写进 commit 正文）：第 1 次**允许**弹一次权限页（首启引导是设计意图，
 * 不是缺陷）；此后（无论用户点按钮、按返回、还是拒绝授权）连续 4 次不得再自动弹任何框。
 *
 * 真机读数归蓝总管采。
 */
class PermissionGuideTest {

    // ── 第一半：纯逻辑 ────────────────────────────────────────────────────

    @Test
    fun `缺权限且没问过_才自动请求`() {
        assertTrue("首次缺权限 —— 该问这一次", PermissionSetup.shouldAutoRequest(asked = false, missing = true))
    }

    @Test
    fun `问过一次之后_再缺也不再自动弹`() {
        assertFalse(
            "已经自动问过一次就不该再弹（这就是「每次都弹」的修法）",
            PermissionSetup.shouldAutoRequest(asked = true, missing = true)
        )
    }

    @Test
    fun `不缺权限时不请求_不论问没问过`() {
        assertFalse(PermissionSetup.shouldAutoRequest(asked = false, missing = false))
        assertFalse(PermissionSetup.shouldAutoRequest(asked = true, missing = false))
    }

    // ── 第二半：接线（文本门禁） ──────────────────────────────────────────

    /** Gradle 单测的工作目录是模块目录 `app/`（与 [TabIndexOrderTest] 同一约定）。 */
    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `MainActivity_两处自动请求都过 shouldAutoRequest 闸门`() {
        val s = src("src/main/java/com/example/zhengdao/MainActivity.kt")
        assertTrue(
            "存储权限的自动请求必须过 shouldAutoRequest 闸门（否则拒绝过就每次冷启动再弹）",
            s.contains("PermissionSetup.shouldAutoRequest(PermissionSetup.storageAsked(this)")
        )
        assertTrue(
            "通知权限的自动请求必须过 shouldAutoRequest 闸门",
            s.contains("PermissionSetup.shouldAutoRequest(PermissionSetup.notifyAsked(this)")
        )
        assertTrue(
            "请求前必须先写标记（请求中途被杀也不会重问）",
            s.contains("PermissionSetup.markStorageAsked(this)")
        )
        assertTrue(s.contains("PermissionSetup.markNotifyAsked(this)"))
    }

    @Test
    fun `更新检查被权限判定挡住_不再无条件发起`() {
        val s = src("src/main/java/com/example/zhengdao/MainActivity.kt")
        // B-补-19（2026-10-16）起调用点前面多了「本次冷启动没排过系统权限框」这一层守卫
        // （`if (!permDialogThisLaunch) `），因此本测试不再匹配"行首缩进 + 调用"的裸调用形态：
        // 那条形态现在**必须在源码里不存在**（无守卫的调用正是要防的回归）。
        val guarded = "if (!permDialogThisLaunch) checkAppUpdateInBackground()"
        val callIdx = s.indexOf(guarded)
        assertTrue("找不到 checkAppUpdateInBackground 的调用点（必须写成 `$guarded`）", callIdx > 0)
        assertFalse(
            "不许出现无守卫的调用点（那会让更新框与系统权限框抢同一屏）",
            s.contains("\n            checkAppUpdateInBackground()")
        )
        val before = s.substring(0, callIdx)
        val lastIf = before.lastIndexOf("if (!permissionSetupPending)")
        assertTrue("更新检查必须写在 `if (!permissionSetupPending)` 块内（与权限框互斥）", lastIf > 0)
        // 位置判据（B-补-19 升级）：原先要求"紧跟权限判定、中间不隔别的逻辑"；现在判据反过来 ——
        // 必须排在**两个权限请求之后**，因为守卫要读到那两段是否已经排了系统框。
        assertTrue(
            "更新检查必须排在存储权限请求（100）之后",
            before.lastIndexOf("requestPermissions(this, needed.toTypedArray(), 100)") > lastIf
        )
        assertTrue(
            "更新检查必须排在通知权限请求（101）之后",
            before.lastIndexOf("requestPermissions(this, arrayOf(perm), 101)") > lastIf
        )
    }

    @Test
    fun `权限页返回键写已引导标记`() {
        val s = src("src/main/java/com/example/zhengdao/ui/PermissionSetupScreen.kt")
        val idx = s.indexOf("BackHandler {")
        assertTrue("权限页必须有 BackHandler（否则返回不写标记、下次冷启动又弹）", idx > 0)
        val block = s.substring(idx, (idx + 260).coerceAtMost(s.length))
        assertTrue("BackHandler 里必须写已引导标记", block.contains("markGuided(ctx)"))
    }
}
