// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的公开资料：resolv.conf(5)、FIPS 180-4（无直接关系，占位说明）。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * DNS 自愈判定（P7）：stale 判定与 ensureDnsFiles 的重写行为。
 * 纯文件操作（TemporaryFolder），零设备依赖。
 */
class EnvSelfHealDnsTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun resolv() = File(tmp.root, "etc/resolv.conf").apply { parentFile.mkdirs() }
    private fun hosts() = File(tmp.root, "etc/hosts").apply { parentFile.mkdirs() }

    @Test
    fun 缺文件时写入标准内容() {
        val changed = EnvSelfHeal.ensureDnsFiles(resolv(), hosts())
        assertTrue(changed)
        val text = resolv().readText()
        assertTrue("缺国内源判定", text.contains("223.5.5.5"))
        assertTrue("缺重试参数判定", text.contains("options timeout"))
        assertTrue("hosts 兜底写入", hosts().readText().contains("127.0.0.1 localhost"))
    }

    @Test
    fun 新版内容幂等_不重写() {
        EnvSelfHeal.ensureDnsFiles(resolv(), hosts())
        val before = resolv().readText()
        val changed = EnvSelfHeal.ensureDnsFiles(resolv(), hosts())
        assertTrue(!changed)
        assertEquals(before, resolv().readText())
    }

    @Test
    fun 旧版只有国际源时重写补国内源() {
        resolv().writeText("nameserver 8.8.8.8\n")
        val changed = EnvSelfHeal.ensureDnsFiles(resolv(), hosts())
        assertTrue(changed)
        val text = resolv().readText()
        assertTrue(text.contains("223.5.5.5"))
        assertTrue(text.contains("119.29.29.29"))
        assertTrue(text.contains("options timeout:1 attempts:3 rotate"))
    }

    @Test
    fun 空文件视为需重写() {
        resolv().writeText("")
        val changed = EnvSelfHeal.ensureDnsFiles(resolv(), hosts())
        assertTrue(changed)
        assertTrue(resolv().readText().contains("nameserver"))
    }
}
