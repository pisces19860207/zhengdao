// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Qoder 审查 🟠-1 的**源码门禁**：系统重建服务时不得留下"会话运行中"的僵尸通知。
 *
 * 为什么用读源码的方式钉（而不是单测行为）：`SessionService` 是 Android 前台 `Service`，
 * 重建路径（`intent == null` + 进程内无会话）在 JVM 单测里造不出来；`StoreTest` 那类纯函数
 * 单测在这里没有着力点。所以这条测试只保证**形状**：谁把这段自愈守卫删掉、或者图省事把
 * 收尾的 `START_STICKY` 全局改成 `START_NOT_STICKY`，谁就会看到它红。
 *
 * 背景：`START_STICKY` 让系统在本服务进程被回收后重建服务，这是保活设计要的行为；但重建时
 * intent 为 null，而"有没有会话"是进程内状态 —— `onCreate` 那句无条件的 `startForeground`
 * 会挂出一张「证道 · 会话运行中」的假通知，且 30 秒的 `monitorTick` 只会原样重发，永不自愈。
 */
class SessionZombieNotificationGuardTest {

    /** Gradle 单测的工作目录是模块目录 `app/`（与 TmuxConfAppendTest 同一约定）。 */
    private fun appFile(rel: String): File =
        File(rel).let { if (it.exists()) it else File("app/$rel") }

    private fun serviceSource(): String {
        val f = appFile("src/main/java/com/example/zhengdao/terminal/SessionService.kt")
        assertTrue("找不到 SessionService.kt：${f.path}", f.isFile)
        return f.readText()
    }

    private fun onStartCommandBody(): String {
        val src = serviceSource()
        val start = src.indexOf("override fun onStartCommand")
        assertTrue("源码里找不到 onStartCommand（锚点变了就更新本测试）", start >= 0)
        val end = src.indexOf("override fun onDestroy", start)
        assertTrue("找不到 onStartCommand 的边界（锚点变了就更新本测试）", end > start)
        return src.substring(start, end)
    }

    private val guardMark = "if (intent == null && !SessionManager.isAlive()) {"

    @Test
    fun `重建且进程内无会话_必须自愈停止并返回 START_NOT_STICKY`() {
        val body = onStartCommandBody()
        val g = body.indexOf(guardMark)
        assertTrue("自愈守卫不见了（Qoder 🟠-1 会复现：通知栏永久挂「会话运行中」）：\n$body", g >= 0)
        val whenMark = body.indexOf("when (intent?.action)")
        assertTrue("守卫必须在分发 intent 之前（否则 ACTION_* 会先执行）：", whenMark > g)
        val branch = body.substring(g, whenMark)
        assertTrue("守卫里没有 stopSelf()：\n$branch", branch.contains("stopSelf()"))
        assertTrue("守卫里没有 return START_NOT_STICKY（不清掉重建标记就会一直被重建）：\n$branch",
            branch.contains("START_NOT_STICKY"))
    }

    @Test
    fun `正常路径的收尾仍然返回 START_STICKY_不得全局改成 NOT_STICKY`() {
        val body = onStartCommandBody()
        val last = body.lastIndexOf("return START_")
        assertTrue("onStartCommand 里找不到任何 return START_：\n$body", last >= 0)
        val tail = body.substring(last).trim()
        assertTrue(
            "onStartCommand 的收尾被人改成了 $tail —— 保活设计要求正常路径（含 ACTION_TOGGLE_KEEPALIVE）" +
                "保持 START_STICKY，只允许「重建且无会话」那一条路返回 START_NOT_STICKY：\n$body",
            tail.startsWith("return START_STICKY"),
        )
        assertTrue("ACTION_STOP_SESSION 的处理不见了（用户点「停止会话」应停服务）：\n$body",
            body.contains("ACTION_STOP_SESSION"))
        assertTrue("ACTION_TOGGLE_KEEPALIVE 的处理不见了（保活开关靠它）：\n$body",
            body.contains("ACTION_TOGGLE_KEEPALIVE"))
    }

    @Test
    fun `自愈守卫必须留痕_不得静默`() {
        val body = onStartCommandBody()
        val g = body.indexOf(guardMark)
        val whenMark = body.indexOf("when (intent?.action)")
        val branch = body.substring(g, whenMark)
        assertTrue("守卫里没有任何落盘痕迹（本项目纪律：失败与降级不许静默）：\n$branch",
            branch.contains("RunLog.log("))
    }
}
