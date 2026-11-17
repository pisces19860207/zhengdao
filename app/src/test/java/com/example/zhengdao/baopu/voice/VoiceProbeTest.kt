// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import com.example.zhengdao.testkit.TempSandbox
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 第七百批 · 「系统识别一个字都不回」的判据（纯 JVM 夹具，不碰真机）。
 *
 * 病（680 已分层，本批采纳）：MagicVoice 服务侧连开始都没接单——12 秒里四个回调
 * 一个都不来；`audio_flinger` 当天 0 条录音会话。TAP 死键只是它的后果。
 *
 * 本批只做一件事：把这个「静默」变成**可判、可记账、可引导**的读数，
 * 并且不再用「等 ready」这种会把正常引擎误杀的判据。
 */
class VoiceProbeTest {

    private class Rig(
        val port: FakeAudioRecord,
        val host: VoiceHostFixture,
        val session: VoiceSession,
        val scheduler: ManualScheduler,
        val failures: MutableList<VoiceFailure?>,
        val probes: MutableList<VoiceProbe>,
    ) {
        val lastFailure: VoiceFailure? get() = failures.lastOrNull()
        val lastProbe: VoiceProbe? get() = probes.lastOrNull()
    }

    private fun rig(
        words: List<String> = listOf("甲"),
        baseline: String = "原有",
        ledger: VoiceLedger? = null,
        probeTimeoutMs: Long = VoiceProbe.TIMEOUT_MS,
    ): Rig {
        val port = FakeAudioRecord(AudioRingBuffer(1024), words)
        val host = VoiceHostFixture(baseline)
        val scheduler = ManualScheduler()
        val failures = mutableListOf<VoiceFailure?>()
        val probes = mutableListOf<VoiceProbe>()
        val session = VoiceSession(
            draft = VoiceDraft(baseline),
            port = port,
            source = VoiceSource.SYSTEM,
            ledger = ledger,
            clock = { 1_000L },
            scheduler = scheduler,
            onText = { host.onVoiceText(it) },
            onPhase = {},
            onSettled = { failures += it },
            probeTimeoutMs = probeTimeoutMs,
            onProbe = { probes += it },
        )
        return Rig(port, host, session, scheduler, failures, probes)
    }

    @Test
    fun `判据1_注入零回调识别器_12秒判未响应且相位归零`() {
        val r = rig()
        r.port.readyOnStart = false
        r.session.start()
        assertEquals(VoicePhase.LISTENING, r.session.phase)

        r.scheduler.advance(VoiceProbe.TIMEOUT_MS - 1)
        assertEquals("12 秒窗口内不许提前判死", VoicePhase.LISTENING, r.session.phase)
        assertNull("还没到点就不许有读数", r.lastProbe)

        r.scheduler.advance(1)
        assertEquals("判未响应后必须归零（TAP 可再点）", VoicePhase.IDLE, r.session.phase)
        assertFalse("会话必须已收口", r.session.active)
        assertEquals(VoiceFailure.NO_RESPONSE, r.lastFailure)
        val probe = r.lastProbe
        assertNotNull("必须留一份探测读数", probe)
        assertTrue("零回调才算未响应", probe!!.silent)
        assertEquals(0, probe.callbacks)
        assertEquals(VoiceProbe.SILENT, probe.conclusion)
        assertEquals("看门狗必须撤干净", 0, r.scheduler.pending())
        assertTrue("内存缓冲要释放", r.port.released)
    }

    @Test
    fun `判据1_文案含12秒内无回调_中英各一句`() {
        val zh = strings("src/main/res/values/strings_voice.xml")
        val en = strings("src/main/res/values-en/strings_voice.xml")
        val zhText = zh["voice_probe_silent"].orEmpty()
        val enText = en["voice_probe_silent"].orEmpty()
        assertTrue("中文必须说清「12 秒内无回调」：$zhText", zhText.contains("12 秒内无回调"))
        assertTrue("中文要给下一步（本地模型 / 稍后再试）：$zhText", zhText.contains("本地模型"))
        assertTrue("英文必须是同一件事：$enText", enText.contains("12 seconds"))
        assertTrue("英文要给下一步：$enText", enText.contains("local model"))
    }

