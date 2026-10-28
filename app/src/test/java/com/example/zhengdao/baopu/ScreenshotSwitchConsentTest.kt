// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
//
// 依据：262 批第二步的切屏征询卡（ScreenshotConsent.kt 内 ScreenshotSwitchConsent）。
// 纯函数 ⇒ JVM 直测；卡面判据与「超时＝跳过」纪律在这里钉死。
package com.example.zhengdao.baopu

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenshotSwitchConsentTest {

    // ── D1 卡面：两张票、同意带推荐、写明时长与切回承诺 ──────────────────────

    @Test
    fun `D1_卡面两张票_同意带推荐`() {
        val card = ScreenshotSwitchConsent.request("example.com", "示例页")

        assertEquals("切屏征询", card.subject)
        assertEquals("只有同意/跳过两张票", 2, card.options.size)
        val agree = card.options.first { it.id == ScreenshotSwitchConsent.OPT_AGREE }
        val skip = card.options.first { it.id == ScreenshotSwitchConsent.OPT_SKIP }
        assertTrue("同意是推荐项", agree.recommended)
        assertTrue("同意要写明时长承诺（约 2 秒）——引擎的等待上限与它对账", agree.detail.contains("约 2 秒"))
        assertTrue("同意要写明切回承诺", agree.detail.contains("切回"))
        assertTrue("同意要写明滚动位置不动（工单红线）", agree.detail.contains("滚动位置不动"))
        assertTrue("跳过要写明不算失败", skip.detail.contains("不算失败"))
        assertTrue("卡面要写明只切 App 内页签", card.detailText.contains("页签"))
        assertTrue("卡面要写明不动其他 App", card.detailText.contains("不动其他 App"))
    }

    // ── D2 卡面：拿不到地址就不写括号 ──────────────────────────────────────

    @Test
    fun `D2_卡面_拿不到地址就不写括号`() {
        val card = ScreenshotSwitchConsent.request(null, null)
        assertFalse("空 where 不许留一个空括号", card.question.contains("（）"))
        assertTrue(card.question.contains("玄览的截图"))
    }

    // ── D2b 判定矩阵：同意⇒AGREE；跳过/超时/认不出⇒SKIP ─────────────────────

    @Test
    fun `D2b_判定矩阵_超时与认不出都是跳过`() {
        assertEquals(
            ScreenshotSwitchConsent.Decision.AGREE,
            ScreenshotSwitchConsent.decide(ScreenshotSwitchConsent.OPT_AGREE),
        )
        assertEquals(
            ScreenshotSwitchConsent.Decision.SKIP,
            ScreenshotSwitchConsent.decide(ScreenshotSwitchConsent.OPT_SKIP),
        )
        assertEquals(
            "超时＝没人答 ⇒ 跳过——「超时算同意切屏」与「超时算同意截图」是同一种错",
            ScreenshotSwitchConsent.Decision.SKIP,
            ScreenshotSwitchConsent.decide(null),
        )
        assertEquals(
            "认不出的 id 同样跳过",
            ScreenshotSwitchConsent.Decision.SKIP,
            ScreenshotSwitchConsent.decide("whatever"),
        )
    }

    // ── D3 跳过文案：要让模型换路，别当失败重试 ────────────────────────────

    @Test
    fun `D3_跳过文案_点名SKIPPED_NO_VISUAL与文字退路`() {
        val text = ScreenshotSwitchConsent.skippedText()
        assertTrue(text.contains("SKIPPED_NO_VISUAL"))
        assertTrue(text.contains("不算失败"))
        assertTrue("要指路文字证据（不依赖前台）", text.contains("browser_snapshot"))
        assertTrue("要指路新证据命令", text.contains("browser_evidence"))
    }
}
