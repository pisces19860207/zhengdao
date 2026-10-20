// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第五十三批（审计第二批 B 段）· 终端/外壳 P1 的**源码门禁**。
 *
 * 五件里 P1-5 是行为类（`ResMonitor.cpuDelta`，见 [ResMonitorTest]），其余四条都落在
 * `TerminalActivity` / `MainActivity` / `HomeScreen` 的接线与取值上 —— 这些类要
 * Context/View/Compose，JVM 里造不出依赖，故照既有风格（`TerminalSessionWiringGateTest`
 * 同型）用剥注释后的文本断言钉住**关键串**，防被悄悄改回去。
 *
 * 对应派工单：`docs/派工单-2026-10-19-第五十三批-终端外壳P1五件.md`。
 */
class TerminalShellP1GateTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（同 TerminalSessionWiringGateTest 的约定）。 */
    private fun read(rel: String): String =
        File(rel).let { if (it.exists()) it else File("app/$rel") }.readText()

    /** 取 `marker` 之后到 `endMarker` 之前的片段（函数体门禁用；带防呆断言）。 */
    private fun slice(src: String, marker: String, endMarker: String): String {
        val start = src.indexOf(marker)
        assertTrue("找不到 $marker —— 方法被改名/删掉了？", start >= 0)
        val end = src.indexOf(endMarker, start + marker.length)
        assertTrue("找不到结束标记 $endMarker", end > start)
        return src.substring(start, end)
    }

    /** 源码门禁统一先剥注释：注释里提到旧写法不等于旧写法还在。 */
    private fun stripComments(src: String): String =
        src.replace(Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL), "")
            .replace(Regex("//[^\\n]*"), "")

    // ── P1-4 · attach 分支必须读 SessionManager.isFallback ─────────────────

    @Test
    fun `P1-4 attach 分支读回退标记且标题按标记分流`() {
        val src = stripComments(read("src/main/java/com/example/zhengdao/TerminalActivity.kt"))
        val attach = slice(src, "private fun ensureStartedAndAttach()", "private fun startSessionOffMainThread()")

        assertTrue(
            "attach 分支必须读 SessionManager.isFallback —— 否则环境未装、会话是系统 shell 时" +
                "重进终端顶栏仍写「Debian 13.7」，启动命令还会打进系统 sh",
            attach.contains("fallbackActive = SessionManager.isFallback")
        )
        assertFalse(
            "attach 分支不得再无条件 fallbackActive = false",
            attach.contains("fallbackActive = false")
        )
        assertTrue(
            "attach 分支的标题必须按 fallbackActive 条件化（不得无条件写 Debian）",
            attach.contains("if (fallbackActive)")
        )
        assertTrue(
            "两条标题串都要在（回退态 / Debian）",
            attach.contains("证道 - 系统 shell") &&
                attach.contains("证道 - Debian 13.7")
        )
        // 2026-10-20：回退态标题必须与 Debian 标题等长级（长标题会被 autoSize 降到 12–13sp，
        // 两条标题字号不一致——用户真机看到「字不一样大」）。
        val fallbackTitle = "证道 - 系统 shell"
        val debianTitle = "证道 - Debian 13.7"
        assertTrue(
            "回退态标题不许比 Debian 标题长（否则 autoSize 降字号）",
            fallbackTitle.length <= debianTitle.length + 1
        )
    }

    // ── P1-6 · 系统文本菜单「粘贴」必须走后台通道 ─────────────────────────

    @Test
    fun `P1-6 文本菜单粘贴不得在主线程直写 pty`() {
        val src = stripComments(read("src/main/java/com/example/zhengdao/TerminalActivity.kt"))
        val paste = slice(
            src,
            "override fun onPasteTextFromClipboard",
            "override fun onCreateContextMenu",
        )

        assertTrue(
            "系统文本菜单「粘贴」必须交给后台通道 pasteText(text)（与 onPasteRequest 同路），" +
                "否则贴长文会在主线程等 4 KiB ByteQueue ⇒ ANR",
            paste.contains("pasteText(text)")
        )
        assertFalse(
            "该回调内不得再出现主线程直写 SessionManager.write(",
            paste.contains("SessionManager.write(")
        )
    }

    // ── P1-9 · 热启动 onNewIntent 必须读 open_terminal ────────────────────

    @Test
    fun `P1-9 onNewIntent 读 open_terminal 并走唯一终端入口`() {
        val src = stripComments(read("src/main/java/com/example/zhengdao/MainActivity.kt"))
        val newIntent = slice(src, "override fun onNewIntent(intent: Intent)", "override fun onTrimMemory")

        assertTrue(
            "热启动必须读 open_terminal —— 否则 App 已在栈里时点「回到终端」通知无效",
            newIntent.contains("open_terminal")
        )
        assertTrue(
            "必须用既有 terminalIntent( 入口拉起终端（不新造第二条终端路由）",
            newIntent.contains("terminalIntent(")
        )
        assertTrue(
            "必须真的 startActivity",
            newIntent.contains("startActivity(")
        )
        assertTrue(
            "必须保留 setIntent（后续读取不被旧 intent 误导）",
            newIntent.contains("setIntent(intent)")
        )
        assertTrue(
            "必须保留 handleBaopuShortcut（抱朴快捷方式请求不受影响）",
            newIntent.contains("handleBaopuShortcut(intent)")
        )
    }

    // ── P1-5 · CPU 两次采样必须按 pid 对齐（行为用例见 ResMonitorTest）────────

    @Test
    fun `P1-5 CPU 采样按 pid 映射求差而不是按列表下标`() {
        val src = stripComments(read("src/main/java/com/example/zhengdao/terminal/ResMonitor.kt"))

        assertTrue(
            "必须抽出可直测的纯函数 cpuDelta(Map, Map)",
            src.contains("internal fun cpuDelta(")
        )
        assertTrue(
            "两侧采样必须收成 pid → ticks 的映射（.toMap）",
            src.contains(".toMap()")
        )
        assertFalse(
            "不得再按列表下标对齐（旧实现 take(n) 的错配源头）",
            src.contains("take(n)") || src.contains(".take(")
        )
    }

    // ── P1-10 · 首页空态判据不得再挂 opencode ─────────────────────────────

    @Test
    fun `P1-10 首页空态判据是空表而不是排除 opencode`() {
        val src = stripComments(read("src/main/java/com/example/zhengdao/ui/HomeScreen.kt"))

        assertFalse(
            "opencode 已不在 agent 列表里，判据 `it.id != \"opencode\"` 会退化成恒真" +
                "（首帧空表就闪「清单拉不到」）",
            src.contains("it.id != \"opencode\"")
        )
        assertTrue(
            "空态判据必须是 agents.isEmpty()",
            src.contains("if (agents.isEmpty())")
        )
    }
}
