// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.startup

import com.example.zhengdao.CodeText
import com.example.zhengdao.GateBodies
import com.example.zhengdao.SrcRoots
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 629 的接线门禁：**装配只有一个出口，且成功/失败在结构上分得开**。
 *
 * 为什么这类判据要读源码而不是只跑单测：单测能证明"状态机行为对"，证明不了
 * "生产里真的只有这一处装配"。历史上正是两处 `runCatching { engine() }`（Application
 * 与 MainActivity）把失败变成了静默降级 —— 只要有人再补一处直接 `engine()`，
 * 错误页就会被绕过，而所有单测仍然全绿。
 *
 * 定位用 [GateBodies.functionBody]（按括号配对切函数体，不按"切到下一个 fun"），
 * 断言只在**函数体内**找子串 —— 否则 `markOk(` 会命中它自己的定义行（恒真陷阱，
 * 428/277/298 都栽过）。文件末尾有一条自证：假 marker 必须定位不到。
 */
class StartupWiringGateTest {

    private fun src(path: String): String = SrcRoots.find(path).readText()

    /** 只留代码（注释与字符串挖空）：注释里**提**旧写法不拦，代码里**留**旧写法才拦。 */
    private fun code(text: String): String = CodeText.codeOnly(text)

    private val gateSrc by lazy { src("app/src/main/java/com/example/zhengdao/startup/StartupGate.kt") }
    private val appSrc by lazy { src("app/src/main/java/com/example/zhengdao/ZhengdaoApp.kt") }
    private val activitySrc by lazy { src("app/src/main/java/com/example/zhengdao/MainActivity.kt") }

    @Test
    fun `装配动作里_注入先于引擎_记账晚于引擎`() {
        val body = GateBodies.functionBody(gateSrc, "fun assembleEngine(")
        assertNotNull("assembleEngine 定位不到（改名了？门禁要跟着改）", body)
        val b = body!!

        val inject = b.indexOf("injectIfAsked(")
        val engine = b.indexOf("BaopuRuntime.engine(")
        val mark = b.indexOf("markOk(")
        val okLine = b.indexOf("StartupLedgerLine.ok()")

        assertTrue("注入点必须在引擎之前（否则注入永远炸不到装配）", inject in 0 until engine)
        assertTrue("引擎必须先真的装起来，才谈得上记账", engine >= 0 && mark > engine)
        assertTrue("成功账行也必须在引擎之后", okLine > engine)
    }

    @Test
    fun `判据五_失败路径写不出成功账行`() {
        val body = GateBodies.functionBody(gateSrc, "fun assembleEngine(")!!
        // 捕获不在这个函数里（在 StartupAssembler.runOnce 的 catch 里）⇒ 任何一步抛出都会
        // 直接从函数里跳出，markOk / ok() 都不会执行。这一条是"结构保证"而不是"记得别写"。
        assertFalse("装配函数里不许自己 try/catch 把异常吞掉", body.contains("catch"))
        assertFalse("装配函数里不许出现「退回懒建」这类静默降级", body.contains("退回懒建"))

        val failed = StartupLedgerLine.failed(
            StartupFailure("IllegalStateException", "java.lang.IllegalStateException", "x", emptyList(), 0L),
        )
        assertTrue("失败行要写明失败", failed.contains("失败"))
        assertFalse("失败行不许出现「完成」：$failed", failed.contains("完成"))
        assertFalse("失败行不许出现「成功」：$failed", failed.contains("成功"))
        assertTrue("成功行就是那句完成", StartupLedgerLine.ok().contains("完成"))
    }

    @Test
    fun `注入开关_只在debug包生效`() {
        val body = GateBodies.functionBody(gateSrc, "fun injectIfAsked(")
        assertNotNull("injectIfAsked 定位不到", body)
        assertTrue("正式包必须整段挡住", body!!.contains("BuildConfig.DEBUG"))
    }

    @Test
    fun `冷启入口_Application走闸且不再直接engine`() {
        val txt = code(appSrc)
        assertTrue("Application 必须走装配闸", txt.contains("StartupGate.install(this)"))
        assertFalse(
            "启动路径不许绕过闸直接 engine()（绕过就没人画错误页了）",
            txt.contains("BaopuRuntime.engine("),
        )
        assertFalse("旧的「退回懒建」兜底必须删掉", txt.contains("退回懒建"))
    }

    @Test
    fun `冷启入口_主界面走闸且错误页分叉在组合最外层`() {
        val txt = code(activitySrc)
        assertTrue("MainActivity 的预建也要过闸", txt.contains("StartupGate.ensure(this)"))
        assertFalse("MainActivity 不许直接 engine()", txt.contains("BaopuRuntime.engine("))
        assertTrue(
            "setContent 里必须先过 StartupRoot（失败时主界面才不会被组合）",
            txt.contains("StartupRoot(lastRoute = lastRoute)"),
        )
    }

    @Test
    fun `判据三_安全模式复用同一个设置页_且不碰抱朴页`() {
        val root = code(src("app/src/main/java/com/example/zhengdao/ui/startup/StartupRoot.kt"))
        // 设置可用＝真的调那个设置页（不是另造一个残缺的设置）
        assertTrue("安全模式要复用 SettingsScreen()", root.contains("SettingsScreen()"))
        // 抱朴不可用＝壳里根本没有抱朴页/引擎的引用
        assertFalse("安全模式里不许出现抱朴页", root.contains("BaopuScreen"))
        assertFalse("安全模式里不许直接取引擎", root.contains("BaopuRuntime"))
    }

    @Test
    fun `自证_假marker定位不到（防恒真）`() {
        assertNull(GateBodies.functionBody(gateSrc, "fun assembleEngineDoesNotExist("))
    }
}
