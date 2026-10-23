// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 抱朴**自己的工作区**（2026-10-18 用户口径：「设置页里的文件夹和抱朴分开设置，**再也不共用了**」）。
 *
 * ## 两组判据
 *
 * 1. **行为层**：路径合法性与解析是纯函数，穷举四个分支（默认／自定义合法／自定义非法／空）；
 * 2. **接线层**：`baopu` 与 `ui/baopu` 两个目录下**不许再引用 `terminal.Workspace`** ——
 *    共用的时代结束了，谁再引回去就是「设了抱朴的、终端跟着变」，用户明确否掉的那件事。
 *    （唯一例外：共享存储授权这一个**事实**判定，走 `ProotLauncher.storageGranted`，
 *    不是设置，也不是第二份判定。）
 */
class BaopuWorkspaceTest {

    /** 仓库相对路径（**目录也要能解析** —— 早先只判 isFile，扫描目录时静默扫空，门禁形同虚设）。 */
    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    // ── 行为层 ───────────────────────────────────────────────────────────────

    @Test
    fun `B1_默认工作区就是 Download 证道`() {
        assertEquals("/storage/emulated/0/Download/证道", BaopuWorkspace.defaultDir().path.replace('\\', '/'))
        assertEquals("证道", BaopuWorkspace.DEFAULT_DIR_NAME)
    }

    @Test
    fun `B2_自定义路径只收共享存储里的绝对路径`() {
        assertTrue(BaopuWorkspace.isValidCustom("/storage/emulated/0/Download/男性"))
        assertTrue("尾斜杠也认", BaopuWorkspace.isValidCustom("/storage/emulated/0/Download/男性/"))
        // 以下都拒
        assertFalse("空", BaopuWorkspace.isValidCustom(""))
        assertFalse("null", BaopuWorkspace.isValidCustom(null))
        assertFalse("应用私有目录不在共享存储根下", BaopuWorkspace.isValidCustom("/data/user/0/com.example.zhengdao/files/workspace"))
        assertFalse("相对路径", BaopuWorkspace.isValidCustom("Download/男性"))
        assertFalse("上跳", BaopuWorkspace.isValidCustom("/storage/emulated/0/Download/../../etc"))
        assertFalse("Windows 风格残留", BaopuWorkspace.isValidCustom("D:\\男性"))
    }

    @Test
    fun `B3_解析四分支_default 走默认_custom 合法走自定义_非法回落`() {
        val fallback = BaopuWorkspace.defaultDir()
        assertEquals(fallback, BaopuWorkspace.resolve("default", "/storage/emulated/0/Download/男性", fallback))
        assertEquals(
            File("/storage/emulated/0/Download/男性"),
            BaopuWorkspace.resolve("custom", "/storage/emulated/0/Download/男性", fallback),
        )
        assertEquals("custom 但路径非法 ⇒ 回落默认", fallback, BaopuWorkspace.resolve("custom", "D:\\男性", fallback))
        assertEquals("custom 但路径为空 ⇒ 回落默认", fallback, BaopuWorkspace.resolve("custom", null, fallback))
        assertEquals("未知 mode 当默认", fallback, BaopuWorkspace.resolve("whatever", null, fallback))
    }

    // ── 接线层：不再共用终端那套 ─────────────────────────────────────────────

    @Test
    fun `B4_抱朴里不许再引用 terminal 的 Workspace`() {
        val offenders = mutableListOf<String>()
        val roots = listOf(
            appFile("src/main/java/com/example/zhengdao/baopu"),
            appFile("src/main/java/com/example/zhengdao/ui/baopu"),
        )
        roots.forEach { root ->
            root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    // 只扫**代码**：行内 // 注释与整行 /** 块注释（含续行 *）都不算引用
                    val trimmed = line.trim()
                    if (trimmed.startsWith("*") || trimmed.startsWith("/*")) return@forEachIndexed
                    val code = line.substringBefore("//")
                    val bad = Regex("""terminal\.Workspace\b""").containsMatchIn(code) ||
                        Regex("""\bWorkspace\.(hostDir|setCustom|setDefault|setPrivate|defaultDir|privateDir|isPrivate)\b""")
                            .containsMatchIn(code)
                    if (bad) offenders += "${f.name}:${i + 1}: ${line.trim()}"
                }
            }
        }
        assertTrue(
            "抱朴侧不许再引用 terminal.Workspace（用户 2026-10-18：两套工作区再也不共用）。违规：\n" +
                offenders.joinToString("\n"),
            offenders.isEmpty(),
        )
    }

    @Test
    fun `B5_授权判定委托同一个实现_不许自己写第二份`() {
        val src = appFile("src/main/java/com/example/zhengdao/baopu/BaopuWorkspace.kt").readText()
        assertTrue("授权判定必须委托 ProotLauncher", src.contains("ProotLauncher.storageGranted(ctx)"))
        assertTrue("用抱朴自己的键，不许碰终端的键", src.contains("\"baopu_ws_mode\""))
        assertTrue(src.contains("\"baopu_ws_path\""))
        assertFalse("不许出现终端的键名", src.contains("\"workspace_mode\""))
        assertFalse("不许出现终端的键名", src.contains("\"workspace_path\""))
    }

    @Test
    fun `B6_抱朴的三处取根都走 BaopuWorkspace`() {
        // 工作区在抱朴里意味着三件事：工具边界根、附件落盘、spill 落盘。三处共用一个根。
        assertTrue(
            "工具/引擎的根",
            appFile("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt").readText()
                .contains("BaopuWorkspace.hostDir(context.applicationContext)"),
        )
        assertTrue(
            "附件落盘",
            appFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuAttachments.kt").readText()
                .contains("BaopuWorkspace.hostDir(ctx)"),
        )
        assertTrue(
            "气泡缩略图读图",
            appFile("src/main/java/com/example/zhengdao/ui/baopu/BaopuMessageList.kt").readText()
                .contains("File(BaopuWorkspace.hostDir(ctx), relPath)"),
        )
    }

    @Test
    fun `B7_StaticFieldLeak修复不许靠错生命周期_两处SP都从applicationContext取`() {
        // 134 批 lint(StaticFieldLeak)：BaopuToolPrefs/BaopuToolUnlocks 不再存 Context 字段，
        // 改存进程级 SharedPreferences。修法的**生命周期前提**必须钉住：
        // - SP 只能从 applicationContext 取（拿 Activity 会随页面销毁变孤儿/泄漏）；
        // - 两个 object 各一处、每次 init 重新绑定（进程级单例，不许“只认第一次”的旧 context）。
        val src = appFile("src/main/java/com/example/zhengdao/baopu/BaopuRuntime.kt").readText()
        assertEquals(
            "两处 SP 都必须从 applicationContext 取",
            2,
            Regex("prefs = context\\.applicationContext\\.getSharedPreferences\\(").findAll(src).count(),
        )
        assertFalse("不许退回到持 Context 字段（那正是 StaticFieldLeak 报的东西）", src.contains("var appCtx: Context?"))
        assertEquals("两个存储对象各一个 prefs 字段", 2, Regex("private var prefs: android\\.content\\.SharedPreferences\\?").findAll(src).count())
    }
}
