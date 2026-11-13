// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第六百二十批收口 · 「码 → 归属」的判据（用户 2026-11-13 裁定的四条）。
//
// 这条缝是 620/631 联调时实测出来的：631 写进 `err` 的 11 条码，
// **一条都不命中** 620 的中文词表 ⇒ 全部落「未归因」⇒ 该弹的行为类约束卡一张不弹。
// 收口后：码是主判据、文本只是兜底、码表只有一份（[ToolErrCodes.ALL]）。
package com.example.zhengdao.baopu

import com.example.zhengdao.baopu.agent.ToolErrCodes

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolErrCodeAttributionTest {

    /** 用户裁定的归属表（故意在这里再写一遍：改了生产表而没改这条 ⇒ 测试红）。 */
    private val expected: Map<String, FailureOwner> = mapOf(
        "dns" to FailureOwner.ENVIRONMENT,
        "port" to FailureOwner.ENVIRONMENT,
        "tls" to FailureOwner.ENVIRONMENT,
        "timeout" to FailureOwner.ENVIRONMENT,
        "version" to FailureOwner.ENVIRONMENT,
        "endpoint" to FailureOwner.ENVIRONMENT,
        "auth" to FailureOwner.ENVIRONMENT,
        "config" to FailureOwner.ENVIRONMENT,
        // protocol：模型把参数发错形状 ⇒ 行为类（若证据显示是对端/协议侧问题，改归环境类）
        "protocol" to FailureOwner.BEHAVIOR,
        "refused" to FailureOwner.ENVIRONMENT,
        "network" to FailureOwner.ENVIRONMENT,
    )

    // ── 判据①：11 条码各一条用例（逐条断言归属符合裁定表）──────────────
    private fun assertCode(code: String) {
        val expectedOwner = expected.getValue(code)
        val line = ToolErrCodes.byCode(code)?.line ?: error("码表里没有 $code")
        val a = ToolFailureAttribution.classify(line)
        assertEquals("码「$code」的归属不对（$line）", expectedOwner, a.owner)
        assertTrue("码「$code」的根因键必须稳定可查：${a.rootCause}", a.rootCause.contains(code.uppercase()))
        assertFalse("码「$code」不该落未归因", a.owner == FailureOwner.UNATTRIBUTED)
    }

    @Test fun `判据1_dns_环境类`() = assertCode("dns")
    @Test fun `判据1_port_环境类`() = assertCode("port")
    @Test fun `判据1_tls_环境类`() = assertCode("tls")
    @Test fun `判据1_timeout_环境类`() = assertCode("timeout")
    @Test fun `判据1_version_环境类`() = assertCode("version")
    @Test fun `判据1_endpoint_环境类`() = assertCode("endpoint")
    @Test fun `判据1_auth_环境类`() = assertCode("auth")
    @Test fun `判据1_config_环境类`() = assertCode("config")
    @Test fun `判据1_protocol_行为类`() = assertCode("protocol")
    @Test fun `判据1_refused_环境类`() = assertCode("refused")
    @Test fun `判据1_network_环境类`() = assertCode("network")

    // ── 判据②：protocol ⇒ 提卡；其余 10 条 ⇒ 不提卡 ──────────────────────
    @Test
    fun `判据2_protocol提一张卡_其余十条都不提`() {
        val protocol = ToolFailureAttribution.classify(ToolErrCodes.byCode("protocol")!!.line)
        assertTrue("protocol 是行为类 ⇒ 必须允许提约束卡", ToolFailureAttribution.mayProposeRule(protocol))

        val others = ToolErrCodes.ALL.filter { it.code != "protocol" }
        assertEquals("除 protocol 外应是 10 条", 10, others.size)
        for (c in others) {
            val a = ToolFailureAttribution.classify(c.line)
            assertFalse("「${c.code}」是环境类，不许提约束卡", ToolFailureAttribution.mayProposeRule(a))
        }
    }

    // ── 判据③：码与文本都认不出 ⇒ 未归因（不弹卡、不硬猜）──────────────
    @Test
    fun `判据3_码和文本都无_未归因且不弹卡`() {
        val a = ToolFailureAttribution.classify("something odd happened")
        assertEquals(FailureOwner.UNATTRIBUTED, a.owner)
        assertEquals(ToolFailureAttribution.NO_CLUE_ROOT, a.rootCause)
        assertFalse(ToolFailureAttribution.mayProposeRule(a))
    }

    // ── 判据④：完整性断言 —— 每个码都有归属；新增码未映射 ⇒ 红 ──────────
    @Test
    fun `判据4_码表完整性_每个码都有归属且都能判出来`() {
        for (c in ToolErrCodes.ALL) {
            assertTrue("码「${c.code}」的固定短句必须以「码 + 分隔符」开头", c.line.startsWith(c.code + ToolErrCodes.SEPARATOR))
            assertTrue("码「${c.code}」没有特征词 ⇒ 写侧永远认不出它", c.patterns.isNotEmpty())
            // 归属必须在裁定表里，且判得出来（新增码没写归属 ⇒ 这里红）
            assertTrue("码「${c.code}」不在裁定表里 ⇒ 加了码忘了归属", expected.containsKey(c.code))
            val a = ToolFailureAttribution.classify(c.line)
            assertEquals("码「${c.code}」判不出归属（落未归因）", expected.getValue(c.code), a.owner)
            assertEquals("码「${c.code}」表内外归属不一致", c.owner, expected.getValue(c.code))
        }
        assertEquals("码表条数变了必须同步改裁定表", expected.size, ToolErrCodes.ALL.size)
    }

    // ── 判据⑥：文本兜底仍在（码认不出时才用它）─────────────────────────
    @Test
    fun `判据6_没有码时文本兜底仍然生效`() {
        val a = ToolFailureAttribution.classify("失败：未知工具「nope」")
        assertEquals("没有码 ⇒ 退回中文词表：未知工具＝行为类", FailureOwner.BEHAVIOR, a.owner)
        val b = ToolFailureAttribution.classify("失败：路径不存在")
        assertEquals("没有码 ⇒ 路径不存在＝环境类", FailureOwner.ENVIRONMENT, b.owner)
    }

    /** 码优先于文本：同一句里两种味道都在时，以码为准。 */
    @Test
    fun `判据6_码优先于文本_同句冲突时以码为准`() {
        // 「timeout: …」是环境类；句子后半出现「未知工具」这类行为类词，也必须是环境类
        val a = ToolFailureAttribution.classify("timeout: 等太久没回音｜未知工具 x")
        assertEquals("码优先：timeout ⇒ 环境类，不许被文本带成行为类", FailureOwner.ENVIRONMENT, a.owner)
        assertFalse(ToolFailureAttribution.mayProposeRule(a))
    }
}