    @Test
    fun `判据2_负例_正常回调不误报`() {
        // 正例：ready 同步到（绝大多数引擎）⇒ 12 秒后仍在听，不判死。
        val ready = rig()
        ready.session.start()
        ready.scheduler.advance(VoiceProbe.TIMEOUT_MS * 2)
        assertEquals("来过 ready 就不许判未响应", VoicePhase.LISTENING, ready.session.phase)
        assertNull("不许产生探测读数", ready.lastProbe)
        assertTrue("失败列表应为空", ready.failures.isEmpty())

        // 另一个负例：**从不发 ready**、但会发 partial 的引擎也不许误杀。
        val partialOnly = rig(words = listOf("今天"))
        partialOnly.port.readyOnStart = false
        partialOnly.session.start()
        partialOnly.port.feedPcm()
        partialOnly.scheduler.advance(VoiceProbe.TIMEOUT_MS)
        assertEquals("partial 也算救命的回调", VoicePhase.LISTENING, partialOnly.session.phase)
        assertNull(partialOnly.lastProbe)
    }

    @Test
    fun `判据2_负例_零回调才判_一次回调就返回null`() {
        assertNull("一次回调就不许出读数", VoiceProbe.verdict(VoiceSource.SYSTEM, 12_000L, 1))
        assertNotNull("零回调必须出读数", VoiceProbe.verdict(VoiceSource.SYSTEM, 12_000L, 0))
    }

    @Test
    fun `判据3_账里一行探测记录_无回调不产文本`() {
        TempSandbox.use("zd700-voice-probe") { dir ->
            val ledger = VoiceLedger(File(dir, VoiceLedger.FILE_NAME))
            val r = rig(ledger = ledger)
            r.port.readyOnStart = false
            r.session.start()
            // 零回调期间输入框一个字都不许变。
            r.scheduler.advance(VoiceProbe.TIMEOUT_MS)

            assertEquals("未响应不许往输入框塞东西", "原有", r.host.text)
            assertEquals("也绝不自动发送", 0, r.host.sendCalls)

            val rows = ledger.rows()
            assertEquals("一行，不多不少", 1, rows.size)
            val row = rows.first()
            assertTrue("这一行必须是探测记录", row.isProbe)
            assertEquals("来源", VoiceSource.SYSTEM, row.source)
            assertEquals("回调计数 0", 0, row.callbacks)
            assertEquals("结论", VoiceProbe.SILENT, row.conclusion)
            assertFalse("没成就是没成", row.success)

            val keys = JSONObject(row.toJson()).keys().asSequence().toSet()
            assertEquals("探测行只多这两列", VoiceLedger.Row.PROBE_KEYS, keys)
            for (banned in listOf("audio", "text", "path", "pcm", "buffer", "content")) {
                assertFalse("探测行里不许出现 $banned", keys.contains(banned))
            }
        }
    }

    @Test
    fun `判据3_普通行仍是三列_探测行没污染旧读数`() {
        val plain = VoiceLedger.Row(1_234L, VoiceSource.SYSTEM, true)
        assertEquals(VoiceLedger.Row.KEYS, JSONObject(plain.toJson()).keys().asSequence().toSet())
        assertFalse(plain.isProbe)
        assertEquals(plain, VoiceLedger.Row.fromJson(plain.toJson()))

        val probe = VoiceLedger.Row(12_000L, VoiceSource.SYSTEM, false, 0, VoiceProbe.SILENT)
        assertEquals(VoiceLedger.Row.PROBE_KEYS, JSONObject(probe.toJson()).keys().asSequence().toSet())
        assertEquals(probe, VoiceLedger.Row.fromJson(probe.toJson()))
    }

    @Test
    fun `判据4_TAP失败后再点能重开_负例死键立刻红`() {
        val r = rig()
        r.port.readyOnStart = false
        r.session.start()
        r.scheduler.advance(VoiceProbe.TIMEOUT_MS)

        // 判据要的两件事：会话已收口（active=false）且相位归零 ⇒ VoiceController 的
        // 「上一轮还活着」那道闸不会再把第二下挡掉。
        assertFalse(r.session.active)
        assertEquals(VoicePhase.IDLE, r.session.phase)

        val second = rig()
        assertTrue("第二下必须真能起一轮", second.session.start())
        assertEquals(VoicePhase.LISTENING, second.session.phase)

        // 负例对照：还在听的时候再来一下必须被挡（否则就是叠了两轮，相位会错乱）。
        assertFalse("同一轮不许被起两次", second.session.start())
        assertEquals("被挡的那一下不许改相位", VoicePhase.LISTENING, second.session.phase)
    }

