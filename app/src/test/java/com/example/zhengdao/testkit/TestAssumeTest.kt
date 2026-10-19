package com.example.zhengdao.testkit

import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.AssumptionViolatedException
import org.junit.Test

/**
 * P0-3 的门禁：跳过出口自身的行为必须可测，否则「统一原因」只是口头约定。
 */
class TestAssumeTest {

    @Test
    fun `原因为空必须被拒绝`() {
        val e = assertThrows(IllegalArgumentException::class.java) {
            TestAssume.assume(condition = false, reason = "  ")
        }
        assertTrue("异常信息应指名 P0-3：${e.message}", e.message.orEmpty().contains("P0-3"))
    }

    @Test
    fun `条件不成立时带原因跳过`() {
        val e = assertThrows(AssumptionViolatedException::class.java) {
            TestAssume.assume(condition = false, reason = "本机缺少测试夹具")
        }
        assertTrue("跳过原因应原样进报告：${e.message}", e.message.orEmpty().contains("本机缺少测试夹具"))
    }

    @Test
    fun `软链不支持时用统一中文原因`() {
        val e = assertThrows(AssumptionViolatedException::class.java) {
            TestAssume.symlinkUnsupported(created = false)
        }
        assertTrue("软链原因应统一：${e.message}", e.message.orEmpty().contains("本机不支持创建符号链接"))
        assertTrue("软链原因应带 E-138：${e.message}", e.message.orEmpty().contains("E-138"))
    }

    @Test
    fun `外部工具缺失时指名工具`() {
        val e = assertThrows(AssumptionViolatedException::class.java) {
            TestAssume.externalTool(present = false, tool = "node")
        }
        assertTrue("原因应指名 node：${e.message}", e.message.orEmpty().contains("node"))
    }
}
