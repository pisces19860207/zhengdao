// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判据①②④：partial 逐段进框 / 取消整段回滚 / 人工编辑保护。 */
class VoiceDraftTest {

    private class Rig(
        val buffer: AudioRingBuffer,
        val port: FakeAudioRecord,
        val host: VoiceHostFixture,
        val session: VoiceSession,
    )

    private fun rig(baseline: String, words: List<String>): Rig {
        val buffer = AudioRingBuffer(1024)
        val port = FakeAudioRecord(buffer, words)
        val host = VoiceHostFixture(baseline)
        val session = VoiceSession(
            draft = VoiceDraft(baseline),
            port = port,
            source = VoiceSource.SYSTEM,
            ledger = null,
            clock = { 1_000L },
            onText = { host.onVoiceText(it) },
        )
        return Rig(buffer, port, host, session)
    }

    @Test
    fun `判据1_partial逐段进框_模拟AudioRecord夹具`() {
        val r = rig("原有", listOf("今", "天", "天气"))
        assertTrue(r.session.start())
        val seen = mutableListOf<String>()
        repeat(3) {
            assertTrue(r.port.feedPcm())
            seen += r.host.text
        }
        assertEquals(listOf("原有 今", "原有 今天", "原有 今天天气"), seen)
        assertTrue("音频应在内存缓冲里", r.buffer.heldBytes() > 0)
        assertEquals(3, r.port.fedCount())
    }

    @Test
    fun `判据2_取消整段丢弃_输入框回到开始前内容`() {
        val r = rig("原有", listOf("半截", "文字"))
        r.session.start()
        r.port.feedPcm()
        assertEquals("原有 半截", r.host.text)
        val restored = r.session.cancel()
        assertEquals("原有", restored)
        assertEquals("原有", r.host.text)
        assertEquals(r.host.before, r.host.text)
        assertTrue(VoiceGate.cancelRestores(r.host.before, r.host.text))
        assertEquals("转写结束音频缓冲即清零", 0, r.buffer.heldBytes())
        assertTrue(r.port.released)
    }

    @Test
    fun `判据2_负例_留半截必须被判红_判别器不是恒真`() {
        assertFalse(VoiceGate.cancelRestores("原有", "原有 半截"))
        assertFalse(VoiceGate.cancelRestores("", "半截"))
        assertTrue(VoiceGate.cancelRestores("原有", "原有"))
        assertTrue(VoiceGate.cancelRestores("", ""))
    }

    @Test
    fun `判据4_人工编辑过的段_后续partial与final都不覆盖`() {
        val r = rig("原有", listOf("今天", "天气", "很好"))
        r.session.start()
        r.port.feedPcm()
        assertEquals("原有 今天", r.host.text)
        r.host.onUserEdit("原有 今日")
        r.session.noteUserEdit(r.host.text)
        r.port.feedPcm()
        assertEquals("人工改过的段不许被 partial 覆盖", "原有 今日", r.host.text)
        r.port.feedPcm()
        r.session.finish()
        assertEquals("收尾也不许覆盖", "原有 今日", r.host.text)
    }

    @Test
    fun `判据4_负例_不通知状态机就会覆盖_证明上条判据有区分力`() {
        val r = rig("原有", listOf("今天", "天气"))
        r.session.start()
        r.port.feedPcm()
        r.host.onUserEdit("原有 今日")
        // 故意漏掉 noteUserEdit（模拟接线断了）
        r.port.feedPcm()
        assertEquals("原有 今天天气", r.host.text)
    }

    @Test
    fun `原有内容不丢_空输入框不补多余空格`() {
        val withText = rig("原有", listOf("甲", "乙"))
        withText.session.start()
        withText.port.feedPcm()
        withText.port.feedPcm()
        withText.session.finish()
        assertEquals("原有 甲乙", withText.host.text)

        val empty = rig("", listOf("甲", "乙"))
        empty.session.start()
        empty.port.feedPcm()
        empty.port.feedPcm()
        empty.session.finish()
        assertEquals("甲乙", empty.host.text)
    }

    @Test
    fun `识别出错也整段丢弃_不留在输入框`() {
        val r = rig("原有", listOf("甲", "乙"))
        r.session.start()
        r.port.feedPcm()
        r.port.cancel()
        // 端口取消不回吐文本；由 VoiceSession 的 cancel 统一回滚
        assertEquals("原有 甲", r.host.text)
        r.session.cancel()
        assertEquals("原有", r.host.text)
    }
}
