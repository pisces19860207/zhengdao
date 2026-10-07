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
}
