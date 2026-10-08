package com.example.zhengdao.ui

import com.example.zhengdao.ui.NetSelfCheck.Probe
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「网络自检」结论拼装的单测：四种组合都要有确定文案，尤其是
 * **国内通 + 更新源不通** 这一档——它曾是"绿灯骗人"的那一档。
 */
class NetSelfCheckTest {

    @Test
    fun bothOk_saysBothGreen() {
        val s = NetSelfCheck.summary(Probe(true, 285), Probe(true, 940))
        assertTrue(s, s.contains("✅ 网络可用（285ms）"))
        assertTrue(s, s.contains("✅ 更新源可达（940ms）"))
    }

    @Test
    fun cnOkButUpdateDown_warnsInsteadOfGreenLight() {
        val s = NetSelfCheck.summary(Probe(true, 285), Probe(false, 3200, "索引不可达"))
        assertTrue(s, s.contains("✅ 网络可用（285ms）"))
        assertTrue("必须点明下载/更新会失败", s.contains("更新源不可达"))
        assertTrue("必须给出可执行的下一步", s.contains("分应用代理"))
        assertFalse("不能只报绿灯", s.endsWith("✅ 网络可用（285ms）"))
    }

    @Test
    fun updateOkButCnDown_saysUpdateReachable() {
        val s = NetSelfCheck.summary(Probe(false, 5000, "连接超时"), Probe(true, 700))
        assertTrue(s, s.contains("国内镜像不通"))
        assertTrue(s, s.contains("连接超时"))
        assertTrue(s, s.contains("更新源可达（700ms）"))
    }

    @Test
    fun bothDown_reportsReasonAndHint() {
        val s = NetSelfCheck.summary(Probe(false, 5000, "无法解析主机名"), Probe(false, 25_000, "超时 25s"))
        assertTrue(s, s.startsWith("❌ 不通"))
        assertTrue(s, s.contains("无法解析主机名"))
        assertTrue(s, s.contains("分应用代理"))
    }

    @Test
    fun missingErrorTextFallsBackToTimeout() {
        val s = NetSelfCheck.summary(Probe(false, 5000), Probe(false, 900))
        assertTrue(s, s.contains("超时"))
    }
}
