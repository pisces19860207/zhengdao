// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [BackgroundOnly] 的判定单测（审计 B4 的配套）。
 *
 * 为什么用「注入两个 Looper 替身」的方式测：JVM 单测里 `Looper.getMainLooper()` 返回 null
 * （`app/build.gradle.kts:295` 的 `unitTests.isReturnDefaultValues = true`），
 * 真机那套 Looper 造不出来。把两个 Looper 做成可注入参数后，三条分支都能在 JVM 上钉住：
 * 无 Looper（单测环境）放行、主线程调用抛、子线程放行。
 */
class BackgroundOnlyTest {

    @Test
    fun `没有 Looper 的环境一律放行`() {
        // 这就是 JVM 单测与任何非 Android 环境的情形 —— 放行，否则所有既有单测都会炸。
        BackgroundOnly.check("测试", mainLooper = null, currentLooper = null)
    }

    @Test
    fun `子线程调用放行`() {
        val main = Any()
        val worker = Any()
        BackgroundOnly.check("测试", mainLooper = main, currentLooper = worker)
    }

    @Test
    fun `主线程调用必须抛而且点名调用处`() {
        val main = Any()
        val e = runCatching { BackgroundOnly.check("ResMonitor.measure", mainLooper = main, currentLooper = main) }
            .exceptionOrNull()
        assertTrue("主线程调用应当抛 IllegalStateException，实际：$e", e is IllegalStateException)
        val msg = e!!.message.orEmpty()
        assertTrue("异常消息应点名调用处，实际：$msg", msg.contains("ResMonitor.measure"))
        assertTrue("异常消息应给出正确做法（Thread{} / Dispatchers.IO），实际：$msg", msg.contains("Dispatchers.IO"))
    }

    @Test
    fun `后台线程放行且不抛`() {
        val main = Any()
        val bg = Any()
        assertEquals(Unit, BackgroundOnly.check("测试", mainLooper = main, currentLooper = bg))
    }
}
