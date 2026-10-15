// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 第⑲刀（issue #14 · P1-6）· **OC serve 关停策略（方案③）** 的验收门禁。
 *
 * 同型照 `ui/browser/BrowserRecycleGateTest.kt`（第⑱刀）：读源码文本做断言，并用
 * [blockAfter] 把断言**限定在函数体内** —— 否则注释或别处的同名字面量就能把断言蒙混过去。
 *
 * 判据（用户派工单 + 台账 §D「用户三答」① 的方案③）：
 * - **G1 收**：主页 Activity「正在结束」（`isFinishing`）⇒ 收 serve（`ZhengdaoApp` 的 Activity 回调）；
 * - **G2 收**：任务被划掉（`onTaskRemoved`）⇒ 收 serve；`onDestroy` 只在该销毁原因下兜底；
 * - **G3 不收**（负例·重点）：切 Tab / 结束会话处**零** `stopServe`；全仓调用点只在白名单内；
 * - **G4 不动**：`OcManager.shutdownProcess` 仍是第⑯刀落地的**四步**收尾语义。
 *
 * 文本门禁的已知局限：它钉的是「代码长这样」，不是「行为真是这样」——
 * 真正的行为读数（切 Tab 后 `ps -A | grep -i serve` 仍在 / 划掉任务后消失）**归真机**，
 * 见交付四件套的「验不了的照实说」。
 *
 * **负例自证**（本门禁敢红）：临时在结束会话处（`TaijiScreen.onCloseSession`）插一行
 * `OcManager.stopServe()`，G3 必红；删掉即复绿。读数记在交付里。
 */
class OcShutdownGateTest {

    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    private fun appSource() = src("src/main/java/com/example/zhengdao/ZhengdaoApp.kt")

    private fun serviceSource() = src("src/main/java/com/example/zhengdao/terminal/SessionService.kt")

    private fun ocManagerSource() = src("src/main/java/com/example/zhengdao/oc/OcManager.kt")

