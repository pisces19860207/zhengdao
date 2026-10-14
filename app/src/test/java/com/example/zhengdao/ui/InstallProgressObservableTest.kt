// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import com.example.zhengdao.rootfs.RootfsInstaller
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * R-26（审计第⑮刀，2026-10-14）：安装中状态必须是**可观察状态**。
 *
 * 原实现是 `@Volatile var busy` + `isRunning(): Boolean = busy`——读的是普通布尔，Composable 里
 * 写 `enabled = !InstallFlow.isRunning()` **不会订阅**它 ⇒ 按钮"时灵时不灵"（要等下一次无关重组）。
 * 本测试钉：① 载体真的是 snapshot `State`（[InstallProgress.running]）；② 值随 start/finish/clear
 * 正确翻转；③ [InstallFlow.isRunning] 跟着变，且安装锁那一端（[RootfsInstaller.installing]）也看得见。
 */
class InstallProgressObservableTest {

    @After
    fun tearDown() {
        InstallProgress.clear()
    }

    @Test
    fun `running 是可订阅的 State 且随 start finish 翻转`() {
        InstallProgress.clear()
        assertFalse("初始未安装", InstallProgress.running.value)
        assertFalse(InstallProgress.isRunning())

        InstallProgress.start("正在安装")
        assertTrue("start 之后应为真", InstallProgress.running.value)
        assertTrue(InstallProgress.isRunning())
        assertEquals(InstallProgress.Phase.Running, InstallProgress.state.value?.phase)

        InstallProgress.finish("装完了")
        assertFalse("finish 之后应为假", InstallProgress.running.value)
        assertEquals(InstallProgress.Phase.Done, InstallProgress.state.value?.phase)

        InstallProgress.clear()
        assertNull("清掉之后没有进度", InstallProgress.state.value)
    }

    @Test
    fun `失败收尾同样把 running 复位`() {
        InstallProgress.start("正在安装")
        InstallProgress.finish("装失败了", failed = true)
        assertFalse(InstallProgress.running.value)
        assertEquals(InstallProgress.Phase.Failed, InstallProgress.state.value?.phase)
    }

    @Test
    fun `InstallFlow 的 isRunning 跟着可观察状态走`() {
        InstallProgress.clear()
        assertFalse("没有任务时不该报在跑", InstallFlow.isRunning())

        InstallProgress.start("正在安装")
        assertTrue("InstallProgress 置位后 InstallFlow 必须能看见", InstallFlow.isRunning())

        InstallProgress.clear()
        assertFalse(InstallFlow.isRunning())
    }

    @Test
    fun `安装锁置位时 InstallFlow 也能看见（另一端来源同样可观察）`() {
        InstallProgress.clear()
        assertFalse(InstallFlow.isRunning())
        RootfsInstaller.withInstallLock("装环境") {
            assertTrue("安装锁在位时 InstallFlow 必须报在跑", InstallFlow.isRunning())
            assertTrue("读的必须是可观察的 RootfsInstaller.installing", RootfsInstaller.installing.value)
        }
        assertFalse(InstallFlow.isRunning())
    }
}
