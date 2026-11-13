// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 判据③（转完不自动发送）＋ 隐私硬线「账只记三字段」的行为级读数。 */
class VoiceSessionTest {

    private class Rig(
        val port: FakeAudioRecord,
        val host: VoiceHostFixture,
        val session: VoiceSession,
    )

    private fun rig(words: List<String>, ledger: VoiceLedger? = null): Rig {
        val port = FakeAudioRecord(AudioRingBuffer(1024), words)
        val host = VoiceHostFixture("原有")
        val session = VoiceSession(
            draft = VoiceDraft("原有"),
            port = port,
            source = VoiceSource.SYSTEM,
            ledger = ledger,
            clock = { 1_000L },
            onText = { host.onVoiceText(it) },
        )
        return Rig(port, host, session)
    }

    @Test
    fun `判据3_转完未调用send_只有点发送按钮才发`() {
        val r = rig(listOf("今天", "天气"))
        r.session.start()
        r.port.feedPcm()
        r.port.feedPcm()
        r.session.finish()
        assertEquals("原有 今天天气", r.host.text)
        assertEquals("语音结束绝不自动发送", 0, r.host.sendCalls)
        assertTrue(sendNotCalled(r.host.sendCalls))

        r.host.clickSend()
        assertEquals("正例对照：spy 真的能记到发送", 1, r.host.sendCalls)
        assertFalse(sendNotCalled(r.host.sendCalls))
    }

    @Test
    fun `判据3_判别器自证_自动发送一次就判红`() {
        assertTrue(sendNotCalled(0))
        assertFalse(sendNotCalled(1))
        assertFalse(sendNotCalled(3))
    }

    @Test
    fun `取消也不发送_输入框回到开始前内容`() {
        val r = rig(listOf("甲", "乙"))
        r.session.start()
        r.port.feedPcm()
        r.session.cancel()
        assertEquals("原有", r.host.text)
        assertEquals(0, r.host.sendCalls)
    }

    @Test
    fun `账只记三字段_时长来源是否成功_不含音频与文本`() {
        val row = VoiceLedger.Row(1_234L, VoiceSource.LOCAL, true)
        val keys = JSONObject(row.toJson()).keys().asSequence().toSet()
        assertEquals(VoiceLedger.Row.KEYS, keys)
        assertEquals(3, keys.size)
        for (banned in listOf("audio", "text", "path", "pcm", "buffer", "content")) {
            assertFalse("账里不许出现 $banned", keys.contains(banned))
        }
        assertEquals(row, VoiceLedger.Row.fromJson(row.toJson()))
    }

    @Test
    fun `一轮真落一条账_成功与取消各记一次`() {
        TempSandbox.use("zd633-voice") { dir ->
            val ledger = VoiceLedger(File(dir, VoiceLedger.FILE_NAME))
            val success = rig(listOf("甲", "乙"), ledger)
            success.session.start()
            success.port.feedPcm()
            success.port.feedPcm()
            success.session.finish()

            val cancelled = rig(listOf("甲"), ledger)
            cancelled.session.start()
            cancelled.port.feedPcm()
            cancelled.session.cancel()

            val rows = ledger.rows()
            assertEquals(2, rows.size)
            assertEquals(VoiceSource.SYSTEM, rows[0].source)
            assertTrue(rows[0].success)
            assertFalse(rows[1].success)
            for (r in rows) {
                assertTrue(r.durationMs >= 0L)
                assertEquals(VoiceLedger.Row.KEYS, JSONObject(r.toJson()).keys().asSequence().toSet())
            }
        }
    }

    private fun sendNotCalled(calls: Int): Boolean = calls == 0
}
