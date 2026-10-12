// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.rootfs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

/**
 * 「会话准备」与「安装」之间的互斥（§B 顺序 18，2026-10-12）。
 *
 * 背景：`ProotLauncher.buildLaunchPlan()` 每次开会话都要往 guest rootfs 里写东西
 * （DNS/时区/profile.d/软链/挂载落点/横幅），而安装最后会**把整棵 rootfs 改名搬走再放新的**
 * （[RootfsInstaller.swapIntoPlace]）。原先这两边只靠"启动前看一眼
 * [RootfsInstaller.isInstalling]"防并发 —— 那是 TOCTOU：查完到写之间安装可能刚好置位。
 *
 * 本测试钉的就是补上的那把锁：**准备期与安装置位互斥**，且两条路都保持"拒绝不排队"的语义。
 * 用的是真实线程 ＋ `CountDownLatch` 信号：先在锁里停住，再断言另一边（不靠 sleep 猜时序）。
 */
class SessionPrepareLockTest {

    private fun thread(name: String, body: () -> Unit): Thread =
        Thread(body, name).also { it.isDaemon = true; it.start() }

    // ── 安装已在跑 ⇒ 准备必须被拒（沿用"拒绝不排队"的语义）─────────────────

    @Test
    fun `安装进行中时 会话准备立刻被拒且一个字节都没写`() {
        val insideInstall = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val installThread = thread("installing") {
            RootfsInstaller.withInstallLock("装环境") {
                insideInstall.countDown()
                releaseInstall.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue("安装线程没能进入块", insideInstall.await(3, TimeUnit.SECONDS))

        var wroteAnything = false
        val err = runCatching {
            RootfsInstaller.withPrepareLock("准备会话") { wroteAnything = true }
        }.exceptionOrNull()

        releaseInstall.countDown()
        installThread.join(3000)

        assertTrue("准备期在安装进行中必须抛 InstallFailed，实际：$err", err is RootfsInstaller.InstallFailed)
        val msg = err!!.message ?: ""
        assertTrue("文案要说明原因：$msg", msg.contains("正在安装/更新环境"))
        assertTrue("文案要点出被拒的那件事：$msg", msg.contains("准备会话"))
        assertFalse("被拒时绝不能再往 rootfs 写", wroteAnything)
        assertFalse("安装结束后锁要释放", RootfsInstaller.isInstalling())
    }

    // ── 准备期持锁 ⇒ 安装无法置位（TOCTOU 的正面防线）─────────────────────

    @Test
    fun `会话准备期间 安装进不来 准备一结束就放行`() {
        val insidePrepare = CountDownLatch(1)
        val releasePrepare = CountDownLatch(1)
        val prepareThread = thread("preparing") {
            RootfsInstaller.withPrepareLock("准备会话") {
                insidePrepare.countDown()          // 已经拿到准备锁
                releasePrepare.await(5, TimeUnit.SECONDS)
            }
        }
        assertTrue("准备线程没能进入块", insidePrepare.await(3, TimeUnit.SECONDS))

        val installEntered = AtomicBoolean(false)
        val installThread = thread("installing") {
            RootfsInstaller.withInstallLock("装环境") { installEntered.set(true) }
        }

        // 给安装线程充分的机会去（错误地）抢到锁
        installThread.join(500)
        assertTrue("准备期没结束，安装不该还在跑", installThread.isAlive)
        assertFalse("准备期没结束，安装绝不能进块", installEntered.get())
        assertFalse("准备期没结束，isInstalling 必须仍是假", RootfsInstaller.isInstalling())

        releasePrepare.countDown()
        prepareThread.join(3000)
        installThread.join(3000)

        assertTrue("准备一结束，安装必须被放行", installEntered.get())
        assertFalse("安装结束后锁要释放", RootfsInstaller.isInstalling())
    }

    // ── 其它边界 ─────────────────────────────────────────────────────────

    @Test
    fun `准备锁可重入 同一线程再进一次不死锁`() {
        val inner = AtomicReference<String?>(null)
        RootfsInstaller.withPrepareLock("外层准备") {
            RootfsInstaller.withPrepareLock("内层准备") { inner.set("进去了") }
        }
        assertEquals("进去了", inner.get())
        assertFalse(RootfsInstaller.isInstalling())
    }

    @Test
    fun `准备块抛异常也必须释放锁`() {
        val err = runCatching {
            RootfsInstaller.withPrepareLock("会炸的准备") { throw IllegalStateException("炸了") }
        }.exceptionOrNull()
        assertTrue(err is IllegalStateException)
        // 释放的判据：紧接着的安装能正常进行（准备锁没被留在手里）
        var installed = false
        RootfsInstaller.withInstallLock("装环境") { installed = true }
        assertTrue(installed)
        assertFalse(RootfsInstaller.isInstalling())
    }

    @Test
    fun `准备锁原样返回块的结果`() {
        assertEquals(11, RootfsInstaller.withPrepareLock("算个数") { 11 })
        assertEquals("好", RootfsInstaller.withPrepareLock("拿个串") { "好" })
    }
}