    @Test
    fun `判据4_超时可配_短窗口同样判零回调`() {
        val r = rig(probeTimeoutMs = 50L)
        r.port.readyOnStart = false
        r.session.start()
        r.scheduler.advance(49L)
        assertEquals(VoicePhase.LISTENING, r.session.phase)
        r.scheduler.advance(1L)
        assertEquals(VoicePhase.IDLE, r.session.phase)
        assertEquals(VoiceProbe.SILENT, r.lastProbe?.conclusion)
    }

    @Test
    fun `判据5_连续两次失败不再自动重试_且一轮只起一次识别`() {
        // 政策：第 1 次失败后仍允许自动重试，第 2 次之后停手（手动入口不受限）。
        assertEquals(1, VoiceRetryPolicy.nextStreak(0, VoiceFailure.NO_RESPONSE))
        assertEquals(2, VoiceRetryPolicy.nextStreak(1, VoiceFailure.NO_RESPONSE))
        assertTrue("第 1 次之后还允许", VoiceRetryPolicy.autoRetryAllowed(1))
        assertFalse("第 2 次之后必须停", VoiceRetryPolicy.autoRetryAllowed(2))
        assertFalse(VoiceRetryPolicy.autoRetryAllowed(3))
        assertTrue("成功一次就清零", VoiceRetryPolicy.nextStreak(2, null) == 0)
        assertFalse("成功之后不许再喊「不再重试」", VoiceRetryPolicy.gaveUp(0))

        // 行为侧：判未响应的那一轮里，识别端口只被起过一次——没有偷偷再起一轮。
        val r = rig()
        r.port.readyOnStart = false
        r.session.start()
        r.scheduler.advance(VoiceProbe.TIMEOUT_MS * 3)
        assertEquals("一轮只许起一次识别", 1, r.port.startCalls)
        assertEquals("也只许收口一次", 1, r.failures.size)
    }

    @Test
    fun `判据6_通道入口_693未合时灰掉并注明`() {
        val blocked = VoiceChannels.switchFrom(VoiceSource.SYSTEM, localInstalled = true)
        assertFalse("693 没合入就不许点", blocked.enabled)
        assertEquals(SwitchBlock.LOCAL_NOT_WIRED, blocked.block)
        assertEquals("换过去的仍是本地档", VoiceSource.LOCAL, blocked.target)

        val noModel = VoiceChannels.switchFrom(
            VoiceSource.SYSTEM,
            localInstalled = false,
            localWired = true,
        )
        assertFalse("接了通道但没装模型也点不动", noModel.enabled)
        assertEquals(SwitchBlock.LOCAL_NOT_INSTALLED, noModel.block)

        // 负例对照：判据不许恒假——通道接上且模型装好时必须能点。
        val ok = VoiceChannels.switchFrom(VoiceSource.SYSTEM, localInstalled = true, localWired = true)
        assertTrue("接好了就必须亮起", ok.enabled)
        assertEquals(SwitchBlock.NONE, ok.block)

        // 本地档切回系统档永远可行（系统档是默认档，不依赖 693）。
        val back = VoiceChannels.switchFrom(VoiceSource.LOCAL, localInstalled = false)
        assertTrue(back.enabled)
        assertEquals(VoiceSource.SYSTEM, back.target)
    }

    @Test
    fun `判据6_通道开关位默认是未接入`() {
        assertFalse("693 合并前这里必须是 false", VoiceChannels.LOCAL_CHANNEL_WIRED)
    }

    @Test
    fun `判据_探测读数自校验_负数不许构造`() {
        assertFalse(runCatching { VoiceProbe(VoiceSource.SYSTEM, -1L, 0) }.isSuccess)
        assertFalse(runCatching { VoiceProbe(VoiceSource.SYSTEM, 0L, -1) }.isSuccess)
    }

    private fun strings(rel: String): Map<String, String> {
        val f = File(rel).let { if (it.isFile) it else File("app/$rel") }
        val text = f.readText(Charsets.UTF_8)
        return Regex("<string\\s+name=\"([^\"]+)\"[^>]*>(.*?)</string>", RegexOption.DOT_MATCHES_ALL)
            .findAll(text)
            .associate { it.groupValues[1] to it.groupValues[2] }
    }
}
