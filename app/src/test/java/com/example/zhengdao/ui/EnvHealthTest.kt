// 独立开发声明：本文件为本项目从零编写，未参考任何第三方同类应用的代码。
package com.example.zhengdao.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 环境体检「网络连通性」判定的单测（[EnvHealth.networkVerdict]）。
 *
 * 背景（2026-10-07 真机实测）：这一项曾是**恒 ✗**——manifest 缺 ACCESS_NETWORK_STATE，
 * `getNetworkCapabilities()` 抛 SecurityException 被 runCatching 吞掉，于是永远判失败，
 * 与网络好坏无关（用户手机上 WiFi 明明已 VALIDATED）。修法是补权限 + 不再只看系统
 * 标记（VPN／企业网下该标记常为 false，但网络可用），不确定时用真实 TCP 探测兜底。
 * 这里把四种组合的结论锁死，防止回归成"猜"。
 */
class EnvHealthTest {

    @Test
    fun `系统已验证直接通过`() {
        val (ok, detail) = EnvHealth.networkVerdict(validated = true, reachable = true, hasInternet = true)
        assertTrue(ok)
        assertEquals("宿主网络可用（系统已验证）", detail)
    }

    @Test
    fun `系统未标记但实测连通也算通过`() {
        // VPN / 企业网 / 部分运营商网络的常见形态：能用，但系统没打 VALIDATED
        val (ok, detail) = EnvHealth.networkVerdict(validated = false, reachable = true, hasInternet = true)
        assertTrue(ok)
        assertEquals("宿主网络可用（实测已连通）", detail)
    }

    @Test
    fun `连上了网但外网不通要点明代理或受限`() {
        val (ok, detail) = EnvHealth.networkVerdict(validated = false, reachable = false, hasInternet = true)
        assertFalse(ok)
        assertEquals("已连上网络但外网不通（代理或受限网络？）", detail)
    }

    @Test
    fun `完全没网才报不可用`() {
        val (ok, detail) = EnvHealth.networkVerdict(validated = false, reachable = false, hasInternet = false)
        assertFalse(ok)
        assertEquals("宿主网络不可用，Agent 安装与更新会失败", detail)
    }
}
