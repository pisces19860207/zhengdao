// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 第七百八十八批 · 段 B ⑥：轮末接线件的三条判据。
//   判据①（零影响）：开关关 ⇒ 不建目录、不碰盘、既有读数逐字节不变。
//   判据③（确定性）：同输入两跑，账本逐字节一致。
//   判据④（只映射既有档）：runZone 三档恒在 0–3，越界喂进来自动降为"不落账"。
// 既有夹具取 722 的三行读回（`BodyReadback` ＋ 660/669 夹具）——它是最"像主链输出"的
// 纯文本，改一个字都能被这里抓住。
package com.example.zhengdao.baopu.brain

import com.example.zhengdao.baopu.state.form.FormFixtures
import com.example.zhengdao.baopu.state.readback.BodyReadback
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class BrainShadowTest {

    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = Files.createTempDirectory("zd788b-wire").toFile()
    }

    @After
    fun tearDown() {
        dir.walkBottomUp().forEach { it.delete() }
    }

    private fun feeds(zone: String = "glans", tier: Int = 2, proxy: Int = 2) =
        listOf(ShadowFeed(zoneId = zone, proxyTier = proxy, stimulusTier = tier))

    /** 722 三行读回（既有夹具的"主链输出"替身）。 */
    private fun mainChainBytes(): String =
        BodyReadback.render(FormFixtures.state(), FormFixtures.anatomy).lines.asText()

    @Test
    fun `开关关_一个字都不写`() {
        val wrote = BrainShadow.onRoundEnd(dir, round = 7, feeds = feeds(), enabled = false)
        assertEquals("关掉开关必须写 0 行", 0, wrote)
        assertFalse("关掉开关不许建账本文件", ShadowRecorder.under(dir).file().exists())
        assertTrue("关掉开关目录里不许出现任何文件", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `开关默认开_一行一物类`() {
        val rows = listOf(
            ShadowFeed("glans", proxyTier = 2, stimulusTier = 2),
            ShadowFeed("shaft", proxyTier = 1, stimulusTier = 1),
        )
        assertEquals(2, BrainShadow.onRoundEnd(dir, round = 7, feeds = rows))
        val lines = ShadowRecorder.under(dir).file().readLines()
        assertEquals(2, lines.size)
        assertTrue("第一行物类必须是 glans：$lines", lines[0].contains("\"zone\":\"glans\""))
        assertTrue("round 必须原样落账：$lines", lines[0].startsWith("{\"round\":7,"))
        assertTrue("字段仍是定稿那七个：$lines", lines[0].endsWith("\"seed\":24301}"))
    }

    @Test
    fun `同输入两跑账本逐字节一致`() {
        val a = File(dir, "a").apply { mkdirs() }
        val b = File(dir, "b").apply { mkdirs() }
        val rows = listOf(
            ShadowFeed("glans", 2, 2, elapsedMs = 1200),
            ShadowFeed("scrotum", 3, 3, elapsedMs = 3000),
        )
        BrainShadow.onRoundEnd(a, round = 41, feeds = rows)
        BrainShadow.onRoundEnd(b, round = 41, feeds = rows)
        assertEquals(
            "两跑账本必须逐字节相同",
            ShadowRecorder.under(a).file().readBytes().toList(),
            ShadowRecorder.under(b).file().readBytes().toList(),
        )
    }

    @Test
    fun `开关开着主链读数也一字不变`() {
        val before = mainChainBytes()
        BrainShadow.onRoundEnd(dir, round = 8, feeds = feeds())
        assertEquals("影子开着跑一轮，主链读数必须逐字节不变", before, mainChainBytes())
    }

    @Test
    fun `开关关着主链读数照样一字不变`() {
        val before = mainChainBytes()
        BrainShadow.onRoundEnd(dir, round = 8, feeds = feeds(), enabled = false)
        assertEquals(before, mainChainBytes())
        assertTrue("关掉时目录里一个文件都不该有", dir.listFiles().isNullOrEmpty())
    }

    @Test
    fun `一行坏掉只少一行_别的行照写`() {
        val rows = listOf(
            ShadowFeed("glans", proxyTier = 9, stimulusTier = 2), // 档越界 ⇒ 这行写不成
            ShadowFeed("shaft", proxyTier = 1, stimulusTier = 1),
        )
        assertEquals("坏一行只少一行", 1, BrainShadow.onRoundEnd(dir, round = 9, feeds = rows))
        val lines = ShadowRecorder.under(dir).file().readLines()
        assertEquals(1, lines.size)
        assertTrue("活下来的是良行：$lines", lines[0].contains("\"zone\":\"shaft\""))
    }

    @Test
    fun `空上下文不建账本`() {
        assertEquals(0, BrainShadow.onRoundEnd(dir, round = 1, feeds = emptyList()))
        assertFalse("没有物类就别建档", ShadowRecorder.under(dir).file().exists())
    }

    @Test
    fun `步数按时长算_夹在二十到一千二百`() {
        assertEquals(1200, BrainShadow.stepsOf(6000))
        assertEquals(20, BrainShadow.stepsOf(0))
        assertEquals(20, BrainShadow.stepsOf(-100))
        assertEquals(1200, BrainShadow.stepsOf(999_999))
        assertEquals(200, BrainShadow.stepsOf(1000))
    }

    @Test
    fun `三档恒在既有档域内`() {
        for (zone in listOf("glans", "shaft", "scrotum")) {
            for (tier in 0..Encode.MAX_TIER) {
                val shadow = BrainShadow.runZone(ShadowFeed(zone, proxyTier = tier, stimulusTier = tier))
                for (t in listOf(shadow.turgorTier, shadow.adaptTier, shadow.arousalTier)) {
                    assertTrue("$zone/$tier 映射出 $t，越域", t in 0..MapToTier.TIER_MAX)
                }
                assertTrue("帧快照不许为空", shadow.frame.isNotBlank())
                assertEquals(1200, shadow.steps)
                println(
                    "[788B-tier] $zone 刺激$tier ⇒ turgor=${shadow.turgorTier} " +
                        "适应=${shadow.adaptTier} 兴奋度=${shadow.arousalTier} " +
                        "率=%.4f".format(shadow.spikeRate),
                )
            }
        }
    }

    @Test
    fun `代理档不参与影子计算_只进账本`() {
        // 判据⑤的口径侧证：同一刺激档、不同代理档 ⇒ 影子读数必须完全相同
        val low = BrainShadow.runZone(ShadowFeed("glans", proxyTier = 0, stimulusTier = 2))
        val high = BrainShadow.runZone(ShadowFeed("glans", proxyTier = 3, stimulusTier = 2))
        assertEquals("代理档不许影响影子本身", low.frame, high.frame)
        assertEquals(low.turgorTier, high.turgorTier)
    }
}
