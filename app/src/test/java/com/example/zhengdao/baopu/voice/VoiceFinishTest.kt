// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第六百八十批 · 「结束」这一半的判据。
 *
 * 真机读数（Honor MagicVoice，见 680-语音结束-交付）：
 * - 点麦克风 → 界面进「正在听…」，12 秒后仍在，`audio_flinger` 里 magicvoice 当天 0 条录音会话
 *   ⇒ 服务接住 bind 却一次回调都不给（机制③）；
 * - 再点一下（stopListening）→ 仍不回调 ⇒ 会话永远不归零，第二下之后按钮彻底失效（机制②）。
 * 所以这里逐条钉死：**任何一条路径都必须回到 IDLE，且必须带一档失败。**
 */
class VoiceFinishTest {

    private class Rig(
        val port: FakeAudioRecord,
        val host: VoiceHostFixture,
        val session: VoiceSession,
        val scheduler: ManualScheduler,
        val phases: MutableList<VoicePhase>,
        val failures: MutableList<VoiceFailure?>,
    ) {
        val lastFailure: VoiceFailure? get() = failures.lastOrNull()
    }

    private fun rig(words: List<String>, baseline: String = "原有"): Rig {
        val port = FakeAudioRecord(AudioRingBuffer(1024), words)
        val host = VoiceHostFixture(baseline)
        val scheduler = ManualScheduler()
        val phases = mutableListOf<VoicePhase>()
        val failures = mutableListOf<VoiceFailure?>()
        val session = VoiceSession(
            draft = VoiceDraft(baseline),
            port = port,
            source = VoiceSource.SYSTEM,
            ledger = null,
            clock = { 1_000L },
            scheduler = scheduler,
            onText = { host.onVoiceText(it) },
            onPhase = { phases += it },
            onSettled = { failures += it },
        )
        return Rig(port, host, session, scheduler, phases, failures)
    }

    @Test
    fun `判据_停止后3秒无结果_用最后partial落地`() {
        val r = rig(listOf("今天", "天气"))
        r.port.respondToStop = false
        r.session.start()
        r.port.feedPcm()
        r.port.feedPcm()
        r.session.finish()

        r.scheduler.advance(VoiceSession.STOP_TIMEOUT_MS - 1)
        assertEquals("不到 3 秒不许收口", VoicePhase.STOPPING, r.session.phase)

        r.scheduler.advance(1)
        assertEquals("3 秒整必须收口", VoicePhase.IDLE, r.session.phase)
        assertEquals("半句必须落地，不许吞", "原有 今天天气", r.host.text)
        assertEquals(VoiceFailure.TIMEOUT, r.lastFailure)
        assertEquals("兜底落地也不发送", 0, r.host.sendCalls)
        assertTrue("内存缓冲要释放", r.port.released)
    }

    @Test
    fun `判据_停止后3秒无partial_明说没听清`() {
        val r = rig(listOf("甲"))
        r.port.respondToStop = false
        r.session.start()
        r.session.finish()
        r.scheduler.advance(VoiceSession.STOP_TIMEOUT_MS)
        assertEquals(VoicePhase.IDLE, r.session.phase)
        assertEquals("没有半句就还原开始前内容", "原有", r.host.text)
        assertEquals("这一档必须说「没听清」", VoiceFailure.NOT_HEARD, r.lastFailure)
    }

    @Test
    fun `判据_服务零回调_到12秒归零并说没响应`() {
        val r = rig(listOf("甲"))
        r.port.readyOnStart = false
        r.session.start()
        assertEquals(VoicePhase.LISTENING, r.session.phase)
        r.scheduler.advance(VoiceProbe.TIMEOUT_MS - 1)
        assertEquals("12 秒窗口内不许提前开火", VoicePhase.LISTENING, r.session.phase)
        r.scheduler.advance(1)
        assertEquals("零回调必须归零", VoicePhase.IDLE, r.session.phase)
        assertEquals(VoiceFailure.NO_RESPONSE, r.lastFailure)
        assertEquals(0, r.scheduler.pending())
    }

