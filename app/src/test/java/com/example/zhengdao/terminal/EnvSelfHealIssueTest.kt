package com.example.zhengdao.terminal

import com.example.zhengdao.core.IssueCenter
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * #4（全 App 错误提示走查）：自愈失败要**在界面上可见**，而且成功后要**自己撤下**。
 *
 * 这两件事必须成对：只 report 不 resolve，用户修好之后那条「最近问题」会一直挂着，
 * 比不报更糟（他会以为没修好）。所以自愈函数进 try 就先 `resolve`——
 * 本次没炸 = 故障不在了，这是幂等自愈的自然语义。
 */
class EnvSelfHealIssueTest {

    private lateinit var rootfs: File

    @Before
    fun setUp() {
        IssueCenter.clear()
        rootfs = Files.createTempDirectory("zd-env-selfheal").toFile()
    }

    @After
    fun tearDown() {
        IssueCenter.clear()
    }

    @Test
    fun `uv 配置写成功后会撤下上次的失败记录`() {
        IssueCenter.report(id = "selfheal-uv", title = "uv 系统级配置没能写入", detail = "上一次的失败")

        val changed = EnvSelfHeal.ensureUvConfig(rootfs)

        assertTrue("首次写入应当有变更", changed)
        assertTrue("uv.toml 必须落盘", File(rootfs, "etc/uv/uv.toml").isFile)
        assertFalse(
            "修好了还挂着失败记录 = 用户以为没修好",
            IssueCenter.snapshot().any { it.id == "selfheal-uv" }
        )
    }

    @Test
    fun `uv 配置写不进去时留下可处理的记录`() {
        // 在 uv.toml 该在的位置放一个**目录**：写入必炸（跨平台，不依赖权限模型）
        val uvDir = File(rootfs, "etc/uv").apply { mkdirs() }
        File(uvDir, "uv.toml").mkdirs()

        val changed = EnvSelfHeal.ensureUvConfig(rootfs)

        assertFalse("写不进去不该报成功", changed)
        val issue = IssueCenter.snapshot().firstOrNull { it.id == "selfheal-uv" }
        assertTrue("必须留下记录，否则用户只会看到「奇怪，还是不生效」", issue != null)
        assertTrue("记录要有去路", issue!!.actionId == IssueCenter.ACTION_REPAIR_ENV)
    }

    @Test
    fun `DNS 与 hosts 自愈成功时两条记录一起撤下`() {
        IssueCenter.report(id = "selfheal-dns", title = "DNS 配置没能写入", detail = "旧账")
        IssueCenter.report(id = "selfheal-hosts", title = "/etc/hosts 没能写入", detail = "旧账")

        EnvSelfHeal.ensureDnsFiles(File(rootfs, "etc/resolv.conf"), File(rootfs, "etc/hosts"))

        assertTrue("resolv.conf 必须落盘", File(rootfs, "etc/resolv.conf").isFile)
        assertTrue("hosts 必须落盘", File(rootfs, "etc/hosts").isFile)
        assertFalse(
            "两条记录都要撤",
            IssueCenter.snapshot().any { it.id == "selfheal-dns" || it.id == "selfheal-hosts" }
        )
    }
}
