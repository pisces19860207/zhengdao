// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
package com.example.zhengdao.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `/etc/hosts` 钉住项撤除的自愈逻辑（v1.2，2026-10-07）。
 *
 * 背景：曾把 `opencode.ai` 钉到硬编码 IPv4（`172.65.90.21`）以规避 IPv6 黑洞，
 * 后实测证明**收益 ≈ 0、风险是硬故障**（Cloudflare 前置、4 个轮换 IP），已撤除。
 * 本测试锁住两件事：
 * 1. 历史遗留的那行会被**主动摘除**（已装环境不能留着脚枪）；
 * 2. 只按**精确域名**匹配 —— `telemetry.opencode.ai` 绝不能被 `opencode.ai` 误伤。
 */
class EnvSelfHealTest {

    @Test
    fun `撤除 opencode 钉 IP`() {
        val src = """
            127.0.0.1 localhost
            ::1 localhost ip6-localhost ip6-loopback
            0.0.0.0 statsig.anthropic.com
            0.0.0.0 statsig.com
            0.0.0.0 telemetry.opencode.ai
            0.0.0.0 telemetry.anthropic.com
            172.65.90.21 opencode.ai
        """.trimIndent()

        val (out, removed) = EnvSelfHeal.stripObsoletePins(src)

        assertEquals(1, removed)
        assertFalse("钉住行必须被删掉", out.contains("opencode.ai\n172") || out.lines().any {
            it.trim().split(Regex("\\s+")).let { f -> f.size >= 2 && f[1] == "opencode.ai" }
        })
    }

    @Test
    fun `不误伤 telemetry_opencode_ai`() {
        val src = """
            127.0.0.1 localhost
            0.0.0.0 telemetry.opencode.ai
            172.65.90.21 opencode.ai
        """.trimIndent()

        val (out, removed) = EnvSelfHeal.stripObsoletePins(src)

        assertEquals(1, removed)
        assertTrue("telemetry.opencode.ai 必须保留", out.contains("0.0.0.0 telemetry.opencode.ai"))
        assertTrue("localhost 必须保留", out.contains("127.0.0.1 localhost"))
    }

    @Test
    fun `其余 hosts 内容原样保留`() {
        val src = "127.0.0.1 localhost\n192.168.1.9 nas\n172.65.90.21 opencode.ai\n"

        val (out, removed) = EnvSelfHeal.stripObsoletePins(src)

        assertEquals(1, removed)
        assertTrue(out.contains("192.168.1.9 nas"))
        assertTrue(out.contains("127.0.0.1 localhost"))
    }

    @Test
    fun `已是干净文本时幂等`() {
        val clean = "127.0.0.1 localhost\n0.0.0.0 statsig.com\n0.0.0.0 telemetry.opencode.ai\n"

        val first = EnvSelfHeal.stripObsoletePins(clean)
        assertEquals(0, first.second)
        assertEquals(clean.trimEnd('\n'), first.first.trimEnd('\n'))

        val second = EnvSelfHeal.stripObsoletePins(first.first)
        assertEquals(0, second.second)
        assertEquals(first.first, second.first)
    }

    @Test
    fun `tab 分隔也能正确识别`() {
        val src = "127.0.0.1\tlocalhost\n172.65.90.21\topencode.ai\n0.0.0.0\ttelemetry.opencode.ai\n"

        val (out, removed) = EnvSelfHeal.stripObsoletePins(src)

        assertEquals(1, removed)
        assertFalse(out.contains("172.65.90.21"))
        assertTrue(out.contains("telemetry.opencode.ai"))
    }

    // ── B-补-4（2026-10-16）：注释行不算「钉住项已在」 ──────────────────────────────

    @Test
    fun `B_补_4_注释行里的词元不算已在`() {
        val text = "# 0.0.0.0 opencode.ai\n127.0.0.1 localhost\n"

        val tokens = EnvSelfHeal.hostsTokens(text)

        assertTrue("生效行里的词元要在集合里", "localhost" in tokens)
        assertFalse("注释行里的钉住域名不能算「已在」（否则永远补不上）", "opencode.ai" in tokens)
    }

    @Test
    fun `B_补_4_生效行与行尾注释都正确切分`() {
        val text = "0.0.0.0 statsig.com # 行尾注释\n\t172.65.90.21\topencode.ai\n\n"

        val tokens = EnvSelfHeal.hostsTokens(text)

        assertTrue(
            "生效行的地址与域名都要在集合里",
            tokens.containsAll(setOf("0.0.0.0", "statsig.com", "172.65.90.21", "opencode.ai"))
        )
        assertFalse("行尾注释里的词不能混进来", tokens.any { it.contains("注释") })
    }

    // ── 源码级门禁：这两条债不许悄悄回归 ─────────────────────────────────────────

    @Test
    fun `门禁_B_补_3_resolv_只读一次`() {
        val src = terminalSource("EnvSelfHeal.kt")
        // 只看代码行：注释里会出现 `readText()` 这个词（本债的说明就在注释里），别把它算进去。
        val codeLines = src.lines().filterNot { line ->
            val t = line.trimStart()
            t.startsWith("//") || t.startsWith("*") || t.startsWith("/*")
        }

        assertEquals(
            "resolv.readText() 在代码里只允许出现一次（B-补-3；连读两次会得到半新半旧的判定）",
            1,
            codeLines.sumOf { Regex("resolv\\.readText\\(\\)").findAll(it).count() }
        )
        assertTrue(
            "应当先读进局部变量再判定",
            src.contains("val resolvText = if (resolv.isFile) resolv.readText() else \"\"")
        )
    }

    @Test
    fun `门禁_B_补_4_hosts_已存在集合走 hostsTokens`() {
        val src = terminalSource("EnvSelfHeal.kt")

        assertTrue("ensureHosts 必须用 hostsTokens 判定「已在」", src.contains("val present = hostsTokens(cur)"))
        assertFalse("不能再用整段 split 当集合（注释行会被算进去）", src.contains("val present = cur.split("))
    }

    /** 双路径找源码（Gradle 工作目录可能是仓库根，也可能是 `app/`）。 */
    private fun terminalSource(name: String): String {
        val candidates = listOf(
            java.io.File("src/main/java/com/example/zhengdao/terminal/$name"),
            java.io.File("app/src/main/java/com/example/zhengdao/terminal/$name"),
        )
        val file = candidates.firstOrNull { it.isFile }
            ?: error("找不到源码 $name，试过：${candidates.joinToString { it.path }}")
        return file.readText()
    }
}
