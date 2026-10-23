// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * B-补-19（2026-10-16）：冷启动弹框守卫 —— **一次冷启动最多一个系统框**。
 *
 * 台账原条目（`docs/FEATURE-LEDGER.md` §B-补 ④）：`- **B-补-19**（P0-3）**冷启动弹窗风暴**：
 * MainActivity.kt 三段独立 runCatching，首启弹 3~4 个框；原文档自记「已放弃」，但**用户从未在
 * 台账层面拍板** ⇒ 要么立账、要么明确不做`。2026-10-16 用户拍板「**立账，并做了**」（见
 * 本类 KDoc 末尾的落账口径），故本刀既立账也真修。
 *
 * 复核后剩下的真缺陷（2026-10-16，逐段读 `MainActivity.kt` 冷启动路径）：
 * - 2026-10-11 那刀已经把「首启一次弹好几个系统框」收成一页 `PermissionSetupScreen`
 *   （`shouldGuide` 为真时下面两个请求一个都不排）⇒ **首启不再有风暴**；
 * - 2026-10-14 那刀给两项权限各自加了「一辈子只自动问一次」的一次性标记（`storageAsked` /
 *   `notifyAsked`）⇒ **同一个框不会每次冷启动重复弹**；
 * - 剩下的问题：两段请求是**互不相干**的 `runCatching`，所以「已引导过（例如权限页点了
 *   『稍后再说』）＋ 两项都没问过」的那一次冷启动会把**两个系统框连着排出去**；而自更新
 *   `AlertDialog` 是同一次冷启动里第 3 个可能出现的框 —— 更新检查走网络后台线程，返回通常
 *   晚于系统框弹出，表现就是「权限框刚点掉、更新框又贴上来」。系统框是系统的独立窗口，
 *   应用内先守 `isFinishing/isDestroyed` 拦不住它。
 *
 * 本刀修法（最小改动，判据都写进源码注释）：`MainActivity.onCreate` 的权限块里加一个
 * **本次冷启动的**局部标记 `permDialogThisLaunch`：① 排过一个系统框就不再排第二个（没排的那项
 * 下次冷启动照常补，一次性标记保证不会无限补）；② 本次排了系统框就不发起自更新检查。
 *
 * 判据走「源码级断言」，与 [LargeFontWrapGuardTest] / [TouchTargetGuardTest] 同惯例：
 * 本模块没有 UI 测试基建（弹框数只能上真机数），而"排了几个框"完全由源码的条件决定，
 * 源码就是唯一真源。断言一律读[剥掉整行注释][code]的源码 —— 解释缺陷的注释里必然出现
 * `requestPermissions` / `checkAppUpdateInBackground` 这类反例词（B-补-22 首跑踩过这个坑）。
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

    /** 按花括号配平，抽 `marker` 之后的那一个 `{ … }`（含最外层花括号）。 */
    private fun bracedBlockAfter(marker: String): String {
        val start = code.indexOf(marker)
        assertTrue("$mainRel 里找不到锚点：$marker", start >= 0)
        val open = code.indexOf('{', start)
        var depth = 0
        var i = open
        while (i < code.length) {
            when (code[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return code.substring(open, i + 1)
                }
            }
            i++
        }
        throw AssertionError("锚点 $marker 之后的花括号不配平")
    }

    /** 冷启动权限块：`if (!permissionSetupPending) { … }` 的块体。 */
    private val permBlock by lazy { bracedBlockAfter("if (!permissionSetupPending) {") }

    @Test
    fun `门禁_B补19_一次冷启动最多只排一个系统权限框`() {
        // 134 批：minSdk=36 ⇒ 存储读写请求（100）已是死分支并删除，只剩通知（101）一个请求点。
        // 守卫的语义不变：同一屏只排一个，且请求前必须看本次冷启动标记。
        assertEquals(
            "冷启动只剩通知一个系统权限请求点（101）",
            1, Regex("requestPermissions\\(").findAll(permBlock).count(),
        )
        // 通知必须在本次冷启动标记为"没排过"时才排
        val notifyAt = permBlock.indexOf("requestPermissions(this, arrayOf(perm), 101)")
        assertTrue("找不到通知权限请求点（101）", notifyAt > 0)
        val guard = permBlock.lastIndexOf("if (!permDialogThisLaunch)", notifyAt)
        assertTrue("通知权限请求前必须有一次 `if (!permDialogThisLaunch)` 守卫", guard > 0)
        assertEquals(
            "请求点要置位本次冷启动标记",
            1, Regex("permDialogThisLaunch = true").findAll(permBlock).count(),
        )
    }

    @Test
    fun `门禁_B补19_更新框不得与系统权限框同一次冷启动`() {
        // 更新检查在权限块内只出现一次，且带本次冷启动标记守卫
        assertEquals(
            "权限块内自更新检查只能有一个调用点",
            1, Regex("checkAppUpdateInBackground\\(\\)").findAll(permBlock).count(),
        )
        assertTrue(
            "自更新检查必须写成 `if (!permDialogThisLaunch) checkAppUpdateInBackground()`",
            permBlock.contains("if (!permDialogThisLaunch) checkAppUpdateInBackground()"),
        )
        // 顺序：先排系统框（通知请求），最后才谈更新 —— 不许再回到"更新检查排在最前"
        val notifyAt = permBlock.indexOf("requestPermissions(this, arrayOf(perm), 101)")
        val updateAt = permBlock.indexOf("checkAppUpdateInBackground()")
        assertTrue("找不到通知权限请求点（101）", notifyAt > 0)
        assertTrue("自更新检查必须在系统权限请求之后", updateAt > notifyAt)
    }

    @Test
    fun `门禁_B补19_本次冷启动标记必须是局部变量而不是常驻开关`() {
        // 局部 `var`：只收敛"这一次冷启动"，绝不能被改成字段/常量而把权限框永久压掉
        assertTrue(
            "标记必须是权限块内的局部 `var permDialogThisLaunch = false`",
            permBlock.contains("var permDialogThisLaunch = false"),
        )
        assertFalse(
            "标记不得提升为类字段（那会让权限框再也排不出来）",
            code.contains("private var permDialogThisLaunch") ||
                code.contains("private val permDialogThisLaunch") ||
                code.contains("companion object") && code.contains("permDialogThisLaunch: Boolean = false"),
        )
    }

    @Test
    fun `门禁_B补19_权限页互斥与两项一次性标记仍在`() {
        // 2026-10-11 那刀的口径：首启引导页为真时，系统框一个都不排
        assertTrue(
            "首启判定必须仍在（PermissionSetup.shouldGuide）",
            code.contains("val permissionSetupPending = PermissionSetup.shouldGuide(this)"),
        )
        // 2026-10-14 那刀的口径：每项权限一辈子最多自动问一次。
        // 134 批：存储读写请求在 minSdk=36 下已死，存储那段"两处标记"随分支一起删除；
        // 通知那半仍在，断言照旧（并由 PermissionGuideTest 的纯逻辑用例兜住语义）。
        assertFalse(
            "存储读写自动请求（100）是死分支，134 批已删（minSdk=36）",
            code.contains("requestPermissions(this, needed.toTypedArray(), 100)"),
        )
        assertTrue(
            "通知权限仍要判一次性标记",
            permBlock.contains("PermissionSetup.shouldAutoRequest(PermissionSetup.notifyAsked(this), missing)"),
        )
        assertTrue(
            "markNotifyAsked 必须在通知请求之前",
            permBlock.indexOf("PermissionSetup.markNotifyAsked(this)") > 0 &&
                permBlock.indexOf("PermissionSetup.markNotifyAsked(this)") <
                permBlock.indexOf("requestPermissions(this, arrayOf(perm), 101)"),
        )
    }

    @Test
    fun `门禁_B补19_首启兜底框与签名提醒仍在`() {
        // 134 批：minSdk=36 已在安装层拦住 <36，`SDK_INT < 36` 兜底框是死分支 ⇒ 删除
        // （对应两条文案 main_android_too_old_* 一并退役）。签名提醒不许被顺手删。
        assertFalse(
            "SDK<36 兜底框已随 minSdk=36 退役（134 批 ObsoleteSdkInt）",
            code.contains("R.string.main_android_too_old_title"),
        )
        assertTrue("非官方签名提醒仍在", code.contains("warnIfUnofficialSignature()"))
        assertTrue("非官方签名提醒仍只在 UNOFFICIAL 时弹", code.contains("SigningCheck.Result.UNOFFICIAL"))
    }

    @Test
    fun `门禁_B补19_立账口径与判据写进源码注释`() {
        // 这一条读**原文**（注释是判据本身，剥掉就没了）
        assertTrue(
            "必须写明用户 2026-10-16 拍板「立账，并做了」",
            screen.contains("B-补-19 立账（2026-10-16，用户拍板「立账，并做了」）"),
        )
        assertTrue(
            "必须写明「一次冷启动最多一个系统框」的取舍",
            screen.contains("一次冷启动最多一个系统框"),
        )
        assertTrue(
            "必须写明「最多跳过一个冷启动」的理由（系统框一辈子只排一次）",
            screen.contains("最多跳过一个冷启动"),
        )
    }
}
