// 独立开发声明：本文件为本项目原创，不含对第三方源码的拷贝。
package com.example.zhengdao.baopu.voice

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 接线闸：新功能必须能从真实入口到达（不是只有新函数、没挂上的按钮）。 */
class VoiceWiringGateTest {

    private fun repoFile(rel: String): File = File(rel).let { if (it.isFile) it else File("app/$rel") }

    private fun code(rel: String): String = repoFile(rel).readText(Charsets.UTF_8)

    private fun composer(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuComposer.kt")

    private fun settings(): String = code("src/main/java/com/example/zhengdao/ui/baopu/BaopuSettingsScreen.kt")

    private fun settingsCard(): String = code("src/main/java/com/example/zhengdao/ui/baopu/VoiceSettingsCard.kt")

    private fun engine(): String = code("src/main/java/com/example/zhengdao/ui/baopu/AndroidSpeechEngine.kt")

    private fun manifest(): String = code("src/main/AndroidManifest.xml")

    @Test
    fun `输入框真实入口_按钮状态行与人工编辑回调都在`() {
        val src = composer()
        for (tok in listOf(
            "rememberVoiceBridge(",
            "VoiceMicButton(ui = voice",
            "VoiceStatusRow(voice)",
            "voice.noteUserEdit(incoming)",
        )) {
            assertTrue("Composer 里缺接线 $tok", src.contains(tok))
        }
        // 语音接线区里不许出现发送回调：转完只进框，等人确认
        val zone = src.substringAfter("rememberVoiceBridge(").substringBefore("VoiceStatusRow(voice)")
        assertFalse("语音接线区不许调用 onSend", zone.contains("onSend("))
    }

    @Test
    fun `设置页挂了语音卡_两模式两来源都能落盘_本地未装灰掉`() {
        assertTrue("设置页没挂语音卡", settings().contains("VoiceSettingsCard()"))
        val card = settingsCard()
        for (tok in listOf(
            "VoicePrefs.setMode(",
            "VoicePrefs.setSource(",
            "enabled = localInstalled",
            "R.string.voice_local_not_installed",
        )) {
            assertTrue("设置卡里缺 $tok", card.contains(tok))
        }
    }

    @Test
    fun `清单声明录音权限与麦克风feature_生产实现先查可用再起识别`() {
        val m = manifest()
        assertTrue(m.contains("android.permission.RECORD_AUDIO"))
        assertTrue(m.contains("android.hardware.microphone"))
        assertTrue(m.contains("android:required=\"false\""))
        val e = engine()
        assertTrue(e.contains("SpeechRecognizer.isRecognitionAvailable(ctx)"))
        assertTrue(e.contains("if (!granted()) return false"))
        assertTrue(e.contains("RecognizerIntent.EXTRA_PREFER_OFFLINE"))
    }

    @Test
    fun `中英文案一一镜像_不许只加一份`() {
        val zh = stringKeys("src/main/res/values/strings_voice.xml")
        val en = stringKeys("src/main/res/values-en/strings_voice.xml")
        assertTrue("中文语音文案一个都没读到", zh.isNotEmpty())
        assertEquals("中英键集合必须完全一致", zh, en)
        assertEquals("633 的 18 条 ＋ 680 的停止 2 条 ＋ 失败 4 档", 24, zh.size)
    }

    @Test
    fun `680_手势被外层抢走也收尾_取消只认显式按钮`() {
        val src = code("src/main/java/com/example/zhengdao/ui/baopu/VoiceMicButton.kt")
        val press = src.substringAfter("onPress = {").substringBefore("onTap = {")
        assertTrue("HOLD 松手/被抢都要收尾", press.contains("s.onStop()"))
        assertFalse("手势被抢不等于取消（旧代码在这里 onCancel ⇒ 一按就取消）", press.contains("onCancel"))
    }

    @Test
    fun `680_状态行有显式停止_且四档文案都挂上了`() {
        val row = code("src/main/java/com/example/zhengdao/ui/baopu/VoiceStatusRow.kt")
        assertTrue("必须有显式「停止」按钮", row.contains("R.string.voice_stop"))
        assertTrue("停止走 onStop", row.contains("onClick = ui.onStop"))
        for (f in listOf("voice_fail_not_heard", "voice_fail_no_response", "voice_fail_timeout", "voice_fail_permission")) {
            assertTrue("状态行没挂 $f", row.contains("R.string.$f"))
        }
        val bridge = code("src/main/java/com/example/zhengdao/ui/baopu/VoiceBridge.kt")
        assertTrue("相位必须由会话推过来", bridge.contains("onPhase = { phase = it }"))
        assertTrue("启动前重读权限（G-673-2）", bridge.contains("granted = engine.granted()"))
        assertTrue("看门狗要挂主线程定时器", bridge.contains("MainHandlerScheduler"))
    }

    @Test
    fun `680_会话有停止后3秒兜底与ready看门狗`() {
        val s = code("src/main/java/com/example/zhengdao/baopu/voice/VoiceSession.kt")
        assertTrue("停止后 3 秒兜底", s.contains("STOP_TIMEOUT_MS = 3_000L"))
        assertTrue("启动看门狗", s.contains("READY_TIMEOUT_MS"))
        assertTrue("有半句就落地", s.contains("landPartial("))
        assertTrue("失败必须把相位归零", s.contains("moveTo(VoicePhase.IDLE)"))
    }

    @Test
    fun `门鉴别器自证_缺一个键或缺一处接线立刻红`() {
        val zh = stringKeys("src/main/res/values/strings_voice.xml")
        val en = stringKeys("src/main/res/values-en/strings_voice.xml")
        assertFalse("删掉一个键后不该还相等", zh.minus("voice_cancel") == en)
        val fake = "fun BaopuComposer() { /* 没有麦克风按钮 */ }"
        assertFalse("缺接线必须被抓到", fake.contains("VoiceMicButton(ui = voice"))
        assertTrue("真源码必须含接线", composer().contains("VoiceMicButton(ui = voice"))
    }

    private fun stringKeys(rel: String): Set<String> = Regex("<string\\s+name=\"([^\"]+)\"")
        .findAll(code(rel)).map { it.groupValues[1] }.toSet()
}
