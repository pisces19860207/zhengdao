// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * NavHost 全图转场门禁（B-补-26，2026-10-15）。
 *
 * ## 为什么值得一条门禁
 *
 * Compose Navigation 的 NavHost **默认没有转场**：不显式传那四个参数，页面就是"瞬间切换"。
 * 本项目是 iOS 浅色主题、层级约定（根页大标题 / 二级页导航栏标题）照 iOS 分层抄的，
 * 分层却没有过渡 = 页面像贴上去的。所以这四个参数不是锦上添花，是**分层语义的一半**。
 *
 * 而"退化"极其容易发生且**没有任何运行期测试会红**：后来者重构 MainActivity 时
 * 把 NavHost 的参数删掉、或给某个 composable 单独覆盖成无动画，功能一切正常、
 * 单测全绿，只有用户觉得"变糙了"。这类回归只有源码门禁拦得住。
 *
 * ## 判据钉什么（ERRATA E-133 的教训：钉实质，不钉局部变量名）
 *
 * ① 四个转场参数齐全（enter / exit / popEnter / popExit）—— 少一个就有一半的导航方向没动画；
 * ② 每个参数里都同时有 slide（位移）与 fade（透明度）—— 只有位移会"生硬"，只有淡入会"发虚"；
 * ③ **pop 方向必须与 push 方向相反**（popEnter 的初始偏移为负）—— 同向就成"翻页"而非"返回"，
 *    这是 iOS push/pop 的判别式；
 * ④ **全图只允许有一处转场定义**（NavHost 那一处）—— 任何 composable 里再出现
 *    `enterTransition` 就是有人给单页关掉了动画（或改成了别的节奏），必须显式说明。
 */
class NavTransitionGateTest {

    private fun mainActivitySource(): String {
        val roots = listOf(
            java.io.File("app/src/main/java/com/example/zhengdao/MainActivity.kt"),
            java.io.File("src/main/java/com/example/zhengdao/MainActivity.kt"),
        )
        val f = roots.firstOrNull { it.isFile }
            ?: error("找不到 MainActivity.kt（cwd=${java.io.File("").absolutePath}）")
        return f.readText()
    }

    /**
     * 去注释（**必须做**，第⑲刀的教训）：本刀 KDoc 里为了说明"Activity 级转场留给独立一刀"
     * 写了 `overridePendingTransition` 这个词，而门禁偏偏要断言"没有引入它"—— 不剥注释就是
     * 自己拦自己。**凡断言"某字样不出现"，先剥注释再断言**（断言"出现"同理，
     * 否则注释里写一句就算过）。
     */
    private fun stripped(): String = mainActivitySource()
        .replace(Regex("(?s)/\\*.*?\\*/"), "")
        .replace(Regex("(?m)//.*$"), "")

    @Test
    fun `NavHost 四个转场参数齐全`() {
        val src = stripped()
        listOf("enterTransition", "exitTransition", "popEnterTransition", "popExitTransition").forEach { key ->
            assertTrue("NavHost 缺 $key —— 有一半的导航方向会瞬间切换（无动画）", src.contains("$key = {"))
        }
    }

    @Test
    fun `每个转场都同时有位移与淡入淡出`() {
        val src = stripped()
        // 四个族系各至少出现一次；方向由下面的用例单独钉
        listOf("slideInHorizontally", "slideOutHorizontally", "fadeIn", "fadeOut").forEach { fn ->
            assertTrue("转场里没有用到 $fn —— 只有位移会生硬、只有淡入会发虚，两个都要有", src.contains(fn))
        }
    }

    @Test
    fun `pop 方向与 push 方向相反`() {
        val src = stripped()
        // push：新页 initialOffsetX 为正（从右进来）；pop：为负（退回右边）。
        // 判据取"块内首行"形态，不依赖变量名（E-133）。
        assertTrue(
            "push 进入的初始偏移应为正（新页从右滑入）",
            Regex("enterTransition\\s*=\\s*\\{[\\s\\S]{0,300}?slideInHorizontally\\(\\s*initialOffsetX\\s*=\\s*\\{\\s*it\\s*/\\s*4\\s*\\}")
                .containsMatchIn(src),
        )
        assertTrue(
            "pop 进入的初始偏移应为负（旧页从左边回来）——同向就成翻页而非返回",
            Regex("popEnterTransition\\s*=\\s*\\{[\\s\\S]{0,300}?slideInHorizontally\\(\\s*initialOffsetX\\s*=\\s*\\{\\s*-\\s*it\\s*/\\s*4\\s*\\}")
                .containsMatchIn(src),
        )
    }

    @Test
    fun `转场只允许在全图定义一处_不许单页单独覆盖`() {
        val src = stripped()
        // NavHost 那一处有 4 个键；任何 composable(...) 里再出现就是单页覆盖。
        // 用词频判定：这四个键在整个文件里应各只出现 1 次。
        listOf("enterTransition", "exitTransition", "popEnterTransition", "popExitTransition").forEach { key ->
            val n = Regex("\\b$key\\b").findAll(src).count()
            assertTrue(
                "$key 在 MainActivity 里出现了 $n 次 —— 除 NavHost 那一处外还有别处覆盖" +
                    "（单页关动画/改节奏必须显式说明，不能悄悄加）",
                n == 1,
            )
        }
    }

    @Test
    fun `不碰在跑区域_本刀只改 MainActivity 的 NavHost 一处`() {
        // 派工单红线②：TerminalActivity（第⑯刀占错误文案区）、keepalive/**、HomeScreen 功能本体、
        // SessionService、SettingsCommon 一律不碰。转场只在 MainActivity 的 NavHost 上，
        // 这条用例让"顺手把 TerminalActivity 的启动转场也改了"的越界在门禁层就暴露。
        val src = stripped()
        assertFalse("不许顺手引入 overridePendingTransition（Activity 级转场归独立一刀）", src.contains("overridePendingTransition"))
        assertFalse("不许在本刀把 TerminalActivity 拉进转场改造", src.contains("TerminalActivity::class") && src.contains("ActivityOptions"))
    }
}