    @Test
    fun `判据_TAP失败后能再开始_第二下不是死的`() {
        val r = rig(listOf("甲"))
        r.port.readyOnStart = false
        r.session.start()
        r.scheduler.advance(VoiceProbe.TIMEOUT_MS)
        assertFalse("失败后会话必须已收口", r.session.active)

        // 用户第二下：新建一轮，必须真的能再进 LISTENING（旧代码 listening 恒 true ⇒ 死键）
        val second = rig(listOf("乙"))
        assertTrue(second.session.start())
        assertEquals(VoicePhase.LISTENING, second.session.phase)
    }

    @Test
    fun `判据_启动前置失败_按档收口且不装成在听`() {
        val r = rig(listOf("甲"))
        assertFalse("前置失败不该返回 true", r.session.start(VoiceFailure.PERMISSION))
        assertEquals(VoicePhase.IDLE, r.session.phase)
        assertEquals(VoiceFailure.PERMISSION, r.lastFailure)
        assertEquals("没起来就别动输入框", "原有", r.host.text)
    }

    @Test
    fun `判据_识别端建不起来_归零并说没响应`() {
        val r = rig(listOf("甲"))
        r.port.startOk = false
        assertFalse(r.session.start())
        assertEquals(VoicePhase.IDLE, r.session.phase)
        assertEquals(VoiceFailure.NO_RESPONSE, r.lastFailure)
    }

    @Test
    fun `判据_报错时已有半句_按超时档落地而不是丢掉`() {
        val r = rig(listOf("今天"))
        r.session.start()
        r.port.feedPcm()
        r.port.failWith(VoiceFailure.NOT_HEARD)
        assertEquals("原有 今天", r.host.text)
        assertEquals(VoiceFailure.TIMEOUT, r.lastFailure)
        assertEquals(VoicePhase.IDLE, r.session.phase)
    }

    @Test
    fun `判据_报错时没有半句_按原档说清楚`() {
        val r = rig(listOf("甲"))
        r.session.start()
        r.port.failWith(VoiceFailure.PERMISSION)
        assertEquals(VoicePhase.IDLE, r.session.phase)
        assertEquals(VoiceFailure.PERMISSION, r.lastFailure)
        assertEquals("原有", r.host.text)
    }

    @Test
    fun `判据_成功路径不重复收口_且不带失败档`() {
        val r = rig(listOf("甲", "乙"))
        r.session.start()
        r.port.feedPcm()
        r.port.feedPcm()
        r.session.finish()
        r.session.finish()
        r.session.cancel()
        assertEquals(1, r.failures.size)
        assertNull(r.lastFailure)
        assertEquals("原有 甲乙", r.host.text)
    }

    @Test
    fun `判据_四档失败文案_中英镜像且一档一句`() {
        assertEquals("失败档是闭集，只有四档", 4, VoiceFailure.values().size)
        val zh = strings("src/main/res/values/strings_voice.xml")
        val en = strings("src/main/res/values-en/strings_voice.xml")
        for (f in VoiceFailure.values()) {
            val key = "voice_fail_" + f.wire
            assertTrue("中文缺文案 $key", zh.containsKey(key) && zh[key]!!.isNotBlank())
            assertTrue("英文缺文案 $key", en.containsKey(key) && en[key]!!.isNotBlank())
            assertTrue("文案要带标点/句子，不许是占位", zh[key]!!.length >= 8)
        }
        assertEquals("四档文案必须各说各的", 4, zh.keys.filter { it.startsWith("voice_fail_") }.toSet().size)
    }

    private fun strings(rel: String): Map<String, String> {
        val f = File(rel).let { if (it.isFile) it else File("app/$rel") }
        val text = f.readText(Charsets.UTF_8)
        return Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
