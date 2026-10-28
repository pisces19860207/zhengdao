// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 259 批 C 段 #12/#13：跨线程可见性（共享可变字段漏 @Volatile）。
//
// 缺陷原文（`全仓隐患审计-2026-10-28.md` A-P2-8 / A-P2-9）：
// · #12 `keepalive/KeepaliveWatcher.kt:45` 的 `installed` 与 `:220` 的 `appContext`——
//   **同一个对象**里的 `lastActivity`、`ioExecutor` 都标了 @Volatile，就这两个没标；
// · #13 `baopu/BaopuRuntime.kt:93` 的 `mcpAuth`——同对象的 `dbInstance`/`engineInstance`/
//   `mcpSource` 全有，唯独它漏了。
//
// 为什么用源码门禁而不是跑一个并发用例：可见性是**内存模型**层面的缺陷，JVM 单测里
// 读不到 ≠ 竞态不存在（x86 上这种漏标基本"测不出问题"，ARM 上才咬人——真机是 Honor，ARM）。
// 所以这条的"改前红"就是注解本身：改前门禁读到 `private var installed = false` 上面没有
// `@Volatile` ⇒ 红；补上 ⇒ 绿。并发时序不假装能确定性复现。
package com.example.zhengdao.keepalive

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SharedVarVolatileGateTest {

    private fun src(rel: String): String {
        val f = listOf(rel, "../$rel").map { File(it) }.firstOrNull { it.isFile }
        assertTrue("找不到源码 $rel（门禁不许空转，cwd=${File("").absolutePath}）", f != null)
        return f!!.readText()
    }

    /** 声明上一行是不是 @Volatile（注释块可以夹在中间，所以往上跳过注释与空行）。 */
    private fun annotated(text: String, decl: String): Boolean {
        val at = text.indexOf(decl)
        assertTrue("声明没找到：$decl", at >= 0)
        val before = text.substring(0, at).lines().reversed()
            .dropWhile { it.isBlank() || it.trimStart().startsWith("//") || it.trimStart().startsWith("/*") || it.trimStart().startsWith("*") }
        return before.firstOrNull()?.trim() == "@Volatile"
    }

    // ── #12 KeepaliveWatcher ────────────────────────────────────────────

    @Test
    fun `巡检对象的两个共享字段都标了 volatile`() {
        val s = src("src/main/java/com/example/zhengdao/keepalive/KeepaliveWatcher.kt")
        assertTrue("installed 必须 @Volatile", annotated(s, "private var installed = false"))
        assertTrue("appContext 必须 @Volatile", annotated(s, "private var appContext: Context? = null"))
    }

    @Test
    fun `同对象里已有的 volatile 一个没被顺手删`() {
        // 改前的对照面就在同一个文件里：这两个本来就有，动它们＝越界。
        val s = src("src/main/java/com/example/zhengdao/keepalive/KeepaliveWatcher.kt")
        assertTrue("lastActivity 的 @Volatile 不许丢", annotated(s, "private var lastActivity = " + '"'))
        assertTrue("ioExecutor 的 @Volatile 不许丢", annotated(s, "internal var ioExecutor: Executor"))
    }

    @Test
    fun `加 volatile 不改任何行为`() {
        // §3 负例：不许借"补注解"把静默改成崩溃、或把幂等闸改成加锁重写。
        val s = src("src/main/java/com/example/zhengdao/keepalive/KeepaliveWatcher.kt")
        assertFalse("不许引入 synchronized（本条只补注解）", s.contains("synchronized"))
        assertFalse("不许引入 AtomicBoolean（同上）", s.contains("AtomicBoolean"))
        assertFalse("不许引入 throw", s.contains("throw "))
        assertTrue("幂等闸原样", s.contains("if (installed) return"))
        assertTrue("发布顺序原样（先置位再注册）",
            s.indexOf("installed = true") < s.indexOf("app.registerActivityLifecycleCallbacks(callbacks)"))
        assertTrue("赋值仍在 startPatrol 里（没搬到别的线程）",
            s.contains("appContext = app.applicationContext"))
    }

    // ── #13 BaopuRuntime.mcpAuth ────────────────────────────────────────

    @Test
    fun `mcpAuth 与同对象其余实例字段同口径`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue("mcpAuth 必须 @Volatile", annotated(s, "private var mcpAuth: McpAuthFlow? = null"))
        listOf(
            "private var dbInstance",
            "private var engineInstance",
            "private var mcpSource",
        ).forEach { assertTrue("$it 的 @Volatile 不许丢", annotated(s, it)) }
    }

    @Test
    fun `双检锁结构没被动`() {
        val s = src("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt")
        assertTrue("仍走 synchronized(this) 双检锁", s.contains("return mcpAuth ?: synchronized(this) {"))
        assertTrue("仍在 also 里发布", s.contains(".also { mcpAuth = it }"))
    }
}
