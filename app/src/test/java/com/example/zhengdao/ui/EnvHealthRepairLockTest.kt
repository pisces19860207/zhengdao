// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import com.example.zhengdao.rootfs.RootfsInstaller
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * R-06（审计第⑮刀，2026-10-14）：体检「一键修复」必须与会话准备/安装互斥。
 *
 * 背景：`EnvHealth.fixAction` 里有三条直接往**已装 rootfs** 写文件（DNS `etc/resolv.conf`、
 * 时区 `etc/localtime`、uv `etc/uv/uv.toml`），而 [RootfsInstaller.swapLocked] 会把整棵 rootfs
 * 改名搬走再放新的。修复通道此前不参与「会话准备锁」（[RootfsInstaller.withPrepareLock]）⇒
 * 两条路可以同时动同一棵树：刚写的配置落进被丢弃的旧树 = 修了等于没修。
 *
 * 本测试钉两条不变量：
 * ① 锁被占时修复体**不被执行、一个字节都不写**，且把原因回报给用户；
 * ② 没被占时照常执行、不残留锁。
 * 用真实线程 + `CountDownLatch` 信号，不靠 `sleep` 猜时序（同 [com.example.zhengdao.rootfs.SessionPrepareLockTest]）。
 */
class EnvHealthRepairLockTest {

    /** 起一条后台线程占住安装锁，直到 [release] 被放行。 */
    private fun holdInstallLock(inside: CountDownLatch, release: CountDownLatch): Thread =
        Thread({
            RootfsInstaller.withInstallLock("装环境") {
                inside.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }, "zd-test-installing").apply { isDaemon = true; start() }

    @Test
    fun `安装进行中时 体检修复被拒且一个字节都没写`() {
        val insideInstall = CountDownLatch(1)
        val releaseInstall = CountDownLatch(1)
        val t = holdInstallLock(insideInstall, releaseInstall)
        assertTrue("安装线程没能进入块", insideInstall.await(3, TimeUnit.SECONDS))

        var wrote = false
        val res = EnvHealth.fixGuarded { wrote = true; true }

        releaseInstall.countDown()
        t.join(3000)

        assertFalse("锁被占时修复体绝不能执行（否则会写进正在被搬走的树）", wrote)
        assertFalse("被拒时不能说改过文件", res.changed)
        assertNotNull("必须把被拒的原因回报给用户", res.error)
        assertTrue("文案要说明是安装占着：${res.error}", res.error!!.contains("正在安装"))
        assertTrue("文案要点出被拒的那件事：${res.error}", res.error!!.contains("体检修复"))
        assertFalse("安装结束后锁要释放", RootfsInstaller.isInstalling())
        assertFalse("Compose 状态也要复位", RootfsInstaller.installing.value)
    }

    @Test
    fun `没有安装时 体检修复照常执行`() {
        var wrote = false
        val res = EnvHealth.fixGuarded { wrote = true; true }
        assertTrue("没被占用时修复体必须执行", wrote)
        assertTrue("正常跑完要说改过文件", res.changed)
        assertNull("成功时不该有错误", res.error)
        assertFalse("跑完要释放准备锁", RootfsInstaller.isInstalling())
    }

    @Test
    fun `修复体抛异常时 既释放锁也不假装改过文件`() {
        val res = EnvHealth.fixGuarded { throw IllegalStateException("resolv.conf 只读") }
        assertFalse("抛异常不能说改过文件", res.changed)
        assertTrue("要带上异常原因：${res.error}", res.error!!.contains("resolv.conf 只读"))
        // 释放的判据：紧接着的安装能正常进行（准备锁没被留在手里）
        var installed = false
        RootfsInstaller.withInstallLock("装环境") { installed = true }
        assertTrue("准备锁必须已释放", installed)
        assertFalse(RootfsInstaller.isInstalling())
    }
}
