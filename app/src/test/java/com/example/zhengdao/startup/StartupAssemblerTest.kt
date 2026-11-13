// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.startup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [StartupAssembler] 的判据用例（629）。
 *
 * 这里钉的是**行为**而不是"某文件里有没有某行"：装配动作是注入的闭包，所以"装配必炸"
 * 与"炸完又好了"两种现场都能在 JVM 上原样造出来，不需要真机也不需要引擎。
 *
 * 对判据的映射：
 *   ① 失败 ⇒ [StartupRoute.Failure]（不是崩溃、不是 [StartupRoute.Main]）
 *   ③ 安全模式 ⇒ 不再装配（[StartupAssembler.attempts] 不涨）
 *   ④ 异常消除后 retry ⇒ Ready
 */
class StartupAssemblerTest {

    private fun failureOf(t: Throwable, at: Long = 1_700_000_000_000L) = StartupFailure.of(t, at)

    @Test
    fun `正例_装配成功即Ready且只装一次`() {
        var calls = 0
        val m = StartupAssembler(assemble = { calls++ })
        m.ensure()
        m.ensure()
        m.ensure()
        assertEquals(StartupState.Ready, m.state)
        assertEquals(1, calls)
        assertEquals(1, m.attempts)
        assertEquals(StartupRoute.Main, routeOf(m.state))
    }

    @Test
    fun `判据一_装配抛异常进错误页而不是主界面`() {
        val m = StartupAssembler(assemble = { throw IllegalStateException("policy 表装不上") })
        m.ensure()
        val s = m.state
        assertTrue("失败必须是 Failed 态，实际是 $s", s is StartupState.Failed)
        assertEquals("IllegalStateException", (s as StartupState.Failed).failure.type)
        assertEquals("policy 表装不上", s.failure.message)
        // 判据①的正题：这一格不许进主界面
        assertEquals(StartupRoute.Failure, routeOf(s))
    }

    @Test
    fun `失败后不自动重试_再调ensure也不重装`() {
        var calls = 0
        val m = StartupAssembler(assemble = { calls++; throw IllegalStateException("boom") })
        m.ensure()
        m.ensure()
        m.ensure()
        // 自动重试会让"装配坏了的机器每次回前台都再炸一次"，而炸出来的东西没人看
        assertEquals(1, calls)
        assertEquals(1, m.attempts)
    }

    @Test
    fun `判据四_异常消除后retry能进主界面`() {
        var broken = true
        var calls = 0
        val m = StartupAssembler(assemble = {
            calls++
            if (broken) throw IllegalStateException("boom")
        })
        m.ensure()
        assertEquals(StartupRoute.Failure, routeOf(m.state))

        broken = false
        m.retry()
        assertEquals(StartupState.Ready, m.state)
        assertEquals(StartupRoute.Main, routeOf(m.state))
        assertEquals(2, calls)
    }

    @Test
    fun `retry仍失败_换成新的现场`() {
        var at = 100L
        var throwIt = true
        val m = StartupAssembler(
            assemble = { if (throwIt) throw IllegalArgumentException("boom") },
            now = { at },
        )
        m.ensure()
        at = 200L
        m.retry()
        val failed = m.state as StartupState.Failed
        assertEquals(2, m.attempts)
        // 现场要是最新的那一次，不能还是第一次的时间戳（诊断文件按它命名）
        assertEquals(200L, failed.failure.atMillis)
        throwIt = false
        m.retry()
        assertEquals(StartupState.Ready, m.state)
    }

    @Test
    fun `LinkageError也算装配失败_不许漏成崩溃`() {
        // NoClassDefFoundError / ExceptionInInitializerError 是装配阶段最典型的一种失败，
        // 它们不是 Exception —— 只捕 Exception 的话这一类会直接崩掉进程
        val m = StartupAssembler(assemble = { throw NoClassDefFoundError("com/example/Foo") })
        m.ensure()
        val s = m.state
        assertTrue("LinkageError 必须被记成失败态，实际是 $s", s is StartupState.Failed)
        assertEquals("NoClassDefFoundError", (s as StartupState.Failed).failure.type)
    }

    @Test
    fun `VirtualMachineError不被吞_不许把它包装成错误页`() {
        // OOM / StackOverflow 那会儿进程自己都要活不下去了，画错误页只会更费解：
        // 这两类必须原样抛出去（这是刻意不捕，不是漏）
        val m = StartupAssembler(assemble = { throw OutOfMemoryError("heap") })
        assertThrows(OutOfMemoryError::class.java) { m.ensure() }
        assertEquals(StartupState.Idle, m.state)
    }

    @Test
    fun `判据三_安全模式只从失败态进_且进去就不再装配`() {
        var calls = 0
        val m = StartupAssembler(assemble = { calls++; throw IllegalStateException("boom") })
        m.enterSafeMode()
        // 还没失败过就点安全模式：不该生效（否则等于凭空关掉抱朴）
        assertEquals(StartupState.Idle, m.state)

        m.ensure()
        m.enterSafeMode()
        assertTrue(m.state is StartupState.SafeMode)
        assertEquals(StartupRoute.SafeMode, routeOf(m.state))
        m.ensure()
        // 安全模式里抱朴不可用 ⇒ 一次都不许再装
        assertEquals(1, calls)
        assertEquals(1, m.attempts)
    }

    @Test
    fun `安全模式里退出重装_与重试同一条路`() {
        var broken = true
        val m = StartupAssembler(assemble = { if (broken) throw IllegalStateException("boom") })
        m.ensure()
        m.enterSafeMode()
        broken = false
        m.retry()
        assertEquals(StartupState.Ready, m.state)
        assertEquals(StartupRoute.Main, routeOf(m.state))
    }

    @Test
    fun `已成功时retry不重装_也不清状态`() {
        var calls = 0
        val m = StartupAssembler(assemble = { calls++ })
        m.ensure()
        m.retry()
        assertEquals(1, calls)
        assertEquals(StartupState.Ready, m.state)
    }

    @Test
    fun `栈被截断_不把整条cause链糊进诊断`() {
        // 造一份 200 帧的假栈（真栈在单测里只有几帧，测不出截断）
        val t = IllegalStateException("root")
        t.stackTrace = Array(200) { StackTraceElement("com.example.C", "m$it", "C.kt", it + 1) }
        val f = failureOf(t)
        assertEquals("首行是 类型: 消息", "java.lang.IllegalStateException: root", f.stack.first())
        assertTrue(
            "栈行数不得超过 ${StartupFailure.MAX_STACK_LINES}，实际 ${f.stack.size}",
            f.stack.size <= StartupFailure.MAX_STACK_LINES,
        )
        assertEquals("IllegalStateException", f.type)
        assertEquals("java.lang.IllegalStateException", f.typeFull)
    }
}
