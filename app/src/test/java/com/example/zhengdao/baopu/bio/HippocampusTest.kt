// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
// 可参考的官方资料清单见仓库根目录 PROVENANCE.md。
//
// 第六百九十四批 · 海马单测：键形状＋hint 只含元数据（敏感夹具）＋指纹形态＋
// 不重放不合并＋确定性。"不含正文"钉法：线索正文用敏感夹具（证件号/手机号/
// 卡号/密码/住址），断言输出里这些片段一个都不出现——指纹是单向摘要，
// 夹具正文从指纹里读不回来。
package com.example.zhengdao.baopu.bio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HippocampusTest {

    /** 敏感夹具：只在本测试里造、只喂给海马，跑完即弃（不落盘）。 */
    private val sensitive = listOf(
        "身份证 110101199003077777",
        "手机 13812345678",
        "卡号 6222000012345678",
        "密码 P@ssw0rd!",
        "住址 幸福小区3栋201",
    )

    private fun key(
        sessionId: Long = 42L,
        seq: Int = 3,
        text: String = sensitive.first(),
        source: String = "chat",
    ): Hippocampus.EpisodeKey = Hippocampus.key(
        sessionId = sessionId,
        seq = seq,
        cue = BioEvent(text = text, source = source),
    )

    // ── 键形状：sessionId/seq 原样回显；hint 是元数据一句话 ──

    @Test
    fun keyEchoesSessionAndSeqVerbatim() {
        val k = key(sessionId = 777L, seq = 12, text = "随便说点什么", source = "tools")
        assertEquals(777L, k.sessionId)
        assertEquals(12, k.seq)
        assertTrue(k.hint.contains("会话 777 第 12 轮"))
        assertTrue(k.hint.contains("来源 tools"))
        assertTrue(k.hint.contains("文长"))
        assertTrue(k.hint.contains("指纹 "))
    }

    // ── 敏感夹具（本批判据）：正文片段一个都不出现在输出里 ──

    @Test
    fun hintCarriesNoBodyTextEvenWithSensitiveFixture() {
        val whole = sensitive.joinToString("；")
        val k = key(text = whole)
        for (piece in sensitive) {
            assertTrue("夹具片段不得进 hint：$piece", piece !in k.hint)
        }
        assertTrue("夹具全文不得进 hint", whole !in k.hint)
        assertTrue("夹具全文不得进整个键的字符串形态", whole !in k.toString())
    }

    @Test
    fun differentSecretsAtSameAddressStayDifferentKeysWithoutLeaking() {
        val a = key(seq = 5, text = sensitive[1])
        val b = key(seq = 5, text = sensitive[3])
        assertEquals(a.sessionId, b.sessionId) // 地址不变：不合并、不重排
        assertEquals(a.seq, b.seq)
        assertNotEquals(a.hint, b.hint) // 内容不同 ⇒ 指纹不同（678 对"是否再发生"靠这个）
        assertTrue(sensitive[1] !in b.hint && sensitive[3] !in a.hint) // 互不串门
    }

    // ── 指纹形态：12 位小写十六进制；同文同指纹、异文异指纹；空文是 "-" ──

    @Test
    fun fingerprintIsStableTwelveHexAndContentFree() {
        val k1 = key(text = "固定的一句话")
        val k2 = key(text = "固定的一句话")
        assertEquals(k1, k2) // 确定性（判据④）
        val fp = k1.hint.substringAfter("指纹 ")
        assertTrue(fp.isNotEmpty())
        assertEquals(12, fp.length)
        assertTrue(fp.all { it in '0'..'9' || it in 'a'..'f' })
        assertNotEquals(fp, key(text = "另一句话").hint.substringAfter("指纹 "))
    }

    @Test
    fun emptyTextGetsEmptyBandAndNoFingerprint() {
        val k = key(text = "")
        assertTrue(k.hint.contains("文长空"))
        assertTrue(k.hint.contains("指纹 -"))
    }

    // ── 文长档边界（只暴露量级，不暴露原文）──

    @Test
    fun lengthBandsFollowThresholds() {
        assertTrue(key(text = "a".repeat(40)).hint.contains("文长短"))
        assertTrue(key(text = "a".repeat(41)).hint.contains("文长中"))
        assertTrue(key(text = "a".repeat(200)).hint.contains("文长中"))
        assertTrue(key(text = "a".repeat(201)).hint.contains("文长长"))
    }

    // ── 不重放、不合并：本件无状态——同键发两次互不影响，也没有"上次"可用 ──

    @Test
    fun issuingKeysTwiceLeavesNoStateBehind() {
        val first = key(text = sensitive[0])
        val again = key(text = sensitive[0])
        assertEquals(first, again) // 纯函数：没有计数器、没有记忆，第二次不"长记性"
        val elsewhere = key(sessionId = 999L, seq = 1, text = sensitive[0])
        assertNotEquals(first.hint, elsewhere.hint) // 换地址就换键，不合并
        assertEquals(999L, elsewhere.sessionId)
    }
}