    /**
     * 取 `marker` 之后那个 `{ … }` 块的正文（按花括号配平），把断言限定在某个函数体内。
     */
    private fun blockAfter(s: String, marker: String): String {
        val at = s.indexOf(marker)
        assertTrue("找不到标记 `$marker`", at >= 0)
        val open = s.indexOf('{', at)
        assertTrue("`$marker` 之后没有函数体 `{`", open > at)
        var depth = 0
        var i = open
        while (i < s.length) {
            when (s[i]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return s.substring(open + 1, i)
                }
            }
            i++
        }
        throw AssertionError("`$marker` 的花括号没配平")
    }

    /**
     * 从 `marker` 切到**下一个 4 空格缩进的成员声明**（KDoc 注释、`@…`、`private/internal/…`）。
     *
     * 为什么不复用 [blockAfter]：`OcManager.shutdownProcess` 的**签名里带 lambda 默认值**
     * （`log: (String) -> Unit = { RunLog.log(it) }`），blockAfter 取到的第一个 `{` 是那个
     * lambda，配平后就只剩 ` RunLog.log(it) ` —— 断言会落在错的地方。这里按"成员边界"切片，
     * 边界取在函数声明之后、下一段 KDoc 之前。
     */
    private fun sliceToNextMember(s: String, marker: String): String {
        val at = s.indexOf(marker)
        assertTrue("找不到标记 `$marker`", at >= 0)
        val next = Regex("\n {4}(/\\*\\*|@|private |internal |public |const )")
            .find(s, at + marker.length)
        return if (next == null) s.substring(at) else s.substring(at, next.range.first)
    }

    /** 全仓 main 源码里出现 `needle` 的 Kotlin 文件（相对 `app/src/main/java` 的路径，正斜杠）。 */
    private fun filesContaining(needle: String): List<String> {
        val root = listOf(File("app/src/main/java"), File("src/main/java"))
            .firstOrNull { it.isDirectory }
            ?: throw AssertionError("找不到 app/src/main/java")
        return root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filter { it.readText().contains(needle) }
            .map { it.relativeTo(root).path.replace('\\', '/') }
            .sorted()
            .toList()
    }

    // ── G1：主页「正在结束」才收（ZhengdaoApp 的 Activity 回调） ──

    @Test
    fun `G1 主页 Activity 正在结束 isFinishing 才收 serve`() {
        val s = appSource()

        assertTrue(
            "ZhengdaoApp 必须注册 ActivityLifecycleCallbacks —— 第⑲刀 T1 的落点",
            s.contains("registerActivityLifecycleCallbacks("),
        )
        assertTrue("必须实现 onActivityDestroyed", s.contains("override fun onActivityDestroyed("))

        val destroyed = blockAfter(s, "override fun onActivityDestroyed(activity: Activity)")
        assertTrue(
            "必须只对 MainActivity 判 —— 别的 Activity 结束不算「App 走了」",
            destroyed.contains("is MainActivity"),
        )
        assertTrue(
            "必须判 isFinishing —— 否则转屏/配置变更（isFinishing=false）也会误收 serve，" +
                "违反方案③的「转屏/配置变更不关」",
            destroyed.contains("isFinishing"),
        )

        // stopServe 必须落在 isFinishing 守卫块**内**（不能无条件收）
        val guard = blockAfter(s, "if (activity is MainActivity && activity.isFinishing)")
        assertTrue(
            "OcManager.stopServe() 必须写在 `is MainActivity && isFinishing` 守卫块里 —— " +
                "无条件收会把「切 Tab / 转屏」也一起踩了",
            guard.contains("OcManager.stopServe()"),
        )
    }

    // ── G2：任务被划掉收；onDestroy 只在该销毁原因下兜底 ──

    @Test
    fun `G2 任务被划掉收 serve 且 onDestroy 兜底带 taskRemoved 守卫`() {
        val s = serviceSource()

        assertTrue("SessionService 必须实现 onTaskRemoved（T2）", s.contains("override fun onTaskRemoved("))
        val removed = blockAfter(s, "override fun onTaskRemoved(rootIntent: Intent?)")
        assertTrue(
            "onTaskRemoved 必须先置 taskRemoved 标记 —— 它是 onDestroy 兜底的唯一依据",
            removed.contains("taskRemoved = true"),
        )
        assertTrue(
            "onTaskRemoved 必须真的收 serve（划掉任务 = 用户不要了的最可靠信号，而 FGS 自身会存活）",
            removed.contains("stopOcServe("),
        )

        val destroy = blockAfter(s, "override fun onDestroy()")
        assertTrue(
            "onDestroy 的兜底必须被 `if (taskRemoved)` 守卫包住 —— 否则用户点通知栏「停止会话」" +
                "（ACTION_STOP_SESSION → stopSelf）会把 serve 一起带走，违反方案③的「结束会话不关」",
            destroy.contains("if (taskRemoved)"),
        )
        assertFalse(
            "onDestroy 里不许出现无条件的 StopOcServe —— 那就是「结束会话也关」，踩红线",
            Regex("\\n\\s*stopOcServe\\(").containsMatchIn(destroy),
        )

        val helper = blockAfter(s, "private fun stopOcServe(reason: String)")
        assertTrue(
            "stopOcServe 必须调 oc 的公开出口 OcManager.stopServe() —— oc/ 是禁区，只调不改",
            helper.contains("OcManager.stopServe()"),
        )
    }

    // ── G3（负例·重点）：切 Tab / 结束会话零 stopServe；调用点只在白名单 ──

    @Test
    fun `G3 切Tab与结束会话零 stopServe 且全仓调用点只在白名单`() {
        val allowed = listOf(
            "com/example/zhengdao/ZhengdaoApp.kt",             // T1：主页结束
            "com/example/zhengdao/oc/OcManager.kt",            // 定义处（KDoc / 函数自身）
            "com/example/zhengdao/terminal/SessionService.kt", // T2 / T3：任务划掉 / 服务销毁兜底
            "com/example/zhengdao/ui/HomeScreen.kt",           // 既有「重启 serve」流（本刀不动）
        )
        assertEquals(
            "`stopServe` 出现的文件超出白名单 —— 新增调用点必须先回到方案③对齐判据" +
                "（只在退后台/进程回收时收，不因切 Tab、不因结束会话就收）",
            allowed.sorted(),
            filesContaining("stopServe"),
        )

        // 显式点名两条「尤其要能红」的负例路径（失败信息比上面的集合比较更直白）
        for (rel in listOf(
            "src/main/java/com/example/zhengdao/MainActivity.kt",
            "src/main/java/com/example/zhengdao/ui/taiji/TaijiScreen.kt",
        )) {
            assertFalse(
                "$rel 里出现了 stopServe —— 这是「切 Tab / 结束会话会收 serve」的直接反证，" +
                    "方案③明确不许（切 Tab 只换 Composition、结束会话只 repo.close + 切 Tab）",
                src(rel).contains("stopServe"),
            )
        }
    }

    // ── G4：不许改第⑯刀落地的 shutdownProcess 四步语义 ──

    @Test
    fun `G4 shutdownProcess 四步收尾语义一行不动`() {
        val body = sliceToNextMember(ocManagerSource(), "internal fun shutdownProcess(")

        assertTrue("四步之一：destroy()", body.contains("p.destroy()"))
        assertTrue("四步之二/四：两轮 waitFor（destroy 后等 + forcibly 后等）", body.contains("waitFor("))
        assertTrue("四步之三：destroyForcibly()", body.contains("destroyForcibly()"))
        val waits = Regex("waitFor\\(").findAll(body).count()
        assertTrue(
            "shutdownProcess 应有两次 waitFor（destroy 后等 ${'$'}waitMs + forcibly 后等），实际 $waits 次" +
                " —— 四步语义被改会在这里红",
            waits >= 2,
        )
    }
}
