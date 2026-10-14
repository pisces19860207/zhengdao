// 独立开发声明：本文件为本项目从零编写。被测类是 Termux 官方 terminal-view
// （Apache-2.0，见 PROVENANCE.md）**衍生**的 `TerminalGestureController`
// （本项目的 6 个"沿用 com.termux.view 包名的第一方类"之一，非上游文件）；
// 本测试钉的是 2026-10-12 的两处手势守卫（§B-补-49 🟡1 / 🟡2）。
package com.termux.view

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * `TerminalGestureController` 两处守卫的 **文本门禁**（2026-10-12 加）。
 *
 * ## 为什么是"文本断言"而不是行为断言
 *
 * 这个类继承 `GestureDetector.SimpleOnGestureListener`，方法签名上就挂着
 * `MotionEvent`；`mView` 是 `TerminalView`（要 Context、要 Renderer、要 Emulator）。
 * JVM 单测里造不出这套依赖（造出来也只是在测桩），真机手测又**很难**把
 * 「选词态下甩一下」与「外接鼠标按住拖动」两条路径都踩到 —— 前者要长按选词后
 * 还能甩出惯性，后者要插鼠标。
 *
 * 所以这里退一步：**只断言守卫还在源码里**，明确它证明的是"没人把它悄悄删掉"，
 * 而不是"行为正确"。行为那一半的判据写在 `docs/FEATURE-LEDGER.md` §B-补-49
 * （🟡1 / 🟡2）与真机走查清单里。
 *
 * ## 它检查什么（三条，任一条不成立立即失败）
 * 1. `onScroll()` 的鼠标跟踪分支**同时**判 `isMouseTrackingActive()` 与
 *    `!isFromSource(SOURCE_MOUSE)`（否则外接鼠标拖动一事两报，而下方鼠标分支成死代码）；
 * 2. `onFling()` 方法体内判了 `isSelectingText()`（否则选词态仍会被异步 Scroller 滚走）；
 * 3. 源码里不再有那句**说错的注释**「只有这里漏判」（它让下一个人以为别处都判了）。
 */
class TerminalGestureGateTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（同 R8KeepRuleGuardTest 的约定）。 */
    private fun sourceFile(): File {
        val rel = "src/main/java/com/termux/view/TerminalGestureController.java"
        return File(rel).let { if (it.exists()) it else File("app/$rel") }
    }

    private val source: String by lazy { sourceFile().readText() }

    /** 截出某个方法的方法体：从签名起，到下一个 `@Override`（或文件尾）为止。 */
    private fun methodBody(signature: String): String {
        val start = source.indexOf(signature)
        assertTrue("源码里找不到方法：$signature（文件：${sourceFile().path}）", start >= 0)
        val next = source.indexOf("@Override", start + signature.length)
        return source.substring(start, if (next > 0) next else source.length)
    }

    @Test
    fun `鼠标跟踪分支必须排除鼠标源`() {
        val onScroll = methodBody("public boolean onScroll(")
        assertTrue(
            "onScroll() 的鼠标跟踪分支丢了 !isFromSource(SOURCE_MOUSE) ⇒ 外接鼠标拖动会被当成滚轮，一事两报",
            onScroll.contains("isMouseTrackingActive() && !e.isFromSource(InputDevice.SOURCE_MOUSE)")
        )
    }

    @Test
    fun `onFling 必须判选词态`() {
        val onFling = methodBody("public boolean onFling(")
        assertTrue(
            "onFling() 没判 isSelectingText() ⇒ 选词时拖手柄会把终端一起滚走（E-109 只修了 onScroll）",
            onFling.contains("isSelectingText()")
        )
    }

    @Test
    fun `那句说错的注释不该再出现`() {
        assertFalse(
            "「只有这里漏判」是错的（onFling 同样漏判）—— 留着会误导下一个人",
            source.contains("只有这里漏判")
        )
    }

    @Test
    fun `onFling 的异步 Runnable 必须判 emulator 空`() {
        // 2026-10-14 追加（§B-补-49 🟡5）：run() 里那条 mEmulator 空判得在
        // attachSession 置空之后还能拦住 ⇒ 必须出现在 onFling 区间（含内嵌 Runnable）。
        val onFling = onFlingWithRunnable()
        assertTrue(
            "onFling 的 post Runnable 没判 mEmulator == null ⇒ attachSession 置空后触发 NPE",
            onFling.contains("if (mView.mEmulator == null)")
        )
        assertTrue(
            "空判分支必须 abortAnimation 并返回（否则 Scroller 继续跑、后续帧还会进来）",
            onFling.contains("mScroller.abortAnimation()")
        )
    }

    /**
     * 截 onFling 到下一个**顶层**方法：不能复用 [methodBody]——它遇到任意 `@Override`
     * 就停，而 onFling 里的匿名 Runnable 自带 `@Override run()`，会把要检查的区间截掉。
     */
    private fun onFlingWithRunnable(): String {
        val start = source.indexOf("public boolean onFling(")
        assertTrue("源码里找不到 onFling（文件：${sourceFile().path}）", start >= 0)
        val end = source.indexOf("\n    @Override", start + 10) // 顶层方法（4 空格缩进）
        return source.substring(start, if (end > 0) end else source.length)
    }
}
