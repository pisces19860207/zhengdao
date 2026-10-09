// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 「有安装/构建在跑吗」的探测单测（[AgentProcesses]，Issue #3）。
 *
 * 这条守卫决定自动清理**动不动手**，判错的两个方向代价不对称：
 * 漏判 ⇒ 删掉正在用的缓存（那次安装白下、可能失败）；误判 ⇒ 这次不清（下次启动再说）。
 * 所以口径写死成"词边界命中"，并把真机上真出现过的形态（hermes 的 `uv.real`、
 * `apt-get`、`python3.14`）与**不该命中**的形态（`uvicorn`）都钉进用例。
 *
 * `/proc` 用假目录注入（[AgentProcesses.scan] 的 `procRoot`/`selfPid` 参数就是为此留的）。
 */
class AgentProcessesTest {

    private fun tmpRoot(): File = File.createTempFile("zd-proc", "").let {
        it.delete(); it.mkdirs(); it
    }

    private fun proc(root: File, pid: String, cmdline: String): File {
        val d = File(root, pid)
        d.mkdirs()
        File(d, "cmdline").writeBytes(cmdline.toByteArray(Charsets.UTF_8))
        return d
    }

    /** 真机形态：hermes 的内嵌 uv 被包装成 shell 脚本，真身叫 `uv.real`。 */
    @Test
    fun `cmdline 拆成 basename，uv 包装器的真身归一成 uv`() {
        val tokens = AgentProcesses.tokensOf(
            "/root/.hermes/tools/uv-0.12.3-linux-arm64/uv.real\u0000cache\u0000prune\u0000"
        )
        assertEquals(listOf("uv", "cache", "prune"), tokens)
    }

    @Test
    fun `词边界：uvicorn 不算，uv点real 与 apt-get 与 python3点14 算`() {
        assertNull("前缀相同但不是一个程序", AgentProcesses.matchGuard("uvicorn"))
        assertEquals("uv", AgentProcesses.matchGuard("uv.real"))
        assertEquals("apt", AgentProcesses.matchGuard("apt-get"))
        assertNotNull("python3.14 应当命中 python/python3 之一", AgentProcesses.matchGuard("python3.14"))
        assertNull(AgentProcesses.matchGuard("gitignore"))
        assertEquals("git", AgentProcesses.matchGuard("git"))
    }

    @Test
    fun `扫到正在跑的 uv 与 npm，空 cmdline 与不可读项不算`() {
        val root = tmpRoot()
        proc(root, "111", "uv\u0000cache\u0000prune\u0000")
        proc(root, "222", "/usr/bin/npm\u0000run\u0000build\u0000")
        proc(root, "333", "")                       // 空 cmdline（僵尸/内核线程）
        File(root, "self").mkdirs()                 // 非数字目录
        File(root, "444").mkdirs()                  // 连 cmdline 都没有
        val found = AgentProcesses.scan(root, selfPid = 0)
        assertEquals(2, found.size)
        assertTrue(found.any { it.startsWith("111 ") && it.contains("uv") })
        assertTrue(found.any { it.startsWith("222 ") && it.contains("npm") })
    }

    @Test
    fun `不把自己这个 App 进程算成在干活`() {
        val root = tmpRoot()
        proc(root, "111", "uv\u0000cache\u0000prune\u0000")
        // 自己的 cmdline 里恰好含守卫词时，也不能永远"忙"
        proc(root, "222", "com.example.zhengdao\u0000hermes\u0000")
        val found = AgentProcesses.scan(root, selfPid = 222)
        assertEquals(1, found.size)
        assertTrue(found.first().startsWith("111 "))
    }

    @Test
    fun `没人干活时命中集为空`() {
        val root = tmpRoot()
        proc(root, "111", "/system/bin/sh\u0000-c\u0000echo hi\u0000")
        proc(root, "222", "com.example.zhengdao\u0000")
        assertTrue(AgentProcesses.scan(root, selfPid = 0).isEmpty())
        assertTrue(AgentProcesses.hits(AgentProcesses.tokensOf("ls\u0000-la\u0000")).isEmpty())
        assertFalse(AgentProcesses.boundaryMatch("hermesx", "hermes"))
    }
}
