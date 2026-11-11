// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第五百九十七批 · 判据②③的门禁负例：
// - 无视觉能力 ⇒ NoVision（"该模型看不了图"），**不发起请求**；
// - 没批 ⇒ NotApproved（"只出本地特征"），**不发起请求**；
// - 能力有＋已批 ⇒ Allow（唯一允许外发的组合）。
// 判据层不持 port，"不发起请求"由工具层测试用假 port 计数证明（PortraitAppearanceToolTest）；
// 本文件钉的是判定本身与逐字文案。
package com.example.zhengdao.baopu.portrait

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PortraitDescribeGateTest {

    @Test
    fun `判据二负例_无视觉能力_不许请求`() {
        val v = PortraitDescribeGate.decide(visionSupported = false, approved = true)
        assertFalse("没批也不行：能力是第一道", v.allowed)
        assertTrue("逐字文案", v is PortraitDescribeGate.Verdict.NoVision)
        assertEquals("该模型看不了图：未发起任何请求。", v.note)
    }

    @Test
    fun `判据三负例_没批_只出本地特征`() {
        val v = PortraitDescribeGate.decide(visionSupported = true, approved = false)
        assertFalse(v.allowed)
        assertTrue(v is PortraitDescribeGate.Verdict.NotApproved)
        assertEquals("没批外发：只出本地特征。", v.note)
    }

    @Test
    fun `能力都没有时_批准与否都到不了请求`() {
        assertFalse(PortraitDescribeGate.decide(false, false).allowed)
        assertFalse(PortraitDescribeGate.decide(false, true).allowed)
    }

    @Test
    fun `唯一放行组合_能力有且回已批`() {
        val v = PortraitDescribeGate.decide(visionSupported = true, approved = true)
        assertTrue(v.allowed)
        assertEquals("", v.note)
    }

    @Test
    fun `上限一百二十字_硬截断`() {
        val ok = "a".repeat(120)
        assertEquals(ok, PortraitDescribeGate.clamp(ok))
        val over = "a".repeat(121)
        assertEquals(120, PortraitDescribeGate.clamp(over).length)
        assertEquals("前后空白去掉", "看图", PortraitDescribeGate.clamp("  看图  "))
    }

    @Test
    fun `提示词钉死口径_只写看得见的_不评价`() {
        assertTrue(PortraitDescribeGate.PROMPT.contains("120"))
        assertTrue(PortraitDescribeGate.PROMPT.contains("不评价"))
    }
}
