package com.example.zhengdao.keepalive

import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * P4-4（issue #17）：协程未捕获异常 handler 的回归。
 *
 * 审计原文：「有 `keepalive/KeepaliveWatcher.kt:218 setDefaultUncaughtExceptionHandler`，
 * `CoroutineExceptionHandler` 仍 0」。
 *
 * 本测试钉：① handler 契约真的成立（接到异常 + 拿到协程名 / Job）；
 * ② 生产 handler **不吞**（委派回线程未捕获处理器，崩溃行为不变）；
 * ③ UI 侧的 rememberCoroutineScope 都装上了。
 *
 * 注：不引 kotlinx-coroutines-test（会多为一个测试依赖）——runBlocking +
 * Unconfined 足够同步复现"协程崩了 handler 接到"这条路径。
 */
class CoroutineCrashHandlerTest {

    @Test
    fun `handler 契约_接到异常且拿到协程上下文`() = runBlocking {
        var caught: Throwable? = null
        var seenName: String? = null
        var seenJob: String? = null
        val probe = CoroutineExceptionHandler { ctx, error ->
            caught = error
            seenName = ctx[CoroutineName]?.name
            seenJob = ctx[Job]?.toString()?.take(20)
        }
        val boom = IllegalStateException("boom")
        // Unconfined：launch 的协程在当前线程立即跑完（抛异常 ⇒ 同步进 handler）
        val scope = CoroutineScope(
            SupervisorJob() + Dispatchers.Unconfined + probe + CoroutineName("probe-coroutine"),
        )
        scope.launch { throw boom }
        assertTrue("handler 必须接到异常", caught === boom)
        assertTrue("协程名必须拿得到（排查第一线索）", seenName == "probe-coroutine")
        assertTrue("Job 必须可读", seenJob != null)
    }

    @Test
    fun `门禁_生产 handler 不吞异常_委派回线程处理器`() {
        val src = File("src/main/java/com/example/zhengdao/keepalive/CoroutineCrashHandler.kt").readText()
        assertTrue(
            "必须把异常交回线程未捕获处理器（不吞、不覆盖系统行为，同 installCrashHandler 哲学）",
            src.contains("Thread.getDefaultUncaughtExceptionHandler()?.uncaughtException("),
        )
        assertTrue("协程名要记（排查第一线索）", src.contains("ctx[CoroutineName]?.name"))
        assertTrue("Job 要记", src.contains("ctx[Job]"))
        assertTrue("日志要经 RunLog（与全项目一致）", src.contains("RunLog.log("))
    }

    @Test
    fun `门禁_UI 作用域都装了 handler`() {
        val root = File("src/main/java")
        val bare = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            // ⚠️ 路径归一化：Windows 上 walk 出来是反斜杠，直接 contains("/oc/") 永不命中
            .filter { !it.path.replace('\\', '/').contains("/oc/") }
            .filter { it.readText().contains("rememberCoroutineScope()") }
            .map { it.path }
            .toList()
        assertTrue(
            "还有没装 handler 的 rememberCoroutineScope()：$bare",
            bare.isEmpty(),
        )
    }
}
