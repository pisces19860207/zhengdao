// 独立开发声明：本文件为本项目从零编写。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * §B 顺序 6 / P1-3「冷启动主线程 IO」的验收（2026-10-14）。
 *
 * ## 修了什么
 * `SessionManager.start()` 里的重活（SHA256 / 拷 assets / `Store.adoptDir` 搬 JM 级缓存）
 * 原先在**主线程**跑 —— 它的三个调用点（`TerminalActivity` 的 `onResume` / `onNewIntent` /
 * `relaunchDebian` 的 `runOnUiThread`）全在主线程，冷启动必卡。本刀把它挪到后台线程，
 * UI 与 attach 回主线程（对外行为不变：仍是「起会话 → 顶栏 → attach」）。
 *
 * ## 异步化带出一个新风险 —— 所以这条单测是**必须**的
 * `hasSession()` 在 `start()` 跑完前仍是 false ⇒ `onResume` 与 `onNewIntent` 在启动完成前
 * 各来一次就会**起两条会话**（同一句启动命令注入两遍、正要启动的 Agent 被第二次顶掉）。
 * 用 [SessionStartGate] 挡住；本类测的就是它的**幂等 / 可重入**（判据点名要的那条）。
 *
 * 真机读数（冷启动主线程无 >16ms 长任务）归蓝总管采，打点已埋（见末条门禁）。
 */
class SessionStartGateTest {

    @Test
    fun `首次进入成功`() {
        val g = SessionStartGate()
        assertTrue("空闲时第一次应当进得去", g.tryEnter())
    }

    @Test
    fun `启动中再进被拒_这是可重入保护`() {
        val g = SessionStartGate()
        assertTrue(g.tryEnter())
        assertFalse("启动还没结束，第二次必须被拒（否则起两条会话）", g.tryEnter())
        assertFalse("再来一次也不放行", g.tryEnter())
    }

    @Test
    fun `exit 之后可以再进_这是幂等`() {
        val g = SessionStartGate()
        repeat(3) { round ->
            assertTrue("第 ${round + 1} 轮应当进得去", g.tryEnter())
            assertTrue("这一轮内应处于启动中", g.isInFlight)
            g.exit()
            assertFalse("exit 之后应回到空闲", g.isInFlight)
        }
    }

    @Test
    fun `并发只有一个赢家`() {
        val g = SessionStartGate()
        val winners = AtomicInteger(0)
        val ready = CountDownLatch(8)
        val done = CountDownLatch(8)
        val pool = Executors.newFixedThreadPool(8)
        repeat(8) {
            pool.execute {
                ready.countDown()
                ready.await() // 尽量让 8 个线程同时冲闸门
                if (g.tryEnter()) winners.incrementAndGet()
                done.countDown()
            }
        }
        done.await()
        pool.shutdown()
        assertEquals("8 个并发调用者里只能有一个进去", 1, winners.get())
    }

    // ── 接线（文本门禁）：钉住"重活真挪走了"，而不是只加了个闸门 ────────────

    /** Gradle 单测的工作目录是模块目录 `app/`。 */
    private fun src(rel: String): String {
        val f = File(rel).let { if (it.exists()) it else File("app/$rel") }
        assertTrue("找不到 $rel：${f.path}", f.isFile)
        return f.readText()
    }

    @Test
    fun `TerminalActivity_只有重活在后台_起会话回主线程且失败不吞`() {
        val s = src("src/main/java/com/example/zhengdao/TerminalActivity.kt")
        assertTrue("必须真的走后台分支", s.contains("startSessionOffMainThread()"))
        assertTrue("必须有后台线程（zd-session-start）", s.contains("zd-session-start"))
        // 2026-10-14 真机退回的教训：整段 start() 丢后台会抛
        // "Can't create handler inside thread Thread[zd-session-start…]"（MainThreadHandler 的 Looper 约束）
        assertTrue(
            "后台只做重活（prepareLaunchPlan）",
            s.contains("SessionManager.prepareLaunchPlan(this)")
        )
        assertTrue(
            "起会话必须回主线程做（SessionManager.start(this, plan) 在 runOnUiThread 里）",
            s.contains("SessionManager.start(this, plan)")
        )
        assertTrue("打点：logcat 侧", s.contains("ZhengdaoColdStart"))
        assertTrue("打点：RunLog 侧（真机读数靠它）", s.contains("冷启动打点：prepareLaunchPlan"))
        assertTrue("失败不许再吞：必须有 Log.e", s.contains("Log.e(\"ZhengdaoColdStart\""))
        assertTrue("失败不许再吞：必须落 RunLog", s.contains("buildLaunchPlan 失败"))
        assertTrue("防重入闸门必须真的用上", s.contains("sessionStartGate.tryEnter()"))
        assertTrue("结束必须放行（否则闸门永久卡死）", s.contains("sessionStartGate.exit()"))
        assertTrue("attach 部分抽成了共用函数", s.contains("attachViewToSession()"))
    }

    @Test
    fun `SessionManager_拆出 prepareLaunchPlan 且 start 收 plan 参数`() {
        val s = src("src/main/java/com/example/zhengdao/terminal/SessionManager.kt")
        assertTrue(
            "必须拆出只算计划的入口（调用方才能在后台调它）",
            s.contains("fun prepareLaunchPlan(context: Context)")
        )
        assertTrue(
            "start() 必须能接收算好的 plan（否则调用方无法做到「只挪重活」）",
            s.contains("plan: ProotLauncher.LaunchPlan? = null")
        )
        assertTrue(
            "起会话的 Looper 约束必须写进注释（防后人再整段丢后台）",
            s.contains("Looper.prepare()")
        )
    }

    @Test
    fun `HomeScreen_轮询走 IO_兜底查盘结果写回缓存`() {
        val s = src("src/main/java/com/example/zhengdao/ui/HomeScreen.kt")
        assertTrue(
            "P1-4：2 秒轮询里的查盘必须挪到 IO 线程",
            s.contains("kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO)")
        )
        assertTrue(
            "P1-5：兜底那次 stateOf 的结果必须写回 installStates 缓存（否则每次重组都重查）",
            s.contains("?: AgentRepository.stateOf(context, agent).also { installStates[agent.id] = it }")
        )
    }
}
